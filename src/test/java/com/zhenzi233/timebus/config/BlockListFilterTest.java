package com.zhenzi233.timebus.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BlockListFilter 纯字符串匹配语义测试（不依赖 MC 运行时）。
 */
class BlockListFilterTest {

    // --- 精确匹配 ---

    @Test
    void blacklist_blocksExactMatchAndAllowsOthers() {
        final BlockListFilter f = new BlockListFilter(true, false, "minecraft:furnace,minecraft:chest");
        assertFalse(f.allows("minecraft:furnace"), "黑名单命中应禁止");
        assertFalse(f.allows("minecraft:chest"), "黑名单命中应禁止");
        assertTrue(f.allows("minecraft:hopper"), "黑名单未命中应放行");
    }

    @Test
    void whitelist_onlyAllowsListed() {
        final BlockListFilter f = new BlockListFilter(true, true, "minecraft:furnace");
        assertTrue(f.allows("minecraft:furnace"), "白名单命中应放行");
        assertFalse(f.allows("minecraft:hopper"), "白名单未命中应禁止");
    }

    // --- 无命名空间补 minecraft: ---

    @Test
    void bareNameDefaultsToMinecraftNamespace() {
        final BlockListFilter f = new BlockListFilter(true, false, "furnace");
        assertFalse(f.allows("minecraft:furnace"), "裸名应匹配 minecraft:furnace");
        assertTrue(f.allows("mekanism:machine"), "裸名不应匹配其他 mod");
    }

    // --- 通配符 ---

    @Test
    void wildcardMatchesEveryBlockOfMod() {
        final BlockListFilter f = new BlockListFilter(true, false, "minecraft:*");
        assertFalse(f.allows("minecraft:furnace"), "modid:* 应匹配该 mod 全部方块");
        assertFalse(f.allows("minecraft:chest"));
        assertTrue(f.allows("appliedenergistics2:block_charger"), "其他 mod 不受影响");
    }

    @Test
    void bareWildcardMatchesEverything() {
        final BlockListFilter f = new BlockListFilter(true, false, "*");
        assertFalse(f.allows("minecraft:furnace"));
        assertFalse(f.allows("mekanism:machine"));
    }

    @Test
    void wildcardOnlyPrefixMatches() {
        final BlockListFilter f = new BlockListFilter(true, false, "appliedenergistics2:block_*");
        assertFalse(f.allows("appliedenergistics2:block_charger"), "前缀通配应命中");
        assertFalse(f.allows("appliedenergistics2:block_inscriber"));
        assertTrue(f.allows("appliedenergistics2:cable_glass"), "非该前缀不应命中");
    }

    // --- 空名单语义 ---

    @Test
    void emptyBlacklistAllowsEverything() {
        final BlockListFilter f = new BlockListFilter(true, false, "");
        assertTrue(f.allows("minecraft:furnace"), "空黑名单 = 全部放行");
    }

    @Test
    void emptyWhitelistDeniesEverything() {
        final BlockListFilter f = new BlockListFilter(true, true, "");
        assertFalse(f.allows("minecraft:furnace"), "空白名单 = 全部禁止");
    }

    // --- 开关 ---

    @Test
    void disabledAlwaysAllows() {
        final BlockListFilter f = new BlockListFilter(false, true, "minecraft:furnace");
        assertTrue(f.allows("minecraft:furnace"), "开关关闭恒放行（即使白名单命中）");
        assertTrue(f.allows("minecraft:hopper"));
        assertTrue(f.allows((String) null), "关闭时 null 也放行");
    }

    // --- 边界输入 ---

    @Test
    void nullOrBlankListEntryIgnored() {
        final BlockListFilter f = new BlockListFilter(true, false, " , minecraft:furnace, ,");
        assertFalse(f.allows("minecraft:furnace"));
        assertTrue(f.allows("minecraft:hopper"));
    }

    @Test
    void nullRegistryNameIsDeniedWhenEnabled() {
        final BlockListFilter f = new BlockListFilter(true, false, "minecraft:furnace");
        assertFalse(f.allows((String) null), "开启时 null 注册名按不匹配处理");
    }

    @Test
    void spacesAroundEntriesAreTrimmed() {
        final BlockListFilter f = new BlockListFilter(true, false, " minecraft:furnace , minecraft:chest ");
        assertFalse(f.allows("minecraft:furnace"));
        assertFalse(f.allows("minecraft:chest"));
    }

    // --- 模式切换联动 ---

    @Test
    void sameListDifferentModeGivesOppositeResults() {
        final BlockListFilter black = new BlockListFilter(true, false, "minecraft:furnace");
        final BlockListFilter white = new BlockListFilter(true, true, "minecraft:furnace");
        assertFalse(black.allows("minecraft:furnace"));
        assertTrue(white.allows("minecraft:furnace"));
        assertTrue(black.allows("minecraft:hopper"));
        assertFalse(white.allows("minecraft:hopper"));
    }
}
