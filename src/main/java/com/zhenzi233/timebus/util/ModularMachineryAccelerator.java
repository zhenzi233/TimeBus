package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

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

    /** 加速来源前缀：Time Bus 部件（"bus:x,y,z:SIDE"）。 */
    public static final String SOURCE_BUS_PREFIX = "bus:";
    /** 加速来源前缀：Time Wand（"wand:playerUUID"）。 */
    public static final String SOURCE_WAND_PREFIX = "wand:";

    /**
     * 来源标识是否来自时间杖：总线走每 tick 注入路径，魔杖走 semi-permanent
     * 配方加速路径（配方完成自动恢复）。判定收拢到一处，避免各处裸字符串前缀。
     */
    public static boolean isWandSource(final String sourceKey) {
        return sourceKey != null && sourceKey.startsWith(SOURCE_WAND_PREFIX);
    }

    /**
     * Remembers which source (Time Bus part / wand) injected a duration modifier
     * on which controller, so {@link #restoreAllForSource} can clean up when a
     * source disappears (e.g. a Time Bus is removed from the world). Without
     * this, injected modifiers would stay on machines forever and even be
     * written into the world save. World keys are weak references: entries go
     * away automatically when the World unloads.
     */
    private static final Map<World, Map<BlockPos, Set<String>>> INJECTED = new WeakHashMap<>();

    /**
     * 记录每个控制器上次"强制刷新"的世界 tick。
     *
     * <p>MM 的 context 是池化的：{@code setContext()} 会把旧 context 归还
     * {@code RecipeCraftingContextPool}，新 context 是 reset 过的空状态。
     * 此时线程的 permanentModifiers 数据源仍持有我们的 modifier（幂等检查
     * 通过），但实际应用的 context 已丢失 —— 只有重新写入 modifier 触发
     * {@code flushContextModifier()} 才会把 permanent 刷回 context。因此
     * 按配置的 mmContextRefreshInterval（0 = 关闭）周期性无条件重注入，
     * 保证 context 脱节后最多一个间隔内自愈。
     */
    private static final Map<World, Map<BlockPos, Long>> LAST_FORCE_REFRESH = new WeakHashMap<>();

    /**
     * 每个 (world, pos, sourceKey) 上次成功注入的加速状态快照。
     *
     * <p>稳态下（倍率未变、能耗守恒开关未变、未到强制刷新周期）直接跳过整轮
     * 反射巡检，把每 tick 的 MM 开销降为零（代码审查 3.1）。正确性由两点兜底：
     * 1) {@link #shouldForceRefresh} 按 mmContextRefreshInterval 周期强制重走反射
     * 路径（context 池化脱节自愈）；2) 倍率 / 能耗配置变化会使快照失配，自动重走。
     */
    private static final Map<World, Map<BlockPos, Map<String, AppliedState>>> APPLIED = new WeakHashMap<>();

    /** 已注入状态快照（{@link #APPLIED} 的值）。 */
    private static final class AppliedState {
        final int speed;
        final boolean energyFollows;

        AppliedState(final int speed, final boolean energyFollows) {
            this.speed = speed;
            this.energyFollows = energyFollows;
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
    private static volatile Method getSemiPermanentModifiers;
    private static volatile Method addModifier;
    private static volatile Method removeModifier;
    private static volatile Method getActiveRecipe;
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
        final boolean scaleEnergy = TimeBusConfig.MM.mmEnergyFollowsSpeed;
        final boolean forceRefresh = shouldForceRefresh(te);
        // 稳态快路径：状态未变且未到强制刷新周期时跳过整轮反射巡检。
        if (!forceRefresh && isAppliedState(te, sourceKey, accelerate, scaleEnergy)) {
            return false;
        }
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
                // 升级迁移：清除旧版总线（无 side）与旧版魔杖 permanent modifier，
                // 避免残留与新的 semi-permanent 连乘导致进度瞬间完成。
                purgeLegacyTimeBusKeys(thread);
                // 配方时长压缩：x 1/speed
                if (ensureModifier(thread, durationKey, recipeDurationType, ioInput, durationTarget, forceRefresh)) {
                    touched = true;
                }
                if (scaleEnergy) {
                    // 能耗守恒：input（消耗）与 output（产出）都 x speed，
                    // 与时长压缩相抵，单次配方总耗电/总产出不变。
                    if (ensureModifier(thread, energyInKey, recipeEnergyType, ioInput, accelerate, forceRefresh)) {
                        touched = true;
                    }
                    if (ensureModifier(thread, energyOutKey, recipeEnergyType, ioOutput, accelerate, forceRefresh)) {
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
            // 无论本轮是否实际改动，都刷新快照，使后续 tick 可走快路径。
            rememberApplied(te, sourceKey, accelerate, scaleEnergy);
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
     * <p>{@code forceRefresh} 时即使值相同也重新 remove+add，触发
     * {@code flushContextModifier()} 把 permanent 刷回当前 context（MM 的
     * context 池化复用可能让实际应用状态与数据源脱节）。
     *
     * @return true 表示数据源的值发生了实际变化（用于日志/记录）
     */
    private static boolean ensureModifier(final Object thread, final String key,
                                          final Object targetType, final Object ioTarget,
                                          final float value, final boolean forceRefresh) throws Exception {
        final boolean exact = hasExactModifier(thread, key, value);
        if (exact && !forceRefresh) {
            return false;
        }
        removePermanentModifier.invoke(thread, key);
        final Object modifier = recipeModifierCtor.newInstance(targetType, ioTarget,
                value, operationMultiply, false);
        addPermanentModifier.invoke(thread, key, modifier);
        return !exact;
    }

    /** 距上次强制刷新是否已达到配置的间隔（达到则记录本次并返回 true；间隔 0 = 关闭）。 */
    private static boolean shouldForceRefresh(final TileEntity te) {
        if (te == null || te.getWorld() == null) {
            return false;
        }
        final int interval = TimeBusConfig.MM.mmContextRefreshInterval;
        if (interval <= 0) {
            return false;
        }
        final long now = te.getWorld().getTotalWorldTime();
        synchronized (LAST_FORCE_REFRESH) {
            final Map<BlockPos, Long> byPos = LAST_FORCE_REFRESH.get(te.getWorld());
            final Long last = byPos == null ? null : byPos.get(te.getPos());
            if (last != null && now - last < interval) {
                return false;
            }
            LAST_FORCE_REFRESH.computeIfAbsent(te.getWorld(), w -> new HashMap<>())
                    .put(te.getPos(), now);
            return true;
        }
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

    /**
     * 确保线程的 semiPermanentModifiers 里 {@code key} 的 modifier 恰好为
     * {@code value}（配方专用，配方完成后 MM 自动清空整表）。已存在且值相同则
     * 跳过，否则替换该 key。add/removeModifier 内部自带 flushContextModifier，
     * 修改立即应用到当前 context。
     */
    private static boolean ensureSemiModifier(final Object thread, final String key,
                                              final Object targetType, final Object ioTarget,
                                              final float value) throws Exception {
        final boolean exact = hasExactSemiModifier(thread, key, value);
        if (exact) {
            return false;
        }
        removeModifier.invoke(thread, key);
        final Object modifier = recipeModifierCtor.newInstance(targetType, ioTarget,
                value, operationMultiply, false);
        addModifier.invoke(thread, key, modifier);
        return true;
    }

    private static boolean hasExactSemiModifier(final Object thread, final String key, final float target) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> semi = (Map<String, Object>) getSemiPermanentModifiers.invoke(thread);
        final Object existing = semi.get(key);
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
                    // 升级迁移：清除旧版总线/魔杖 permanent modifier（若之前开过并残留）。
                    purgeLegacyTimeBusKeys(thread);
                    final Map<String, Object> permanent =
                            (Map<String, Object>) getPermanentModifiers.invoke(thread);
                    final int before = permanent.size();
                    removePermanentModifier.invoke(thread, durationKey);
                    removePermanentModifier.invoke(thread, energyInKey);
                    removePermanentModifier.invoke(thread, energyOutKey);
                    removed += before - permanent.size();
                }
            }
            if (removed > 0) {
                // debug 级：restore 可能被周期性调用（断电/失活时每 20 tick 一次），
                // 只有实际移除了 modifier 才输出，避免无意义的刷屏。
                TimeBus.LOGGER.debug("Time Bus: MM restored source={} at {} (removed {} modifier(s), tile {})",
                        sourceKey, te.getPos(), removed, te.getClass().getSimpleName());
            }
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: MM restore failed at {}: {}", te.getPos(), e.toString());
        } finally {
            forgetInjected(te, sourceKey);
            // 同时清掉"已应用"快照与强制刷新记录：否则重新 apply 时会被
            // isAppliedState 快路径跳过，导致恢复加速后机器反而不加速。
            forgetApplied(te, sourceKey);
            forgetForceRefresh(te);
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

    /**
     * Remove every injected modifier on controllers inside {@code chunk} before
     * the chunk is unloaded. A single chunk unload (while the world keeps
     * running) would otherwise let injected modifiers fall into the save and
     * permanently accelerate the machine after a restart (代码审查 4.2).
     * Tiles are pulled from the chunk's tile map directly: by the time
     * {@code ChunkEvent.Unload} fires, {@code Chunk.onUnload()} has already
     * invalidated every tile ({@code getTileEntity(pos, CHECK)} returns null),
     * but the objects themselves are still intact and safe to restore.
     */
    public static void restoreAllForChunk(final World world, final Chunk chunk) {
        if (world == null || chunk == null) {
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
            final int chunkX = chunk.getPos().x;
            final int chunkZ = chunk.getPos().z;
            for (final Map.Entry<BlockPos, Set<String>> e : byPos.entrySet()) {
                final BlockPos pos = e.getKey();
                if ((pos.getX() >> 4) != chunkX || (pos.getZ() >> 4) != chunkZ) {
                    continue;
                }
                for (final String sourceKey : e.getValue()) {
                    pending.add(new AbstractMap.SimpleEntry<>(pos, sourceKey));
                }
            }
        }
        for (final Map.Entry<BlockPos, String> e : pending) {
            final TileEntity te = chunk.getTileEntityMap().get(e.getKey());
            if (te != null) {
                restore(te, e.getValue());
            }
        }
    }

    /** 机器是否有正在运行的配方（魔杖点击前的预检，避免空点扣费）。 */
    public static boolean hasActiveRecipes(final TileEntity te) {
        if (te == null) {
            return false;
        }
        resolve();
        if (!available) {
            return false;
        }
        try {
            final Method threadsGetter = getRecipeThreadListFor(te);
            if (threadsGetter == null) {
                return false;
            }
            final Object[] threads = (Object[]) threadsGetter.invoke(te);
            if (threads == null) {
                return false;
            }
            for (final Object thread : threads) {
                if (thread != null && getActiveRecipe.invoke(thread) != null) {
                    return true;
                }
            }
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: MM active-recipe check failed at {}: {}", te.getPos(), e.toString());
        }
        return false;
    }

    /**
     * 魔杖一次性加速（semi-permanent modifier 方案）：给所有"正在运行配方"的
     * 线程注入当前配方专用的加速 modifier——配方时长 ×1/speed，能耗 input/output
     * ×speed（配置 {@code mmEnergyFollowsSpeed} 开启时，每 tick 耗电/产出放大、
     * 单次配方总耗电守恒）。
     *
     * <p>modifier 写入 {@code RecipeThread.semiPermanentModifiers}：MM 在配方完成
     * （或失败）时自动清空该表，因此加速效果**只持续到当前进度完成**，之后所有
     * 配方恢复原速，不会留下任何持久状态。空闲线程不注入（它们没有当前配方，
     * 注入会让未来的配方也被加速）。重复点击幂等：同倍率跳过，不同倍率替换。
     *
     * @return true 表示至少一个线程被注入/更新
     */
    public static boolean applyWandToActiveRecipes(final TileEntity te, final String sourceKey, final int speed) {
        if (te == null || speed <= 1 || sourceKey == null) {
            return false;
        }
        resolve();
        if (!available) {
            return false;
        }
        final String durationKey = keyFor(sourceKey);
        final String energyInKey = keyForEnergyIn(sourceKey);
        final String energyOutKey = keyForEnergyOut(sourceKey);
        final float durationTarget = 1.0f / speed;
        final boolean scaleEnergy = TimeBusConfig.MM.mmEnergyFollowsSpeed;
        boolean touched = false;
        try {
            final Method threadsGetter = getRecipeThreadListFor(te);
            if (threadsGetter == null) {
                return false;
            }
            final Object[] threads = (Object[]) threadsGetter.invoke(te);
            if (threads == null || threads.length == 0) {
                return false;
            }
            for (final Object thread : threads) {
                if (thread == null) {
                    continue;
                }
                final Object active = getActiveRecipe.invoke(thread);
                if (active == null) {
                    continue; // 只加速正在运行的配方；空闲线程不注入
                }
                // 升级迁移：先清旧版 permanent 残留（v1.0.8 及以前的魔杖/总线注入），
                // 避免与 semi-permanent 连乘。
                purgeLegacyTimeBusKeys(thread);
                if (ensureSemiModifier(thread, durationKey, recipeDurationType, ioInput, durationTarget)) {
                    touched = true;
                }
                if (scaleEnergy) {
                    if (ensureSemiModifier(thread, energyInKey, recipeEnergyType, ioInput, speed)) {
                        touched = true;
                    }
                    if (ensureSemiModifier(thread, energyOutKey, recipeEnergyType, ioOutput, speed)) {
                        touched = true;
                    }
                }
            }
            if (touched) {
                TimeBus.LOGGER.info("Time Bus: wand MM semi-accelerated source={} speed={} at {} ({} threads, tile {})",
                        sourceKey, speed, te.getPos(), threads.length, te.getClass().getSimpleName());
            }
            return touched;
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: wand MM acceleration failed at {}: {}", te.getPos(), e.toString());
            return false;
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
                getSemiPermanentModifiers = recipeThreadClass.getMethod("getSemiPermanentModifiers");
                addModifier = recipeThreadClass.getMethod("addModifier", String.class, modifierClass);
                removeModifier = recipeThreadClass.getMethod("removeModifier", String.class);
                final Class<?> activeRecipeClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.crafting.ActiveMachineRecipe");
                getActiveRecipe = recipeThreadClass.getMethod("getActiveRecipe");
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
        // 同步清理各状态缓存，避免拆除/过期后残留（代码审查 4.4）。
        forgetApplied(te, sourceKey);
        forgetForceRefresh(te);
    }

    /**
     * True if the key is a legacy TimeBus permanent modifier that must be purged
     * on upgrade: pre-1.0.9 bus keys ("bus:x,y,z" without the part side) and any
     * wand key in the permanent table (since v1.0.9 the wand only uses the
     * semi-permanent table; a wand key in permanent is always a pre-upgrade
     * leftover that would multiply with the new semi-permanent modifier and
     * instantly finish recipes).
     */
    private static boolean isLegacyTimeBusKey(final String key) {
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
    private static int purgeLegacyTimeBusKeys(final Object thread) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> permanent = (Map<String, Object>) getPermanentModifiers.invoke(thread);
        if (permanent.isEmpty()) {
            return 0;
        }
        final List<String> legacy = new ArrayList<>();
        for (final String key : permanent.keySet()) {
            if (isLegacyTimeBusKey(key)) {
                legacy.add(key);
            }
        }
        for (final String key : legacy) {
            removePermanentModifier.invoke(thread, key);
        }
        if (!legacy.isEmpty()) {
            TimeBus.LOGGER.info("Time Bus: purged {} legacy permanent MM modifier key(s) {}", legacy.size(), legacy);
        }
        return legacy.size();
    }

    private static boolean isAppliedState(final TileEntity te, final String sourceKey,
                                          final int speed, final boolean energyFollows) {
        if (te == null || te.getWorld() == null || sourceKey == null) {
            return false;
        }
        synchronized (APPLIED) {
            final Map<BlockPos, Map<String, AppliedState>> byPos = APPLIED.get(te.getWorld());
            if (byPos == null) {
                return false;
            }
            final Map<String, AppliedState> bySource = byPos.get(te.getPos());
            if (bySource == null) {
                return false;
            }
            final AppliedState state = bySource.get(sourceKey);
            return state != null && state.speed == speed && state.energyFollows == energyFollows;
        }
    }

    private static void rememberApplied(final TileEntity te, final String sourceKey,
                                        final int speed, final boolean energyFollows) {
        if (te == null || te.getWorld() == null || sourceKey == null) {
            return;
        }
        synchronized (APPLIED) {
            APPLIED.computeIfAbsent(te.getWorld(), w -> new HashMap<>())
                    .computeIfAbsent(te.getPos(), p -> new HashMap<>())
                    .put(sourceKey, new AppliedState(speed, energyFollows));
        }
    }

    private static void forgetApplied(final TileEntity te, final String sourceKey) {
        if (te == null || te.getWorld() == null || sourceKey == null) {
            return;
        }
        synchronized (APPLIED) {
            final Map<BlockPos, Map<String, AppliedState>> byPos = APPLIED.get(te.getWorld());
            if (byPos == null) {
                return;
            }
            final Map<String, AppliedState> bySource = byPos.get(te.getPos());
            if (bySource != null) {
                bySource.remove(sourceKey);
                if (bySource.isEmpty()) {
                    byPos.remove(te.getPos());
                }
            }
            if (byPos.isEmpty()) {
                APPLIED.remove(te.getWorld());
            }
        }
    }

    private static void forgetForceRefresh(final TileEntity te) {
        if (te == null || te.getWorld() == null) {
            return;
        }
        synchronized (LAST_FORCE_REFRESH) {
            final Map<BlockPos, Long> byPos = LAST_FORCE_REFRESH.get(te.getWorld());
            if (byPos != null) {
                byPos.remove(te.getPos());
                if (byPos.isEmpty()) {
                    LAST_FORCE_REFRESH.remove(te.getWorld());
                }
            }
        }
    }

}
