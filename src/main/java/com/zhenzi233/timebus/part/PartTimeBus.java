package com.zhenzi233.timebus.part;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.config.Upgrades;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartModel;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.core.sync.GuiWrapper;
import appeng.fluids.util.AEFluidStack;
import appeng.items.parts.PartModels;
import appeng.me.GridAccessException;
import appeng.parts.PartModel;
import appeng.parts.automation.PartUpgradeable;
import appeng.util.Platform;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.client.gui.TimeBusGui;
import com.zhenzi233.timebus.config.TimeBusConfig;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import java.util.Optional;
import java.util.Random;

public class PartTimeBus extends PartUpgradeable implements IGridTickable {

    public static final ResourceLocation MODEL_BASE = new ResourceLocation(TimeBus.MOD_ID, "part/time_bus_base");

    @PartModels
    public static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, new ResourceLocation(TimeBus.MOD_ID, "part/time_bus_off"));
    @PartModels
    public static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, new ResourceLocation(TimeBus.MOD_ID, "part/time_bus_on"));
    @PartModels
    public static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, new ResourceLocation(TimeBus.MOD_ID, "part/time_bus_has_channel"));

    public PartTimeBus(final ItemStack is) {
        super(is);
        this.getConfigManager().registerSetting(Settings.REDSTONE_CONTROLLED, RedstoneMode.IGNORE);
        this.getConfigManager().registerSetting(Settings.FUZZY_MODE, FuzzyMode.IGNORE_ALL);
        this.getProxy().setIdlePowerUsage(TimeBusConfig.idlePower);
    }

    private final ItemStackHandler configInventory = new ItemStackHandler(9);
    private boolean lastRedstone = false;
    private double fluidAccumulator = 0.0;
    private final IActionSource machineSource = new IActionSource() {
        @Override public Optional<EntityPlayer> player() { return Optional.empty(); }
        @Override public Optional<IActionHost> machine() { return Optional.of(PartTimeBus.this); }
        @Override public <T> Optional<T> context(Class<T> key) { return Optional.empty(); }
    };

    // --- Work budget state ---
    // Acceleration work is measured in "calls" (scheduleBlockUpdate / ITickable.update / Block.updateTick).
    // Each server tick the Time Bus executes at most maxCallsPerTick calls and carries the remainder
    // to the next tick, so a fully-upgraded bus never spikes the server tick.
    private static final int PHASE_SCHEDULE = 0;
    private static final int PHASE_TILE = 1;
    private static final int PHASE_RANDOM = 2;

    private int workBlockIndex = 0;      // which block (0..capacityWidth-1) is currently being processed
    private int workPhase = PHASE_SCHEDULE; // phase of the current block
    private int workPhaseRemaining = 0;  // calls left in the current phase
    private boolean workActive = false;  // true while a work batch is in progress
    private int budgetUsedLastTick = 0;  // calls actually spent last tick (for GUI)
    private int budgetTotalLastTick = 0; // budget available last tick (for GUI)
    private boolean workDidSomething = false; // true if the last completed batch did real work
    private int idleTicks = 0;               // consecutive ticks with nothing to accelerate

    @Override
    public IItemHandler getInventoryByName(String name) {
        if (name.equals("config")) {
            return configInventory;
        }
        return super.getInventoryByName(name);
    }

    @Override
    public RedstoneMode getRSMode() {
        return (RedstoneMode) this.getConfigManager().getSetting(Settings.REDSTONE_CONTROLLED);
    }

    @Override
    protected int getUpgradeSlots() {
        return 4;
    }

    private int getCardCount() {
        return this.getInstalledUpgrades(Upgrades.SPEED);
    }

    public int getEffectiveSpeed() {
        try {
            String[] parts = TimeBusConfig.speedMultipliers.split(",");
            if (parts.length == 0) return 1;
            int idx = Math.min(getCardCount(), parts.length - 1);
            return Math.max(1, Integer.parseInt(parts[idx].trim()));
        } catch (NumberFormatException | NullPointerException e) {
            TimeBus.LOGGER.warn("Invalid speedMultipliers config: '{}'", TimeBusConfig.speedMultipliers);
            return 1;
        }
    }

    private int getTotalUpgrades() {
        return this.getInstalledUpgrades(Upgrades.SPEED)
             + this.getInstalledUpgrades(Upgrades.CAPACITY)
             + this.getInstalledUpgrades(Upgrades.REDSTONE)
             + this.getInstalledUpgrades(Upgrades.FUZZY);
    }

    public double getPowerDraw() {
        return Math.max(TimeBusConfig.idlePower, (getEffectiveSpeed() + 2 * getTotalUpgrades()) * TimeBusConfig.powerPerSpeed);
    }

    // Fluid mode - query methods for GUI
    public String getFluidDisplayName() {
        Fluid f = FluidRegistry.getFluid(TimeBusConfig.fluidName);
        return f != null ? f.getLocalizedName(new FluidStack(f, 1)) : TimeBusConfig.fluidName;
    }

    public double getFluidRate() {
        return TimeBusConfig.fluidPerTick * Math.pow(TimeBusConfig.fluidConsumeMultiplier, getCardCount());
    }

    public int getCapacityWidth() {
        try {
            String[] parts = TimeBusConfig.capacityWidths.split(",");
            if (parts.length == 0) return 1;
            int idx = Math.min(this.getInstalledUpgrades(Upgrades.CAPACITY), parts.length - 1);
            return Math.max(1, Integer.parseInt(parts[idx].trim()));
        } catch (NumberFormatException | NullPointerException e) {
            TimeBus.LOGGER.warn("Invalid capacityWidths config: '{}'", TimeBusConfig.capacityWidths);
            return 1;
        }
    }

    @Override
    protected boolean isSleeping() {
        if (this.getInstalledUpgrades(Upgrades.REDSTONE) <= 0) {
            return false;
        }
        RedstoneMode rsMode;
        try {
            rsMode = getRSMode();
        } catch (Exception e) {
            return false;
        }
        if (rsMode == null) {
            return false;
        }
        boolean hasSignal = getHost().hasRedstone(getSide());
        switch (rsMode) {
            case IGNORE:
                return false;
            case HIGH_SIGNAL:
                return !hasSignal;
            case LOW_SIGNAL:
                return hasSignal;
            case SIGNAL_PULSE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onNeighborChanged(IBlockAccess world, BlockPos pos, BlockPos neighbor) {
        // Sync sleep state with tick manager (PartSharedItemBus pattern)
        try {
            if (!isSleeping()) {
                this.getProxy().getTick().wakeDevice(this.getProxy().getNode());
            } else {
                this.getProxy().getTick().sleepDevice(this.getProxy().getNode());
            }
        } catch (appeng.me.GridAccessException ignored) {
        }
        // Track redstone changes
        boolean hasSignal = getHost().hasRedstone(getSide());
        if (hasSignal != this.lastRedstone) {
            this.lastRedstone = hasSignal;
            if (hasSignal && getRSMode() == RedstoneMode.SIGNAL_PULSE) {
                doWork();
            }
        }
    }

    @Override
    public void upgradesChanged() {
        // Track redstone for pulse mode when upgrades change
        boolean hasSignal = getHost().hasRedstone(getSide());
        if (hasSignal != this.lastRedstone) {
            this.lastRedstone = hasSignal;
            if (hasSignal && getRSMode() == RedstoneMode.SIGNAL_PULSE) {
                doWork();
            }
        }
        super.upgradesChanged();
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 20, isSleeping(), false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        if (!getHost().getTile().getWorld().isBlockLoaded(getHost().getTile().getPos())) {
            return TickRateModulation.SLEEP;
        }
        if (!this.getProxy().isPowered() || !this.getProxy().isActive()) {
            return TickRateModulation.SLOWER;
        }
        extractAEPower(ticksSinceLastCall);
        doWork();
        // Back off when nothing real happened for a while (no blocks in range,
        // no fluid, ...) so an idle bus stops scanning every tick. Any actual
        // work or an unfinished batch resets the counter.
        if (!workActive && !workDidSomething) {
            idleTicks++;
        } else {
            idleTicks = 0;
        }
        return idleTicks >= 40 ? TickRateModulation.SLOWER : TickRateModulation.URGENT;
    }

    private void doWork() {
        if (TimeBusConfig.fluidMode && !consumeFluid()) {
            return; // Not enough fluid to operate
        }
        int speed = getEffectiveSpeed();
        int width = getCapacityWidth();
        int budget = Math.max(1, TimeBusConfig.maxCallsPerTick);
        int used = 0;

        if (!workActive) {
            // Start a fresh batch over the whole width.
            workBlockIndex = 0;
            workPhase = PHASE_SCHEDULE;
            workPhaseRemaining = 0;
            workActive = true;
            workDidSomething = false;
        }

        AEPartLocation facing = this.getSide();
        net.minecraft.world.World world = getHost().getTile().getWorld();
        BlockPos start = getHost().getTile().getPos().offset(facing.getFacing());

        while (used < budget && workActive) {
            if (workBlockIndex >= width) {
                workActive = false;
                break;
            }
            BlockPos target = start.offset(facing.getFacing(), workBlockIndex);
            IBlockState targetState = world.getBlockState(target);
            Block targetBlock = targetState.getBlock();

            switch (workPhase) {
                case PHASE_SCHEDULE: {
                    if (!targetBlock.isAir(targetState, world, target)) {
                        workDidSomething = true;
                        try {
                            // MC dedupes scheduled updates by (pos, block), so one call is enough.
                            world.scheduleBlockUpdate(target, targetBlock, 1, 0);
                        } catch (Exception e) {
                            TimeBus.LOGGER.warn("Time Bus: scheduleBlockUpdate failed at {}: {}", target, e.toString());
                        }
                    }
                    used++;
                    TileEntity targetTE = world.getTileEntity(target);
                    if (targetTE instanceof ITickable || targetTE instanceof appeng.tile.misc.TileCharger || targetTE instanceof appeng.tile.misc.TileInscriber || targetTE instanceof appeng.tile.crafting.TileMolecularAssembler || targetTE instanceof appeng.tile.misc.TileVibrationChamber || targetTE instanceof appeng.tile.storage.TileIOPort) {
                        workPhase = PHASE_TILE;
                        workPhaseRemaining = Math.max(0, speed - 1);
                        if (workPhaseRemaining == 0) {
                            advancePhase(targetBlock, target, world, speed);
                        }
                    } else {
                        advancePhase(targetBlock, target, world, speed);
                    }
                    break;
                }
                case PHASE_TILE: {
                    int n = Math.min(workPhaseRemaining, budget - used);
                    n = com.zhenzi233.timebus.util.AccelerateHelper.runTileUpdates(world, target, n, speed);
                    used += n;
                    if (n > 0) {
                        workDidSomething = true;
                    }
                    workPhaseRemaining -= n;
                    if (workPhaseRemaining <= 0) {
                        advancePhase(targetBlock, target, world, speed);
                    } else if (n <= 0) {
                        // No progress possible (TE removed / not ITickable): force advance.
                        workPhaseRemaining = 0;
                        advancePhase(targetBlock, target, world, speed);
                    }
                    break;
                }
                case PHASE_RANDOM: {
                    int n = Math.min(workPhaseRemaining, budget - used);
                    n = com.zhenzi233.timebus.util.AccelerateHelper.runRandomTicks(world, target, targetState, targetBlock, n);
                    used += n;
                    if (n > 0) {
                        workDidSomething = true;
                    }
                    workPhaseRemaining -= n;
                    if (workPhaseRemaining <= 0) {
                        workBlockIndex++;
                        workPhase = PHASE_SCHEDULE;
                        workPhaseRemaining = 0;
                    } else if (n <= 0) {
                        // No progress possible (every call failed): force advance.
                        workPhaseRemaining = 0;
                        workBlockIndex++;
                        workPhase = PHASE_SCHEDULE;
                    }
                    break;
                }
                default:
                    workActive = false;
            }
        }

        budgetUsedLastTick = used;
        budgetTotalLastTick = budget;
    }

    /** Advance to the next phase of the current block (or the next block). */
    private void advancePhase(Block targetBlock, BlockPos target, net.minecraft.world.World world, int speed) {
        // Move on to random ticks if this block uses them and we have not done so yet.
        if (workPhase != PHASE_RANDOM && targetBlock.getTickRandomly()) {
            workPhase = PHASE_RANDOM;
            workPhaseRemaining = Math.max(1, speed * 20);
            return;
        }
        workBlockIndex++;
        workPhase = PHASE_SCHEDULE;
        workPhaseRemaining = 0;
    }

    // Budget usage info for the GUI.
    public int getBudgetUsedLastTick() {
        return budgetUsedLastTick;
    }

    public int getBudgetTotalLastTick() {
        return budgetTotalLastTick;
    }

    private boolean consumeFluid() {
        try {
            // Accumulate fractional mB, extract only when >= 1 mB
            fluidAccumulator += TimeBusConfig.fluidPerTick * Math.pow(TimeBusConfig.fluidConsumeMultiplier, getCardCount());
            if (fluidAccumulator < 1.0) return true;
            int toDrain = (int) fluidAccumulator;
            fluidAccumulator -= toDrain;

            IStorageGrid storage = this.getProxy().getStorage();
            IFluidStorageChannel fluidChannel = AEApi.instance().storage()
                    .getStorageChannel(IFluidStorageChannel.class);
            IMEMonitor<IAEFluidStack> fluidInv = storage.getInventory(fluidChannel);

            Fluid fluid = FluidRegistry.getFluid(TimeBusConfig.fluidName);
            if (fluid == null) return false;

            // Check minimum threshold
            IAEFluidStack minCheck = AEFluidStack.fromFluidStack(
                    new FluidStack(fluid, TimeBusConfig.minFluid));
            IAEFluidStack simulated = fluidInv.extractItems(minCheck, Actionable.SIMULATE, machineSource);
            if (simulated == null || simulated.getStackSize() < TimeBusConfig.minFluid) return false;

            // Consume accumulated amount
            IAEFluidStack toConsume = AEFluidStack.fromFluidStack(
                    new FluidStack(fluid, toDrain));
            IAEFluidStack extracted = fluidInv.extractItems(toConsume, Actionable.MODULATE, machineSource);
            return extracted != null && extracted.getStackSize() > 0;
        } catch (GridAccessException e) {
            return false;
        }
    }


    private void extractAEPower(int ticksSinceLastCall) {
        if (TimeBusConfig.fluidMode) return;
        try {
            IEnergyGrid grid = this.getProxy().getEnergy();
            double total = getPowerDraw();
            double active = total - TimeBusConfig.idlePower;
            if (active > 0) {
                grid.extractAEPower(active * ticksSinceLastCall, Actionable.MODULATE, PowerMultiplier.CONFIG);
            }
        } catch (GridAccessException e) {}
    }

    @Override
    public boolean onPartActivate(EntityPlayer player, EnumHand hand, Vec3d pos) {
        if (!player.getEntityWorld().isRemote) {
            Platform.openGUI(player, getHost().getTile(), this.getSide(),
                    GuiWrapper.INSTANCE.wrap(TimeBusGui.INSTANCE));
        }
        return true;
    }

    @Override
    public void randomDisplayTick(net.minecraft.world.World world, BlockPos pos, Random rand) {
        if (!this.isActive() || !this.isPowered()) return;
        if (isSleeping()) return;

        AEPartLocation facing = this.getSide();
        net.minecraft.util.EnumFacing dir = facing.getFacing();
        double dx = dir.getXOffset() * 0.55;
        double dz = dir.getZOffset() * 0.55;

        int w = getCapacityWidth();
        BlockPos start = pos.offset(dir);
        for (int i = 0; i < w; i++) {
            if (rand.nextInt(3) != 0) continue;
            BlockPos target = start.offset(dir, i);
            if (world.isAirBlock(target)) continue;
            world.spawnParticle(EnumParticleTypes.END_ROD,
                target.getX() + 0.5 + dx,
                target.getY() + rand.nextDouble() * 0.8 + 0.1,
                target.getZ() + 0.5 + dz,
                dir.getXOffset() * 0.03, 0, dir.getZOffset() * 0.03);
        }
        if (TimeBusConfig.fluidMode && rand.nextInt(2) == 0) {
            boolean isTimeFluid = "time_fluid".equals(TimeBusConfig.fluidName);
            if (isTimeFluid) {
                world.spawnParticle(EnumParticleTypes.PORTAL,
                    start.getX() + 0.5 + dx,
                    start.getY() + 0.5,
                    start.getZ() + 0.5 + dz,
                    (rand.nextDouble() - 0.5) * 0.2, 0.1, (rand.nextDouble() - 0.5) * 0.2);
            } else {
                world.spawnParticle(EnumParticleTypes.DRIP_WATER,
                    start.getX() + 0.5 + dx,
                    start.getY() + 0.5,
                    start.getZ() + 0.5 + dz, 0, 0, 0);
            }
        }
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(4, 4, 12, 12, 12, 14);
        bch.addBox(5, 5, 14, 11, 11, 15);
        bch.addBox(6, 6, 15, 10, 10, 16);
        bch.addBox(6, 6, 11, 10, 10, 12);
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 5.0f;
    }

    @Override
    public IPartModel getStaticModels() {
        if (this.isActive() && this.isPowered()) return MODELS_HAS_CHANNEL;
        else if (this.isPowered()) return MODELS_ON;
        return MODELS_OFF;
    }


}
