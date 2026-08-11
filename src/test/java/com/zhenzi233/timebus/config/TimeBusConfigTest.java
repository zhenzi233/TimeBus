package com.zhenzi233.timebus.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeBusConfigTest {

    private static final int[] DEFAULTS = {2, 4, 8, 16, 32};

    @Test
    void parseList_nullEmptyOrBlankFallsBackToDefaults() {
        assertArrayEquals(DEFAULTS, TimeBusConfig.parseList(null, DEFAULTS));
        assertArrayEquals(DEFAULTS, TimeBusConfig.parseList("", DEFAULTS));
        assertArrayEquals(DEFAULTS, TimeBusConfig.parseList("   ", DEFAULTS));
    }

    @Test
    void parseList_singleValue() {
        assertArrayEquals(new int[]{7}, TimeBusConfig.parseList("7", new int[]{2}));
    }

    @Test
    void parseList_trimsWhitespaceAroundEntries() {
        assertArrayEquals(new int[]{2, 4, 8}, TimeBusConfig.parseList(" 2, 4 ,8 ", new int[]{2, 4, 8}));
    }

    @Test
    void parseList_invalidEntryFallsBackToDefaults() {
        assertArrayEquals(DEFAULTS, TimeBusConfig.parseList("1,abc,9", DEFAULTS));
    }

    @Test
    void parseList_longerListKeepsAllEntries() {
        final int[] extended = {2, 4, 8, 16, 32, 64};
        assertArrayEquals(extended, TimeBusConfig.parseList("2,4,8,16,32,64", DEFAULTS));
    }

    @Test
    void valueForCardCount_clampsToLastEntry() {
        final int[] multipliers = {2, 4, 8, 16, 32};
        assertEquals(2, TimeBusConfig.valueForCardCount(0, multipliers));
        assertEquals(32, TimeBusConfig.valueForCardCount(4, multipliers));
        assertEquals(32, TimeBusConfig.valueForCardCount(9, multipliers), "超长卡数回退到最后一项");
        assertEquals(2, TimeBusConfig.valueForCardCount(-1, multipliers), "负卡数取第一项");
    }

    @Test
    void valueForCardCount_emptyOrNullListReturnsOne() {
        assertEquals(1, TimeBusConfig.valueForCardCount(0, new int[0]));
        assertEquals(1, TimeBusConfig.valueForCardCount(4, null));
    }

    @Test
    void valueForCardCount_singleEntry() {
        assertEquals(2, TimeBusConfig.valueForCardCount(0, new int[]{2}));
        assertEquals(2, TimeBusConfig.valueForCardCount(3, new int[]{2}));
    }
}
