package com.zhenzi233.timebus.mixin.mod;

import appeng.api.features.IInscriberRecipe;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.parts.automation.UpgradeInventory;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.tile.misc.TileInscriber;
import com.zhenzi233.timebus.item.ITimeBusUpgradeModule;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inscriber stacked-input and Machine Parallel Card support.
 *
 * <p><b>Stacked inputs (no card required):</b> {@link #timebus$getCount}
 * redirects every {@code ItemStack.getCount()} call inside the 3-arg
 * {@code getTask} to return 1 (same trick as RandomComplement), so stacked
 * inputs are accepted; {@link #timebus$hasWork} keeps the grid scheduler
 * ticking the machine while a valid recipe is present.
 *
 * <p><b>Parallel cards:</b> the vanilla inscriber workflow (progress bar,
 * smash animation, power draw) is left untouched; with N cards installed
 * only the finished batch is scaled: {@link #timebus$moreOutputReal} /
 * {@link #timebus$moreOutputSim} multiply the produced output by (N+1),
 * {@link #timebus$consumeSide} consumes (N+1) input items instead of 1,
 * and output is scaled to match the actually consumed amount (no item dupe).
 * Press plates follow vanilla behaviour (consumed by PRESS recipes, kept by INSCRIBE).
 *
 * <p>Only fields/methods declared directly in {@code TileInscriber} are
 * {@code @Shadow}ed (sponge-mixin does not resolve inherited methods for
 * shadows). Inherited public methods ({@code getProxy},
 * {@code extractAEPower}, {@code saveChanges}, {@code markForUpdate}) are
 * called through {@link #timebus$self()}. Constructor {@code @Redirect} is
 * illegal in sponge-mixin 0.8.7, so the slot maxStacks are raised via
 * {@code @Inject(<init> TAIL)} instead.
 */
@Mixin(value = TileInscriber.class, remap = false)
public abstract class MixinTileInscriber {

    @Shadow
    @Final
    private AppEngInternalInventory topItemHandler;
    @Shadow
    @Final
    private AppEngInternalInventory bottomItemHandler;
    @Shadow
    @Final
    private AppEngInternalInventory sideItemHandler;
    @Shadow
    @Final
    private UpgradeInventory upgrades;

    /** Batch size for the in-progress finish, computed once per tickingRequest call. */
    @Unique
    private int timebus$batch = -1;

    /** Exposes the private 3-arg {@code getTask(input, plateA, plateB)}. */
    @Invoker(value = "getTask", remap = false)
    abstract IInscriberRecipe timebus$getTask(ItemStack input, ItemStack plateA, ItemStack plateB);

    /** Casts this mixin instance back to the target tile for inherited public method calls. */
    private TileInscriber timebus$self() {
        return (TileInscriber) (Object) this;
    }

    /**
     * Raise all three inventory slots (press plates + side input) to 64 so
     * stacked items fit.
     */
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void timebus$biggerSlots(CallbackInfo ci) {
        this.sideItemHandler.setMaxStackSize(0, 64);
        this.topItemHandler.setMaxStackSize(0, 64);
        this.bottomItemHandler.setMaxStackSize(0, 64);
    }

    /**
     * Let the 3-arg {@code getTask} accept stacked inputs: vanilla rejects
     * {@code count > 1}. Same approach as RandomComplement's inscriber
     * mixin. {@code method} must be the exact descriptor — matching by name
     * would also hit the no-arg {@code getTask()}, which contains no
     * {@code getCount()} call and silently voids the whole injection.
     */
    @Redirect(method = "getTask(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Lappeng/api/features/IInscriberRecipe;", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;getCount()I", remap = false))
    private static int timebus$getCount(ItemStack stack) {
        return 1;
    }

    /**
     * Keep the grid scheduler ticking while the input slot holds a valid
     * recipe. Vanilla {@code hasWork()} rejects stacked inputs (count>1),
     * which would stop {@code tickingRequest} from ever running. Checking
     * the recipe also stops the progress bar from spinning on invalid
     * recipes (an unconditional true would idle-spin it).
     */
    @Inject(method = "tickingRequest", at = @At("HEAD"), remap = false)
    private void timebus$resetBatch(IGridNode node, int ticksSinceLastCall, CallbackInfoReturnable<TickRateModulation> cir) {
        this.timebus$batch = -1;
    }

    @Inject(method = "hasWork", at = @At("HEAD"), cancellable = true, remap = false)
    private void timebus$hasWork(CallbackInfoReturnable<Boolean> cir) {
        ItemStack input = this.sideItemHandler.getStackInSlot(0);
        if (!input.isEmpty() && this.timebus$getTask(input,
                this.topItemHandler.getStackInSlot(0), this.bottomItemHandler.getStackInSlot(0)) != null) {
            cir.setReturnValue(true);
        }
    }

    /**
     * The vanilla smash-finish step clears the whole slot with
     * {@code setStackInSlot(0, EMPTY)} (fine while only 1 item fits, but
     * with stacked inputs the whole stack would be swallowed). The three
     * {@code setStackInSlot} calls in {@code tickingRequest} (ordinal 0/1/2:
     * top press, bottom press, side input) are redirected to consume exactly
     * 1 item each — except with parallel cards installed, where the press
     * plates are kept and the side input consumes (N+1).
     */
    @ModifyArg(method = "tickingRequest", at = @At(value = "INVOKE", target = "Lappeng/tile/inventory/AppEngInternalInventory;setStackInSlot(ILnet/minecraft/item/ItemStack;)V", remap = false, ordinal = 0), index = 1, remap = false)
    private ItemStack timebus$consumeTop(ItemStack stack) {
        return timebus$consumeN(this.topItemHandler, this.timebus$batch);
    }

    @ModifyArg(method = "tickingRequest", at = @At(value = "INVOKE", target = "Lappeng/tile/inventory/AppEngInternalInventory;setStackInSlot(ILnet/minecraft/item/ItemStack;)V", remap = false, ordinal = 1), index = 1, remap = false)
    private ItemStack timebus$consumeBottom(ItemStack stack) {
        return timebus$consumeN(this.bottomItemHandler, this.timebus$batch);
    }

    @ModifyArg(method = "tickingRequest", at = @At(value = "INVOKE", target = "Lappeng/tile/inventory/AppEngInternalInventory;setStackInSlot(ILnet/minecraft/item/ItemStack;)V", remap = false, ordinal = 2), index = 1, remap = false)
    private ItemStack timebus$consumeSide(ItemStack stack) {
        return timebus$consumeN(this.sideItemHandler, this.timebus$batch);
    }

    /** Consume up to {@code amount} items from the given inventory slot, keeping the rest. */
    private ItemStack timebus$consumeN(AppEngInternalInventory inv, int amount) {
        ItemStack current = inv.getStackInSlot(0);
        final int take = Math.min(current.getCount(), amount);
        current.shrink(take);
        return current.isEmpty() ? ItemStack.EMPTY : current;
    }

    /**
     * Items each slot contributes to the next finished job: (N+1) with N cards,
     * capped by the stock of every non-empty slot (empty press-plate slots do
     * not limit the batch).
     */
    private int timebus$computeBatch() {
        int batch = this.timebus$getParallelCount() + 1;
        batch = Math.min(batch, this.sideItemHandler.getStackInSlot(0).getCount());
        ItemStack top = this.topItemHandler.getStackInSlot(0);
        if (!top.isEmpty()) {
            batch = Math.min(batch, top.getCount());
        }
        ItemStack bottom = this.bottomItemHandler.getStackInSlot(0);
        if (!bottom.isEmpty()) {
            batch = Math.min(batch, bottom.getCount());
        }
        return batch;
    }

    /**
     * Scale the finished output by (N+1) with parallel cards. Both
     * {@code insertItem(1, outputCopy, ...)} calls in {@code tickingRequest}
     * are scaled: ordinal 0 is the smash-finish actual insert, ordinal 1 is
     * the output-slot pre-check (simulate) that starts the smash.
     */
    @ModifyArg(method = "tickingRequest", at = @At(value = "INVOKE", target = "Lappeng/tile/inventory/AppEngInternalInventory;insertItem(ILnet/minecraft/item/ItemStack;Z)Lnet/minecraft/item/ItemStack;", remap = false, ordinal = 0), index = 1, remap = false)
    private ItemStack timebus$moreOutputReal(ItemStack stack) {
        return timebus$scaleOutput(stack);
    }

    @ModifyArg(method = "tickingRequest", at = @At(value = "INVOKE", target = "Lappeng/tile/inventory/AppEngInternalInventory;insertItem(ILnet/minecraft/item/ItemStack;Z)Lnet/minecraft/item/ItemStack;", remap = false, ordinal = 1), index = 1, remap = false)
    private ItemStack timebus$moreOutputSim(ItemStack stack) {
        return timebus$scaleOutput(stack);
    }

    private ItemStack timebus$scaleOutput(ItemStack stack) {
        // Produce exactly as many as we consume (capped by every slot's stock).
        if (this.timebus$batch < 0) {
            this.timebus$batch = this.timebus$computeBatch();
        }
        // Produce exactly as many as we consume (capped by every slot stock).
        stack.setCount(stack.getCount() * this.timebus$batch);
        return stack;
    }

    /** Counts installed TimeBus upgrade cards. */
    private int timebus$getParallelCount() {
        int count = 0;
        for (int i = 0; i < this.upgrades.getSlots(); i++) {
            ItemStack s = this.upgrades.getStackInSlot(i);
            if (s != null && s.getItem() instanceof ITimeBusUpgradeModule) {
                count++;
            }
        }
        return count;
    }
}
