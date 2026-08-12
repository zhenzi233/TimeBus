package com.zhenzi233.timebus.part;

import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.config.Upgrades;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartModel;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.core.sync.GuiWrapper;
import appeng.items.parts.PartModels;
import appeng.me.GridAccessException;
import appeng.parts.PartModel;
import appeng.parts.automation.PartUpgradeable;
import appeng.util.Platform;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.client.gui.TimeSlowBusGui;
import com.zhenzi233.timebus.config.TimeBusConfig;
import com.zhenzi233.timebus.util.TileSlowdownTable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.util.Random;

/**
 * 时间减速总线：让正面一排 ITickable 方块"变慢"——每 N tick 才执行一次
 * update（N = 速度卡档位 2,4,8,16,32），燃料/进度/冷却同步降频。
 *
 * <p>机制：{@link World#updateEntities} 的 ITickable.update 调用点由
 * {@code MixinWorldTileUpdate} 拦截，本 part 每 tick 对正面范围内每个方块调用
 * {@link TileSlowdownTable#register} 登记档位；移走/断电/红石睡眠后停止登记，
 * 记录超过新鲜窗口自动失效，方块恢复原速（纯运行时，不写存档）。
 *
 * <p>与加速互斥：目标方块在减速表中时，{@code AccelerateHelper.runTileUpdates}
 * 返回 0（加速让路，见该方法的减速检查）。AE2 grid-tick 机器不走 World 循环，
 * 不在减速覆盖范围。
 */
public class PartTimeSlowBus extends PartUpgradeable implements IGridTickable {

    public static final ResourceLocation MODEL_BASE = new ResourceLocation(TimeBus.MOD_ID, "part/time_slow_bus_base");

    @PartModels
    public static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, new ResourceLocation(TimeBus.MOD_ID, "part/time_slow_bus_off"));
    @PartModels
    public static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, new ResourceLocation(TimeBus.MOD_ID, "part/time_slow_bus_on"));
    @PartModels
    public static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, new ResourceLocation(TimeBus.MOD_ID, "part/time_slow_bus_has_channel"));

    private final ItemStackHandler configInventory = new ItemStackHandler(9);
    private boolean lastRedstone = false;
    // 升级计数缓存：升级只在 GUI 插拔卡时变化，upgradesChanged() 时失效。
    private int cachedCardCount = -1;
    private int cachedCapacityCount = -1;
    private int cachedRedstoneCount = -1;
    private int cachedTotalUpgrades = -1;

    public PartTimeSlowBus(final ItemStack is) {
        super(is);
        this.getConfigManager().registerSetting(Settings.REDSTONE_CONTROLLED, RedstoneMode.IGNORE);
        // ContainerUpgradeable.loadSettingsFromHost 会读取这两个设置,缺一即抛
        // IllegalStateException(与 PartTimeBus 一致,必须都注册)。
        this.getConfigManager().registerSetting(Settings.FUZZY_MODE, appeng.api.config.FuzzyMode.IGNORE_ALL);
        this.getProxy().setIdlePowerUsage(TimeBusConfig.SlowBus.idlePower);
    }

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

    /** 升级插拔后清空升级计数缓存。 */
    private void invalidateUpgradeCaches() {
        cachedCardCount = -1;
        cachedCapacityCount = -1;
        cachedRedstoneCount = -1;
        cachedTotalUpgrades = -1;
    }

    /** 减速档位：每 {@code getEffectiveSlowdown()} tick 才执行一次 update。 */
    public int getEffectiveSlowdown() {
        return TimeBusConfig.valueForCardCount(getCardCount(), TimeBusConfig.SlowBus.getSlowdownMultipliers());
    }

    /** 减速范围宽度（格），复用时间总线的容量卡配置。 */
    public int getCapacityWidth() {
        return TimeBusConfig.valueForCardCount(getCapacityCount(), TimeBusConfig.Bus.getCapacityWidths());
    }

    public double getPowerDraw() {
        return Math.max(TimeBusConfig.SlowBus.idlePower,
                (getEffectiveSlowdown() + 2 * getTotalUpgrades()) * TimeBusConfig.SlowBus.powerPerSpeed);
    }

    private int getTotalUpgrades() {
        if (cachedTotalUpgrades < 0) {
            cachedTotalUpgrades = getCardCount() + getCapacityCount() + getRedstoneCount();
        }
        return cachedTotalUpgrades;
    }

    /** True when the redstone upgrade is installed and set to pulse mode. */
    private boolean isPulseMode() {
        return getRedstoneCount() > 0 && getRSMode() == RedstoneMode.SIGNAL_PULSE;
    }

    @Override
    protected boolean isSleeping() {
        if (getRedstoneCount() <= 0) {
            return false;
        }
        final RedstoneMode rsMode = getRSMode();
        if (rsMode == null) {
            return false;
        }
        final boolean hasSignal = getHost().hasRedstone(getSide());
        switch (rsMode) {
            case IGNORE:
                return false;
            case HIGH_SIGNAL:
                return !hasSignal;
            case LOW_SIGNAL:
                return hasSignal;
            case SIGNAL_PULSE:
                // 脉冲模式:信号上升沿触发一次工作(见 onNeighborChanged/upgradesChanged)。
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onNeighborChanged(IBlockAccess world, BlockPos pos, BlockPos neighbor) {
        boolean hasSignal = getHost().hasRedstone(getSide());
        if (hasSignal != this.lastRedstone) {
            this.lastRedstone = hasSignal;
            if (hasSignal && getRSMode() == RedstoneMode.SIGNAL_PULSE) {
                registerSlowdown();
            }
        }
        try {
            if (!isSleeping()) {
                this.getProxy().getTick().wakeDevice(this.getProxy().getNode());
            } else {
                this.getProxy().getTick().sleepDevice(this.getProxy().getNode());
            }
        } catch (GridAccessException ignored) {
        }
    }

    @Override
    public void upgradesChanged() {
        invalidateUpgradeCaches();
        boolean hasSignal = getHost().hasRedstone(getSide());
        if (hasSignal != this.lastRedstone) {
            this.lastRedstone = hasSignal;
            if (hasSignal && getRSMode() == RedstoneMode.SIGNAL_PULSE) {
                registerSlowdown();
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
        if (isSleeping()) {
            return TickRateModulation.SLEEP;
        }
        extractAEPower(ticksSinceLastCall);
        registerSlowdown();
        // 减速登记本身开销可忽略,无需 idle 退避,保持每 tick 刷新使记录不失效。
        return TickRateModulation.URGENT;
    }

    /**
     * 对正面范围内每个方块登记减速档位。
     *
     * <p>只登记有 ITickable tile 的方块（其他方块跳过,不占登记);每 tick 刷新
     * 记录使新鲜窗口永不失效,直到本总线停止工作。
     */
    private void registerSlowdown() {
        final AEPartLocation facing = this.getSide();
        final net.minecraft.world.World world = getHost().getTile().getWorld();
        final BlockPos start = getHost().getTile().getPos().offset(facing.getFacing());
        final int width = getCapacityWidth();
        final int n = getEffectiveSlowdown();
        final long tick = world.getTotalWorldTime();

        for (int i = 0; i < width; i++) {
            final BlockPos target = start.offset(facing.getFacing(), i);
            if (!world.isBlockLoaded(target)) {
                continue;
            }
            final TileEntity te = world.getTileEntity(target);
            if (te instanceof ITickable) {
                TileSlowdownTable.register(world, target, n, tick);
            }
        }
    }

    private void extractAEPower(int ticksSinceLastCall) {
        try {
            IEnergyGrid grid = this.getProxy().getEnergy();
            double active = getPowerDraw() - TimeBusConfig.SlowBus.idlePower;
            if (active > 0) {
                grid.extractAEPower(active * ticksSinceLastCall, appeng.api.config.Actionable.MODULATE,
                        appeng.api.config.PowerMultiplier.CONFIG);
            }
        } catch (GridAccessException e) {
        }
    }

    @Override
    public boolean onPartActivate(EntityPlayer player, EnumHand hand, Vec3d pos) {
        if (!player.getEntityWorld().isRemote) {
            Platform.openGUI(player, getHost().getTile(), this.getSide(),
                    GuiWrapper.INSTANCE.wrap(TimeSlowBusGui.INSTANCE));
        }
        return true;
    }

    @Override
    public void randomDisplayTick(net.minecraft.world.World world, BlockPos pos, Random rand) {
        if (!this.isActive() || !this.isPowered() || isSleeping()) {
            return;
        }
        // 减速主题:倒流方向的粒子(与加速总线的 END_ROD 前冲区分)。
        final AEPartLocation facing = this.getSide();
        final net.minecraft.util.EnumFacing dir = facing.getFacing();
        final double dx = dir.getXOffset() * 0.55;
        final double dz = dir.getZOffset() * 0.55;
        final int w = getCapacityWidth();
        final BlockPos start = pos.offset(dir);
        for (int i = 0; i < w; i++) {
            if (rand.nextInt(3) != 0) {
                continue;
            }
            final BlockPos target = start.offset(dir, i);
            if (world.isAirBlock(target)) {
                continue;
            }
            world.spawnParticle(net.minecraft.util.EnumParticleTypes.PORTAL,
                target.getX() + 0.5 - dx,
                target.getY() + rand.nextDouble() * 0.8 + 0.1,
                target.getZ() + 0.5 - dz,
                -dir.getXOffset() * 0.03, 0, -dir.getZOffset() * 0.03);
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
        if (this.isActive() && this.isPowered()) {
            return MODELS_HAS_CHANNEL;
        } else if (this.isPowered()) {
            return MODELS_ON;
        }
        return MODELS_OFF;
    }
}
