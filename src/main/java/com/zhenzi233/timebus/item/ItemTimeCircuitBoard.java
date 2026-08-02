package com.zhenzi233.timebus.item;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.item.Item;

/**
 * Time Circuit Board (AE2 Printed Engineering Circuit analogue).
 * Material item for the Time Bus progression line.
 */
public class ItemTimeCircuitBoard extends Item {

    public static ItemTimeCircuitBoard ITEM;

    public ItemTimeCircuitBoard() {
        this.setCreativeTab(TimeBusCreativeTab.INSTANCE);
        this.setMaxStackSize(64);
        this.setRegistryName(TimeBus.MOD_ID, "time_circuit_board");
        this.setTranslationKey(TimeBus.MOD_ID + ".time_circuit_board");
    }
}
