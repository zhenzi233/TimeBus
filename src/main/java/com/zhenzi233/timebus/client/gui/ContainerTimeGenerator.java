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
    public int matterBallCount = 0;
    @GuiSync(3)
    public int singularityCount = 0;
    @GuiSync(4)
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
            this.matterBallCount = this.generator.getMatterBallCount();
            this.singularityCount = this.generator.getSingularityCount();
            this.output = this.generator.getOutput();
        }
        super.detectAndSendChanges();
    }

    @Override
    public int getCurrentProgress() {
        return (int) this.storedFluid;
    }

    @Override
    public int getMaxProgress() {
        return (int) Math.max(1, this.maxFluid);
    }

    public CondenserOutput getOutput() {
        return this.output;
    }
}
