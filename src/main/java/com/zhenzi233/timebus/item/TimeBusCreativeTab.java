package com.zhenzi233.timebus.item;

import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.part.ItemTimeBus;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;

/**
 * Creative tab containing all Time Bus mod items
 * (tab name key: {@code itemGroup.timebus}).
 */
public class TimeBusCreativeTab extends CreativeTabs {

    public static final TimeBusCreativeTab INSTANCE = new TimeBusCreativeTab();

    private TimeBusCreativeTab() {
        super(TimeBus.MOD_ID);
    }

    @Override
    public ItemStack createIcon() {
        return new ItemStack(ItemTimeBus.ITEM);
    }
}
