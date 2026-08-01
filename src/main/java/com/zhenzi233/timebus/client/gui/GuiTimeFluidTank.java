package com.zhenzi233.timebus.client.gui;

import appeng.api.storage.data.IAEFluidStack;
import appeng.client.gui.widgets.GuiCustomSlot;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketInventoryAction;
import appeng.fluids.util.IAEFluidTank;
import appeng.helpers.InventoryAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;

/**
 * Fluid tank widget for the Time Fluid Generator.
 * Same rendering as AE2's GuiFluidTank, except the fill height has a one-tile
 * (16px) minimum: generator capacities are huge (storage component bytes x 8)
 * while batches are small, so a pure ratio would make the fluid nearly
 * invisible. As soon as there is any fluid, at least one full tile is drawn.
 * The amount in buckets (1000 mB = 1 b) is overlaid at the bottom-right corner.
 */
public class GuiTimeFluidTank extends GuiCustomSlot {

    private final IAEFluidTank tank;
    private final int slot;
    private final int width;
    private final int height;

    public GuiTimeFluidTank(final IAEFluidTank tank, final int slot, final int id, final int x, final int y, final int w, final int h) {
        super(id, x, y);
        this.tank = tank;
        this.slot = slot;
        this.width = w;
        this.height = h;
    }

    @Override
    public void drawContent(final Minecraft mc, final int mouseX, final int mouseY, final float partialTicks) {
        final IAEFluidStack fluid = this.tank.getFluidInSlot(this.slot);
        if (fluid == null || fluid.getStackSize() <= 0) {
            return;
        }

        GlStateManager.disableBlend();
        GlStateManager.disableLighting();

        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        final Fluid fl = fluid.getFluid();
        final float red = (fl.getColor() >> 16 & 255) / 255.0F;
        final float green = (fl.getColor() >> 8 & 255) / 255.0F;
        final float blue = (fl.getColor() & 255) / 255.0F;
        GlStateManager.color(red, green, blue);

        final TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite(fl.getStill().toString());
        // Full coverage: whenever there is any fluid, stretch it across the
        // whole slot, independent of capacity. The bucket amount is overlaid.
        this.drawTexturedModalRect(this.xPos(), this.yPos(), sprite, this.getWidth(), this.getHeight());

        // Amount overlay in buckets (1000 mB = 1 b) at the bottom-right corner,
        // drawn at half scale so it does not crowd the 16x16 slot.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        final String text = formatBuckets(fluid.getStackSize());
        final int textWidth = mc.fontRenderer.getStringWidth(text);
        GlStateManager.pushMatrix();
        GlStateManager.scale(0.5F, 0.5F, 1.0F);
        mc.fontRenderer.drawStringWithShadow(text,
                (this.xPos() + this.getWidth()) * 2 - textWidth - 4,
                (this.yPos() + this.getHeight()) * 2 - 12, 0xFFFFFF);
        GlStateManager.popMatrix();
    }

    /** 1000 mB = 1 b; integers print without decimals (1 b -> "1", 1500 mB -> "1.5"). */
    private static String formatBuckets(final long mB) {
        if (mB <= 0) {
            return "0";
        }
        final double buckets = mB / 1000.0;
        if (buckets >= 100 || buckets == Math.floor(buckets)) {
            return String.valueOf((long) buckets);
        }
        return String.format("%.1f", buckets);
    }

    @Override
    public void slotClicked(final ItemStack clickStack, final int mouseButton) {
        if (this.getFluidStack() != null) {
            NetworkHandler.instance().sendToServer(new PacketInventoryAction(InventoryAction.FILL_ITEM, this.slot, this.id));
        } else {
            NetworkHandler.instance().sendToServer(new PacketInventoryAction(InventoryAction.EMPTY_ITEM, this.slot, this.id));
        }
    }

    @Override
    public String getMessage() {
        final IAEFluidStack fluid = this.tank.getFluidInSlot(this.slot);
        if (fluid != null && fluid.getStackSize() > 0) {
            return fluid.getFluid().getLocalizedName(fluid.getFluidStack()) + "\n"
                    + fluid.getStackSize() + "/" + this.tank.getTankProperties()[this.slot].getCapacity() + "mB";
        }
        return null;
    }

    @Override
    public int xPos() {
        return this.x;
    }

    @Override
    public int yPos() {
        return this.y;
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public int getHeight() {
        return this.height;
    }

    @Override
    public boolean isVisible() {
        return true;
    }

    public IAEFluidStack getFluidStack() {
        return this.tank.getFluidInSlot(this.slot);
    }

    @Override
    public Object getIngredient() {
        return this.getFluidStack() == null ? null : this.getFluidStack().getFluidStack();
    }
}
