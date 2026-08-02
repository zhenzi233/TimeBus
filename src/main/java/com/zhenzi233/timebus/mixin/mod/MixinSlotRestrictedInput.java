package com.zhenzi233.timebus.mixin.mod;

import appeng.container.slot.SlotRestrictedInput;
import com.zhenzi233.timebus.item.ITimeBusUpgradeModule;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the Machine Parallel Card be clicked into AE2 upgrade slots.
 *
 * <p>MC's {@code Container.slotClick} checks {@code slot.isItemValid} before
 * putStack. {@code SlotRestrictedInput} overrides that MC method, so its
 * runtime (obfuscated) name is {@code func_75214_a} while dev uses
 * {@code isItemValid} — the released jar keeps the MCP annotation name and
 * would silently miss. We inject BOTH names with {@code require=0} (the
 * non-matching one is skipped), sharing one {@code @Unique} handler.
 * The {@code UPGRADES} guard keeps the card out of the material / press-plate
 * slots, which are also {@code SlotRestrictedInput}.
 */
@Mixin(targets = "appeng/container/slot/SlotRestrictedInput")
public abstract class MixinSlotRestrictedInput {

    // remap=false: AE2 is an external mod, its method name is never obfuscated.
    @Shadow(remap = false)
    public abstract SlotRestrictedInput.PlacableItemType getPlaceableItemType();

    @Unique
    private void timebus$allowParallelCard(ItemStack i, CallbackInfoReturnable<Boolean> cir) {
        // TEMP DIAG
        if (this.getPlaceableItemType() == SlotRestrictedInput.PlacableItemType.UPGRADES
                && i != null && i.getItem() instanceof ITimeBusUpgradeModule) {
            cir.setReturnValue(true);
        }
    }

    // dev: MCP name
    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void timebus$allowMCP(ItemStack i, CallbackInfoReturnable<Boolean> cir) {
        this.timebus$allowParallelCard(i, cir);
    }

    // released jar: SRG name (MC method override)
    @Inject(method = "func_75214_a", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void timebus$allowSRG(ItemStack i, CallbackInfoReturnable<Boolean> cir) {
        this.timebus$allowParallelCard(i, cir);
    }
}
