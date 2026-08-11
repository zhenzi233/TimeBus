package com.zhenzi233.timebus.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
}
