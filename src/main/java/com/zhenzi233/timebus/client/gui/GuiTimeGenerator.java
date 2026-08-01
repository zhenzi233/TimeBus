package com.zhenzi233.timebus.client.gui;

import appeng.api.storage.data.IAEFluidStack;
import appeng.client.gui.AEBaseGui;
import appeng.client.gui.widgets.GuiCustomSlot;
import appeng.fluids.util.AEFluidStack;
import appeng.fluids.util.IAEFluidTank;
import com.zhenzi233.timebus.fluid.TimeBusFluids;
import com.zhenzi233.timebus.tile.TileTimeGenerator;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import java.io.IOException;

/**
 * GUI for the Time Fluid Generator.
 * Mirrors the Matter Condenser exactly: ySize 197, vertical progress bar at (120,25),
 * input slot at (51,52), storage component slot at (101,26), output-mode toggle button
 * at (128,52) that cycles Matter Balls / Singularity / Trash.
 * The progress bar is drawn manually (GuiProgressBar hardcodes the AE2 modid).
 * A fluid tank at the output slot position (104,51) shows the stored Time Fluid and
 * supports bottling/pouring with fluid containers.
 */
public class GuiTimeGenerator extends AEBaseGui {

    private final ContainerTimeGenerator cvc;
    private GuiTimeModeButton mode;

    public GuiTimeGenerator(final InventoryPlayer inventoryPlayer, final TileTimeGenerator te) {
        super(new ContainerTimeGenerator(inventoryPlayer, te));
        this.cvc = (ContainerTimeGenerator) this.inventorySlots;
        this.ySize = 197;
    }

    /**
     * Tank data source backed by the container's @GuiSync fields, so the client
     * shows the server-side fluid level even though the client tile is not ticked.
     */
    private IAEFluidTank getTankSource() {
        return new IAEFluidTank() {
            @Override
            public IFluidTankProperties[] getTankProperties() {
                return new IFluidTankProperties[]{new FluidTankProperties(
                        storedStack(), (int) Math.max(0, GuiTimeGenerator.this.cvc.maxFluid), false, true)};
            }

            @Override
            public int fill(FluidStack resource, boolean doFill) {
                return 0;
            }

            @Override
            public FluidStack drain(FluidStack resource, boolean doDrain) {
                return null;
            }

            @Override
            public FluidStack drain(int maxDrain, boolean doDrain) {
                return null;
            }

            @Override
            public void setFluidInSlot(int slot, IAEFluidStack fluid) {
            }

            @Override
            public IAEFluidStack getFluidInSlot(int slot) {
                FluidStack fs = storedStack();
                return fs == null ? null : AEFluidStack.fromFluidStack(fs);
            }

            @Override
            public int getSlots() {
                return 1;
            }

            private FluidStack storedStack() {
                if (TimeBusFluids.TIME_FLUID == null || GuiTimeGenerator.this.cvc.storedFluid <= 0) {
                    return null;
                }
                return new FluidStack(TimeBusFluids.TIME_FLUID, (int) GuiTimeGenerator.this.cvc.storedFluid);
            }
        };
    }

    @Override
    protected void actionPerformed(final GuiButton btn) throws IOException {
        super.actionPerformed(btn);

        if (this.mode == btn) {
            // Custom two-mode toggle: never cycles through TRASH (destroy) mode.
            com.zhenzi233.timebus.TimeBus.NETWORK.sendToServer(new com.zhenzi233.timebus.network.PacketToggleOutput());
        }
    }

    @Override
    public void initGui() {
        super.initGui();

        this.mode = new GuiTimeModeButton(this.cvc, 128 + this.guiLeft, 52 + this.guiTop);
        this.buttonList.add(this.mode);

        // Hover zone over the progress bar (drawn in drawBG) so the mouse
        // shows a tooltip with the current production progress.
        this.buttonList.add(new GuiTimeProgressBar(this.cvc, 120 + this.guiLeft, 25 + this.guiTop));

        // Fluid tank at the output slot position; clicking with a fluid container
        // bottles (FILL_ITEM) or pours (EMPTY_ITEM) via the container.
        this.guiSlots.add(new GuiTimeFluidTank(this.getTankSource(), 0, 0, 101, 49, 24, 24));
    }

    @Override
    protected void mouseClicked(final int xCoord, final int yCoord, final int btn) throws IOException {
        for (GuiCustomSlot slot : this.guiSlots) {
            if (slot instanceof GuiTimeFluidTank) {
                if (this.isPointInRegion(slot.xPos(), slot.yPos(), slot.getWidth(), slot.getHeight(), xCoord, yCoord)
                        && slot.canClick(this.mc.player)) {
                    slot.slotClicked(this.mc.player.inventory.getItemStack(), btn);
                    return;
                }
            }
        }
        super.mouseClicked(xCoord, yCoord, btn);
    }

    @Override
    public void drawFG(final int offsetX, final int offsetY, final int mouseX, final int mouseY) {
        this.fontRenderer.drawString(this.getGuiDisplayName(I18n.format("gui.timebus.generator.title")), 8, 6, 4210752);
        this.fontRenderer.drawString(I18n.format("gui.timebus.inventory"), 8, this.ySize - 96 + 3, 4210752);

        // The mode button reads the synced output itself; no per-frame set() needed.

        // Info text intentionally omitted: the fluid tank overlay shows the
        // amount, the progress bar shows the batch progress, and the mode
        // button shows the current input type.
    }

    @Override
    public void drawBG(final int offsetX, final int offsetY, final int mouseX, final int mouseY) {
        this.bindTexture("timebus", "guis/generator.png");
        this.drawTexturedModalRect(offsetX, offsetY, 0, 0, this.xSize, this.ySize);

        // Vertical progress bar at (120, 25), 6x18, fill texture region starts at (178, 25)
        // (same coordinates as the Matter Condenser's GuiProgressBar)
        int maxProgress = this.cvc.getMaxProgress();
        int current = this.cvc.getCurrentProgress();
        int fillHeight = 0;
        if (maxProgress > 0 && current > 0) {
            fillHeight = (int) Math.min(18, 18L * current / maxProgress);
        }
        if (fillHeight > 0) {
            this.drawTexturedModalRect(offsetX + 120, offsetY + 25 + 18 - fillHeight,
                    178, 25 + 18 - fillHeight, 6, fillHeight);
        }
    }
}
