package com.zhenzi233.timebus.client.gui;

import appeng.api.parts.IPartHost;
import appeng.api.util.AEPartLocation;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.part.PartTimeBus;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

import javax.annotation.Nullable;

public class GuiHandler implements IGuiHandler {

    public static final int GUI_TIME_BUS = 0;

    @Nullable
    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID == GUI_TIME_BUS) {
            if (world.getTileEntity(new net.minecraft.util.math.BlockPos(x, y, z)) instanceof IPartHost) {
                IPartHost host = (IPartHost) world.getTileEntity(new net.minecraft.util.math.BlockPos(x, y, z));
                for (AEPartLocation side : AEPartLocation.SIDE_LOCATIONS) {
                    if (host.getPart(side) instanceof PartTimeBus) {
                        PartTimeBus part = (PartTimeBus) host.getPart(side);
                        return new ContainerTimeBus(player.inventory, part);
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID == GUI_TIME_BUS) {
            Object serverElement = getServerGuiElement(ID, player, world, x, y, z);
            if (serverElement instanceof ContainerTimeBus) {
                return new GuiTimeBus((ContainerTimeBus) serverElement);
            }
        }
        return null;
    }
}
