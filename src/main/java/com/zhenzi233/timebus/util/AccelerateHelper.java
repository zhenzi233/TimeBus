package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
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
 *   2. call ITickable.update() (speed-1) times 鈥?AE2 Chargers get their
 *      private doWork() invoked via a cached reflection handle instead
 *   3. call Block.updateTick() (speed * 20) times for randomly-ticking blocks
 *
 * Every per-block action is isolated with try/catch so one broken block
 * never breaks the rest of the batch.
 */
public final class AccelerateHelper {

    private AccelerateHelper() {
    }

    /** Perform one full acceleration burst on the block at {@code pos}. */
    public static void accelerateOnce(final World world, final BlockPos pos, final int speed) {
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
        runTileUpdates(world, pos, Math.max(0, speed - 1));
        runRandomTicks(world, pos, state, block, speed * 20);
    }

    /**
     * Run up to {@code n} acceleration calls on the tile at {@code target}.
     * ITickable tiles get update() calls; AE2 Chargers (grid-ticked, not
     * ITickable) get doWork() calls via a cached reflection handle.
     *
     * @return how many calls actually ran
     */
    public static int runTileUpdates(final World world, final BlockPos target, final int n) {
        if (n <= 0 || world == null || target == null) {
            return 0;
        }
        final TileEntity targetTE = world.getTileEntity(target);

        if (targetTE instanceof appeng.tile.misc.TileCharger) {
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
        if (targetTE instanceof appeng.tile.misc.TileInscriber) {
            try {
                ((appeng.api.networking.ticking.IGridTickable) targetTE).tickingRequest(null, 1);
                return 1;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: TileInscriber.tickingRequest failed at {}: {}", target, e.toString());
                return 0;
            }
        }

        if (!(targetTE instanceof net.minecraft.util.ITickable)) {
            return 0;
        }
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

    /**
     * Run up to {@code n} Block.updateTick calls on a randomly-ticking block.
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
                targetBlock.updateTick(world, target, targetState, world.rand);
                ran++;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: updateTick failed at {}: {}", target, e.toString());
                break;
            }
        }
        return ran;
    }

    /** Cached reflection handle for TileCharger.doWork() (private in AE2). */
    private static java.lang.reflect.Method chargerDoWork;

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
