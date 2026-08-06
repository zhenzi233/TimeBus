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
        /** Mekanism CE (1.12) machine: public onUpdate() advancement. */
        MEK_MACHINE,
        /** Plain ITickable machine: update() spam. */
        ITICKABLE,
        /** Not acceleratable. */
        NONE
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
        runTileUpdates(world, pos, Math.max(0, speed - 1), speed, sourceKey);
        runRandomTicks(world, pos, state, block, speed * 20);
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
        final TileEntity targetTE = world.getTileEntity(target);
        final TileKind kind = getTileKind(targetTE);

        switch (kind) {
            case CHARGER: {
                final appeng.tile.misc.TileCharger charger = (appeng.tile.misc.TileCharger) targetTE;
                // doWork() is private in TileCharger; invoke it via reflection at runtime.
                // (An access transformer only changes runtime access, not compile-time
                // visibility, so reflection is the portable way to call it.)
                final java.lang.reflect.Method doWork = getChargerDoWork();
                if (doWork == null) {
                    return 0;
                }
                int ran = 0;
                for (int i = 0; i < n; i++) {
                    try {
                        doWork.invoke(charger);
                        ran++;
                    } catch (Exception e) {
                        TimeBus.LOGGER.warn("Time Bus: TileCharger.doWork failed at {}: {}", target, e.toString());
                        break;
                    }
                }
                return ran;
            }

            // AE2 Inscriber: grid-ticked (IGridTickable), not ITickable. Its public
            // tickingRequest(node, ticksSinceLastCall) advances processingTime by
            // ticksSinceLastCall each call; the node argument is unused inside, so
            // passing null is safe. One call = one tick of progress.
            case INSCRIBER: {
                try {
                    // One call = `speed` ticks of progress (ticksSinceLastCall).
                    ((appeng.api.networking.ticking.IGridTickable) targetTE).tickingRequest(null, Math.max(1, speed));
                    return 1;
                } catch (Exception e) {
                    TimeBus.LOGGER.warn("Time Bus: TileInscriber.tickingRequest failed at {}: {}", target, e.toString());
                    return 0;
                }
            }

            // AE2 Molecular Assembler: grid-ticked (IGridTickable), not ITickable.
            // tickingRequest(node, ticksSinceLastCall) advances progress by
            // userPower(ticksSinceLastCall, ...); node is unused inside, so null is
            // safe. One call = `speed` ticks of progress (only while it is awake,
            // i.e. actively crafting with network power).
            case MOLECULAR_ASSEMBLER: {
                try {
                    ((appeng.api.networking.ticking.IGridTickable) targetTE).tickingRequest(null, Math.max(1, speed));
                    return 1;
                } catch (Exception e) {
                    TimeBus.LOGGER.warn("Time Bus: TileMolecularAssembler.tickingRequest failed at {}: {}", target, e.toString());
                    return 0;
                }
            }

            // AE2 Vibration Chamber: grid-ticked generator (IGridTickable). Its
            // tickingRequest burns fuel and injects timePassed*5 AE into the grid;
            // timePassed scales with ticksSinceLastCall, so one call = `speed`
            // ticks of burn time (only useful while the grid needs power; when the
            // grid is full it slows itself down anyway).
            case VIBRATION_CHAMBER: {
                try {
                    ((appeng.api.networking.ticking.IGridTickable) targetTE).tickingRequest(null, Math.max(1, speed));
                    return 1;
                } catch (Exception e) {
                    TimeBus.LOGGER.warn("Time Bus: TileVibrationChamber.tickingRequest failed at {}: {}", target, e.toString());
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
                if (TimeBusConfig.mmAccelerationEnabled) {
                    if (ModularMachineryAccelerator.apply(targetTE, sourceKey, speed)) {
                        return 1;
                    }
                } else {
                    // Feature switched off: remove this source's injected modifier.
                    ModularMachineryAccelerator.restore(targetTE, sourceKey);
                }
                return 0;
            }

            // Mekanism CE (1.12): plain ITickable, but update() has heavy side
            // effects (GUI sync packets, ticker++, factory neighbor updates),
            // so advance only the public onUpdate() processing logic. Energy is
            // drawn from the machine's own Mekanism grid, never converted.
            case MEK_MACHINE: {
                if (TimeBusConfig.mekAccelerationEnabled) {
                    return MekanismAccelerator.runOnUpdate(targetTE, n);
                }
                return 0;
            }

            case ITICKABLE: {
                final net.minecraft.util.ITickable tickable = (net.minecraft.util.ITickable) targetTE;
                int ran = 0;
                for (int i = 0; i < n; i++) {
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
        int ran = 0;
        // Re-check the block state occasionally instead of every call.
        for (int i = 0; i < n; i++) {
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

    /** Cached reflection handle for TileCharger.doWork() (private in AE2). */
    private static volatile java.lang.reflect.Method chargerDoWork;

    private static java.lang.reflect.Method getChargerDoWork() {
        if (chargerDoWork == null) {
            try {
                chargerDoWork = appeng.tile.misc.TileCharger.class.getDeclaredMethod("doWork");
                chargerDoWork.setAccessible(true);
            } catch (NoSuchMethodException e) {
                TimeBus.LOGGER.warn("Time Bus: could not find TileCharger.doWork: {}", e.toString());
            }
        }
        return chargerDoWork;
    }
}
