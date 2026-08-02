package com.zhenzi233.timebus.item;

import net.minecraft.item.ItemStack;

/**
 * Marker interface for TimeBus upgrade cards (modelled after NAE2's
 * {@code INAEUpgradeModule}). AE2's own {@code IUpgradeModule} cannot be
 * implemented for new cards because it must return a value of the AE2
 * {@code Upgrades} enum, which is not extensible. Cards instead implement
 * this interface and are admitted into AE2 upgrade slots via mixins
 * ({@code MixinUpgradeInvFilter}, {@code MixinSlotRestrictedInput}).
 */
public interface ITimeBusUpgradeModule {

    TimeBusUpgradeType getType(ItemStack is);
}
