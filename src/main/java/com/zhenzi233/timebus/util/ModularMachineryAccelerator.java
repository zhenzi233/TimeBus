package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modular Machinery (CE) recipe-duration accelerator.
 *
 * <p>MM machine controllers extend {@code TileEntityRestrictedTick} whose
 * {@code update()} is final and deduplicated per world tick, so the Time Bus's
 * normal ITickable update-call path cannot speed them up. Instead we compress
 * the recipe duration inside MM's own modifier system: a duration modifier
 * (multiply by 1/N) is injected into each recipe thread's permanent modifiers.
 *
 * <p>The injected modifier is re-applied automatically to every new recipe
 * context ({@code RecipeThread.createContext} copies permanent/semi-permanent
 * modifiers), and {@code ActiveMachineRecipe.tick()} recomputes totalTick from
 * the context modifiers every tick, so the effect applies immediately and
 * survives recipe switches. Idempotency is guaranteed by
 * {@code hasPermanentModifier} plus a WeakHashMap that remembers the injected
 * multiplier per thread (so a config change re-applies the new value).
 *
 * <p>All access goes through reflection (MM is an optional mod; Time Bus has
 * no hard dependency). If the classes are absent or signatures change, the
 * calls fail softly and are logged.
 */
public final class ModularMachineryAccelerator {

    private ModularMachineryAccelerator() {
    }

    /** Modifier key prefix for the duration compression (namespaced to avoid collisions). */
    private static final String MODIFIER_KEY_PREFIX = "timebus_duration_accel";
    /** Modifier key prefix for the energy consumption/production scaling. */
    private static final String ENERGY_KEY_PREFIX = "timebus_energy_accel";

    /**
     * Remembers which source (Time Bus part / wand) injected a duration modifier
     * on which controller, so {@link #restoreAllForSource} can clean up when a
     * source disappears (e.g. a Time Bus is removed from the world). Without
     * this, injected modifiers would stay on machines forever and even be
     * written into the world save. World keys are weak references: entries go
     * away automatically when the World unloads.
     */
    private static final Map<World, Map<BlockPos, Set<String>>> INJECTED = new WeakHashMap<>();

    private static volatile boolean resolved;
    private static volatile boolean available;

    private static volatile Class<?> controllerClass;
    /**
     * getRecipeThreadList is declared separately on the concrete controller
     * classes (TileMachineController and TileFactoryController), not on the
     * shared base, so it must be resolved from the actual tile class at
     * runtime and cached per class.
     */
    private static final Map<Class<?>, Method> GET_RECIPE_THREAD_LIST = new ConcurrentHashMap<>();
    private static volatile Method getPermanentModifiers;
    private static volatile Method getModifier;
    private static volatile Method addPermanentModifier;
    private static volatile Method removePermanentModifier;
    private static volatile Constructor<?> recipeModifierCtor;
    private static volatile Object ioInput;
    private static volatile Object ioOutput;
    private static volatile int operationMultiply;
    /**
     * 配方时长 modifier 的 target（RequirementTypesMM.REQUIREMENT_DURATION）。
     * MM 计算 totalTick 时按该 target 查找 modifier（ActiveMachineRecipe.tick），
     * 传 null 或其它类型都不会命中；同时该 target 已注册，序列化/同步时才
     * 不会产生空注册名导致客户端反序列化崩溃。
     */
    private static volatile Object recipeDurationType;
    /**
     * 能耗 modifier 的 target（RequirementTypesMM.REQUIREMENT_ENERGY）。
     * MM 的 RequirementEnergy.deepCopyModified() 按该 target + IOType 匹配
     * modifier：input 放大机器每 tick 消耗，output 放大机器每 tick 产出，
     * 使加速后单次配方总耗电/总产出守恒。
     */
    private static volatile Object recipeEnergyType;

    /** True if the tile is an MM (CE) multiblock machine controller. */
    public static boolean isController(final TileEntity te) {
        if (te == null) {
            return false;
        }
        resolve();
        return available && controllerClass.isInstance(te);
    }

    /**
     * Compress the recipe duration of every recipe thread on the controller by
     * {@code accelerate}, under the per-source key derived from
     * {@code sourceKey}. Multiple sources (e.g. several Time Buses aimed at
     * the same controller) each get their own multiplier, and MM multiplies
     * all of them together, so the total speed-up stacks: the recipe runs
     * {@code (1 / speed_1) * (1 / speed_2) * ...} as fast.
     *
     * <p>Idempotent per (thread, source): re-applying the same multiplier
     * skips, a different multiplier replaces this source's own modifier only.
     *
     * Besides the duration modifier, an energy modifier (input and output, x speed)
     * is injected when mmEnergyFollowsSpeed is enabled so per-recipe total
     * energy stays unchanged.
     *
     * @return true if at least one thread was touched
     */
    public static boolean apply(final TileEntity te, final String sourceKey, final int accelerate) {
        if (te == null || accelerate <= 1) {
            return false;
        }
        resolve();
        if (!available) {
            return false;
        }
        final String durationKey = keyFor(sourceKey);
        final String energyInKey = keyForEnergyIn(sourceKey);
        final String energyOutKey = keyForEnergyOut(sourceKey);
        final float durationTarget = 1.0f / accelerate;
        final boolean scaleEnergy = TimeBusConfig.mmEnergyFollowsSpeed;
        boolean touched = false;
        try {
            final Method threadsGetter = getRecipeThreadListFor(te);
            if (threadsGetter == null) {
                return false;
            }
            final Object[] threads = (Object[]) threadsGetter.invoke(te);
            if (threads == null) {
                return false;
            }
            if (threads.length == 0) {
                // 工厂机器没有核心线程（CraftTweaker 未配置 addCoreThread）时线程列表为空，
                // 这是机器配置问题而非错误；用 debug 级别避免每个 tick 刷屏。
                TimeBus.LOGGER.debug("Time Bus: MM controller {} ({}) has no recipe threads",
                        te.getPos(), te.getClass().getSimpleName());
                return false;
            }
            for (final Object thread : threads) {
                if (thread == null) {
                    continue;
                }
                // 配方时长压缩：x 1/speed
                if (ensureModifier(thread, durationKey, recipeDurationType, ioInput, durationTarget)) {
                    touched = true;
                }
                if (scaleEnergy) {
                    // 能耗守恒：input（消耗）与 output（产出）都 x speed，
                    // 与时长压缩相抵，单次配方总耗电/总产出不变。
                    if (ensureModifier(thread, energyInKey, recipeEnergyType, ioInput, accelerate)) {
                        touched = true;
                    }
                    if (ensureModifier(thread, energyOutKey, recipeEnergyType, ioOutput, accelerate)) {
                        touched = true;
                    }
                } else {
                    // 配置关闭：摘掉旧的能耗 modifier（若之前开过）。
                    removeEnergyModifiers(thread, energyInKey, energyOutKey);
                }
            }
            if (touched) {
                rememberInjected(te, sourceKey);
                TimeBus.LOGGER.info("Time Bus: MM applied source={} speed={} at {} ({} threads, tile {})",
                        sourceKey, accelerate, te.getPos(), threads.length, te.getClass().getSimpleName());
            }
            return touched;
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: MM acceleration failed at {}: {}", te.getPos(), e.toString());
            return false;
        }
    }

    /**
     * Resolve getRecipeThreadList from the actual tile class, walking up the
     * hierarchy until a declaration is found (TileMachineController and
     * TileFactoryController each declare their own copy).
     */
    private static Method getRecipeThreadListFor(final TileEntity te) {
        if (te == null) {
            return null;
        }
        return GET_RECIPE_THREAD_LIST.computeIfAbsent(te.getClass(), clazz -> {
            Class<?> current = clazz;
            while (current != null) {
                try {
                    return current.getDeclaredMethod("getRecipeThreadList");
                } catch (NoSuchMethodException e) {
                    current = current.getSuperclass();
                }
            }
            return null;
        });
    }

    /**
     * 确保线程的 permanentModifiers 里 {@code key} 的 modifier 恰好为
     * {@code value}（按 targetType/ioTarget 构造）。已存在且值相同则跳过，
     * 否则替换该 key 的 modifier。
     *
     * @return true 表示本次实际写入/替换了 modifier
     */
    private static boolean ensureModifier(final Object thread, final String key,
                                          final Object targetType, final Object ioTarget,
                                          final float value) throws Exception {
        if (hasExactModifier(thread, key, value)) {
            return false;
        }
        removePermanentModifier.invoke(thread, key);
        final Object modifier = recipeModifierCtor.newInstance(targetType, ioTarget,
                value, operationMultiply, false);
        addPermanentModifier.invoke(thread, key, modifier);
        return true;
    }

    /** 移除能耗 modifier（配置关闭时清理残留）。 */
    private static void removeEnergyModifiers(final Object thread, final String energyInKey,
                                              final String energyOutKey) throws Exception {
        removePermanentModifier.invoke(thread, energyInKey);
        removePermanentModifier.invoke(thread, energyOutKey);
    }

    /** True if {@code thread} already carries exactly {@code target} under {@code key}. */
    private static boolean hasExactModifier(final Object thread, final String key, final float target) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> permanent = (Map<String, Object>) getPermanentModifiers.invoke(thread);
        final Object existing = permanent.get(key);
        return existing != null && Math.abs((Float) getModifier.invoke(existing) - target) < 1e-4f;
    }

    private static String keyFor(final String sourceKey) {
        return MODIFIER_KEY_PREFIX + ":" + (sourceKey == null ? "unknown" : sourceKey);
    }

    private static String keyForEnergyIn(final String sourceKey) {
        return ENERGY_KEY_PREFIX + ":in:" + (sourceKey == null ? "unknown" : sourceKey);
    }

    private static String keyForEnergyOut(final String sourceKey) {
        return ENERGY_KEY_PREFIX + ":out:" + (sourceKey == null ? "unknown" : sourceKey);
    }

    /** Remove this source's injected modifier again (immediate restore of the original duration). */
    public static void restore(final TileEntity te, final String sourceKey) {
        if (te == null) {
            return;
        }
        resolve();
        if (!available) {
            return;
        }
        final String durationKey = keyFor(sourceKey);
        final String energyInKey = keyForEnergyIn(sourceKey);
        final String energyOutKey = keyForEnergyOut(sourceKey);
        try {
            final Method threadsGetter = getRecipeThreadListFor(te);
            if (threadsGetter == null) {
                return;
            }
            final Object[] threads = (Object[]) threadsGetter.invoke(te);
            if (threads == null) {
                return;
            }
            int removed = 0;
            for (final Object thread : threads) {
                if (thread != null) {
                    removePermanentModifier.invoke(thread, durationKey);
                    removePermanentModifier.invoke(thread, energyInKey);
                    removePermanentModifier.invoke(thread, energyOutKey);
                    removed++;
                }
            }
            if (removed > 0) {
                TimeBus.LOGGER.info("Time Bus: MM restored source={} at {} ({} threads, tile {})",
                        sourceKey, te.getPos(), removed, te.getClass().getSimpleName());
            }
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: MM restore failed at {}: {}", te.getPos(), e.toString());
        } finally {
            forgetInjected(te, sourceKey);
        }
    }

    /**
     * Remove every modifier injected by {@code sourceKey} on any controller in
     * {@code world}. Called when the source goes away (Time Bus removed from
     * the world), so machines are not left permanently accelerated.
     */
    public static void restoreAllForSource(final World world, final String sourceKey) {
        if (world == null || sourceKey == null) {
            return;
        }
        resolve();
        if (!available) {
            return;
        }
        final Set<BlockPos> positions;
        synchronized (INJECTED) {
            final Map<BlockPos, Set<String>> byPos = INJECTED.get(world);
            positions = byPos == null ? java.util.Collections.emptySet() : new HashSet<>(byPos.keySet());
        }
        for (final BlockPos pos : positions) {
            if (!world.isBlockLoaded(pos)) {
                continue;
            }
            final TileEntity te = world.getTileEntity(pos);
            if (te != null) {
                restore(te, sourceKey);
            }
        }
    }

    /**
     * Remove every modifier injected by any source on any controller in
     * {@code world}. Used as a safety net when a world is about to be
     * unloaded (dimension unload / server shutdown), so injected modifiers
     * are never written into the world save and never leak into a reloaded
     * machine. Unlike {@link #restoreAllForSource}, this walks the whole
     * tracking map instead of a single source key.
     */
    public static void restoreAllForWorld(final World world) {
        if (world == null) {
            return;
        }
        resolve();
        if (!available) {
            return;
        }
        final List<Map.Entry<BlockPos, String>> pending = new ArrayList<>();
        synchronized (INJECTED) {
            final Map<BlockPos, Set<String>> byPos = INJECTED.get(world);
            if (byPos == null || byPos.isEmpty()) {
                return;
            }
            for (final Map.Entry<BlockPos, Set<String>> e : byPos.entrySet()) {
                final BlockPos pos = e.getKey();
                for (final String sourceKey : e.getValue()) {
                    pending.add(new AbstractMap.SimpleEntry<>(pos, sourceKey));
                }
            }
        }
        for (final Map.Entry<BlockPos, String> e : pending) {
            final TileEntity te = world.getTileEntity(e.getKey());
            if (te != null) {
                restore(te, e.getValue());
            }
        }
    }

    /** Remove every injected modifier in every world still tracked (server shutdown). */
    public static void restoreAll() {
        resolve();
        if (!available) {
            return;
        }
        final List<World> worlds;
        synchronized (INJECTED) {
            worlds = new ArrayList<>(INJECTED.keySet());
        }
        for (final World world : worlds) {
            restoreAllForWorld(world);
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (ModularMachineryAccelerator.class) {
            if (resolved) {
                return;
            }
            try {
                controllerClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController");
                final Class<?> recipeThreadClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.machine.RecipeThread");
                final Class<?> modifierClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.modifier.RecipeModifier");
                final Class<?> requirementTypeClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.crafting.requirement.type.RequirementType");
                final Class<?> ioTypeClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.machine.IOType");

                getPermanentModifiers = recipeThreadClass.getMethod("getPermanentModifiers");
                getModifier = modifierClass.getMethod("getModifier");
                addPermanentModifier = recipeThreadClass.getMethod(
                        "addPermanentModifier", String.class, modifierClass);
                removePermanentModifier = recipeThreadClass.getMethod("removePermanentModifier", String.class);
                recipeModifierCtor = modifierClass.getConstructor(
                        requirementTypeClass, ioTypeClass, float.class, int.class, boolean.class);

                final Field ioInputField = ioTypeClass.getField("INPUT");
                ioInput = ioInputField.get(null);
                ioOutput = ioTypeClass.getField("OUTPUT").get(null);
                operationMultiply = modifierClass.getField("OPERATION_MULTIPLY").getInt(null);
                // 配方时长与能耗专用 target；必须取注册过的实例，构造 modifier 时传给
                // 第一个参数，否则序列化出空注册名（见 serialize()/deserialize()）。
                final Class<?> requirementTypesClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.lib.RequirementTypesMM");
                recipeDurationType = requirementTypesClass.getField("REQUIREMENT_DURATION").get(null);
                recipeEnergyType = requirementTypesClass.getField("REQUIREMENT_ENERGY").get(null);
                available = true;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: MM acceleration unavailable: {}", e.toString());
                available = false;
            } finally {
                resolved = true;
            }
        }
    }

    /** 该控制器当前是否仍被某个加速来源注入（时间总线/时间杖）。 */
    public static boolean isAccelerated(final TileEntity te) {
        if (te == null) {
            return false;
        }
        synchronized (INJECTED) {
            final Map<BlockPos, Set<String>> byPos = INJECTED.get(te.getWorld());
            if (byPos == null) {
                return false;
            }
            final Set<String> sources = byPos.get(te.getPos());
            return sources != null && !sources.isEmpty();
        }
    }

    private static void rememberInjected(final TileEntity te, final String sourceKey) {
        if (te == null || sourceKey == null) {
            return;
        }
        synchronized (INJECTED) {
            INJECTED.computeIfAbsent(te.getWorld(), w -> new HashMap<>())
                    .computeIfAbsent(te.getPos(), p -> new HashSet<>())
                    .add(sourceKey);
        }
    }

    private static void forgetInjected(final TileEntity te, final String sourceKey) {
        if (te == null || sourceKey == null) {
            return;
        }
        synchronized (INJECTED) {
            final Map<BlockPos, Set<String>> byPos = INJECTED.get(te.getWorld());
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
                INJECTED.remove(te.getWorld());
            }
        }
    }
}