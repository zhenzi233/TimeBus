package com.zhenzi233.timebus.util;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 加速状态追踪：四张 WeakHashMap 登记表，从原
 * {@code ModularMachineryAccelerator} 里整体搬出。
 *
 * <p>{@link #INJECTED_TABLE}（总线 permanent 注入）、
 * {@link #SEMI_INJECTED_TABLE}（魔杖 semi-permanent 注入）、
 * {@link #LAST_FORCE_REFRESH_TABLE}（强制刷新节流）、{@link #APPLIED_TABLE}
 * （已应用状态快照）。每张表仍以自身为锁对象（{@code synchronized (TABLE)}），
 * 世界键为弱引用，卸载后条目自动消失。
 *
 * <p>本类刻意不依赖 {@code TimeBusConfig}：需要配置值的地方（如强制刷新间隔）
 * 由调用方取值后传参。
 */
final class MMTracking {

    private MMTracking() {
    }

    /**
     * Remembers which source (Time Bus part / wand) injected a duration modifier
     * on which controller, so the source-driven restore can clean up when a
     * source disappears (e.g. a Time Bus is removed from the world). Without
     * this, injected modifiers would stay on machines forever and even be
     * written into the world save. World keys are weak references: entries go
     * away automatically when the World unloads.
     */
    static final Map<World, Map<BlockPos, Set<String>>> INJECTED_TABLE = new WeakHashMap<>();

    /**
     * 魔杖注入的 semi-permanent modifier 追踪（独立于 {@link #INJECTED_TABLE}）。
     *
     * <p>魔杖的配方加速写入 {@code RecipeThread.semiPermanentModifiers}，MM 在
     * 配方完成/失败时自动清空整表，正常路径无需干预；但配方中途拆机、区块卸载
     * 或关服时 MM 的自动清空不会触发，加速状态可能随存档残留，因此单独登记，由
     * {@link ModularMachineryAccelerator#restoreAllForWorld(World)} 等恢复路径
     * 兜底清理。
     *
     * <p>不复用 {@link #INJECTED_TABLE}：{@link #isInjected}（工厂线程回收 Mixin 的判定）
     * 只应反映持续注入的总线来源——魔杖是瞬时配方级加速，配方完成后线程理应
     * 正常回收，若混入同一张表会导致空闲线程被长期保留。
     */
    static final Map<World, Map<BlockPos, Set<String>>> SEMI_INJECTED_TABLE = new WeakHashMap<>();

    /**
     * 记录每个控制器上次"强制刷新"的世界 tick。
     *
     * <p>MM 的 context 是池化的：{@code setContext()} 会把旧 context 归还
     * {@code RecipeCraftingContextPool}，新 context 是 reset 过的空状态。
     * 此时线程的 permanentModifiers 数据源仍持有我们的 modifier（幂等检查
     * 通过），但实际应用的 context 已丢失 —— 只有重新写入 modifier 触发
     * {@code flushContextModifier()} 才会把 permanent 刷回 context。因此
     * 按配置的 mmContextRefreshInterval（0 = 关闭）周期性无条件重注入，
     * 保证 context 脱节后最多一个间隔内自愈。
     */
    static final Map<World, Map<BlockPos, Long>> LAST_FORCE_REFRESH_TABLE = new WeakHashMap<>();

    /**
     * 每个 (world, pos, sourceKey) 上次成功注入的加速状态快照。
     *
     * <p>稳态下（倍率未变、能耗守恒开关未变、未到强制刷新周期）直接跳过整轮
     * 反射巡检，把每 tick 的 MM 开销降为零（代码审查 3.1）。正确性由两点兜底：
     * 1) {@link #shouldForceRefresh(TileEntity, int)} 按 mmContextRefreshInterval
     * 周期强制重走反射
     * 路径（context 池化脱节自愈）；2) 倍率 / 能耗配置变化会使快照失配，自动重走。
     */
    static final Map<World, Map<BlockPos, Map<String, AppliedState>>> APPLIED_TABLE = new WeakHashMap<>();

    /** 已注入状态快照（{@link #APPLIED_TABLE} 的值）。 */
    private static final class AppliedState {
        final int speed;
        final boolean energyFollows;

        AppliedState(final int speed, final boolean energyFollows) {
            this.speed = speed;
            this.energyFollows = energyFollows;
        }
    }

    /**
     * 该控制器当前是否仍被某个加速来源注入（时间总线/时间杖）。
     *
     * <p>只看持续注入的 {@link #INJECTED_TABLE}（总线路径）。魔杖是瞬时配方级
     * 加速，配方完成后线程理应正常回收，因此不参与该判定。
     */
    static boolean isInjected(final TileEntity te) {
        if (te == null) {
            return false;
        }
        synchronized (INJECTED_TABLE) {
            final Map<BlockPos, Set<String>> byPos = INJECTED_TABLE.get(te.getWorld());
            if (byPos == null) {
                return false;
            }
            final Set<String> sources = byPos.get(te.getPos());
            return sources != null && !sources.isEmpty();
        }
    }

    static void rememberInjected(final TileEntity te, final String sourceKey) {
        if (te == null || sourceKey == null) {
            return;
        }
        synchronized (INJECTED_TABLE) {
            INJECTED_TABLE.computeIfAbsent(te.getWorld(), w -> new HashMap<>())
                    .computeIfAbsent(te.getPos(), p -> new HashSet<>())
                    .add(sourceKey);
        }
    }

    static void forgetInjected(final TileEntity te, final String sourceKey) {
        if (te == null || sourceKey == null) {
            return;
        }
        synchronized (INJECTED_TABLE) {
            final Map<BlockPos, Set<String>> byPos = INJECTED_TABLE.get(te.getWorld());
            if (byPos == null) {
                return;
            }
            final Set<String> sources = byPos.get(te.getPos());
            if (sources != null) {
                sources.remove(sourceKey);
                if (sources.isEmpty()) {
                    byPos.remove(te.getPos());
                }
            }
            if (byPos.isEmpty()) {
                INJECTED_TABLE.remove(te.getWorld());
            }
        }
        // 同步清理各状态缓存，避免拆除/过期后残留（代码审查 4.4）。
        forgetApplied(te, sourceKey);
        forgetForceRefresh(te);
    }

    /** 记录魔杖 semi-permanent 注入（见 {@link #SEMI_INJECTED_TABLE} 的说明）。 */
    static void rememberSemiInjected(final TileEntity te, final String sourceKey) {
        if (te == null || sourceKey == null) {
            return;
        }
        synchronized (SEMI_INJECTED_TABLE) {
            SEMI_INJECTED_TABLE.computeIfAbsent(te.getWorld(), w -> new HashMap<>())
                    .computeIfAbsent(te.getPos(), p -> new HashSet<>())
                    .add(sourceKey);
        }
    }

    static void forgetSemiInjected(final TileEntity te, final String sourceKey) {
        if (te == null || sourceKey == null) {
            return;
        }
        synchronized (SEMI_INJECTED_TABLE) {
            final Map<BlockPos, Set<String>> byPos = SEMI_INJECTED_TABLE.get(te.getWorld());
            if (byPos == null) {
                return;
            }
            final Set<String> sources = byPos.get(te.getPos());
            if (sources != null) {
                sources.remove(sourceKey);
                if (sources.isEmpty()) {
                    byPos.remove(te.getPos());
                }
            }
            if (byPos.isEmpty()) {
                SEMI_INJECTED_TABLE.remove(te.getWorld());
            }
        }
    }

    /**
     * 距上次强制刷新是否已达到 {@code interval}（达到则记录本次并返回 true；
     * 间隔 0 = 关闭）。
     *
     * <p>间隔由调用方从配置取出后传入，本类不依赖 {@code TimeBusConfig}。
     */
    static boolean shouldForceRefresh(final TileEntity te, final int interval) {
        if (te == null || te.getWorld() == null) {
            return false;
        }
        if (interval <= 0) {
            return false;
        }
        final long now = te.getWorld().getTotalWorldTime();
        synchronized (LAST_FORCE_REFRESH_TABLE) {
            final Map<BlockPos, Long> byPos = LAST_FORCE_REFRESH_TABLE.get(te.getWorld());
            final Long last = byPos == null ? null : byPos.get(te.getPos());
            if (last != null && now - last < interval) {
                return false;
            }
            LAST_FORCE_REFRESH_TABLE.computeIfAbsent(te.getWorld(), w -> new HashMap<>())
                    .put(te.getPos(), now);
            return true;
        }
    }

    static boolean isAppliedState(final TileEntity te, final String sourceKey,
                                  final int speed, final boolean energyFollows) {
        if (te == null || te.getWorld() == null || sourceKey == null) {
            return false;
        }
        synchronized (APPLIED_TABLE) {
            final Map<BlockPos, Map<String, AppliedState>> byPos = APPLIED_TABLE.get(te.getWorld());
            if (byPos == null) {
                return false;
            }
            final Map<String, AppliedState> bySource = byPos.get(te.getPos());
            if (bySource == null) {
                return false;
            }
            final AppliedState state = bySource.get(sourceKey);
            return state != null && state.speed == speed && state.energyFollows == energyFollows;
        }
    }

    static void rememberApplied(final TileEntity te, final String sourceKey,
                                final int speed, final boolean energyFollows) {
        if (te == null || te.getWorld() == null || sourceKey == null) {
            return;
        }
        synchronized (APPLIED_TABLE) {
            APPLIED_TABLE.computeIfAbsent(te.getWorld(), w -> new HashMap<>())
                    .computeIfAbsent(te.getPos(), p -> new HashMap<>())
                    .put(sourceKey, new AppliedState(speed, energyFollows));
        }
    }

    static void forgetApplied(final TileEntity te, final String sourceKey) {
        if (te == null || te.getWorld() == null || sourceKey == null) {
            return;
        }
        synchronized (APPLIED_TABLE) {
            final Map<BlockPos, Map<String, AppliedState>> byPos = APPLIED_TABLE.get(te.getWorld());
            if (byPos == null) {
                return;
            }
            final Map<String, AppliedState> bySource = byPos.get(te.getPos());
            if (bySource != null) {
                bySource.remove(sourceKey);
                if (bySource.isEmpty()) {
                    byPos.remove(te.getPos());
                }
            }
            if (byPos.isEmpty()) {
                APPLIED_TABLE.remove(te.getWorld());
            }
        }
    }

    static void forgetForceRefresh(final TileEntity te) {
        if (te == null || te.getWorld() == null) {
            return;
        }
        synchronized (LAST_FORCE_REFRESH_TABLE) {
            final Map<BlockPos, Long> byPos = LAST_FORCE_REFRESH_TABLE.get(te.getWorld());
            if (byPos != null) {
                byPos.remove(te.getPos());
                if (byPos.isEmpty()) {
                    LAST_FORCE_REFRESH_TABLE.remove(te.getWorld());
                }
            }
        }
    }

    /**
     * 收集 (pos, sourceKey) 待恢复列表，合并遍历 {@link #INJECTED_TABLE}（总线）与
     * {@link #SEMI_INJECTED_TABLE}（魔杖）两张追踪表。chunkX/chunkZ 传
     * {@link Integer#MIN_VALUE} 表示不按区块过滤（全量）。
     */
    static List<Map.Entry<BlockPos, String>> collectPendingRestores(final World world,
                                                                    final int chunkX, final int chunkZ) {
        final List<Map.Entry<BlockPos, String>> pending = new ArrayList<>();
        collectFromTable(pending, world, INJECTED_TABLE, chunkX, chunkZ);
        collectFromTable(pending, world, SEMI_INJECTED_TABLE, chunkX, chunkZ);
        return pending;
    }

    private static void collectFromTable(final List<Map.Entry<BlockPos, String>> pending, final World world,
                                         final Map<World, Map<BlockPos, Set<String>>> table,
                                         final int chunkX, final int chunkZ) {
        synchronized (table) {
            final Map<BlockPos, Set<String>> byPos = table.get(world);
            if (byPos == null || byPos.isEmpty()) {
                return;
            }
            for (final Map.Entry<BlockPos, Set<String>> e : byPos.entrySet()) {
                final BlockPos pos = e.getKey();
                if (chunkX != Integer.MIN_VALUE && ((pos.getX() >> 4) != chunkX || (pos.getZ() >> 4) != chunkZ)) {
                    continue;
                }
                for (final String sourceKey : e.getValue()) {
                    pending.add(new AbstractMap.SimpleEntry<>(pos, sourceKey));
                }
            }
        }
    }

    /** 复制 {@link #INJECTED_TABLE} 里该世界的控制器坐标快照（按源恢复时的遍历集）。 */
    static Set<BlockPos> injectedPositions(final World world) {
        if (world == null) {
            return java.util.Collections.emptySet();
        }
        synchronized (INJECTED_TABLE) {
            final Map<BlockPos, Set<String>> byPos = INJECTED_TABLE.get(world);
            return byPos == null ? java.util.Collections.emptySet() : new HashSet<>(byPos.keySet());
        }
    }

    /**
     * 合并 {@link #INJECTED_TABLE} 与 {@link #SEMI_INJECTED_TABLE} 里所有仍被追踪的
     * 世界（关服恢复用）。纯魔杖注入（无总线）的世界只登记在后者，也必须覆盖。
     */
    static List<World> trackedWorlds() {
        final List<World> worlds = new ArrayList<>();
        synchronized (INJECTED_TABLE) {
            worlds.addAll(INJECTED_TABLE.keySet());
        }
        synchronized (SEMI_INJECTED_TABLE) {
            for (final World world : SEMI_INJECTED_TABLE.keySet()) {
                if (!worlds.contains(world)) {
                    worlds.add(world);
                }
            }
        }
        return worlds;
    }

}
