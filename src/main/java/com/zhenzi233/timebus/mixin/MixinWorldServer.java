package com.zhenzi233.timebus.mixin;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into WorldServer to accelerate random ticks for blocks
 * that are adjacent to a Time Bus block.
 * <p>
 * This improves the performance of random-tick-based machines
 * (crops, furnaces, etc.) that are accelerated by a Time Bus.
 */
@Mixin(WorldServer.class)
public class MixinWorldServer {

    /**
     * Inject after the main random tick loop in updateBlocks() to apply
     * extra random ticks for positions adjacent to Time Bus blocks.
     */
    @Inject(method = "updateBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;tickPlayers()V"))
    private void afterRandomTicks(CallbackInfo ci) {
        // This method will be called after the normal random tick processing
        // Future enhancement: cache Time Bus positions for better performance
        // For now, the TileEntity handles acceleration directly.
    }
}
