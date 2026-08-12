package com.zhenzi233.timebus.util;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 通用"活跃加速表"，与 Minecraft 无关，可脱离运行时单测。
 *
 * <p>语义：{@link #register(Object, int, long)} 登记一条加速记录（同 tick 重复
 * 登记幂等）；{@link #query(Object, long)} 只在记录新鲜（nowTick - tick <=
 * freshWindow）且 speed &gt; 1 时返回 speed，否则 null。键保存在 WeakHashMap
 * 中，键对象不再被强引用后自动清理。所有访问同步，兼容多线程调用
 * （Mek 的异步任务线程）。
 */
public final class ActiveSpeedTable<K> {

    /** 单台机器的加速状态。 */
    private static final class AccelState {
        final int speed;
        final long tick;

        AccelState(final int speed, final long tick) {
            this.speed = speed;
            this.tick = tick;
        }
    }

    /** 记录新鲜窗口：超过该 tick 数未刷新即视为失效。 */
    private final long freshWindow;
    private final Map<K, AccelState> table = new WeakHashMap<>();

    public ActiveSpeedTable(final long freshWindow) {
        this.freshWindow = freshWindow;
    }

    /**
     * 登记一条加速记录。
     *
     * @return true 表示本次是新登记（同 tick 重复调用返回 false，避免重复计预算）
     */
    public synchronized boolean register(final K key, final int speed, final long tick) {
        if (key == null || speed <= 1) {
            return false;
        }
        final AccelState existing = table.get(key);
        if (existing != null && existing.tick == tick) {
            return false;
        }
        table.put(key, new AccelState(speed, tick));
        return true;
    }

    /** 查询 key 当前是否被加速；记录新鲜且 speed > 1 时返回 speed，否则 null。 */
    public synchronized Integer query(final K key, final long nowTick) {
        if (key == null) {
            return null;
        }
        final AccelState state = table.get(key);
        if (state == null) {
            return null;
        }
        if (nowTick - state.tick > freshWindow) {
            // 过期即失效,顺手删除:避免仅靠弱引用回收的过期条目累积
            // (魔杖一次性点击等不再持续登记的加速源)。
            table.remove(key);
            return null;
        }
        return state.speed > 1 ? state.speed : null;
    }

    /** 当前登记数（含待清理的过期弱键；主要供测试断言弱引用回收）。 */
    synchronized int size() {
        return table.size();
    }
}
