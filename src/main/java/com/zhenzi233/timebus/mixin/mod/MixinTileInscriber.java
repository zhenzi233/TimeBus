package com.zhenzi233.timebus.mixin.mod;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.features.IInscriberRecipe;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.me.GridAccessException;
import appeng.parts.automation.UpgradeInventory;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.tile.misc.TileInscriber;
import com.zhenzi233.timebus.item.ItemMachineParallelCard;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Machine Parallel Card support for the AE2 Inscriber.
 *
 * <p>With N Machine Parallel Cards installed in the upgrade slots, every
 * completed inscriber job consumes (N+1) input items and produces (N+1)
 * outputs instead of 1/1 (each card adds one extra material consumed and
 * one extra product produced). Power is drawn at (N+1) times the base
 * 10 AE per job. The press plates (top/bottom) are not consumed.
 *
 * <p>Without any parallel card the vanilla (or RandomComplement) behaviour
 * is left untouched (the injection returns early). When RandomComplement is
 * installed its stacked-input / auto-input / auto-output features keep
 * working; this mixin only adds the parallel batch size on top.
 *
 * <p>Only fields/methods declared directly in {@code TileInscriber} are
 * {@code @Shadow}ed (sponge-mixin does not resolve inherited methods for
 * shadows). Inherited public methods ({@code getProxy},
 * {@code extractAEPower}, {@code saveChanges}, {@code markForUpdate}) are
 * called through {@link #timebus$self()}. Constructor {@code @Redirect} is
 * illegal in sponge-mixin 0.8.7, so the input slot maxStack is raised via
 * {@code @Inject(<init> TAIL)} instead (the output slot is already 64).
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

    /** Exposes the private 3-arg {@code getTask(input, plateA, plateB)}. */
    @Invoker(value = "getTask", remap = false)
    abstract IInscriberRecipe timebus$getTask(ItemStack input, ItemStack plateA, ItemStack plateB);

    /** Casts this mixin instance back to the target tile for inherited public method calls. */
    private TileInscriber timebus$self() {
        return (TileInscriber) (Object) this;
    }

    /**
     * Raise the side input slot maxStack to 64 so stacked inputs fit.
     * The output slot (slot 1) is already 64 by vanilla code.
     */
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void timebus$biggerSideInventory(CallbackInfo ci) {
        this.sideItemHandler.setMaxStackSize(0, 64);
    }

    /** Counts Machine Parallel Cards in the upgrade inventory. */
    private int timebus$getParallelCount() {
        int count = 0;
        for (int i = 0; i < this.upgrades.getSlots(); i++) {
            ItemStack s = this.upgrades.getStackInSlot(i);
            if (s != null && s.getItem() instanceof ItemMachineParallelCard) {
                count++;
            }
        }
        return count;
    }

    /**
     * Parallel work: when parallel cards are installed, take over
     * {@code tickingRequest} and complete one batch of (parallel+1) jobs
     * per tick. Without cards, fall through to vanilla / RC logic.
     */
    @Inject(method = "tickingRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private void timebus$parallelTicking(IGridNode node, int ticksSinceLastCall, CallbackInfoReturnable<TickRateModulation> cir) {
        final int parallel = this.timebus$getParallelCount();
        if (parallel <= 0) {
            return;
        }

        ItemStack input = this.sideItemHandler.getStackInSlot(0);
        if (input.isEmpty()) {
            cir.setReturnValue(TickRateModulation.SLEEP);
            return;
        }

        // Recipe lookup with a count=1 copy so the vanilla count>1 guard
        // never rejects stacked inputs (works with or without RC).
        ItemStack inputOne = input.copy();
        inputOne.setCount(1);
        final IInscriberRecipe recipe = this.timebus$getTask(inputOne,
                this.topItemHandler.getStackInSlot(0), this.bottomItemHandler.getStackInSlot(0));
        if (recipe == null) {
            cir.setReturnValue(TickRateModulation.SLEEP);
            return;
        }

        // Power: (parallel+1) times the base 10 AE per job.
        final int jobs = parallel + 1;
        final double cost = 10.0 * jobs;
        boolean powered = false;
        try {
            final IEnergyGrid eg = this.timebus$self().getProxy().getEnergy();
            double powerReq = this.timebus$self().extractAEPower(cost, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (powerReq <= cost - 0.01) {
                powerReq = eg.extractAEPower(cost, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            }
            if (powerReq > cost - 0.01) {
                this.timebus$self().extractAEPower(cost, Actionable.MODULATE, PowerMultiplier.CONFIG);
                powered = true;
            }
        } catch (GridAccessException e) {
            // ignore, same as vanilla
        }
        if (!powered) {
            cir.setReturnValue(TickRateModulation.URGENT); // wait for power
            return;
        }

        // Output: (parallel+1) products, simulated first to avoid item loss.
        ItemStack output = recipe.getOutput().copy();
        output.setCount(output.getCount() * jobs);
        if (!this.sideItemHandler.insertItem(1, output, true).isEmpty()) {
            cir.setReturnValue(TickRateModulation.URGENT); // output full, retry
            return;
        }
        this.sideItemHandler.insertItem(1, output, false);

        // Consume (parallel+1) inputs (all remaining if fewer are present).
        int consume = Math.min(input.getCount(), jobs);
        input.setCount(input.getCount() - consume);
        if (input.getCount() <= 0) {
            this.sideItemHandler.setStackInSlot(0, ItemStack.EMPTY);
        } else {
            this.sideItemHandler.setStackInSlot(0, input);
        }

        this.timebus$self().saveChanges();
        this.timebus$self().markForUpdate();
        cir.setReturnValue(input.getCount() > 0 ? TickRateModulation.URGENT : TickRateModulation.SLEEP);
    }
}
