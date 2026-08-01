package com.zhenzi233.timebus.item;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.Upgrades;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
import com.zhenzi233.timebus.util.AccelerateHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import appeng.api.config.FuzzyMode;
import appeng.api.implementations.items.IStorageCell;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.core.AEConfig;
import appeng.fluids.helper.FluidCellConfig;
import appeng.fluids.util.AEFluidStack;
import appeng.items.contents.CellUpgrades;
import appeng.items.tools.powered.powersink.AEBasePoweredItem;
import appeng.util.Platform;
import com.zhenzi233.timebus.fluid.TimeBusFluids;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Time Wand - a powered storage cell that holds ONLY Time Fluid.
 * Mirrors the Matter Cannon's structure (powered cell), but on the fluid
 * channel: 512 bytes, 1 type, AE-powered. Exposes FLUID_HANDLER_ITEM_CAPABILITY
 * so fluid terminals and other Forge-capability consumers can interact with it.
 */
public class ItemTimeWand extends AEBasePoweredItem implements IStorageCell<IAEFluidStack> {

    public static ItemTimeWand ITEM;

    public ItemTimeWand() {
        super(AEConfig.instance().getMatterCannonBattery());
        this.setRegistryName("timebus", "time_wand");
        this.setTranslationKey("timebus.time_wand");
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addCheckedInformation(final ItemStack stack, final World world, final List<String> lines, final ITooltipFlag advancedTooltips) {
        super.addCheckedInformation(stack, world, lines, advancedTooltips);

        final ICellInventoryHandler<IAEFluidStack> cdi = AEApi.instance()
                .registries()
                .cell()
                .getCellInventory(stack, null,
                        AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));

        AEApi.instance().client().addCellInformation(cdi, lines);
    }

    // --- Right-click acceleration (shift + right-click) ---

    /** Number of Speed Cards installed via the AE2 cell workbench. */
    private int getCardCount(final ItemStack stack) {
        final net.minecraftforge.items.IItemHandler upgrades = this.getUpgradesInventory(stack);
        if (upgrades instanceof appeng.parts.automation.UpgradeInventory) {
            return ((appeng.parts.automation.UpgradeInventory) upgrades).getInstalledUpgrades(Upgrades.SPEED);
        }
        return 0;
    }

    /** Effective speed from the wand's own configuration (independent of the Time Bus). */
    private int getWandSpeed(final ItemStack stack) {
        try {
            final String[] parts = TimeBusConfig.wandSpeedMultipliers.split(",");
            if (parts.length == 0) {
                return 1;
            }
            final int idx = Math.min(getCardCount(stack), parts.length - 1);
            return Math.max(1, Integer.parseInt(parts[idx].trim()));
        } catch (NumberFormatException | NullPointerException e) {
            TimeBus.LOGGER.warn("Invalid wandSpeedMultipliers config: '{}'", TimeBusConfig.wandSpeedMultipliers);
            return 1;
        }
    }

    @Override
    public EnumActionResult onItemUse(final EntityPlayer player, final net.minecraft.world.World worldIn,
                                      final BlockPos pos, final EnumHand hand, final net.minecraft.util.EnumFacing facing,
                                      final float hitX, final float hitY, final float hitZ) {
        // Shift + right-click accelerates the targeted block.
        if (!player.isSneaking()) {
            return EnumActionResult.PASS;
        }
        if (worldIn.isRemote) {
            return EnumActionResult.SUCCESS; // server-side only
        }
        final ItemStack stack = player.getHeldItem(hand);
        if (TimeBusFluids.TIME_FLUID == null) {
            return EnumActionResult.FAIL;
        }

        // 1. Check AE energy (simulate first, commit only if everything is available).
        final double energyNeed = TimeBusConfig.wandEnergyCost;
        if (this.extractAEPower(stack, energyNeed, Actionable.SIMULATE) < energyNeed) {
            return EnumActionResult.FAIL; // not enough AE power
        }

        // 2. Check Time Fluid in the wand's cell.
        final appeng.api.storage.ICellInventoryHandler<IAEFluidStack> cell = AEApi.instance()
                .registries().cell()
                .getCellInventory(stack, null,
                        AEApi.instance().storage().getStorageChannel(appeng.api.storage.channels.IFluidStorageChannel.class));
        if (cell == null) {
            return EnumActionResult.FAIL;
        }
        final int fluidNeed = TimeBusConfig.wandFluidCost;
        final IAEFluidStack request = AEFluidStack.fromFluidStack(new FluidStack(TimeBusFluids.TIME_FLUID, fluidNeed));
        final IAEFluidStack taken = cell.extractItems(request, Actionable.SIMULATE, null);
        if (taken == null || taken.getStackSize() < fluidNeed) {
            return EnumActionResult.FAIL; // not enough Time Fluid
        }

        // 3. Commit both costs.
        this.extractAEPower(stack, energyNeed, Actionable.MODULATE);
        cell.extractItems(request, Actionable.MODULATE, null);

        // 4. Accelerate the target block once.
        AccelerateHelper.accelerateOnce(worldIn, pos, getWandSpeed(stack));
        return EnumActionResult.SUCCESS;
    }
    // --- IStorageCell ---

    @Override
    public int getBytes(final ItemStack cellItem) {
        return 512;
    }

    @Override
    public int getBytesPerType(final ItemStack cellItem) {
        return 8;
    }

    @Override
    public int getTotalTypes(final ItemStack cellItem) {
        return 1; // Time Fluid only
    }

    @Override
    public boolean isBlackListed(final ItemStack cellItem, final IAEFluidStack requestedAddition) {
        // Only Time Fluid may enter the wand.
        return TimeBusFluids.TIME_FLUID == null
                || requestedAddition == null
                || requestedAddition.getFluid() != TimeBusFluids.TIME_FLUID;
    }

    @Override
    public boolean storableInStorageCell() {
        return true;
    }

    @Override
    public boolean isStorageCell(final ItemStack i) {
        return true;
    }

    @Override
    public double getIdleDrain() {
        return 0.5;
    }

    @Override
    public IStorageChannel<IAEFluidStack> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);
    }

    // --- ICellWorkbenchItem ---

    @Override
    public boolean isEditable(final ItemStack is) {
        return true;
    }

    @Override
    public IItemHandler getUpgradesInventory(final ItemStack is) {
        return new CellUpgrades(is, 4);
    }

    @Override
    public IItemHandler getConfigInventory(final ItemStack is) {
        return new FluidCellConfig(is);
    }

    @Override
    public FuzzyMode getFuzzyMode(final ItemStack is) {
        final String fz = Platform.openNbtData(is).getString("FuzzyMode");
        try {
            return FuzzyMode.valueOf(fz);
        } catch (final Throwable t) {
            return FuzzyMode.IGNORE_ALL;
        }
    }

    @Override
    public void setFuzzyMode(final ItemStack is, final FuzzyMode fzMode) {
        Platform.openNbtData(is).setString("FuzzyMode", fzMode.name());
    }

    // --- Capabilities: expose as a Forge fluid container item ---

    @Override
    public ICapabilityProvider initCapabilities(final ItemStack stack, final NBTTagCompound nbt) {
        return new ICapabilityProvider() {
            @Override
            public boolean hasCapability(final Capability<?> capability, @Nullable final EnumFacing facing) {
                return capability == CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getCapability(final Capability<T> capability, @Nullable final EnumFacing facing) {
                if (capability == CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY) {
                    return (T) new WandFluidHandler(stack);
                }
                return null;
            }
        };
    }

    /**
     * Forge fluid container facade over the wand's cell inventory, so the wand
     * works in fluid terminals and any other FLUID_HANDLER_ITEM consumer.
     */
    private class WandFluidHandler implements IFluidHandlerItem {

        private final ItemStack stack;

        WandFluidHandler(final ItemStack stack) {
            this.stack = stack;
        }

        private ICellInventoryHandler<IAEFluidStack> cell() {
            return AEApi.instance().registries().cell()
                    .getCellInventory(this.stack, null,
                            AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
        }

        @Override
        public IFluidTankProperties[] getTankProperties() {
            final ICellInventoryHandler<IAEFluidStack> cell = this.cell();
            if (cell == null || TimeBusFluids.TIME_FLUID == null) {
                return new IFluidTankProperties[0];
            }
            final ICellInventory<IAEFluidStack> inv = cell.getCellInv();
            final long capacity = inv == null ? 0 : inv.getTotalBytes() * 1000;
            FluidStack contents = null;
            final IAEFluidStack first = cell.getAvailableItems(
                    AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class).createList()).getFirstItem();
            if (first != null) {
                contents = first.getFluidStack();
            }
            return new IFluidTankProperties[]{new FluidTankProperties(contents, (int) capacity, true, true)};
        }

        @Override
        public int fill(final FluidStack resource, final boolean doFill) {
            if (resource == null || TimeBusFluids.TIME_FLUID == null
                    || resource.getFluid() != TimeBusFluids.TIME_FLUID) {
                return 0;
            }
            final ICellInventoryHandler<IAEFluidStack> cell = this.cell();
            if (cell == null) {
                return 0;
            }
            final IAEFluidStack toStore = AEFluidStack.fromFluidStack(resource);
            final IAEFluidStack leftover = cell.injectItems(toStore,
                    doFill ? Actionable.MODULATE : Actionable.SIMULATE, null);
            return resource.amount - (leftover == null ? 0 : (int) leftover.getStackSize());
        }

        @Override
        @Nullable
        public FluidStack drain(final FluidStack resource, final boolean doDrain) {
            if (resource == null || TimeBusFluids.TIME_FLUID == null
                    || resource.getFluid() != TimeBusFluids.TIME_FLUID) {
                return null;
            }
            return this.drain(resource.amount, doDrain);
        }

        @Override
        @Nullable
        public FluidStack drain(final int maxDrain, final boolean doDrain) {
            if (maxDrain <= 0 || TimeBusFluids.TIME_FLUID == null) {
                return null;
            }
            final ICellInventoryHandler<IAEFluidStack> cell = this.cell();
            if (cell == null) {
                return null;
            }
            final IAEFluidStack request = AEFluidStack.fromFluidStack(
                    new FluidStack(TimeBusFluids.TIME_FLUID, maxDrain));
            final IAEFluidStack taken = cell.extractItems(request,
                    doDrain ? Actionable.MODULATE : Actionable.SIMULATE, null);
            return taken == null ? null : taken.getFluidStack();
        }

        @Override
        public ItemStack getContainer() {
            return this.stack;
        }
    }
}
