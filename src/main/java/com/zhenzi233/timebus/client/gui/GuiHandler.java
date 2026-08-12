package com.zhenzi233.timebus.client.gui;

import appeng.api.parts.IPartHost;
import appeng.api.util.AEPartLocation;
import com.zhenzi233.timebus.part.PartTimeBus;
import com.zhenzi233.timebus.part.PartTimeSlowBus;
import com.zhenzi233.timebus.tile.TileTimeGenerator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

import javax.annotation.Nullable;

public class GuiHandler implements IGuiHandler {

    public static final int GUI_TIME_BUS = 0;
    public static final int GUI_TIME_GENERATOR = 1;
    public static final int GUI_TIME_SLOW_BUS = 2;

    @Nullable
    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (ID == GUI_TIME_BUS) {
            if (world.getTileEntity(pos) instanceof IPartHost) {
                IPartHost host = (IPartHost) world.getTileEntity(pos);
                for (AEPartLocation side : AEPartLocation.SIDE_LOCATIONS) {
                    if (host.getPart(side) instanceof PartTimeBus) {
                        PartTimeBus part = (PartTimeBus) host.getPart(side);
                        return new ContainerTimeBus(player.inventory, part);
                    }
                }
            }
        } else if (ID == GUI_TIME_SLOW_BUS) {
            if (world.getTileEntity(pos) instanceof IPartHost) {
                IPartHost host = (IPartHost) world.getTileEntity(pos);
                for (AEPartLocation side : AEPartLocation.SIDE_LOCATIONS) {
                    if (host.getPart(side) instanceof PartTimeSlowBus) {
                        PartTimeSlowBus part = (PartTimeSlowBus) host.getPart(side);
                        return new ContainerTimeSlowBus(player.inventory, part);
                    }
                }
            }
        } else if (ID == GUI_TIME_GENERATOR) {
            TileEntity te = world.getTileEntity(pos);
            if (te instanceof TileTimeGenerator) {
                return new ContainerTimeGenerator(player.inventory, (TileTimeGenerator) te);
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (ID == GUI_TIME_BUS) {
            Object serverElement = getServerGuiElement(ID, player, world, x, y, z);
            if (serverElement instanceof ContainerTimeBus) {
                return new GuiTimeBus((ContainerTimeBus) serverElement);
            }
        } else if (ID == GUI_TIME_SLOW_BUS) {
            Object serverElement = getServerGuiElement(ID, player, world, x, y, z);
            if (serverElement instanceof ContainerTimeSlowBus) {
                return new GuiTimeSlowBus((ContainerTimeSlowBus) serverElement);
            }
        } else if (ID == GUI_TIME_GENERATOR) {
            TileEntity te = world.getTileEntity(pos);
            if (te instanceof TileTimeGenerator) {
                return new GuiTimeGenerator(player.inventory, (TileTimeGenerator) te);
            }
        }
        return null;
    }
}
