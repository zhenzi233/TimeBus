package com.zhenzi233.timebus.tile;

import appeng.api.AEApi;
import appeng.api.config.CondenserOutput;
import appeng.api.config.Settings;
import appeng.api.definitions.IMaterials;
import appeng.api.implementations.items.IStorageComponent;
import appeng.api.util.IConfigManager;
import appeng.api.util.IConfigurableObject;
import appeng.tile.AEBaseInvTile;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.util.ConfigManager;
import appeng.util.IConfigManagerHost;
import appeng.util.inv.InvOperation;
import com.zhenzi233.timebus.config.TimeBusConfig;
import com.zhenzi233.timebus.fluid.TimeBusFluids;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;

/**
 * Time Fluid Generator.
 * <p>
 * Mirrors the AE2 Matter Condenser: the output mode (Matter Balls / Singularity)
 * is switchable via a GUI button, inputs are accumulated until enough have been
 * fed in, then Time Fluid is produced in one batch (256 Matter Balls = 1 mB,
 * 256 Singularities = 10000 mB by default). The storage component slot decides
 * the maximum fluid buffer.
 */
public class TileTimeGenerator extends AEBaseInvTile implements ITickable, IFluidHandler, IConfigManagerHost, IConfigurableObject {

    public static final int BYTE_MULTIPLIER = 8;

    // Slot indices
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_STORAGE = 1;

    private final ConfigManager cm = new ConfigManager(this);
    private final AppEngInternalInventory inputSlot = new AppEngInternalInventory(this, 1);
    private final AppEngInternalInventory storageSlot = new AppEngInternalInventory(this, 1);
    private final IItemHandler combinedInv = new CombinedInventory();

    private double storedFluid = 0;

    // Batch counters (condenser-style: accumulate inputs, then produce)
    private int matterBallCount = 0;
    private int singularityCount = 0;

    public TileTimeGenerator() {
        // Default mode: Matter Balls. GUI button cycles MATTER_BALLS -> SINGULARITY -> TRASH.
        this.cm.registerSetting(Settings.CONDENSER_OUTPUT, CondenserOutput.MATTER_BALLS);
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote || this.isInvalid()) {
            return;
        }
        processInput();
    }

    /**
     * Consume one input item per tick. Counters accumulate independently per item
     * type; when enough have been fed in and there is room, Time Fluid is produced
     * in one batch (exactly like the condenser waiting until requiredPower is met).
     * Only the input matching the current output mode is accepted.
     */
    private void processInput() {
        ItemStack input = this.inputSlot.getStackInSlot(0);
        if (input.isEmpty()) {
            return;
        }
        double space = getStorage() - this.storedFluid;
        CondenserOutput mode = (CondenserOutput) this.cm.getSetting(Settings.CONDENSER_OUTPUT);

        if (mode == CondenserOutput.MATTER_BALLS && isMatterBall(input)) {
            if (space < TimeBusConfig.timePerMatterBallBatch) {
                return; // no room for one full batch yet
            }
            ItemStack consumed = this.inputSlot.extractItem(0, 1, false);
            if (consumed.isEmpty()) {
                return;
            }
            this.matterBallCount++;
            if (this.matterBallCount >= TimeBusConfig.matterBallsPerBatch) {
                this.matterBallCount -= TimeBusConfig.matterBallsPerBatch;
                this.storedFluid += TimeBusConfig.timePerMatterBallBatch;
                this.markDirty();
            }
        } else if (mode == CondenserOutput.SINGULARITY && isSingularity(input)) {
            if (space < TimeBusConfig.timePerSingularityBatch) {
                return; // no room for one full batch yet
            }
            ItemStack consumed = this.inputSlot.extractItem(0, 1, false);
            if (consumed.isEmpty()) {
                return;
            }
            this.singularityCount++;
            if (this.singularityCount >= TimeBusConfig.singularitiesPerBatch) {
                this.singularityCount -= TimeBusConfig.singularitiesPerBatch;
                this.storedFluid += TimeBusConfig.timePerSingularityBatch;
                this.markDirty();
            }
        }
        // TRASH mode (or mismatched input) consumes nothing - item stays in the slot.
    }

    private boolean isMatterBall(ItemStack is) {
        IMaterials materials = AEApi.instance().definitions().materials();
        return materials.matterBall().isSameAs(is);
    }

    private boolean isSingularity(ItemStack is) {
        IMaterials materials = AEApi.instance().definitions().materials();
        return materials.singularity().isSameAs(is);
    }

    /** Current output mode (for GUI display). */
    public CondenserOutput getOutput() {
        return (CondenserOutput) this.cm.getSetting(Settings.CONDENSER_OUTPUT);
    }

    /**
     * Maximum fluid buffer, decided by the installed storage component
     * (mirrors the Matter Condenser: bytes * BYTE_MULTIPLIER).
     */
    public double getStorage() {
        ItemStack is = this.storageSlot.getStackInSlot(0);
        if (!is.isEmpty() && is.getItem() instanceof IStorageComponent) {
            IStorageComponent sc = (IStorageComponent) is.getItem();
            if (sc.isStorageComponent(is)) {
                return sc.getBytes(is) * BYTE_MULTIPLIER;
            }
        }
        return 0;
    }

    public double getStoredFluid() {
        return this.storedFluid;
    }

    public int getMatterBallCount() {
        return this.matterBallCount;
    }

    public int getSingularityCount() {
        return this.singularityCount;
    }

    @Override
    public void updateSetting(IConfigManager manager, Enum settingName, Enum newValue) {
        // no special handling needed; processInput reads the mode every tick
    }

    @Override
    public IConfigManager getConfigManager() {
        return this.cm;
    }

    // --- IFluidHandler (output only) ---

    @Override
    public IFluidTankProperties[] getTankProperties() {
        if (TimeBusFluids.TIME_FLUID == null) {
            return new IFluidTankProperties[0];
        }
        return new IFluidTankProperties[]{
                new FluidTankProperties(new FluidStack(TimeBusFluids.TIME_FLUID, (int) this.storedFluid), (int) getStorage(), false, true)
        };
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        return 0; // output-only
    }

    @Nullable
    @Override
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        if (resource == null || TimeBusFluids.TIME_FLUID == null
                || resource.getFluid() != TimeBusFluids.TIME_FLUID || this.storedFluid <= 0) {
            return null;
        }
        int drain = (int) Math.min(resource.amount, this.storedFluid);
        if (drain <= 0) {
            return null;
        }
        if (doDrain) {
            this.storedFluid -= drain;
            this.markDirty();
        }
        return new FluidStack(TimeBusFluids.TIME_FLUID, drain);
    }

    @Nullable
    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        if (maxDrain <= 0 || TimeBusFluids.TIME_FLUID == null || this.storedFluid <= 0) {
            return null;
        }
        int drain = (int) Math.min(maxDrain, this.storedFluid);
        if (drain <= 0) {
            return null;
        }
        if (doDrain) {
            this.storedFluid -= drain;
            this.markDirty();
        }
        return new FluidStack(TimeBusFluids.TIME_FLUID, drain);
    }

    // --- Inventory plumbing ---

    @Override
    public IItemHandler getInternalInventory() {
        return this.combinedInv;
    }

    @Override
    public void onChangeInventory(IItemHandler inv, int slot, InvOperation mc, ItemStack removed, ItemStack added) {
        // nothing special; processInput runs every tick
    }

    /** Chained inventory exposed to machines: input slot is insert+extract, storage slot is insert+extract. */
    private class CombinedInventory implements IItemHandler {
        @Override
        public int getSlots() {
            return 2;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == SLOT_INPUT ? inputSlot.getStackInSlot(0) : storageSlot.getStackInSlot(0);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot == SLOT_INPUT) {
                // only accept matter balls / singularities
                if (!isMatterBall(stack) && !isSingularity(stack)) {
                    return stack;
                }
                return inputSlot.insertItem(0, stack, simulate);
            }
            if (slot == SLOT_STORAGE) {
                if (stack.isEmpty() || !(stack.getItem() instanceof IStorageComponent)) {
                    return stack;
                }
                return storageSlot.insertItem(0, stack, simulate);
            }
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == SLOT_INPUT) {
                return inputSlot.extractItem(0, amount, simulate);
            }
            if (slot == SLOT_STORAGE) {
                return storageSlot.extractItem(0, amount, simulate);
            }
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    }

    // --- NBT ---

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        this.cm.writeToNBT(data);
        this.inputSlot.writeToNBT(data, "inputSlot");
        this.storageSlot.writeToNBT(data, "storageSlot");
        data.setDouble("storedFluid", this.storedFluid);
        data.setInteger("matterBallCount", this.matterBallCount);
        data.setInteger("singularityCount", this.singularityCount);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.cm.readFromNBT(data);
        this.inputSlot.readFromNBT(data, "inputSlot");
        this.storageSlot.readFromNBT(data, "storageSlot");
        this.storedFluid = data.getDouble("storedFluid");
        this.matterBallCount = data.getInteger("matterBallCount");
        this.singularityCount = data.getInteger("singularityCount");
    }

    // --- Capabilities ---

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return (T) this;
        }
        return super.getCapability(capability, facing);
    }
}
