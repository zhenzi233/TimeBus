package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mekanism-CE-Unofficial 连拍加速（活跃表）。
 *
 * <p>Mek 机器继承 {@code TileEntityRestrictedTick}，其 final {@code update()}
 * 带世界 tick 去重，TimeBus 的 ITickable 连拍无效。改用 Mixin @Redirect
 * {@code RecipeCacheLookupMonitor.updateAndProcess} 里的
 * {@code CachedRecipe.process()} 调用点，循环调用 N 次推进配方进度
 * （见 MixinMekanismCacheLoop，覆盖所有走 CachedRecipe 的配方机器）。
 *
 * <p>本类维护"当前被 TimeBus 加速的 Mek 机器"活跃表：TimeBus 每 tick 扫描时
 * 登记 (world, pos, speed)，Mixin 查询时校验新鲜度（超过窗口自动失效），
 * 因此总线移走后加速自动停止。所有访问走同步，兼容 Mek 的异步任务线程。
 */
public final class MekanismAccelerator {

    private MekanismAccelerator() {
    }

    /** 活跃表记录的新鲜窗口（tick）：TimeBus 每 tick 刷新记录，此为异步任务延迟容限。 */
    private static final long FRESH_WINDOW = 10;

    /** 每台被加速的 Mek 机器的活跃状态，按世界分组（world 弱引用，卸载自动清理）。 */
    private static final Map<World, ActiveSpeedTable<BlockPos>> ACTIVE = new WeakHashMap<>();

    /**
     * 活跃机器计数（新机器登记 +1，过期清理 -1）。
     *
     * <p>无任何被加速的 Mek 机器时，三个 Mek mixin 的查询入口直接短路——
     * 每台 Mek 机器每次配方推进/产电/发包只多一次 volatile 读，不再做双重
     * synchronized 查表。计数只会因弱键回收而偏高（无害：漏判只是多查一次
     * 表，短路仅在计数为 0 时生效，此时表必为空）。
     */
    private static final AtomicInteger ACTIVE_COUNT = new AtomicInteger();

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static volatile Class<?> restrictedTickClass;

    private static volatile boolean generatorResolved;
    private static volatile boolean generatorAvailable;
    private static volatile Class<?> generatorClass;

    /** True if the tile is a Mekanism (CE-Unofficial) machine. */
    public static boolean isMachine(final TileEntity te) {
        if (te == null) {
            return false;
        }
        resolve();
        return available && restrictedTickClass.isInstance(te);
    }

    /** True if the tile is a Mek generator (wind/gas/bio/solar/heat, incl. large multiblock). */
    public static boolean isGenerator(final TileEntity te) {
        if (te == null) {
            return false;
        }
        resolveGenerator();
        return generatorAvailable && generatorClass.isAssignableFrom(te.getClass());
    }

    /** 是否正有被加速的 Mek 机器（全局短路入口：计数为 0 时 mixin 可直接跳过查表）。 */
    public static boolean isActive() {
        return ACTIVE_COUNT.get() > 0;
    }

    /**
     * TimeBus 每 tick 扫描到被加速的 Mek 机器时登记一次。
     *
     * @return true 表示本次是新登记（同 tick 重复调用返回 false，避免重复计预算）
     */
    public static boolean registerOnce(final World world, final BlockPos pos,
                                       final int speed, final long tick) {
        if (world == null || pos == null || speed <= 1) {
            return false;
        }
        synchronized (ACTIVE) {
            final ActiveSpeedTable<BlockPos> byPos =
                    ACTIVE.computeIfAbsent(world, w -> new ActiveSpeedTable<>(FRESH_WINDOW, ACTIVE_COUNT::decrementAndGet));
            // 新条目才计入活跃计数（同 tick 刷新/替换不加）；contains 与 register
            // 都在 ACTIVE 锁内执行，相对其它 ACTIVE 持有者是原子的。
            final boolean isNew = !byPos.contains(pos);
            final boolean registered = byPos.register(pos, speed, tick);
            if (registered && isNew) {
                ACTIVE_COUNT.incrementAndGet();
            }
            return registered;
        }
    }

    /** 查询机器当前是否被加速；记录新鲜且 speed > 1 时返回 speed，否则 null。 */
    public static Integer queryActive(final TileEntity te) {
        if (te == null || te.getWorld() == null || ACTIVE_COUNT.get() == 0) {
            return null;
        }
        synchronized (ACTIVE) {
            final ActiveSpeedTable<BlockPos> byPos = ACTIVE.get(te.getWorld());
            return byPos == null ? null : byPos.query(te.getPos(), te.getWorld().getTotalWorldTime());
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (MekanismAccelerator.class) {
            if (resolved) {
                return;
            }
            try {
                restrictedTickClass = Class.forName("mekanism.common.tile.base.TileEntityRestrictedTick");
                available = true;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: Mekanism acceleration unavailable: {}", e.toString());
                available = false;
            } finally {
                resolved = true;
            }
        }
    }

    /** 解析发电机基类 TileEntityGenerator（普通与大型发电机都继承它）。 */
    private static void resolveGenerator() {
        if (generatorResolved) {
            return;
        }
        synchronized (MekanismAccelerator.class) {
            if (generatorResolved) {
                return;
            }
            try {
                generatorClass = Class.forName("mekanism.generators.common.tile.TileEntityGenerator");
                generatorAvailable = true;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: Mek generator acceleration unavailable: {}", e.toString());
                generatorAvailable = false;
            }
            generatorResolved = true;
        }
    }
}
