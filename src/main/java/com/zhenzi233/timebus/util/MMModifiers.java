package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Modular Machinery recipe modifier 的读写：从原
 * {@code ModularMachineryAccelerator} 里搬出的 key 命名、幂等增删与升级迁移。
 *
 * <p>所有操作都通过 {@link MMReflection.Handles} 里的反射句柄完成，本类自身不
 * 触碰反射解析，也不感知加速策略。key 的字符串值会随存档序列化，不可更改。
 */
final class MMModifiers {

    private MMModifiers() {
    }

    /** Modifier key prefix for the duration compression (namespaced to avoid collisions). */
    private static final String MODIFIER_KEY_PREFIX = "timebus_duration_accel";
    /** Modifier key prefix for the energy consumption/production scaling. */
    private static final String ENERGY_KEY_PREFIX = "timebus_energy_accel";

    /** 时长 modifier 的 key（字符串值随存档序列化，不可更改）。 */
    static String durationKey(final String sourceKey) {
        return MODIFIER_KEY_PREFIX + ":" + (sourceKey == null ? "unknown" : sourceKey);
    }

    /** 能耗 modifier（input，消耗）的 key。 */
    static String energyInKey(final String sourceKey) {
        return ENERGY_KEY_PREFIX + ":in:" + (sourceKey == null ? "unknown" : sourceKey);
    }

    /** 能耗 modifier（output，产出）的 key。 */
    static String energyOutKey(final String sourceKey) {
        return ENERGY_KEY_PREFIX + ":out:" + (sourceKey == null ? "unknown" : sourceKey);
    }

    /** True if {@code thread} already carries exactly {@code target} under {@code key}. */
    static boolean hasExactPermanent(final MMReflection.Handles handles, final Object thread,
                                     final String key, final float target) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> permanent = (Map<String, Object>) handles.getPermanentModifiers.invoke(thread);
        final Object existing = permanent.get(key);
        return existing != null && Math.abs((Float) handles.getModifier.invoke(existing) - target) < 1e-4f;
    }

    /**
     * 确保线程的 permanentModifiers 里 {@code key} 的 modifier 恰好为
     * {@code value}（按 targetType/ioTarget 构造）。已存在且值相同则跳过，
     * 否则替换该 key 的 modifier。
     *
     * <p>{@code forceRefresh} 时即使值相同也重新 remove+add，触发
     * {@code flushContextModifier()} 把 permanent 刷回当前 context（MM 的
     * context 池化复用可能让实际应用状态与数据源脱节）。
     *
     * @return true 表示数据源的值发生了实际变化（用于日志/记录）
     */
    static boolean ensurePermanent(final MMReflection.Handles handles, final Object thread,
                                   final String key, final Object targetType, final Object ioTarget,
                                   final float value, final boolean forceRefresh) throws Exception {
        final boolean exact = hasExactPermanent(handles, thread, key, value);
        if (exact && !forceRefresh) {
            return false;
        }
        handles.removePermanentModifier.invoke(thread, key);
        final Object modifier = handles.recipeModifierCtor.newInstance(targetType, ioTarget,
                value, handles.operationMultiply, false);
        handles.addPermanentModifier.invoke(thread, key, modifier);
        return !exact;
    }

    static boolean hasExactSemi(final MMReflection.Handles handles, final Object thread,
                                final String key, final float target) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> semi = (Map<String, Object>) handles.getSemiPermanentModifiers.invoke(thread);
        final Object existing = semi.get(key);
        return existing != null && Math.abs((Float) handles.getModifier.invoke(existing) - target) < 1e-4f;
    }

    /**
     * 确保线程的 semiPermanentModifiers 里 {@code key} 的 modifier 恰好为
     * {@code value}（配方专用，配方完成后 MM 自动清空整表）。已存在且值相同则
     * 跳过，否则替换该 key。add/removeModifier 内部自带 flushContextModifier，
     * 修改立即应用到当前 context。
     */
    static boolean ensureSemi(final MMReflection.Handles handles, final Object thread,
                              final String key, final Object targetType, final Object ioTarget,
                              final float value) throws Exception {
        final boolean exact = hasExactSemi(handles, thread, key, value);
        if (exact) {
            return false;
        }
        handles.removeModifier.invoke(thread, key);
        final Object modifier = handles.recipeModifierCtor.newInstance(targetType, ioTarget,
                value, handles.operationMultiply, false);
        handles.addModifier.invoke(thread, key, modifier);
        return true;
    }

    /** 移除能耗 modifier（配置关闭时清理残留）。 */
    static void removeEnergy(final MMReflection.Handles handles, final Object thread,
                             final String energyInKey, final String energyOutKey) throws Exception {
        handles.removePermanentModifier.invoke(thread, energyInKey);
        handles.removePermanentModifier.invoke(thread, energyOutKey);
    }

    /**
     * True if the key is a legacy TimeBus permanent modifier that must be purged
     * on upgrade: pre-1.0.9 bus keys ("bus:x,y,z" without the part side) and any
     * wand key in the permanent table (since v1.0.9 the wand only uses the
     * semi-permanent table; a wand key in permanent is always a pre-upgrade
     * leftover that would multiply with the new semi-permanent modifier and
     * instantly finish recipes).
     */
    static boolean isLegacyKey(final String key) {
        if (key == null) {
            return false;
        }
        final String[] legacyBusPrefixes = {
                MODIFIER_KEY_PREFIX + ":bus:",
                ENERGY_KEY_PREFIX + ":in:bus:",
                ENERGY_KEY_PREFIX + ":out:bus:"
        };
        for (final String prefix : legacyBusPrefixes) {
            if (key.startsWith(prefix)) {
                final String rest = key.substring(prefix.length());
                return rest.indexOf(':') < 0 && rest.matches("\\d+,\\d+,\\d+");
            }
        }
        // 旧版魔杖永久注入：duration / energy-in / energy-out 三个 key 前缀。
        return key.startsWith(MODIFIER_KEY_PREFIX + ":wand:")
                || key.startsWith(ENERGY_KEY_PREFIX + ":in:wand:")
                || key.startsWith(ENERGY_KEY_PREFIX + ":out:wand:");
    }

    /**
     * 清除线程 permanent 表里所有旧版 TimeBus modifier（升级迁移）。
     * 返回清除数量，便于日志。
     */
    static int purgeLegacy(final MMReflection.Handles handles, final Object thread) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> permanent = (Map<String, Object>) handles.getPermanentModifiers.invoke(thread);
        if (permanent.isEmpty()) {
            return 0;
        }
        final List<String> legacy = new ArrayList<>();
        for (final String key : permanent.keySet()) {
            if (isLegacyKey(key)) {
                legacy.add(key);
            }
        }
        for (final String key : legacy) {
            handles.removePermanentModifier.invoke(thread, key);
        }
        if (!legacy.isEmpty()) {
            TimeBus.LOGGER.info("Time Bus: purged {} legacy permanent MM modifier key(s) {}", legacy.size(), legacy);
        }
        return legacy.size();
    }
}
