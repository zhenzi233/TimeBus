package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Shared block-acceleration logic, used by both the Time Bus (PartTimeBus)
 * and the Time Wand (ItemTimeWand).
 *
 * "Accelerating once" mirrors what the Time Bus does for one block:
 *   1. schedule one tick
 *   2. call ITickable.update() (speed-1) times; AE2 Chargers get their
 *      private doWork() invoked via a cached reflection handle instead
 *   3. call Block.randomTick() (speed * 20) times for randomly-ticking blocks
 *
 * Every per-block action is isolated with try/catch so one broken block
 * never breaks the rest of the batch.
 */
public final class AccelerateHelper {

    /**
     * 单次加速调用的 tile update 次数上限。
     *
     * <p>时间总线有每 tick 工作预算（maxCallsPerTick + 跨 tick 结转），但魔杖
     * 是主线程一次性执行：满配倍率（512x）会一次连调 511 次 update()，极端
     * 配置更无上界。此上限让单次调用最多推进 512 tick 进度（约 25.6 秒），
     * 效果依然显著但不会拖垮服务端 tick。总线每 tick 传入量（≤ speed-1 ≤ 31）
     * 远低于上限，不受影响。
     */
    private static final int MAX_TILE_UPDATES_PER_CALL = 512;

    /**
     * 单次加速调用的 Block.randomTick 次数上限（原因同上；总线每 tick 传入量
     * ≤ 32x20 = 640，远低于上限）。512x 满配点击作物 = 512x20 = 10240 次,
     * 封顶到 2048 次仍约合数秒作物进度。
     */
    private static final int MAX_RANDOM_TICKS_PER_CALL = 2048;

    private AccelerateHelper() {
    }

    /**
     * The kind of a tile from the acceleration engine's point of view.
     * Single source of truth shared by the Time Bus scheduling gate and
     * {@link #runTileUpdates}, so a new machine type only needs one edit.
     */
    public enum TileKind {
        /** AE2 Charger: grid-driven, accelerated via its private doWork(). */
        CHARGER,
        /** AE2 Inscriber: tickingRequest advances {@code speed} ticks per call. */
        INSCRIBER,
        /** AE2 Molecular Assembler: same tickingRequest semantics. */
        MOLECULAR_ASSEMBLER,
        /** AE2 Vibration Chamber: burns fuel faster via tickingRequest. */
        VIBRATION_CHAMBER,
        /** AE2 IO Port: one call = one transfer batch. */
        IO_PORT,
        /** Modular Machinery (CE) controller: recipe-duration compression. */
        MM_CONTROLLER,
        /** Mekanism (CE-Unofficial) machine: virtual speed card injection. */
        MEK_MACHINE,
        /** Plain ITickable machine: update() spam. */
        ITICKABLE,
        /** Not acceleratable. */
        NONE
    }

    /**
     * 该方块是否可被加速引擎处理（无副作用，用于时间杖扣费前的预检）。
     *
     * <p>与 {@link #getTileKind} 保持单一事实来源：可加速 = 随机刻方块
     * （无 tile 也可加速，如作物），或 tile 的加速类型非 NONE 且对应的
     * 功能开关已开启（MM/MEK 类型在开关关闭时 {@link #runTileUpdates}
     * 不会做任何事，预检须一致，否则仍会空扣费）。
     */
    public static boolean canAccelerate(final World world, final BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        final IBlockState state = world.getBlockState(pos);
        final Block block = state.getBlock();
        if (block == null || block.isAir(state, world, pos)) {
            return false;
        }
        // 随机刻方块：无 tile 也能加速（作物、草方块等）。
        if (block.getTickRandomly()) {
            return true;
        }
        final TileEntity te = world.getTileEntity(pos);
        if (te == null) {
            return false;
        }
        final TileKind kind = getTileKind(te);
        switch (kind) {
            case MM_CONTROLLER:
                return TimeBusConfig.MM.mmAccelerationEnabled;
            case MEK_MACHINE:
                return MekanismAccelerator.isGenerator(te)
                        ? TimeBusConfig.Mek.mekGeneratorAccelerationEnabled
                        : TimeBusConfig.Mek.mekAccelerationEnabled;
            default:
                return kind != TileKind.NONE;
        }
    }

    /** Classify a tile entity (null-safe). */
    public static TileKind getTileKind(final TileEntity te) {
        if (te instanceof appeng.tile.misc.TileCharger) {
            return TileKind.CHARGER;
        }
        if (te instanceof appeng.tile.misc.TileInscriber) {
            return TileKind.INSCRIBER;
        }
        if (te instanceof appeng.tile.crafting.TileMolecularAssembler) {
            return TileKind.MOLECULAR_ASSEMBLER;
        }
        if (te instanceof appeng.tile.misc.TileVibrationChamber) {
            return TileKind.VIBRATION_CHAMBER;
        }
        if (te instanceof appeng.tile.storage.TileIOPort) {
            return TileKind.IO_PORT;
        }
        if (ModularMachineryAccelerator.isController(te)) {
            return TileKind.MM_CONTROLLER;
        }
        if (MekanismAccelerator.isMachine(te)) {
            return TileKind.MEK_MACHINE;
        }
        if (te instanceof net.minecraft.util.ITickable) {
            return TileKind.ITICKABLE;
        }
        return TileKind.NONE;
    }

    /**
     * Perform one full acceleration burst on the block at {@code pos}.
     *
     * @param sourceKey identifies the accelerating source (Time Bus / wand) so
     *                  MM recipe-duration modifiers stack per source instead of
     *                  overwriting each other
     */
    public static void accelerateOnce(final World world, final BlockPos pos, final int speed, final String sourceKey) {
        if (world == null || pos == null || speed <= 0) {
            return;
        }
        final IBlockState state = world.getBlockState(pos);
        final Block block = state.getBlock();
        if (block == null || block.isAir(state, world, pos)) {
            return;
        }
        try {
            world.scheduleBlockUpdate(pos, block, 1, 0);
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: scheduleBlockUpdate failed at {}: {}", pos, e.toString());
        }
        // 单次调用的工作量封顶（见 MAX_*_PER_CALL 注释；随机刻次数先按 long 计算
        // 再封顶，避免极端配置下 speed * perSpeed 的 int 溢出退化为 1 次）。
        runTileUpdates(world, pos, Math.max(0, Math.min(speed - 1, MAX_TILE_UPDATES_PER_CALL)), speed, sourceKey);
        runRandomTicks(world, pos, state, block,
                (int) Math.min((long) speed * TimeBusConfig.Bus.randomTickCallsPerSpeed, MAX_RANDOM_TICKS_PER_CALL));
    }

    /**
     * Run up to {@code n} acceleration calls on the tile at {@code target}.
     * Dispatch is driven by {@link #getTileKind(TileEntity)}, which is also the
     * scheduling gate used by the Time Bus, so a new machine type only needs to
     * be added to that single classifier.
     *
     * @return how many calls actually ran
     */
    public static int runTileUpdates(final World world, final BlockPos target, final int n, final int speed,
                                     final String sourceKey) {
        if (n <= 0 || world == null || target == null) {
            return 0;
        }
        // 防御性封顶：任何调用方（总线/魔杖）单次都不能超限。
        final int calls = Math.min(n, MAX_TILE_UPDATES_PER_CALL);
        final TileEntity targetTE = world.getTileEntity(target);
        final TileKind kind = getTileKind(targetTE);

        switch (kind) {
            case CHARGER: {
                final appeng.tile.misc.TileCharger charger = (appeng.tile.misc.TileCharger) targetTE;
                int ran = 0;
                for (int i = 0; i < calls; i++) {
                    try {
                        // doWork() is private in TileCharger; the mixin invoker
                        // exposes it directly - no reflection dispatch overhead
                        ((com.zhenzi233.timebus.mixin.mod.MixinTileCharger) charger).timebus$doWork();
                        ran++;
                    } catch (Exception e) {
                        TimeBus.LOGGER.warn("Time Bus: TileCharger.doWork failed at {}: {}", target, e.toString());
                        break;
                    }
                }
                return ran;
            }

            // AE2 Inscriber / Molecular Assembler / Vibration Chamber: grid-ticked
            // (IGridTickable), not ITickable. tickingRequest(null, ticksSinceLastCall)
            // advances progress by ticksSinceLastCall (the node argument is unused
            // inside, so null is safe); the three classes share exactly this
            // semantics - inscriber processingTime, assembler userPower (only while
            // awake with network power), chamber fuel burn + AE injection - so one
            // call = `speed` ticks of progress.
            case INSCRIBER:
            case MOLECULAR_ASSEMBLER:
            case VIBRATION_CHAMBER: {
                try {
                    ((appeng.api.networking.ticking.IGridTickable) targetTE).tickingRequest(null, Math.max(1, speed));
                    return 1;
                } catch (Exception e) {
                    TimeBus.LOGGER.warn("Time Bus: {} tickingRequest failed at {}: {}", kind.name(), target, e.toString());
                    return 0;
                }
            }

            // AE2 IO Port: grid-ticked (IGridTickable). tickingRequest ignores
            // ticksSinceLastCall and just calls doWork() once, which moves up to
            // 256 items (speed cards multiply it). Each call = one transfer batch,
            // so acceleration = more calls per tick from the budget.
            case IO_PORT: {
                try {
                    ((appeng.api.networking.ticking.IGridTickable) targetTE).tickingRequest(null, 1);
                    return 1;
                } catch (Exception e) {
                    TimeBus.LOGGER.warn("Time Bus: TileIOPort.tickingRequest failed at {}: {}", target, e.toString());
                    return 0;
                }
            }

            // Modular Machinery (CE) controllers: restricted-tick anti-acceleration
            // blocks update() spam, so compress the recipe duration through MM's own
            // modifier system instead. One injection per tick, counted once. The
            // branch is also taken while the feature is disabled so the useless
            // ITickable update-call path never wastes budget on these machines.
            case MM_CONTROLLER: {
                if (TimeBusConfig.MM.mmAccelerationEnabled) {
                    if (ModularMachineryAccelerator.isWandSource(sourceKey)) {
                        // 兜底：魔杖点击应走 ItemTimeWand.onItemUse 的 semi-permanent
                        // 注入路径；若意外走到这里也绝不注入永久 modifier。
                        return ModularMachineryAccelerator.applyWandToActiveRecipes(targetTE, sourceKey, speed) ? 1 : 0;
                    }
                    if (ModularMachineryAccelerator.apply(targetTE, sourceKey, speed)) {
                        return 1;
                    }
                } else {
                    // Feature switched off: remove this source's injected modifier.
                    ModularMachineryAccelerator.restore(targetTE, sourceKey);
                }
                return 0;
            }

            // Mekanism: final update() with world-tick deduplication makes update()
            // spam useless; register the machine as accelerated so the Mixin on
            // RecipeCacheLookupMonitor repeats CachedRecipe.process() `speed`
            // times per tick (one tick of progress per extra run).
            case MEK_MACHINE: {
                // 发电机（风力/燃气/生物/太阳能/热力/大型）走独立的"开挂产电"
                // 开关（Mek 官方防加速，多产电不守恒）；其余配方机器走
                // mekAccelerationEnabled 连拍开关（能耗守恒）。
                final boolean generator = MekanismAccelerator.isGenerator(targetTE);
                final boolean enabled = generator
                        ? TimeBusConfig.Mek.mekGeneratorAccelerationEnabled
                        : TimeBusConfig.Mek.mekAccelerationEnabled;
                if (enabled && MekanismAccelerator.registerOnce(world, target, speed, world.getTotalWorldTime())) {
                    return 1;
                }
                return 0;
            }

            case ITICKABLE: {
                final net.minecraft.util.ITickable tickable = (net.minecraft.util.ITickable) targetTE;
                int ran = 0;
                for (int i = 0; i < calls; i++) {
                    try {
                        tickable.update();
                        ran++;
                    } catch (Exception e) {
                        TimeBus.LOGGER.warn("Time Bus: ITickable.update failed at {}: {}", target, e.toString());
                        break;
                    }
                }
                return ran;
            }

            case NONE:
            default:
                return 0;
        }
    }

    /**
     * Run up to {@code n} Block.randomTick calls on a randomly-ticking block.
     * randomTick is the entry point of the vanilla random-tick loop: the
     * default implementation delegates to updateTick (crops, saplings, ...),
     * while blocks that override randomTick directly (grass spread, ice melt,
     * snow, torch burnout, ...) are covered as well.
     *
     * @return how many calls actually ran
     */
    public static int runRandomTicks(final World world, final BlockPos target, final IBlockState targetState,
                                     final Block targetBlock, final int n) {
        if (n <= 0 || world == null || target == null || !targetBlock.getTickRandomly()) {
            return 0;
        }
        // 防御性封顶（见 MAX_RANDOM_TICKS_PER_CALL 注释）。
        final int calls = Math.min(n, MAX_RANDOM_TICKS_PER_CALL);
        int ran = 0;
        // Re-check the block state occasionally instead of every call.
        for (int i = 0; i < calls; i++) {
            if (ran % 20 == 0 && world.getBlockState(target) != targetState) {
                break;
            }
            try {
                targetBlock.randomTick(world, target, targetState, world.rand);
                ran++;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: randomTick failed at {}: {}", target, e.toString());
                break;
            }
        }
        return ran;
    }
}
