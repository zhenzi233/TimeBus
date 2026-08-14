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
import com.zhenzi233.timebus.util.AccelerateHelper;
import com.zhenzi233.timebus.util.ModularMachineryAccelerator;
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
        this.getProxy().setIdlePowerUsage(TimeBusConfig.Bus.idlePower);
    }

    private final ItemStackHandler configInventory = new ItemStackHandler(9);
    private boolean lastRedstone = false;
    private double fluidAccumulator = 0.0;
    // 流体耗尽退避：连续提取不足后进入节流，每 FLUID_EMPTY_POLL_INTERVAL tick
    // 才完整探测一次（照抄 TileTimeGenerator.EMPTY_POLL_INTERVAL 模式）；一旦
    // 成功立即恢复每 tick 探测。网络缺流体时总线反正不干活（doWork 提前返回），
    // 没理由每 tick 做两次全网格提取操作。
    private static final int FLUID_EMPTY_POLL_INTERVAL = 10;
    private int fluidEmptyTicks = 0;
    // Cached fluid-mode lookups. A registered fluid never changes at runtime,
    // so the lookup is done once and re-resolved only when the config name
    // changes (checked cheaply every call).
    private String cachedFluidName = null;
    private Fluid cachedFluid = null;
    private IFluidStorageChannel cachedFluidChannel = null;
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
    // Lazy-cached MM modifier source key (host position + part side). The host
    // position is fixed while the part is attached, so the concatenation is
    // done once instead of every work tick.
    private String cachedSourceKey = null;
    private BlockPos cachedSourcePos = null;
    // 升级计数缓存：升级只在 GUI 插拔卡时变化，upgradesChanged() 时失效，
    // 避免每 tick 的耗电/速度/红石查询反复遍历升级物品槽（getInstalledUpgrades）。
    private int cachedCardCount = -1;
    private int cachedCapacityCount = -1;
    private int cachedRedstoneCount = -1;
    private int cachedTotalUpgrades = -1;

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
        if (cachedCardCount < 0) {
            cachedCardCount = this.getInstalledUpgrades(Upgrades.SPEED);
        }
        return cachedCardCount;
    }

    private int getCapacityCount() {
        if (cachedCapacityCount < 0) {
            cachedCapacityCount = this.getInstalledUpgrades(Upgrades.CAPACITY);
        }
        return cachedCapacityCount;
    }

    private int getRedstoneCount() {
        if (cachedRedstoneCount < 0) {
            cachedRedstoneCount = this.getInstalledUpgrades(Upgrades.REDSTONE);
        }
        return cachedRedstoneCount;
    }

    /** 升级插拔后清空升级计数缓存（upgradesChanged 由 AE2 在卡片变化时回调）。 */
    private void invalidateUpgradeCaches() {
        cachedCardCount = -1;
        cachedCapacityCount = -1;
        cachedRedstoneCount = -1;
        cachedTotalUpgrades = -1;
    }

    /**
     * Stable per-part identity used as the MM duration-modifier source key.
     *
     * <p>Must include the part's side: one AE2 cable can hold up to 6 parts on
     * different faces, and without the side two Time Buses on the same cable
     * would share one modifier key - overwriting each other's multiplier and
     * clearing the other bus's modifier when either one is removed.
     */
    private String getSourceKey() {
        if (getHost() == null || getHost().getTile() == null) {
            return "bus:unknown";
        }
        final BlockPos pos = getHost().getTile().getPos();
        if (cachedSourceKey == null || !pos.equals(cachedSourcePos)) {
            cachedSourceKey = ModularMachineryAccelerator.SOURCE_BUS_PREFIX
                    + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":" + getSide().name();
            cachedSourcePos = pos;
        }
        return cachedSourceKey;
    }

    @Override
    public void removeFromWorld() {
        // 拆除时间总线时，恢复本总线注入过的 MM 配方时长 modifier，
        // 否则机器会一直保持加速且 modifier 还会被写进存档（跨存档残留）。
        if (getHost() != null && getHost().getTile() != null) {
            ModularMachineryAccelerator.restoreAllForSource(getHost().getTile().getWorld(), getSourceKey());
        }
        super.removeFromWorld();
    }

    public int getEffectiveSpeed() {
        return TimeBusConfig.valueForCardCount(getCardCount(), TimeBusConfig.Bus.getSpeedMultipliers());
    }

    private int getTotalUpgrades() {
        if (cachedTotalUpgrades < 0) {
            cachedTotalUpgrades = getCardCount()
                 + getCapacityCount()
                 + getRedstoneCount()
                 + this.getInstalledUpgrades(Upgrades.FUZZY);
        }
        return cachedTotalUpgrades;
    }

    public double getPowerDraw() {
        return Math.max(TimeBusConfig.Bus.idlePower, (getEffectiveSpeed() + 2 * getTotalUpgrades()) * TimeBusConfig.Bus.powerPerSpeed);
    }

    // Fluid mode - query methods for GUI
    public String getFluidDisplayName() {
        Fluid f = FluidRegistry.getFluid(TimeBusConfig.Bus.fluidName);
        return f != null ? f.getLocalizedName(new FluidStack(f, 1)) : TimeBusConfig.Bus.fluidName;
    }

    public double getFluidRate() {
        return TimeBusConfig.Bus.fluidPerTick * Math.pow(TimeBusConfig.Bus.fluidConsumeMultiplier, getCardCount());
    }

    public int getCapacityWidth() {
        return TimeBusConfig.valueForCardCount(getCapacityCount(), TimeBusConfig.Bus.getCapacityWidths());
    }

    /** True when the redstone upgrade is installed and set to pulse mode. */
    private boolean isPulseMode() {
        if (getRedstoneCount() <= 0) {
            return false;
        }
        try {
            return getRSMode() == RedstoneMode.SIGNAL_PULSE;
        } catch (Exception e) {
            TimeBus.LOGGER.debug("Time Bus: isPulseMode failed, defaulting to false: {}", e.toString());
            return false;
        }
    }

    /**
     * 总线进入"停止加速"状态（红石睡眠 / 断电 / 失活）时，撤销本总线已注入
     * 到 MM 机器上的配方时长/能耗 modifier。
     *
     * <p>MM 加速是"注入持久 modifier"模式：apply() 写进 RecipeThread 的
     * permanentModifiers 后不会自动消失，若只在总线侧停止 doWork，机器会
     * 继续被加速（红石关闭无效）。restoreAllForSource 按 sourceKey 精确清理，
     * 多总线叠加时只恢复本总线的部分。
     */
    private void restoreMMIfAny() {
        try {
            if (getHost() != null && getHost().getTile() != null) {
                ModularMachineryAccelerator.restoreAllForSource(getHost().getTile().getWorld(), getSourceKey());
            }
        } catch (Exception e) {
            TimeBus.LOGGER.debug("Time Bus: restoreMMIfAny failed: {}", e.toString());
        }
    }

    @Override
    protected boolean isSleeping() {
        if (getRedstoneCount() <= 0) {
            return false;
        }
        RedstoneMode rsMode;
        try {
            rsMode = getRSMode();
        } catch (Exception e) {
            TimeBus.LOGGER.debug("Time Bus: isSleeping failed, defaulting to awake: {}", e.toString());
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
                // Stay awake while a batch is still in progress; sleep once the
                // pulse-triggered work has fully finished (see tickingRequest).
                return !workActive;
            default:
                return false;
        }
    }

    @Override
    public void onNeighborChanged(IBlockAccess world, BlockPos pos, BlockPos neighbor) {
        // Track redstone changes first: in pulse mode a rising edge starts a
        // batch, so the device must already be awake when we sync its sleep
        // state below (otherwise the freshly started batch would stay asleep).
        boolean hasSignal = getHost().hasRedstone(getSide());
        if (hasSignal != this.lastRedstone) {
            this.lastRedstone = hasSignal;
            if (hasSignal && getRSMode() == RedstoneMode.SIGNAL_PULSE) {
                doWork();
            }
        }
        // 红石条件变为"应睡眠"（高电平无信号 / 低电平有信号）时撤销 MM modifier，
        // 否则机器会残留加速。脉冲模式的批次结束由 tickingRequest 兜底。
        if (isSleeping()) {
            restoreMMIfAny();
        }
        // Sync sleep state with tick manager (PartSharedItemBus pattern).
        try {
            if (!isSleeping()) {
                this.getProxy().getTick().wakeDevice(this.getProxy().getNode());
            } else {
                this.getProxy().getTick().sleepDevice(this.getProxy().getNode());
            }
        } catch (appeng.me.GridAccessException ignored) {
        }
    }

    @Override
    public void upgradesChanged() {
        invalidateUpgradeCaches();
        // Track redstone for pulse mode when upgrades change
        boolean hasSignal = getHost().hasRedstone(getSide());
        if (hasSignal != this.lastRedstone) {
            this.lastRedstone = hasSignal;
            if (hasSignal && getRSMode() == RedstoneMode.SIGNAL_PULSE) {
                doWork();
            }
        }
        if (isSleeping()) {
            restoreMMIfAny();
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
            // 断电/失活时总线无法推进任何加速，撤销已注入的 MM modifier，
            // 避免机器在总线未工作时仍被残留 modifier 加速。
            restoreMMIfAny();
            return TickRateModulation.SLOWER;
        }
        // 红石条件不满足（高电平无信号 / 低电平有信号 / 脉冲批次已结束）时
        // 停止工作并撤销 MM modifier。此分支是 onNeighborChanged 之外的兜底
        // （如直接在 GUI 切换红石模式）。
        if (isSleeping()) {
            restoreMMIfAny();
            return TickRateModulation.SLEEP;
        }
        extractAEPower(ticksSinceLastCall);
        doWork();
        // Pulse mode: the device is only kept awake while a batch is still in
        // progress. As soon as it finishes, go back to sleep so the grid does
        // not keep polling an idle bus; the next redstone edge wakes it again.
        if (isPulseMode() && !workActive) {
            // 本 tick 批次刚完成：立即恢复 MM 机器原速再入睡。
            restoreMMIfAny();
            return TickRateModulation.SLEEP;
        }
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
        if (TimeBusConfig.Bus.fluidMode && !consumeFluid()) {
            return; // Not enough fluid to operate
        }
        int speed = getEffectiveSpeed();
        int width = getCapacityWidth();
        int budget = Math.max(1, TimeBusConfig.Bus.maxCallsPerTick);
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
        final String sourceKey = getSourceKey();
        // 同一 doWork 内 PHASE_SCHEDULE 取到的 tile/分类结果传给 PHASE_TILE，
        // 避免同一 tick 对同一方块二次 getTileEntity + 分类链。跨 tick 续跑时
        // 为 null，runTileUpdates 回退自分类（见其重载的 null 约定）。
        TileEntity scheduleTE = null;
        AccelerateHelper.TileKind scheduleKind = null;

        while (used < budget && workActive) {
            if (workBlockIndex >= width) {
                workActive = false;
                break;
            }
            BlockPos target = start.offset(facing.getFacing(), workBlockIndex);
            // Cheap per-block loaded check: getBlockState on an unloaded chunk
            // forces a synchronous disk load (tick hitch on chunk borders).
            // Unloaded targets are skipped for this batch; the next batch
            // re-checks them once their chunk has loaded.
            if (!world.isBlockLoaded(target)) {
                workBlockIndex++;
                workPhase = PHASE_SCHEDULE;
                workPhaseRemaining = 0;
                continue;
            }
            IBlockState targetState = world.getBlockState(target);
            Block targetBlock = targetState.getBlock();

            switch (workPhase) {
                case PHASE_SCHEDULE: {
                    // 黑白名单：名单外的方块整块跳过（不调度、不加速、不随机刻），
                    // 不算工作预算。只按方块注册名匹配、无视 tile NBT。
                    // 注意不能走 advancePhase：它会因 getTickRandomly 进入 PHASE_RANDOM
                    // 随机刻加速，名单外方块必须整块跳过。
                    final net.minecraft.util.ResourceLocation blockRl = targetBlock.getRegistryName();
                    if (blockRl == null || !TimeBusConfig.Bus.getBusFilter().allows(blockRl.toString())) {
                        if (blockRl != null) {
                            TimeBus.LOGGER.info("Time Bus: block list blocked {} at {}", blockRl, target);
                        }
                        workBlockIndex++;
                        workPhase = PHASE_SCHEDULE;
                        workPhaseRemaining = 0;
                        break;
                    }
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
                    scheduleTE = world.getTileEntity(target);
                    scheduleKind = AccelerateHelper.getTileKind(scheduleTE);
                    if (scheduleKind != AccelerateHelper.TileKind.NONE) {
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
                    // 跨 tick 续跑时名单可能已变更：复查一次（同 PHASE_SCHEDULE 语义），
                    // 否则名单开启瞬间 PHASE_TILE 残留会继续连拍当前方块。
                    final net.minecraft.util.ResourceLocation tileRl = targetBlock.getRegistryName();
                    if (tileRl == null || !TimeBusConfig.Bus.getBusFilter().allows(tileRl.toString())) {
                        workBlockIndex++;
                        workPhase = PHASE_SCHEDULE;
                        workPhaseRemaining = 0;
                        break;
                    }
                    int n = Math.min(workPhaseRemaining, budget - used);
                    n = AccelerateHelper.runTileUpdates(world, target, n, speed, sourceKey, scheduleTE, scheduleKind);
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
                    // 跨 tick 续跑时名单可能已变更：复查一次（名单外方块不做随机刻加速）。
                    final net.minecraft.util.ResourceLocation randomRl = targetBlock.getRegistryName();
                    if (randomRl == null || !TimeBusConfig.Bus.getBusFilter().allows(randomRl.toString())) {
                        workBlockIndex++;
                        workPhase = PHASE_SCHEDULE;
                        workPhaseRemaining = 0;
                        break;
                    }
                    int n = Math.min(workPhaseRemaining, budget - used);
                    n = AccelerateHelper.runRandomTicks(world, target, targetState, targetBlock, n);
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
            workPhaseRemaining = Math.max(1, speed * TimeBusConfig.Bus.randomTickCallsPerSpeed);
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
            // 节流检查：上次提取不足后先数 tick，未到间隔直接返回（不探测）。
            // 到间隔时清零并走完整检查；成功路径同样清零恢复每 tick 探测。
            if (fluidEmptyTicks > 0) {
                if (++fluidEmptyTicks <= FLUID_EMPTY_POLL_INTERVAL) {
                    return false;
                }
                fluidEmptyTicks = 0;
            }
            // Accumulate fractional mB, extract only when >= 1 mB
            fluidAccumulator += TimeBusConfig.Bus.fluidPerTick * Math.pow(TimeBusConfig.Bus.fluidConsumeMultiplier, getCardCount());
            if (fluidAccumulator < 1.0) return true;
            int toDrain = (int) fluidAccumulator;
            fluidAccumulator -= toDrain;

            IStorageGrid storage = this.getProxy().getStorage();
            if (cachedFluid == null || !TimeBusConfig.Bus.fluidName.equals(cachedFluidName)) {
                cachedFluidName = TimeBusConfig.Bus.fluidName;
                cachedFluid = FluidRegistry.getFluid(cachedFluidName);
                cachedFluidChannel = AEApi.instance().storage()
                        .getStorageChannel(IFluidStorageChannel.class);
            }
            if (cachedFluid == null) {
                fluidEmptyTicks = 1;
                return false;
            }
            IMEMonitor<IAEFluidStack> fluidInv = storage.getInventory(cachedFluidChannel);

            Fluid fluid = cachedFluid;

            // Check minimum threshold
            IAEFluidStack minCheck = AEFluidStack.fromFluidStack(
                    new FluidStack(fluid, TimeBusConfig.Bus.minFluid));
            IAEFluidStack simulated = fluidInv.extractItems(minCheck, Actionable.SIMULATE, machineSource);
            if (simulated == null || simulated.getStackSize() < TimeBusConfig.Bus.minFluid) {
                fluidEmptyTicks = 1; // 网络缺流体:进入节流
                return false;
            }

            // Consume accumulated amount
            IAEFluidStack toConsume = AEFluidStack.fromFluidStack(
                    new FluidStack(fluid, toDrain));
            IAEFluidStack extracted = fluidInv.extractItems(toConsume, Actionable.MODULATE, machineSource);
            if (extracted == null) {
                // Nothing was drained (simulate passed but modulate lost the
                // race): put the amount back instead of losing it.
                fluidAccumulator += toDrain;
                fluidEmptyTicks = 1;
                return false;
            }
            long actualDrained = extracted.getStackSize();
            // Keep the books aligned with reality: if the network only had
            // part of the requested amount, carry the difference to the next
            // tick instead of silently billing more than was drained.
            fluidAccumulator += toDrain - actualDrained;
            fluidEmptyTicks = 0; // 提取成功:恢复每 tick 探测
            return actualDrained > 0;
        } catch (GridAccessException e) {
            fluidEmptyTicks = 1;
            return false;
        }
    }


    private void extractAEPower(int ticksSinceLastCall) {
        if (TimeBusConfig.Bus.fluidMode) return;
        try {
            IEnergyGrid grid = this.getProxy().getEnergy();
            double total = getPowerDraw();
            double active = total - TimeBusConfig.Bus.idlePower;
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
        if (TimeBusConfig.Bus.fluidMode && rand.nextInt(2) == 0) {
            boolean isTimeFluid = "time_fluid".equals(TimeBusConfig.Bus.fluidName);
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
