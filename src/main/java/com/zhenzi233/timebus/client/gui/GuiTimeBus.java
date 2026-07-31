package com.zhenzi233.timebus.client.gui;

import appeng.api.config.Upgrades;
import appeng.client.gui.implementations.GuiUpgradeable;
import appeng.core.localization.GuiText;
import com.zhenzi233.timebus.part.PartTimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;

public class GuiTimeBus extends GuiUpgradeable {

    public GuiTimeBus(ContainerTimeBus container) {
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

        if (this.fuzzyMode != null) {
            this.fuzzyMode.set(this.cvb.getFuzzyMode());
        }

        if (this.craftMode != null) {
            this.craftMode.set(this.cvb.getCraftingMode());
        }

        if (this.schedulingMode != null) {
            this.schedulingMode.set(this.cvb.getSchedulingMode());
        }

        if (this.bc instanceof PartTimeBus) {
            PartTimeBus part = (PartTimeBus) this.bc;
            int speed = part.getEffectiveSpeed();
            int cards = part.getInstalledUpgrades(Upgrades.SPEED);

            // Override title
            this.fontRenderer.drawString(I18n.format("gui.timebus.title"), 8, 6, 4210752);

            // Acceleration info
            this.fontRenderer.drawString(I18n.format("gui.timebus.speed", speed, cards), 8, 25, 4210752);

            int yOffset = 37;
            int capCards = part.getInstalledUpgrades(Upgrades.CAPACITY);
            if (capCards > 0) {
                this.fontRenderer.drawString(TextFormatting.GRAY + I18n.format("gui.timebus.range", part.getCapacityWidth()), 8, yOffset, 4210752);
                yOffset += 12;
            }

            // Power draw info
            double power = part.getPowerDraw();
            String powerText = TextFormatting.AQUA + I18n.format("gui.timebus.power", power);
            this.fontRenderer.drawString(powerText, 8, yOffset, 4210752);
            yOffset += 12;

            // Work budget usage (synced via AE2 @GuiSync)
            ContainerTimeBus container = (ContainerTimeBus) this.cvb;
            int used = container.budgetUsed;
            int total = container.budgetTotal;
            String budgetText = TextFormatting.GREEN + I18n.format("gui.timebus.budget", used, total);
            this.fontRenderer.drawString(budgetText, 8, yOffset, 4210752);
            yOffset += 12;

            // Fluid mode info
            if (TimeBusConfig.fluidMode) {
                this.fontRenderer.drawString(TextFormatting.DARK_AQUA + I18n.format("gui.timebus.fluid_title", part.getFluidDisplayName()), 8, yOffset, 4210752);
                yOffset += 12;
                this.fontRenderer.drawString(TextFormatting.AQUA + I18n.format("gui.timebus.fluid_rate", part.getFluidRate()), 8, yOffset, 4210752);
            }
        }
    }
}
