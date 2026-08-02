package com.zhenzi233.timebus.item;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.item.Item;

/**
 * Time Inscriber Template (AE2 Engineering Processor Press analogue).
 * Material item for the Time Bus progression line.
 */
public class ItemTimeInscriberTemplate extends Item {

    public static ItemTimeInscriberTemplate ITEM;

    public ItemTimeInscriberTemplate() {
        this.setCreativeTab(TimeBusCreativeTab.INSTANCE);
        this.setMaxStackSize(64);
        this.setRegistryName(TimeBus.MOD_ID, "time_inscriber_template");
        this.setTranslationKey(TimeBus.MOD_ID + ".time_inscriber_template");
    }
}
