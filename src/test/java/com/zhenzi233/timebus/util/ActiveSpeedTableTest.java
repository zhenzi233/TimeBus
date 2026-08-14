package com.zhenzi233.timebus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ActiveSpeedTableTest {

    private final ActiveSpeedTable<String> table = new ActiveSpeedTable<>(10);

    @Test
    void register_sameTickIsIdempotent() {
        assertTrue(table.register("a", 4, 100));
        assertFalse(table.register("a", 4, 100), "同 tick 重复登记应返回 false");
        assertFalse(table.register("a", 8, 100), "同 tick 换速度也算重复");
    }

    @Test
    void register_newTickReplacesEntry() {
        assertTrue(table.register("a", 4, 100));
        assertTrue(table.register("a", 8, 101));
        assertEquals(Integer.valueOf(8), table.query("a", 101));
    }

    @Test
    void register_rejectsSpeedBelowTwo() {
        assertFalse(table.register("a", 1, 0));
        assertFalse(table.register("a", 0, 0));
    }

    @Test
    void query_returnsSpeedWhileFresh() {
        table.register("a", 4, 100);
        assertEquals(Integer.valueOf(4), table.query("a", 105));
        assertEquals(Integer.valueOf(4), table.query("a", 110), "窗口边界仍新鲜");
    }

    @Test
    void query_expiresAfterFreshWindow() {
        table.register("a", 4, 100);
        assertNull(table.query("a", 111), "超过 10 tick 窗口应失效");
    }

    @Test
    void query_unknownKeyReturnsNull() {
        assertNull(table.query("missing", 0));
        assertNull(table.query(null, 0));
    }

    @Test
    void register_afterExpiryCountsAsNewRegistration() {
        assertTrue(table.register("a", 4, 100));
        assertTrue(table.register("a", 4, 200), "过期后重新登记应计为新登记");
    }

    @Test
    void weakKeysAreReclaimedAfterDroppingReferences() {
        final ActiveSpeedTable<Object> objectTable = new ActiveSpeedTable<>(10);
        Object key = new Object();
        objectTable.register(key, 4, 0);
        assertEquals(1, objectTable.size());
        key = null;
        for (int i = 0; i < 50 && objectTable.size() > 0; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // GC 不保证立即执行，极端环境下允许跳过而非失败。
        assumeTrue(objectTable.size() == 0, "弱键应在释放强引用后被回收");
    }

    @Test
    void removalHook_firesOnExpiryCleanup() {
        final int[] removed = {0};
        final ActiveSpeedTable<String> hooked = new ActiveSpeedTable<>(10, () -> removed[0]++);
        hooked.register("a", 4, 100);
        // 新鲜查询不触发删除回调。
        hooked.query("a", 110);
        assertEquals(0, removed[0]);
        // 过期查询触发顺手删除 → 回调恰好一次。
        assertNull(hooked.query("a", 111));
        assertEquals(1, removed[0]);
        // 再查同一 key：已删除，不重复回调。
        assertNull(hooked.query("a", 112));
        assertEquals(1, removed[0]);
    }

    @Test
    void removalHook_firesForEachExpiredEntry() {
        final int[] removed = {0};
        final ActiveSpeedTable<String> hooked = new ActiveSpeedTable<>(10, () -> removed[0]++);
        hooked.register("a", 4, 100);
        hooked.register("b", 8, 100);
        assertNull(hooked.query("a", 111));
        assertNull(hooked.query("b", 111));
        assertEquals(2, removed[0]);
    }

    @Test
    void contains_reflectsTableMembership() {
        table.register("a", 4, 100);
        assertTrue(table.contains("a"));
        assertFalse(table.contains("missing"));
        assertFalse(table.contains(null));
        // 过期条目在查询清理前仍被 contains 视为存在（调用方负责在同锁内配合）。
        assertTrue(table.contains("a"));
    }

    @Test
    void noHook_defaultConstructorIsSafe() {
        final ActiveSpeedTable<String> plain = new ActiveSpeedTable<>(10);
        plain.register("a", 4, 100);
        assertNull(plain.query("a", 111), "无回调构造器在过期清理时不应抛错");
    }
}
