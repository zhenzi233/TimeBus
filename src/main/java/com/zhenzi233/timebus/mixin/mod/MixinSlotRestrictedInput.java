package com.zhenzi233.timebus.mixin.mod;

import appeng.container.slot.SlotRestrictedInput;
import com.zhenzi233.timebus.item.ITimeBusUpgradeModule;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the Machine Parallel Card be dragged into AE2 upgrade slots.
 *
 * <p>The GUI drag path goes through {@code SlotRestrictedInput.isItemValid},
 * which checks the slot's {@code PlacableItemType} (UPGRADES only accepts
 * vanilla {@code IUpgradeModule} items) long before the inventory-level
 * {@code allowInsert} filter runs. Redirecting at HEAD bypasses all of:
 * container {@code isValidForSlot}, {@code AppEngSlot.isItemValid} and the
 * type check. The {@code which == UPGRADES} guard keeps the card out of the
 * material / press-plate slots, which are also {@code SlotRestrictedInput}.
 */
@Mixin(targets = "appeng/container/slot/SlotRestrictedInput")
public abstract class MixinSlotRestrictedInput {

    @Shadow
    @Final
    private SlotRestrictedInput.PlacableItemType which;

    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true, remap = false)
    private void timebus$allowMachineParallelCard(ItemStack i, CallbackInfoReturnable<Boolean> cir) {
        if (this.which == SlotRestrictedInput.PlacableItemType.UPGRADES
                && i != null && i.getItem() instanceof ITimeBusUpgradeModule) {
            cir.setReturnValue(true);
        }
    }
}
