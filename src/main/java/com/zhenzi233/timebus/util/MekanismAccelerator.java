package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

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

    /** 单台机器的加速状态。 */
    private static final class AccelState {
        final int speed;
        final long tick;

        AccelState(final int speed, final long tick) {
            this.speed = speed;
            this.tick = tick;
        }
    }

    private static final Map<World, Map<BlockPos, AccelState>> ACTIVE = new WeakHashMap<>();

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static volatile Class<?> restrictedTickClass;

    /** True if the tile is a Mekanism (CE-Unofficial) machine. */
    public static boolean isMachine(final TileEntity te) {
        if (te == null) {
            return false;
        }
        resolve();
        return available && restrictedTickClass.isInstance(te);
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
            final Map<BlockPos, AccelState> byPos = ACTIVE.computeIfAbsent(world, w -> new HashMap<>());
            final AccelState existing = byPos.get(pos);
            if (existing != null && existing.tick == tick) {
                return false;
            }
            byPos.put(pos, new AccelState(speed, tick));
            return true;
        }
    }

    /** 查询机器当前是否被加速；记录新鲜且 speed > 1 时返回 speed，否则 null。 */
    public static Integer queryActive(final TileEntity te) {
        if (te == null || te.getWorld() == null) {
            return null;
        }
        synchronized (ACTIVE) {
            final Map<BlockPos, AccelState> byPos = ACTIVE.get(te.getWorld());
            if (byPos == null) {
                return null;
            }
            final AccelState state = byPos.get(te.getPos());
            if (state == null || te.getWorld().getTotalWorldTime() - state.tick > FRESH_WINDOW) {
                return null;
            }
            return state.speed > 1 ? state.speed : null;
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
}
