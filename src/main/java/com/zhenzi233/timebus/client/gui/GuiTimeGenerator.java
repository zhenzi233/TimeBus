package com.zhenzi233.timebus.client.gui;

import appeng.api.config.Settings;
import appeng.client.gui.AEBaseGui;
import appeng.client.gui.widgets.GuiImgButton;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketConfigButton;
import com.zhenzi233.timebus.tile.TileTimeGenerator;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * GUI for the Time Fluid Generator.
 * Mirrors the Matter Condenser exactly: ySize 197, vertical progress bar at (120,25),
 * input slot at (51,52), storage component slot at (101,26), output-mode toggle button
 * at (128,52) that cycles Matter Balls / Singularity / Trash.
 * The progress bar is drawn manually (GuiProgressBar hardcodes the AE2 modid).
 */
public class GuiTimeGenerator extends AEBaseGui {

    private final ContainerTimeGenerator cvc;
    private GuiImgButton mode;

    public GuiTimeGenerator(final InventoryPlayer inventoryPlayer, final TileTimeGenerator te) {
        super(new ContainerTimeGenerator(inventoryPlayer, te));
        this.cvc = (ContainerTimeGenerator) this.inventorySlots;
        this.ySize = 197;
    }

    @Override
    protected void actionPerformed(final GuiButton btn) throws IOException {
        super.actionPerformed(btn);

        final boolean backwards = Mouse.isButtonDown(1);

        if (this.mode == btn) {
            NetworkHandler.instance().sendToServer(new PacketConfigButton(Settings.CONDENSER_OUTPUT, backwards));
        }
    }

    @Override
    public void initGui() {
        super.initGui();

        this.mode = new GuiImgButton(128 + this.guiLeft, 52 + this.guiTop, Settings.CONDENSER_OUTPUT, this.cvc.getOutput());
        this.buttonList.add(this.mode);
    }

    @Override
    public void drawFG(final int offsetX, final int offsetY, final int mouseX, final int mouseY) {
        this.fontRenderer.drawString(this.getGuiDisplayName(I18n.format("gui.timebus.generator.title")), 8, 6, 4210752);
        this.fontRenderer.drawString(I18n.format("gui.timebus.inventory"), 8, this.ySize - 96 + 3, 4210752);

        // Sync the button with the server-side mode
        this.mode.set(this.cvc.getOutput());

        // Fluid level (StoredEnergy-style text)
        long stored = this.cvc.storedFluid;
        long max = this.cvc.maxFluid;
        String fluidText = TextFormatting.AQUA + I18n.format("gui.timebus.generator.fluid", stored, max);
        this.fontRenderer.drawString(fluidText, 8, 46, 4210752);

        // Batch progress lines
        int balls = this.cvc.matterBallCount;
        int singularities = this.cvc.singularityCount;
        String ballText = TextFormatting.WHITE + I18n.format("gui.timebus.generator.balls", balls);
        this.fontRenderer.drawString(ballText, 8, 60, 4210752);
        String singText = TextFormatting.WHITE + I18n.format("gui.timebus.generator.singularities", singularities);
        this.fontRenderer.drawString(singText, 8, 72, 4210752);
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
