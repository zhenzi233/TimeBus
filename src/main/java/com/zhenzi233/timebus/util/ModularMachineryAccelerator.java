package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
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

    /**
     * Tracks which source (Time Bus part / wand) injected a duration modifier
     * on which controller, so {@link #restoreAllForSource} can clean up when a
     * source disappears (e.g. a Time Bus is removed from the world), and caches
     * the last applied multiplier + thread count so {@link #apply} can skip the
     * reflection hot path while nothing has changed. Without this, injected
     * modifiers would stay on machines forever and even be written into the
     * world save. World keys are weak references: entries go away
     * automatically when the World unloads.
     */
    private static final Map<World, Map<BlockPos, Map<String, CachedInjection>>> INJECTED = new WeakHashMap<>();

    /** 上次注入状态缓存：来源在某个控制器上注入的倍率与当时的线程数。
     *  倍率与线程数都未变化时，apply 的快路径直接信任缓存，热路径零反射；
     *  任一变化（速度卡调整 / 机器重建线程）都会重新走完整注入流程。 */
    private static final class CachedInjection {
        final int speed;
        final int threadCount;

        CachedInjection(final int speed, final int threadCount) {
            this.speed = speed;
            this.threadCount = threadCount;
        }
    }

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
    private static volatile int operationMultiply;

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
     * @return true if the acceleration is in effect (threads were touched this
     *         call, or the cached state already matches so nothing was needed)
     */
    public static boolean apply(final TileEntity te, final String sourceKey, final int accelerate) {
        if (te == null || accelerate <= 1) {
            return false;
        }
        resolve();
        if (!available) {
            return false;
        }
        final String key = keyFor(sourceKey);
        final float target = 1.0f / accelerate;
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

            // 快路径：上次注入的倍率与线程数都未变，直接信任缓存，
            // 热路径不再做 per-thread 的反射检查。
            final CachedInjection cached = getCached(te, sourceKey);
            if (cached != null && cached.speed == accelerate && cached.threadCount == threads.length) {
                return cached.threadCount > 0;
            }

            if (threads.length == 0) {
                // 工厂机器没有核心线程（CraftTweaker 未配置 addCoreThread）时线程列表为空，
                // 这是机器配置问题而非错误；缓存空状态避免每个 tick 刷屏。
                rememberInjected(te, sourceKey, accelerate, 0);
                return false;
            }
            for (final Object thread : threads) {
                if (thread == null) {
                    continue;
                }
                if (hasExactModifier(thread, key, target)) {
                    continue; // already injected with this multiplier
                }
                removePermanentModifier.invoke(thread, key);
                final Object modifier = recipeModifierCtor.newInstance(null, ioInput,
                        target, operationMultiply, false);
                addPermanentModifier.invoke(thread, key, modifier);
                touched = true;
            }
            // 无论本次是否实际注入过，只要线程列表与倍率已确认一致就缓存，
            // 让下一次 apply 直接走快路径。
            rememberInjected(te, sourceKey, accelerate, threads.length);
            if (touched) {
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

    /** Remove this source's injected modifier again (immediate restore of the original duration). */
    public static void restore(final TileEntity te, final String sourceKey) {
        if (te == null) {
            return;
        }
        resolve();
        if (!available) {
            return;
        }
        final String key = keyFor(sourceKey);
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
                    removePermanentModifier.invoke(thread, key);
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
            final Map<BlockPos, Map<String, CachedInjection>> byPos = INJECTED.get(world);
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
            final Map<BlockPos, Map<String, CachedInjection>> byPos = INJECTED.get(world);
            if (byPos == null || byPos.isEmpty()) {
                return;
            }
            for (final Map.Entry<BlockPos, Map<String, CachedInjection>> e : byPos.entrySet()) {
                final BlockPos pos = e.getKey();
                for (final String sourceKey : e.getValue().keySet()) {
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
                operationMultiply = modifierClass.getField("OPERATION_MULTIPLY").getInt(null);
                available = true;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: MM acceleration unavailable: {}", e.toString());
                available = false;
            } finally {
                resolved = true;
            }
        }
    }

    private static void rememberInjected(final TileEntity te, final String sourceKey,
                                         final int speed, final int threadCount) {
        if (te == null || sourceKey == null) {
            return;
        }
        synchronized (INJECTED) {
            INJECTED.computeIfAbsent(te.getWorld(), w -> new HashMap<>())
                    .computeIfAbsent(te.getPos(), p -> new HashMap<>())
                    .put(sourceKey, new CachedInjection(speed, threadCount));
        }
    }

    /** 读取某来源在控制器上的上次注入状态；无记录返回 null。 */
    private static CachedInjection getCached(final TileEntity te, final String sourceKey) {
        if (te == null || sourceKey == null) {
            return null;
        }
        synchronized (INJECTED) {
            final Map<BlockPos, Map<String, CachedInjection>> byPos = INJECTED.get(te.getWorld());
            if (byPos == null) {
                return null;
            }
            final Map<String, CachedInjection> sources = byPos.get(te.getPos());
            return sources == null ? null : sources.get(sourceKey);
        }
    }

    private static void forgetInjected(final TileEntity te, final String sourceKey) {
        if (te == null || sourceKey == null) {
            return;
        }
        synchronized (INJECTED) {
            final Map<BlockPos, Map<String, CachedInjection>> byPos = INJECTED.get(te.getWorld());
            if (byPos == null) {
                return;
            }
            final Map<String, CachedInjection> sources = byPos.get(te.getPos());
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
