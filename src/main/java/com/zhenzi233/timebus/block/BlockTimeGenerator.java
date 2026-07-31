package com.zhenzi233.timebus.block;

import appeng.block.AEBaseTileBlock;
import appeng.util.Platform;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.client.gui.GuiHandler;
import com.zhenzi233.timebus.tile.TileTimeGenerator;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import javax.annotation.Nullable;

/**
 * Time Fluid Generator block.
 * Consumes Matter Balls / Singularities and produces Time Fluid (fluid output).
 */
public class BlockTimeGenerator extends AEBaseTileBlock {

    public BlockTimeGenerator() {
        super(Material.IRON);
        this.setTileEntity(TileTimeGenerator.class);
        this.setRegistryName(TimeBus.MOD_ID, "time_generator");
        this.setTranslationKey(TimeBus.MOD_ID + ".time_generator");
        this.setCreativeTab(net.minecraft.creativetab.CreativeTabs.REDSTONE);
    }

    @Override
    public boolean onActivated(final World w, final BlockPos pos, final EntityPlayer player, final EnumHand hand,
                               final @Nullable ItemStack heldItem, final EnumFacing side,
                               final float hitX, final float hitY, final float hitZ) {
        if (player.isSneaking()) {
            return false;
        }
        if (Platform.isServer()) {
            player.openGui(TimeBus.instance, GuiHandler.GUI_TIME_GENERATOR, w, pos.getX(), pos.getY(), pos.getZ());
            return true;
        }
        return true;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        TileTimeGenerator te = this.getTileEntity(worldIn, pos);
        if (te != null) {
            te.dropItems();
        }
        super.breakBlock(worldIn, pos, state);
    }
}
