package com.zhenzi233.timebus.item;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.item.ItemStack;

/**
 * TimeBus upgrade card types (modelled after NAE2's {@code UpgradeType}).
 *
 * <p>Each constant describes one upgrade card. With a single card today
 * ({@link #MACHINE_PARALLEL}) this stays minimal, but the structure makes
 * adding further cards (speed, overdrive, ...) a one-constant change plus a
 * mixin-free behavior hook.
 */
public enum TimeBusUpgradeType {

    MACHINE_PARALLEL("machine_parallel");

    private final String id;

    TimeBusUpgradeType(final String id) {
        this.id = id;
    }

    public String getId() {
        return this.id;
    }

    public String getTranslationKey() {
        return "item." + TimeBus.MOD_ID + ".upgrade." + this.id;
    }

    /** Returns the default stack for this card. */
    public ItemStack stack() {
        return new ItemStack(ItemMachineParallelCard.ITEM, 1, 0);
    }
}
