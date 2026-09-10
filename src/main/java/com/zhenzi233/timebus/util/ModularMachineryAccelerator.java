package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

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
 * <p>加速来源分两条追踪路径：总线每 tick 注入的 permanent modifier 登记在
 * {@link MMTracking}（源消失时按源恢复，断电/拆除/卸载均覆盖）；魔杖注入的
 * semi-permanent 配方加速同样登记追踪，配方完成由 MM 自动清空，拆机/卸载/关服
 * 由兜底清理摘除。两张表都随世界弱引用，卸载后自动消失。
 *
 * <p>实现拆分为三个包内类：{@link MMReflection}（反射句柄解析与缓存）、
 * {@link MMTracking}（四张状态追踪表）、{@link MMModifiers}（modifier key 与幂等增删）；
 * 本类只保留公开 API 与加速策略编排。
 *
 * <p>All access goes through reflection (MM is an optional mod; Time Bus has
 * no hard dependency). If the classes are absent or signatures change, the
 * calls fail softly and are logged.
 */
public final class ModularMachineryAccelerator {

    private ModularMachineryAccelerator() {
    }

    /** 加速来源前缀：Time Bus 部件（"bus:x,y,z:SIDE"）。 */
    public static final String SOURCE_BUS_PREFIX = "bus:";
    /** 加速来源前缀：Time Wand（"wand:playerUUID"）。 */
    public static final String SOURCE_WAND_PREFIX = "wand:";
    /**
     * MM 配方时长加速的等效倍率上限。
     *
     * <p>时长 modifier 为乘法（配方总时长 × 1/speed），MM 每 tick 用
     * {@code Math.round} 把结果取整为 {@code totalTick}：倍率过高（多台总线/
     * 魔杖叠加、或配置把倍率调大）时 totalTick 会被取整成 0/1，配方表现为
     * "完成但不出货"或进度异常。32x 下 1 秒配方（20 tick）仍可取整为 1 tick，
     * 是安全上限；能耗 modifier 与时长共用同一有效倍率，保证单次配方总耗电守恒。
     */
    private static final int MAX_MM_EFFECTIVE_SPEED = 32;
    /**
     * 来源标识是否来自时间杖：总线走每 tick 注入路径，魔杖走 semi-permanent
     * 配方加速路径（配方完成自动恢复）。判定收拢到一处，避免各处裸字符串前缀。
     */
    public static boolean isWandSource(final String sourceKey) {
        return sourceKey != null && sourceKey.startsWith(SOURCE_WAND_PREFIX);
    }

    /** True if the tile is an MM (CE) multiblock machine controller. */
    public static boolean isController(final TileEntity te) {
        if (te == null) {
            return false;
        }
        final MMReflection.Handles handles = MMReflection.handles();
        return handles != null && handles.controllerClass.isInstance(te);
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
        final MMReflection.Handles handles = MMReflection.handles();
        if (handles == null) {
            return false;
        }
        final String durationKey = MMModifiers.durationKey(sourceKey);
        final String energyInKey = MMModifiers.energyInKey(sourceKey);
        final String energyOutKey = MMModifiers.energyOutKey(sourceKey);
        // 有效倍率封顶:防叠加/极端配置把配方时长压到取整为 0/1 tick(见常量注释)。
        final int effectiveSpeed = Math.min(accelerate, MAX_MM_EFFECTIVE_SPEED);
        final float durationTarget = 1.0f / effectiveSpeed;
        final boolean scaleEnergy = TimeBusConfig.MM.mmEnergyFollowsSpeed;
        final boolean forceRefresh = MMTracking.shouldForceRefresh(te, TimeBusConfig.MM.mmContextRefreshInterval);
        // 稳态快路径：状态未变且未到强制刷新周期时跳过整轮反射巡检。
        if (!forceRefresh && MMTracking.isAppliedState(te, sourceKey, effectiveSpeed, scaleEnergy)) {
            return false;
        }
        boolean touched = false;
        try {
            final Method threadsGetter = MMReflection.getRecipeThreadListFor(te);
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
                MMModifiers.purgeLegacy(handles, thread);
                // 配方时长压缩：x 1/speed
                if (MMModifiers.ensurePermanent(handles, thread, durationKey,
                                handles.recipeDurationType, handles.ioInput, durationTarget, forceRefresh)) {
                    touched = true;
                }
                if (scaleEnergy) {
                    // 能耗守恒：input（消耗）与 output（产出）都 x effectiveSpeed，
                    // 与时长压缩相抵，单次配方总耗电/总产出不变。
                    if (MMModifiers.ensurePermanent(handles, thread, energyInKey,
                                handles.recipeEnergyType, handles.ioInput, effectiveSpeed, forceRefresh)) {
                        touched = true;
                    }
                    if (MMModifiers.ensurePermanent(handles, thread, energyOutKey,
                                handles.recipeEnergyType, handles.ioOutput, effectiveSpeed, forceRefresh)) {
                        touched = true;
                    }
                } else {
                    // 配置关闭：摘掉旧的能耗 modifier（若之前开过）。
                    MMModifiers.removeEnergy(handles, thread, energyInKey, energyOutKey);
                }
            }
            if (touched) {
                MMTracking.rememberInjected(te, sourceKey);
                TimeBus.LOGGER.info("Time Bus: MM applied source={} speed={} at {} ({} threads, tile {})",
                        sourceKey, effectiveSpeed, te.getPos(), threads.length, te.getClass().getSimpleName());
            }
            // 无论本轮是否实际改动，都刷新快照，使后续 tick 可走快路径。
            MMTracking.rememberApplied(te, sourceKey, effectiveSpeed, scaleEnergy);
            return touched;
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: MM acceleration failed at {}: {}", te.getPos(), e.toString());
            return false;
        }
    }

    /** Remove this source's injected modifier again (immediate restore of the original duration). */
    public static void restore(final TileEntity te, final String sourceKey) {
        if (te == null) {
            return;
        }
        final MMReflection.Handles handles = MMReflection.handles();
        if (handles == null) {
            return;
        }
        final String durationKey = MMModifiers.durationKey(sourceKey);
        final String energyInKey = MMModifiers.energyInKey(sourceKey);
        final String energyOutKey = MMModifiers.energyOutKey(sourceKey);
        try {
            final Method threadsGetter = MMReflection.getRecipeThreadListFor(te);
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
                    MMModifiers.purgeLegacy(handles, thread);
                    final Map<String, Object> permanent =
                            (Map<String, Object>) handles.getPermanentModifiers.invoke(thread);
                    final int before = permanent.size();
                    handles.removePermanentModifier.invoke(thread, durationKey);
                    handles.removePermanentModifier.invoke(thread, energyInKey);
                    handles.removePermanentModifier.invoke(thread, energyOutKey);
                    removed += before - permanent.size();
                    // 半永久表：魔杖点击注入的配方加速。配方自然完成时 MM 会自行
                    // 清空；这里兜底配方中途拆机/区块卸载/关服等场景，幂等无害。
                    final Map<String, Object> semi =
                            (Map<String, Object>) handles.getSemiPermanentModifiers.invoke(thread);
                    final int semiBefore = semi.size();
                    handles.removeModifier.invoke(thread, durationKey);
                    handles.removeModifier.invoke(thread, energyInKey);
                    handles.removeModifier.invoke(thread, energyOutKey);
                    removed += semiBefore - semi.size();
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
            MMTracking.forgetInjected(te, sourceKey);
            MMTracking.forgetSemiInjected(te, sourceKey);
            // 同时清掉"已应用"快照与强制刷新记录：否则重新 apply 时会被
            // isAppliedState 快路径跳过，导致恢复加速后机器反而不加速。
            MMTracking.forgetApplied(te, sourceKey);
            MMTracking.forgetForceRefresh(te);
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
        if (!available()) {
            return;
        }
        final Set<BlockPos> positions = MMTracking.injectedPositions(world);
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
        if (!available()) {
            return;
        }
        final List<Entry<BlockPos, String>> pending =
                MMTracking.collectPendingRestores(world, Integer.MIN_VALUE, Integer.MIN_VALUE);
        for (final Entry<BlockPos, String> e : pending) {
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
        if (!available()) {
            return;
        }
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        final List<Entry<BlockPos, String>> pending =
                MMTracking.collectPendingRestores(world, chunkX, chunkZ);
        for (final Entry<BlockPos, String> e : pending) {
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
        final MMReflection.Handles handles = MMReflection.handles();
        if (handles == null) {
            return false;
        }
        try {
            final Method threadsGetter = MMReflection.getRecipeThreadListFor(te);
            if (threadsGetter == null) {
                return false;
            }
            final Object[] threads = (Object[]) threadsGetter.invoke(te);
            if (threads == null) {
                return false;
            }
            for (final Object thread : threads) {
                if (thread != null && handles.getActiveRecipe.invoke(thread) != null) {
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
     * 配方恢复原速。空闲线程不注入（它们没有当前配方，注入会让未来的配方也被
     * 加速）。重复点击幂等：同倍率跳过，不同倍率替换。
     *
     * <p>兜底清理：注入成功的同时登记进 {@link MMTracking}。配方自然完成
     * 由 MM 自动清空；配方中途拆机、区块卸载或关服等场景由
     * {@link #restoreAllForWorld}/{@link #restoreAllForChunk}/{@link #restoreAll}
     * 统一摘除，双保险确保加速状态不会随存档残留。
     *
     * @return true 表示至少一个线程被注入/更新
     */
    public static boolean applyWandToActiveRecipes(final TileEntity te, final String sourceKey, final int speed) {
        if (te == null || speed <= 1 || sourceKey == null) {
            return false;
        }
        final MMReflection.Handles handles = MMReflection.handles();
        if (handles == null) {
            return false;
        }
        final String durationKey = MMModifiers.durationKey(sourceKey);
        final String energyInKey = MMModifiers.energyInKey(sourceKey);
        final String energyOutKey = MMModifiers.energyOutKey(sourceKey);
        // 有效倍率封顶:与总线路径一致,防止叠加/极端配置把配方时长压到
        // 取整为 0/1 tick(见 MAX_MM_EFFECTIVE_SPEED 注释);能耗同步用该值。
        final int effectiveSpeed = Math.min(speed, MAX_MM_EFFECTIVE_SPEED);
        final float durationTarget = 1.0f / effectiveSpeed;
        final boolean scaleEnergy = TimeBusConfig.MM.mmEnergyFollowsSpeed;
        boolean touched = false;
        try {
            final Method threadsGetter = MMReflection.getRecipeThreadListFor(te);
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
                final Object active = handles.getActiveRecipe.invoke(thread);
                if (active == null) {
                    continue; // 只加速正在运行的配方；空闲线程不注入
                }
                // 升级迁移：先清旧版 permanent 残留（v1.0.8 及以前的魔杖/总线注入），
                // 避免与 semi-permanent 连乘。
                MMModifiers.purgeLegacy(handles, thread);
                if (MMModifiers.ensureSemi(handles, thread, durationKey,
                                handles.recipeDurationType, handles.ioInput, durationTarget)) {
                    touched = true;
                }
                if (scaleEnergy) {
                    if (MMModifiers.ensureSemi(handles, thread, energyInKey,
                                handles.recipeEnergyType, handles.ioInput, effectiveSpeed)) {
                        touched = true;
                    }
                    if (MMModifiers.ensureSemi(handles, thread, energyOutKey,
                                handles.recipeEnergyType, handles.ioOutput, effectiveSpeed)) {
                        touched = true;
                    }
                }
            }
            if (touched) {
                MMTracking.rememberSemiInjected(te, sourceKey);
                TimeBus.LOGGER.info("Time Bus: wand MM semi-accelerated source={} speed={} at {} ({} threads, tile {})",
                        sourceKey, effectiveSpeed, te.getPos(), threads.length, te.getClass().getSimpleName());
            }
            return touched;
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: wand MM acceleration failed at {}: {}", te.getPos(), e.toString());
            return false;
        }
    }

    /** Remove every injected modifier in every world still tracked (server shutdown). */
    public static void restoreAll() {
        if (!available()) {
            return;
        }
        final List<World> worlds = MMTracking.trackedWorlds();
        for (final World world : worlds) {
            restoreAllForWorld(world);
        }
    }

    /**
     * 该控制器当前是否仍被某个加速来源注入（时间总线/时间杖）。
     *
     * <p>判定委托给 {@link MMTracking#isInjected(TileEntity)}：只看持续注入的总线
     * 来源，魔杖的瞬时配方级加速不参与（否则工厂空闲线程会被长期保留）。
     */
    public static boolean isAccelerated(final TileEntity te) {
        return MMTracking.isInjected(te);
    }

    /**
     * MM 反射是否可用（首次调用时惰性解析，失败后不再重试）。
     *
     * <p>与 {@link MMReflection#handles()} 返回 null 等价；需要句柄的路径直接持有
     * {@code Handles} 做空检查，因此这里只给不关心句柄的编排方法用。
     */
    private static boolean available() {
        return MMReflection.handles() != null;
    }

}
