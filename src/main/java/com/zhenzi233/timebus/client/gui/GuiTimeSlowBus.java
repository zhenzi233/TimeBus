package com.zhenzi233.timebus.client.gui;

import appeng.client.gui.implementations.GuiUpgradeable;
import appeng.core.localization.GuiText;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;

public class GuiTimeSlowBus extends GuiUpgradeable {

    public GuiTimeSlowBus(ContainerTimeSlowBus container) {
        super(container);
    }

    @Override
    public void drawBG(int offsetX, int offsetY, int mouseX, int mouseY) {
        handleButtonVisibility();
        bindTexture("timebus", "guis/bus.png");
        drawTexturedModalRect(offsetX, offsetY, 0, 0, 177, this.ySize);
        if (this.drawUpgrades()) {
            this.drawTexturedModalRect(offsetX + 177, offsetY, 177, 0, 35, 14 + this.cvb.availableUpgrades() * 18);
        }
        if (this.hasToolbox()) {
            this.drawTexturedModalRect(offsetX + 178, offsetY + this.ySize - 90, 178, this.ySize - 90, 68, 68);
        }
    }

    @Override
    public void drawFG(int offsetX, int offsetY, int mouseX, int mouseY) {
        this.fontRenderer.drawString(GuiText.inventory.getLocal(), 8, this.ySize - 96 + 3, 4210752);
        if (this.redstoneMode != null) {
            this.redstoneMode.set(this.cvb.getRedStoneMode());
        }

        if (this.cvb instanceof ContainerTimeSlowBus) {
            ContainerTimeSlowBus container = (ContainerTimeSlowBus) this.cvb;

            // Override title
            this.fontRenderer.drawString(I18n.format("gui.timebus.slow_title"), 8, 6, 4210752);

            // Slowdown info: every N ticks the block tick runs once.
            this.fontRenderer.drawString(I18n.format("gui.timebus.slow_rate", container.syncedSlowdown,
                    container.syncedSpeedCards), 8, 25, 4210752);

            int yOffset = 37;
            if (container.syncedCapacityCards > 0) {
                this.fontRenderer.drawString(TextFormatting.GRAY + I18n.format("gui.timebus.slow_range",
                        container.syncedCapacityWidth), 8, yOffset, 4210752);
                yOffset += 12;
            }

            double power = container.syncedPowerDraw / 100.0;
            this.fontRenderer.drawString(TextFormatting.AQUA + I18n.format("gui.timebus.power", power),
                    8, yOffset, 4210752);
        }
    }
}
