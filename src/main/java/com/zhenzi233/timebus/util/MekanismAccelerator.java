package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.tileentity.TileEntity;

import java.lang.reflect.Method;

/**
 * Mekanism CE (1.12 branch) machine accelerator.
 *
 * <p>Mekanism machines are plain {@code ITickable} tiles (no anti-acceleration
 * design in the 1.12 branch, contrary to what was previously assumed), so the
 * Time Bus could in theory accelerate them through the generic update() path.
 * That path is deliberately avoided: {@code TileEntityBasicBlock.update()} also
 * ticks every component, sends full tile sync packets to players with the GUI
 * open (network storm when called hundreds of times per tick) and increments
 * the internal {@code ticker} (factory neighbor notifications). Calling the
 * public {@code onUpdate()} directly advances only the processing logic
 * (energy cost, operatingTicks, recipe completion), which is what acceleration
 * should do.
 *
 * <p>All access goes through reflection (Mekanism is an optional mod; Time Bus
 * has no hard dependency). If the classes are absent or signatures change, the
 * calls fail softly and are logged. Range assumption: every machine is a
 * subclass of {@code mekanism.common.tile.prefab.TileEntityBasicBlock}.
 */
public final class MekanismAccelerator {

    private MekanismAccelerator() {
    }

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static volatile Class<?> basicBlockClass;
    private static volatile Method onUpdate;

    /** True if the tile is a Mekanism CE (1.12) machine. */
    public static boolean isMachine(final TileEntity te) {
        if (te == null) {
            return false;
        }
        resolve();
        return available && basicBlockClass.isInstance(te);
    }

    /**
     * Advance the machine's processing logic {@code n} times by calling the
     * public onUpdate() directly, skipping update()'s sync/ticker side effects.
     *
     * @return how many calls actually ran
     */
    public static int runOnUpdate(final TileEntity te, final int n) {
        if (n <= 0 || te == null || te.getWorld() == null || te.getWorld().isRemote) {
            return 0;
        }
        resolve();
        if (!available || onUpdate == null) {
            return 0;
        }
        int ran = 0;
        for (int i = 0; i < n; i++) {
            try {
                onUpdate.invoke(te);
                ran++;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: Mekanism onUpdate failed at {}: {}", te.getPos(), e.toString());
                break;
            }
        }
        return ran;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (MekanismAccelerator.class) {
            if (resolved) {
                return;
            }
            try {
                basicBlockClass = Class.forName("mekanism.common.tile.prefab.TileEntityBasicBlock");
                onUpdate = basicBlockClass.getDeclaredMethod("onUpdate");
                onUpdate.setAccessible(true);
                available = true;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: Mekanism acceleration unavailable: {}", e.toString());
                available = false;
            } finally {
                resolved = true;
            }
        }
    }
}