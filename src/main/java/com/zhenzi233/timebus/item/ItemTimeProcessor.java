package com.zhenzi233.timebus.item;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.item.Item;

/**
 * Time Processor (AE2 Engineering Processor analogue).
 * Material item for the Time Bus progression line.
 */
public class ItemTimeProcessor extends Item {

    public static ItemTimeProcessor ITEM;

    public ItemTimeProcessor() {
        this.setCreativeTab(TimeBusCreativeTab.INSTANCE);
        this.setMaxStackSize(64);
        this.setRegistryName(TimeBus.MOD_ID, "time_processor");
        this.setTranslationKey(TimeBus.MOD_ID + ".time_processor");
    }
}
