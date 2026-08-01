package com.zhenzi233.timebus.client.gui;

import appeng.api.config.CondenserOutput;
import appeng.client.gui.widgets.ITooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

/**
 * Mode toggle button for the Time Fluid Generator.
 * Uses AE2's states.png artwork for the icon (matter ball / singularity) but
 * shows this mod's own tooltip, and never exposes the TRASH (destroy) mode.
 */
public class GuiTimeModeButton extends GuiButton implements ITooltip {

    private static final ResourceLocation STATES = new ResourceLocation("appliedenergistics2", "textures/guis/states.png");
    // Icon indices in states.png for condenser output modes.
    private static final int ICON_MATTER_BALLS = 16 * 7 + 1;
    private static final int ICON_SINGULARITY = 16 * 7 + 2;

    private final ContainerTimeGenerator cvc;

    public GuiTimeModeButton(final ContainerTimeGenerator cvc, final int x, final int y) {
        super(0, x, y, 16, 16, "");
        this.cvc = cvc;
    }

    @Override
    public void drawButton(final Minecraft mc, final int mouseX, final int mouseY, final float partialTicks) {
        if (!this.visible) {
            return;
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.renderEngine.bindTexture(STATES);
        this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;

        final int iconIndex = this.cvc.getOutput() == CondenserOutput.SINGULARITY ? ICON_SINGULARITY : ICON_MATTER_BALLS;
        final int uvY = iconIndex / 16;
        final int uvX = iconIndex - uvY * 16;

        // Button background (states.png bottom-right tile) + icon on top.
        this.drawTexturedModalRect(this.x, this.y, 256 - 16, 256 - 16, 16, 16);
        this.drawTexturedModalRect(this.x, this.y, uvX * 16, uvY * 16, 16, 16);
    }

    @Override
    public String getMessage() {
        final CondenserOutput out = this.cvc.getOutput();
        final String modeKey = out == CondenserOutput.SINGULARITY
                ? "gui.timebus.generator.mode.singularity"
                : "gui.timebus.generator.mode.matter_ball";
        return I18n.format("gui.timebus.generator.mode") + "\n" + I18n.format(modeKey);
    }

    @Override
    public int xPos() {
        return this.x - 2;
    }

    @Override
    public int yPos() {
        return this.y - 2;
    }

    @Override
    public int getWidth() {
        return this.width + 4;
    }

    @Override
    public int getHeight() {
        return this.height + 4;
    }

    @Override
    public boolean isVisible() {
        return this.visible;
    }
}
