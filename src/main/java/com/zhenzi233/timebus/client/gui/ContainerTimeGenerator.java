package com.zhenzi233.timebus.client.gui;

import appeng.api.config.CondenserOutput;
import appeng.container.AEBaseContainer;
import appeng.container.guisync.GuiSync;
import appeng.container.interfaces.IProgressProvider;
import appeng.container.slot.SlotRestrictedInput;
import appeng.util.Platform;
import com.zhenzi233.timebus.tile.TileTimeGenerator;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.items.IItemHandler;

/**
 * Container for the Time Fluid Generator.
 * Mirrors the Matter Condenser layout: input slot (51,52), storage component slot (101,26),
 * output mode button at (128,52). Syncs fluid level, batch counters and the output mode
 * via AE2's @GuiSync mechanism.
 */
public class ContainerTimeGenerator extends AEBaseContainer implements IProgressProvider {

    private final TileTimeGenerator generator;

    @GuiSync(0)
    public long storedFluid = 0;
    @GuiSync(1)
    public long maxFluid = 0;
    @GuiSync(2)
    public long progressUnits = 0;
    @GuiSync(3)
    public long unitsPerBatch = 0;
    @GuiSync(4)
    public long matterBallCount = 0;
    @GuiSync(5)
    public long singularityCount = 0;
    @GuiSync(6)
    public CondenserOutput output = CondenserOutput.MATTER_BALLS;

    public ContainerTimeGenerator(final InventoryPlayer ip, final TileTimeGenerator generator) {
        super(ip, generator, null);
        this.generator = generator;

        IItemHandler inv = generator.getInternalInventory();

        // Input slot: matter balls / singularities (the tile's inventory filter enforces this)
        this.addSlotToContainer(new SlotRestrictedInput(SlotRestrictedInput.PlacableItemType.TRASH, inv, 0, 51, 52, ip));
        // Storage component slot: decides the fluid buffer capacity
        this.addSlotToContainer((new SlotRestrictedInput(SlotRestrictedInput.PlacableItemType.STORAGE_COMPONENT, inv, 1, 101, 26, ip)).setStackLimit(1));

        this.bindPlayerInventory(ip, 0, 197 - 82);
    }

    @Override
    public void detectAndSendChanges() {
        if (Platform.isServer()) {
            this.storedFluid = (long) this.generator.getStoredFluid();
            this.maxFluid = (long) this.generator.getStorage();
            this.progressUnits = (long) this.generator.getProgressUnits();
            this.unitsPerBatch = (long) this.generator.getUnitsPerBatch();
            this.matterBallCount = this.generator.getMatterBallCount();
            this.singularityCount = this.generator.getSingularityCount();
            this.output = this.generator.getOutput();
        }
        super.detectAndSendChanges();
    }

    @Override
    public int getCurrentProgress() {
        // Progress bar shows the production progress in unified units,
        // so switching the input mode keeps the visible progress.
        // Without a storage component the bar reads 0 (hidden), but the
        // accumulated units stay stored in the tile and reappear once a
        // storage component is re-inserted.
        return this.maxFluid > 0 ? (int) this.progressUnits : 0;
    }

    @Override
    public int getMaxProgress() {
        return (int) Math.max(1, this.unitsPerBatch);
    }

    public CondenserOutput getOutput() {
        return this.output;
    }

    public TileTimeGenerator getGenerator() {
        return this.generator;
    }

    /**
     * Handles fluid container interaction with the generator's tank:
     * FILL_ITEM fills the held container from the tank (bottling), EMPTY_ITEM
     * empties the held container into the tank. Unlike AE2's fluid containers,
     * we do not need a client-requested target fluid: the tank holds only Time
     * Fluid, so the server reads it straight from the tile.
     */
    @Override
    public void doAction(final net.minecraft.entity.player.EntityPlayerMP player,
                         final appeng.helpers.InventoryAction action, final int slot, final long id) {
        if (action != appeng.helpers.InventoryAction.FILL_ITEM && action != appeng.helpers.InventoryAction.EMPTY_ITEM) {
            super.doAction(player, action, slot, id);
            return;
        }

        final net.minecraft.item.ItemStack held = player.inventory.getItemStack();
        final net.minecraft.item.ItemStack heldCopy = held.copy();
        heldCopy.setCount(1);
        final net.minecraftforge.fluids.capability.IFluidHandlerItem fh = net.minecraftforge.fluids.FluidUtil.getFluidHandler(heldCopy);
        if (fh == null) {
            return; // only items with a fluid handler can be filled/emptied
        }

        if (action == appeng.helpers.InventoryAction.FILL_ITEM) {
            // Bottle: drain from the generator tank into the held container.
            final appeng.api.storage.data.IAEFluidStack tankFluid = this.generator.getFluidInSlot(slot);
            if (tankFluid == null) {
                return;
            }
            final int amountAllowed = fh.fill(tankFluid.getFluidStack(), false);
            if (amountAllowed <= 0) {
                return;
            }
            final net.minecraftforge.fluids.FluidStack extractable = this.generator.drain(
                    tankFluid.setStackSize(amountAllowed).getFluidStack(), false);
            if (extractable == null || extractable.amount <= 0) {
                return;
            }
            final net.minecraftforge.fluids.FluidStack extracted = this.generator.drain(extractable, true);
            if (extracted != null && extracted.amount > 0) {
                fh.fill(extracted, true);
                updateHeldContainer(player, fh.getContainer(), held);
            }
        } else if (action == appeng.helpers.InventoryAction.EMPTY_ITEM) {
            // Pour: drain the held container into the generator tank (output-only tank
            // may reject the fluid; if so, nothing is lost).
            final int capacity = this.generator.getTankProperties()[slot].getCapacity();
            final net.minecraftforge.fluids.FluidStack drainable = fh.drain(capacity, false);
            if (drainable == null || drainable.amount <= 0) {
                return;
            }
            final int filled = this.generator.fill(drainable, true);
            if (filled > 0) {
                fh.drain(new net.minecraftforge.fluids.FluidStack(drainable.getFluid(), filled), true);
                updateHeldContainer(player, fh.getContainer(), held);
            }
        }
        this.updateHeld(player);
    }

    private void updateHeldContainer(final net.minecraft.entity.player.EntityPlayerMP player,
                                     final net.minecraft.item.ItemStack container, final net.minecraft.item.ItemStack held) {
        if (held.getCount() == 1) {
            player.inventory.setItemStack(container);
        } else {
            player.inventory.getItemStack().shrink(1);
            if (!player.inventory.addItemStackToInventory(container)) {
                player.dropItem(container, false);
            }
        }
    }
}
