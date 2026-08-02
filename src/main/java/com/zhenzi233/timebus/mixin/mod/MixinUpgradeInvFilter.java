package com.zhenzi233.timebus.mixin.mod;

import com.zhenzi233.timebus.item.ITimeBusUpgradeModule;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the Machine Parallel Card be inserted into AE2 upgrade slots
 * (e.g. the Inscriber's 3 upgrade slots). The card stays a plain item
 * (no {@code IUpgradeModule}), so the vanilla AE2 upgrade filter would
 * otherwise reject it.
 *
 * <p>Targets the AE2 inner class {@code UpgradeInventory$UpgradeInvFilter}
 * and intercepts {@code allowInsert}. {@code remap=false} because AE2 is
 * an external mod and its method names are not part of the MC mapping
 * refmap (dev environment uses MCP names, matching this injection).
 */
@Mixin(targets = "appeng/parts/automation/UpgradeInventory$UpgradeInvFilter")
public abstract class MixinUpgradeInvFilter {

    @Inject(method = "allowInsert", at = @At("HEAD"), cancellable = true, remap = false)
    private void timebus$allowMachineParallelCard(IItemHandler inv, int slot, ItemStack itemstack, CallbackInfoReturnable<Boolean> cir) {
        // TEMP DIAG
        if (itemstack != null && itemstack.getItem() instanceof ITimeBusUpgradeModule) {
            cir.setReturnValue(true);
        }
    }
}
