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
 * fed in, then Time Fluid is produced in one batch (by default 64000 Matter
 * Balls or 64 Singularities = 1000 mB). The storage component slot decides
 * the maximum fluid buffer.
 */
public class TileTimeGenerator extends AEBaseInvTile implements ITickable, appeng.fluids.util.IAEFluidTank, IConfigManagerHost, IConfigurableObject {

    public static final int BYTE_MULTIPLIER = 8;

    // Slot indices
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_STORAGE = 1;

    private final ConfigManager cm = new ConfigManager(this);
    private final AppEngInternalInventory inputSlot = new AppEngInternalInventory(this, 1);
    private final AppEngInternalInventory storageSlot = new AppEngInternalInventory(this, 1);
    private final IItemHandler combinedInv = new CombinedInventory();

    private double storedFluid = 0;

    // Empty-input polling throttle: when the input slot is empty, re-check it
    // every EMPTY_POLL_INTERVAL ticks instead of every tick.
    private static final int EMPTY_POLL_INTERVAL = 10;
    private int emptyInputTicks = 0;

    // Condenser-style progress in unified "units": 1 Matter Ball = 1 unit,
    // 1 Singularity = singularityUnit (1000) units. Progress is preserved
    // across mode switches because both inputs accumulate into this counter.
    private double progressUnits = 0;
    // Counters kept for GUI display of how many of each item were fed.
    private long matterBallCount = 0;
    private long singularityCount = 0;

    public TileTimeGenerator() {
        // Default mode: Matter Balls. GUI button cycles MATTER_BALLS -> SINGULARITY -> TRASH.
        this.cm.registerSetting(Settings.CONDENSER_OUTPUT, CondenserOutput.MATTER_BALLS);
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote || this.isInvalid()) {
            return;
        }
        // Throttle empty-input polling: nothing to do when the slot is empty,
        // so only re-check it every EMPTY_POLL_INTERVAL ticks. Any real input
        // resets the counter and processing resumes every tick.
        if (this.inputSlot.getStackInSlot(0).isEmpty()) {
            if (++this.emptyInputTicks < EMPTY_POLL_INTERVAL) {
                return;
            }
            this.emptyInputTicks = 0;
        } else {
            this.emptyInputTicks = 0;
        }
        processInput();
    }

    /**
     * Consume up to {@link TimeBusConfig#generatorConsumePerTick} input items per update (normally one
     * update per tick, or several updates per tick while a Time Bus is accelerating
     * the machine). Every accepted item adds its unit value to a single progress
     * counter (1 Matter Ball = 1 unit, 1 Singularity = singularityUnit
     * units), so progress survives switching the input mode. When the counter reaches
     * unitsPerBatch and there is room, Time Fluid is produced in a full batch.
     * Only the input matching the current output mode is accepted.
     */
    private void processInput() {
        ItemStack input = this.inputSlot.getStackInSlot(0);
        if (input.isEmpty()) {
            return;
        }
        CondenserOutput mode = (CondenserOutput) this.cm.getSetting(Settings.CONDENSER_OUTPUT);

        // TRASH (destroy) is never selectable from our GUI, but if the mode is
        // forced externally (NBT / another mod), consume the input instead of
        // letting it sit in the slot forever.
        if (mode == CondenserOutput.TRASH) {
            this.inputSlot.extractItem(0, TimeBusConfig.TimeGenerator.generatorConsumePerTick, false);
            return;
        }

        double space = getStorage() - this.storedFluid;

        double unitValue;
        if (mode == CondenserOutput.MATTER_BALLS && isMatterBall(input)) {
            unitValue = TimeBusConfig.TimeGenerator.matterBallUnit;
        } else if (mode == CondenserOutput.SINGULARITY && isSingularity(input)) {
            unitValue = TimeBusConfig.TimeGenerator.singularityUnit;
        } else {
            // Mismatched input: keep it in the slot so the player can pull it out.
            return;
        }

        // No room for at least one full batch: keep the input in the slot.
        if (space < TimeBusConfig.TimeGenerator.timeFluidPerBatch) {
            return;
        }

        ItemStack consumed = this.inputSlot.extractItem(0, TimeBusConfig.TimeGenerator.generatorConsumePerTick, false);
        if (consumed.isEmpty()) {
            return;
        }

        final int consumedCount = consumed.getCount();
        if (mode == CondenserOutput.MATTER_BALLS) {
            this.matterBallCount += consumedCount;
        } else {
            this.singularityCount += consumedCount;
        }
        this.progressUnits += unitValue * consumedCount;

        // Produce as many full batches as the accumulated units allow.
        while (this.progressUnits >= TimeBusConfig.TimeGenerator.unitsPerBatch && space >= TimeBusConfig.TimeGenerator.timeFluidPerBatch) {
            this.progressUnits -= TimeBusConfig.TimeGenerator.unitsPerBatch;
            this.storedFluid += TimeBusConfig.TimeGenerator.timeFluidPerBatch;
            space -= TimeBusConfig.TimeGenerator.timeFluidPerBatch;
            this.markDirty();
        }
    }

    /**
     * AEApi materials 引用缓存：AE2 definitions 在运行时不变，初始化一次即可，
     * 避免每次 update 都做 API 单例查询（懒加载，调用发生在 update 主线程内，
     * 无并发问题；AE2 未就绪前也不会走到这里）。
     */
    private static IMaterials cachedMaterials;

    private static IMaterials materials() {
        if (cachedMaterials == null) {
            cachedMaterials = AEApi.instance().definitions().materials();
        }
        return cachedMaterials;
    }

    private boolean isMatterBall(ItemStack is) {
        return materials().matterBall().isSameAs(is);
    }

    private boolean isSingularity(ItemStack is) {
        return materials().singularity().isSameAs(is);
    }

    /** Current output mode (for GUI display). */
    public CondenserOutput getOutput() {
        return (CondenserOutput) this.cm.getSetting(Settings.CONDENSER_OUTPUT);
    }

    /**
     * Toggle between Matter Balls and Singularity input mode.
     * Deliberately never enters TRASH (destroy) mode.
     */
    public void cycleOutput() {
        CondenserOutput current = getOutput();
        CondenserOutput next = current == CondenserOutput.MATTER_BALLS
                ? CondenserOutput.SINGULARITY : CondenserOutput.MATTER_BALLS;
        this.cm.putSetting(Settings.CONDENSER_OUTPUT, next);
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

    /** Progress in unified units (1 Matter Ball = 1, 1 Singularity = singularityUnit). */
    public double getProgressUnits() {
        return this.progressUnits;
    }

    /** Total units required for one batch. */
    public double getUnitsPerBatch() {
        return TimeBusConfig.TimeGenerator.unitsPerBatch;
    }

    public long getMatterBallCount() {
        return this.matterBallCount;
    }

    public long getSingularityCount() {
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

    // --- IAEFluidTank (GUI tank display) ---

    @Override
    public void setFluidInSlot(int slot, appeng.api.storage.data.IAEFluidStack fluid) {
        // Output-only tank: no external writes.
    }

    @Override
    public appeng.api.storage.data.IAEFluidStack getFluidInSlot(int slot) {
        if (slot != 0 || TimeBusFluids.TIME_FLUID == null || this.storedFluid <= 0) {
            return null;
        }
        return appeng.fluids.util.AEFluidStack.fromFluidStack(
                new FluidStack(TimeBusFluids.TIME_FLUID, (int) this.storedFluid));
    }

    @Override
    public int getSlots() {
        return 1;
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
            // 输入槽堆叠上限固定为物品最大堆叠（64），与每次消费量独立；
            // 玩家可在配置中调整每次 update 的消费速度。
            return 64;
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
        data.setDouble("progressUnits", this.progressUnits);
        data.setLong("matterBallCount", this.matterBallCount);
        data.setLong("singularityCount", this.singularityCount);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.cm.readFromNBT(data);
        this.inputSlot.readFromNBT(data, "inputSlot");
        this.storageSlot.readFromNBT(data, "storageSlot");
        this.storedFluid = data.getDouble("storedFluid");
        this.progressUnits = data.getDouble("progressUnits");
        this.matterBallCount = data.getLong("matterBallCount");
        this.singularityCount = data.getLong("singularityCount");
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
