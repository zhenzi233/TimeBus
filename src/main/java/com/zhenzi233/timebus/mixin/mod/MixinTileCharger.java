package com.zhenzi233.timebus.mixin.mod;

import appeng.tile.misc.TileCharger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code TileCharger.doWork()} (private) as a direct invoker, replacing
 * the cached-reflection call in {@code AccelerateHelper} - zero reflection
 * dispatch overhead and compile-time visibility, consistent with the existing
 * {@code MixinTileInscriber.timebus$getTask} pattern (代码审查 3.2).
 *
 * <p>AE2 is a hard dependency and its runtime method names are MCP names, so
 * {@code remap=false} is correct for released jars.
 */
@Mixin(value = TileCharger.class, remap = false)
public interface MixinTileCharger {

    @Invoker(value = "doWork", remap = false)
    boolean timebus$doWork();
}
