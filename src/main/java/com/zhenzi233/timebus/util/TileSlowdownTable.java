package com.zhenzi233.timebus.util;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 减速活跃表：记录"被时间减速总线减速"的方块与档位。
 *
 * <p>语义：时间减速总线每 tick 对正面范围内每个方块调用
 * {@link #register(World, BlockPos, int, long)} 登记档位 N（每 N tick 才执行
 * 一次该方块的 {@code ITickable.update()}）；{@link #shouldSkip} 在
 * {@code World.updateEntities} 的 mixin 里每 tick 查询——命中且
 * {@code nowTick % N != 0} 时跳过本次 update 调用。总线移走/断电后不再登记，
 * 记录超过新鲜窗口自动失效并清理，方块恢复原速。
 *
 * <p>与 {@link ActiveSpeedTable} 同构：世界弱引用（卸载自动清理）、同步访问、
 * 同 tick 重复登记幂等。多台减速总线叠加时取最大档位（最慢者生效）。
 * 纯运行时数据，不写存档。
 */
public final class TileSlowdownTable {

    /** 记录新鲜窗口（tick）：超过该 tick 数未刷新即视为失效。 */
    private static final long FRESH_WINDOW = 10;

    /** 单方块减速状态。 */
    private static final class SlowState {
        final int n;    // 每 n tick 执行一次 update
        final long tick;

        SlowState(final int n, final long tick) {
            this.n = n;
            this.tick = tick;
        }
    }

    private static final Map<World, Map<BlockPos, SlowState>> ACTIVE = new WeakHashMap<>();

    private TileSlowdownTable() {
    }

    /**
     * 登记一个方块的减速档位（时间减速总线每 tick 调用）。
     *
     * @param n    每 n tick 执行一次 update；n &lt;= 1 视为无效档位
     * @return true 表示本次产生了新的（更慢的）档位
     */
    public static synchronized boolean register(final World world, final BlockPos pos, final int n, final long tick) {
        if (world == null || pos == null || n <= 1) {
            return false;
        }
        final Map<BlockPos, SlowState> byPos = ACTIVE.computeIfAbsent(world, w -> new java.util.HashMap<>());
        final SlowState existing = byPos.get(pos);
        if (existing != null && existing.tick == tick && existing.n >= n) {
            return false; // 同 tick 且不更慢:幂等
        }
        byPos.put(pos, new SlowState(n, tick));
        return true;
    }

    /**
     * 查询本次 tick 是否应跳过该方块的 update 调用（mixin 每 tick 调用）。
     *
     * <p>命中（记录新鲜）且 {@code nowTick % n != 0} → true（跳过）；记录过期时
     * 顺手删除并返回 false。未登记返回 false（正常执行）。
     */
    public static synchronized boolean shouldSkip(final World world, final BlockPos pos, final long nowTick) {
        if (world == null || pos == null) {
            return false;
        }
        final Map<BlockPos, SlowState> byPos = ACTIVE.get(world);
        if (byPos == null) {
            return false;
        }
        final SlowState state = byPos.get(pos);
        if (state == null) {
            return false;
        }
        if (nowTick - state.tick > FRESH_WINDOW) {
            // 过期即失效,顺手删除:总线移走/断电后方块自动恢复原速。
            byPos.remove(pos);
            if (byPos.isEmpty()) {
                ACTIVE.remove(world);
            }
            return false;
        }
        return nowTick % state.n != 0;
    }

    /** 该方块当前是否在减速表中（用于加速/减速互斥判定,不判断本次是否跳过）。 */
    public static synchronized boolean isSlowed(final World world, final BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        final Map<BlockPos, SlowState> byPos = ACTIVE.get(world);
        return byPos != null && byPos.get(pos) != null;
    }

    /** 便捷重载：从 tile 直接判定（加速侧互斥检查用）。 */
    public static boolean isSlowed(final TileEntity te) {
        return te != null && te.getWorld() != null && isSlowed(te.getWorld(), te.getPos());
    }
}
