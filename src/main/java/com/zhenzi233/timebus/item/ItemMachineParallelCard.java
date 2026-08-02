package com.zhenzi233.timebus.item;

import appeng.api.implementations.IUpgradeableHost;
import com.zhenzi233.timebus.TimeBus;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Machine Parallel Card (modelled after NAE2's {@code NAEBaseItemUpgrade}).
 *
 * <p>With N cards in the inscriber's upgrade slots, every completed job
 * consumes (N+1) input items and produces (N+1) outputs; power is (N+1)
 * times the base cost. The behaviour lives in
 * {@code mixin/mod/MixinTileInscriber.java}; this class only describes the
 * card (type, tooltip, direct insertion).
 */
public class ItemMachineParallelCard extends Item implements ITimeBusUpgradeModule {

    public static ItemMachineParallelCard ITEM;

    public ItemMachineParallelCard() {
        this.setCreativeTab(TimeBusCreativeTab.INSTANCE);
        this.setMaxStackSize(64);
        this.setRegistryName(TimeBus.MOD_ID, "machine_parallel_card");
        this.setTranslationKey(TimeBus.MOD_ID + ".machine_parallel_card");
    }

    @Override
    public TimeBusUpgradeType getType(final ItemStack is) {
        return TimeBusUpgradeType.MACHINE_PARALLEL;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(final ItemStack stack, @Nullable final World worldIn,
                               final List<String> tooltip, final ITooltipFlag flagIn) {
        tooltip.add(new TextComponentTranslation("item." + TimeBus.MOD_ID + ".upgrade.machine_parallel.desc").getFormattedText());
        tooltip.add(new TextComponentTranslation("item." + TimeBus.MOD_ID + ".upgrade.machine_parallel.applies").getFormattedText());
    }

    /**
     * Sneak-right-click an inscriber (or any AE2 upgrade host) to insert
     * the card into its upgrade slots directly, without opening the GUI.
     */
    @Override
    public EnumActionResult onItemUseFirst(final EntityPlayer player, final World world, final BlockPos pos,
                                           final EnumFacing side, final float hitX, final float hitY,
                                           final float hitZ, final EnumHand hand) {
        if (player.isSneaking() && !world.isRemote && world.getTileEntity(pos) instanceof IUpgradeableHost) {
            final IItemHandler upgrades = ((IUpgradeableHost) world.getTileEntity(pos)).getInventoryByName("upgrades");
            if (upgrades != null) {
                final ItemStack held = player.getHeldItem(hand);
                final ItemStack remaining = insertInto(upgrades, held.copy());
                if (remaining.getCount() != held.getCount()) {
                    player.setHeldItem(hand, remaining);
                    return EnumActionResult.SUCCESS;
                }
            }
        }
        return super.onItemUseFirst(player, world, pos, side, hitX, hitY, hitZ, hand);
    }

    /** Inserts as much of the stack as fits, returning what was not inserted. */
    private static ItemStack insertInto(final IItemHandler inv, ItemStack stack) {
        for (int i = 0; i < inv.getSlots() && !stack.isEmpty(); i++) {
            stack = inv.insertItem(i, stack, false);
        }
        return stack;
    }
}
