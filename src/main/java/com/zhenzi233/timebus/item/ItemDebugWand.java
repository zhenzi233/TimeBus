package com.zhenzi233.timebus.item;

import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.fluid.TimeBusFluids;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Debug wand: right-click on a block face to spawn time fluid there.
 */
public class ItemDebugWand extends Item {

    public static ItemDebugWand ITEM;

    public ItemDebugWand() {
        this.setCreativeTab(TimeBusCreativeTab.INSTANCE);
        this.setMaxStackSize(1);
        this.setRegistryName(TimeBus.MOD_ID, "debug_wand");
        this.setTranslationKey(TimeBus.MOD_ID + ".debug_wand");
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        // Place a time fluid block at the clicked face (server side only).
        if (!world.isRemote && TimeBusFluids.TIME_FLUID_BLOCK != null) {
            world.setBlockState(pos.offset(facing), TimeBusFluids.TIME_FLUID_BLOCK.getDefaultState());
        }
        return EnumActionResult.SUCCESS;
    }
}
