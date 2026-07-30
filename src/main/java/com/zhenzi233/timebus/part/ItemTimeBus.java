package com.zhenzi233.timebus.part;

import appeng.api.AEApi;
import appeng.api.parts.IPartItem;
import com.zhenzi233.timebus.TimeBus;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class ItemTimeBus extends Item implements IPartItem<PartTimeBus> {

    public static ItemTimeBus ITEM;

    public ItemTimeBus() {
        this.setCreativeTab(CreativeTabs.REDSTONE);
        this.setRegistryName(TimeBus.MOD_ID, "time_bus");
        this.setTranslationKey(TimeBus.MOD_ID + ".time_bus");
    }

    @Nullable
    @Override
    public PartTimeBus createPartFromItemStack(ItemStack is) {
        ItemStack copy = is.copy();
        copy.setCount(1);
        return new PartTimeBus(copy);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        return AEApi.instance().partHelper().placeBus(player.getHeldItem(hand), pos, facing, player, hand, world);
    }
}
