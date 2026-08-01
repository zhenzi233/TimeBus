package com.zhenzi233.timebus.client.gui;

import appeng.client.gui.widgets.ITooltip;
import appeng.container.interfaces.IProgressProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

/**
 * Invisible hover zone for the Time Fluid Generator's progress bar.
 * The bar itself is drawn by {@link GuiTimeGenerator#drawBG}; this button only
 * supplies the tooltip shown when the mouse is over the bar, using the same
 * ITooltip mechanism as AE2's own GUI widgets.
 */
public class GuiTimeProgressBar extends GuiButton implements ITooltip {

    private final IProgressProvider source;

    public GuiTimeProgressBar(final IProgressProvider source, final int posX, final int posY) {
        super(0, posX, posY, 6, 18, "");
        this.source = source;
        this.visible = true;
    }

    @Override
    public void drawButton(final Minecraft mc, final int mouseX, final int mouseY, final float partialTicks) {
        // The progress bar fill is drawn by GuiTimeGenerator.drawBG; nothing to do here.
    }

    @Override
    public String getMessage() {
        String title = I18n.format("gui.timebus.generator.title");
        String progress = I18n.format("gui.timebus.generator.progress",
                this.source.getCurrentProgress(), this.source.getMaxProgress());
        return title + '\n' + progress;
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
