package com.zhenzi233.timebus.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 方块黑白名单过滤器：按方块注册名（registry name，如 {@code "minecraft:furnace"}）
 * 精确匹配，支持尾部 {@code "*"} 通配（如 {@code "minecraft:*"} 匹配某个 mod 的
 * 全部方块）。只匹配方块本身，不读取 tile NBT（"无视对应的方块 nbt"）。
 *
 * <p>语义（{@code enabled} 时）：黑名单模式命中 → 禁止；白名单模式命中 → 放行。
 * 空黑名单 = 全部放行（等价关闭）；空白名单 = 全部禁止。{@code enabled=false}
 * 时恒放行。无命名空间的条目按 {@code minecraft:} 前缀处理（与
 * {@code ResourceLocation} 默认一致）。
 *
 * <p>纯字符串匹配，不依赖 MC 运行时，可脱离游戏环境单测；调用方（总线）负责
 * 从 {@code Block} 取注册名字符串后传入。
 */
public final class BlockListFilter {

    private final boolean enabled;
    private final boolean whitelist;
    private final String rawList;
    private final List<String> patterns;

    public BlockListFilter(final boolean enabled, final boolean whitelist, final String rawList) {
        this.enabled = enabled;
        this.whitelist = whitelist;
        this.rawList = rawList == null ? "" : rawList;
        this.patterns = parse(this.rawList);
    }

    /** 是否启用（供缓存自校验：字段变化时据此判断是否需要重建）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 是否为白名单模式（供缓存自校验）。 */
    public boolean isWhitelist() {
        return whitelist;
    }

    /** 原始列表字符串（供缓存自校验：列表内容变化时重建）。 */
    public String getRawList() {
        return rawList;
    }

    /** 判定：{@code registryName} 形如 {@code "modid:name"}（可单测）。 */
    public boolean allows(final String registryName) {
        if (!enabled) {
            return true;
        }
        if (registryName == null) {
            return false;
        }
        final boolean hit = matches(registryName);
        return whitelist ? hit : !hit;
    }

    private boolean matches(final String registryName) {
        for (final String pattern : patterns) {
            if (pattern.endsWith("*")) {
                if (registryName.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (pattern.equals(registryName)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> parse(final String rawList) {
        if (rawList == null || rawList.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>();
        for (final String part : rawList.split(",")) {
            final String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            out.add(normalize(entry));
        }
        return out;
    }

    /** 无命名空间且非纯通配时补 {@code "minecraft:"}；其余原样保留。 */
    private static String normalize(final String entry) {
        if (entry.indexOf(':') < 0 && !entry.endsWith("*")) {
            return "minecraft:" + entry;
        }
        return entry;
    }
}
