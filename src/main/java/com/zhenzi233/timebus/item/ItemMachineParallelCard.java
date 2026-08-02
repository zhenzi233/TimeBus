package com.zhenzi233.timebus.item;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.item.Item;

/**
 * Machine Parallel Card.
 *
 * <p>Placeholder upgrade card for future "machine parallel" behavior on the
 * Time Bus / Time Wand. The item exists and renders with a copied AE2 upgrade
 * card texture; the actual parallel logic is not implemented yet.
 */
public class ItemMachineParallelCard extends Item {

    public static ItemMachineParallelCard ITEM;

    public ItemMachineParallelCard() {
        this.setCreativeTab(TimeBusCreativeTab.INSTANCE);
        this.setMaxStackSize(64);
        this.setRegistryName(TimeBus.MOD_ID, "machine_parallel_card");
        this.setTranslationKey(TimeBus.MOD_ID + ".machine_parallel_card");
    }
}
