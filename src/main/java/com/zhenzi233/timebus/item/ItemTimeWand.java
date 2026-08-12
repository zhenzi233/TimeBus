package com.zhenzi233.timebus.item;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.Upgrades;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.parts.IPart;
import appeng.parts.automation.PartExportBus;
import appeng.parts.automation.PartImportBus;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
import com.zhenzi233.timebus.util.AccelerateHelper;
import com.zhenzi233.timebus.util.ModularMachineryAccelerator;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentTranslation;
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
 * channel: configurable bytes (default 512), 1 type, AE-powered. Exposes FLUID_HANDLER_ITEM_CAPABILITY
 * so fluid terminals and other Forge-capability consumers can interact with it.
 */
public class ItemTimeWand extends AEBasePoweredItem implements IStorageCell<IAEFluidStack> {

    public static ItemTimeWand ITEM;

    public ItemTimeWand() {
        super(AEConfig.instance().getMatterCannonBattery());
        this.setCreativeTab(TimeBusCreativeTab.INSTANCE);
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
        final int[] multipliers = TimeBusConfig.Wand.getWandSpeedMultipliers();
        if (multipliers.length == 0) {
            return 1;
        }
        final int idx = Math.min(getCardCount(stack), multipliers.length - 1);
        return Math.max(1, multipliers[idx]);
    }

    /**
     * MM 机器上的加速倍率：跟随时间总线的 {@code Speed Multipliers}
     * （0 卡 = 2x，满配 = 32x），而不是魔杖独立的 wandSpeedMultipliers——
     * 用户按"时间总线倍率"的直觉配置，魔杖自己那套可能起步更高。
     */
    private int getWandMmSpeed(final ItemStack stack) {
        final int[] multipliers = TimeBusConfig.Bus.getSpeedMultipliers();
        if (multipliers.length == 0) {
            return 1;
        }
        final int idx = Math.min(getCardCount(stack), multipliers.length - 1);
        return Math.max(1, multipliers[idx]);
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
            // 服务端权威:直接放行(1.12.2 Forge 的 processRightClickBlock 对
            // onItemUseFirst 返回非 PASS 的结果都会发包),由服务端执行并负责
            // 状态消息与粒子广播。客户端不做本地预检,避免客户端 cfg 与服务端
            // 不一致时误判(MM/Mek 开关仅在服务端生效)。返回 SUCCESS 同时拦截
            // 方块 GUI(总线 GUI、熔炉 GUI 等),与"潜行 = 加速"语义一致。
            return EnumActionResult.SUCCESS;
        }
        final ItemStack stack = player.getHeldItem(hand);
        if (TimeBusFluids.TIME_FLUID == null) {
            return EnumActionResult.FAIL;
        }

        // Shift + right-click on an ME Import/Export Bus: batch transfer
        // (consumes fluid + AE, runs the bus work wandBatchSize times).
        final IPart hitPart = findImportExportBus(worldIn, pos, facing, hitX, hitY, hitZ);
        if (hitPart != null) {
            return handleBusBatch(worldIn, pos, facing, player, hand, hitX, hitY, hitZ);
        }

        // Shift + right-click on an MM (CE) controller: accelerate the currently
        // running recipe until it finishes (recipe duration x1/speed, per-tick
        // energy x speed when enabled), then MM auto-restores normal speed.
        // Injected as semi-permanent modifiers, which MM clears on recipe finish
        // or failure - nothing is written permanently into the save. An idle
        // machine is not charged.
        final TileEntity targetTE = worldIn.getTileEntity(pos);
        if (TimeBusConfig.MM.mmAccelerationEnabled && targetTE != null
                && ModularMachineryAccelerator.isController(targetTE)) {
            if (!ModularMachineryAccelerator.hasActiveRecipes(targetTE)) {
                sendNoActiveRecipe(player);
                return EnumActionResult.FAIL;
            }
            if (!tryConsumeCosts(player, stack)) {
                return EnumActionResult.FAIL;
            }
            ModularMachineryAccelerator.applyWandToActiveRecipes(targetTE,
                    ModularMachineryAccelerator.SOURCE_WAND_PREFIX + player.getUniqueID(), getWandMmSpeed(stack));
            spawnBurstParticles(worldIn, pos, getWandMmSpeed(stack));
            return EnumActionResult.SUCCESS;
        }
        // TODO(Mek): 魔杖对 Mek 机器目前只有约 0.5 秒的加速效果（活跃表 10 tick
        // 新鲜窗口，魔杖一次性点击不持续注册）。后续参考 MM 的 semi-permanent
        // 方案，对 Mek 做"当前配方加速到完成"的特化，此处预留分支位置。

        // Pre-check that the target can be accelerated at all: without this
        // the wand would charge for a click on stone / an empty cable bus /
        // a Time Bus itself and then do nothing (the MM and bus paths have
        // their own pre-checks above).
        if (!AccelerateHelper.canAccelerate(worldIn, pos)) {
            // MM 控制器在开关关闭时落入此路径（上方 MM 分支仅在开关开启时命中）：
            // 给出针对性提示引导开配置，而不是笼统的"无法加速"。
            if (targetTE != null && ModularMachineryAccelerator.isController(targetTE)) {
                sendMmAccelerationDisabled(player);
            } else {
                sendCannotAccelerate(player);
            }
            return EnumActionResult.FAIL;
        }

        // Consume AE power + Time Fluid (simulate first, commit only if both are available).
        if (!tryConsumeCosts(player, stack)) {
            return EnumActionResult.FAIL;
        }

        // Accelerate the target block once. The source key is per player so
        // different players' wands stack on the same machine instead of
        // overwriting each other (MM modifier key collision). The particle burst
        // is broadcast by the server so all nearby players see it.
        final int speed = getWandSpeed(stack);
        AccelerateHelper.accelerateOnce(worldIn, pos, speed,
                ModularMachineryAccelerator.SOURCE_WAND_PREFIX + player.getUniqueID());
        spawnBurstParticles(worldIn, pos, speed);
        return EnumActionResult.SUCCESS;
    }

    /** Report a failed use to the player (synced to the client by vanilla). */
    private void sendNotEnough(final EntityPlayer player, final boolean energy) {
        if (player != null) {
            player.sendStatusMessage(new TextComponentTranslation(
                    energy ? "msg.timebus.not_enough_energy" : "msg.timebus.not_enough_fluid"), true);
        }
    }

    /** Report that the targeted bus has nothing to transfer. */
    private void sendBusIdle(final EntityPlayer player) {
        if (player != null) {
            player.sendStatusMessage(new TextComponentTranslation("msg.timebus.bus_idle"), true);
        }
    }

    /** Report that the targeted MM controller has no running recipe. */
    private void sendNoActiveRecipe(final EntityPlayer player) {
        if (player != null) {
            player.sendStatusMessage(new TextComponentTranslation("msg.timebus.no_active_recipe"), true);
        }
    }

    /** Report that the targeted block cannot be accelerated at all. */
    private void sendCannotAccelerate(final EntityPlayer player) {
        if (player != null) {
            player.sendStatusMessage(new TextComponentTranslation("msg.timebus.cannot_accelerate"), true);
        }
    }

    /** Report that MM acceleration is switched off in the config. */
    private void sendMmAccelerationDisabled(final EntityPlayer player) {
        if (player != null) {
            player.sendStatusMessage(new TextComponentTranslation("msg.timebus.mm_acceleration_disabled"), true);
        }
    }
    /**
     * Server-side resource payment: simulate AE power + Time Fluid first,
     * commit only if both are available, and tell the player what is missing.
     * Shared by the block-acceleration and bus-batch paths.
     */
    private boolean tryConsumeCosts(final EntityPlayer player, final ItemStack stack) {
        if (!hasEnoughCosts(player, stack)) {
            return false;
        }
        commitCosts(stack);
        return true;
    }

    /** Simulate AE power + Time Fluid availability; reports what is missing. */
    private boolean hasEnoughCosts(final EntityPlayer player, final ItemStack stack) {
        if (TimeBusFluids.TIME_FLUID == null) {
            return false;
        }
        final double energyNeed = TimeBusConfig.Wand.wandEnergyCost;
        if (this.extractAEPower(stack, energyNeed, Actionable.SIMULATE) < energyNeed) {
            sendNotEnough(player, true);
            return false;
        }
        final ICellInventoryHandler<IAEFluidStack> cell = AEApi.instance()
                .registries().cell()
                .getCellInventory(stack, null,
                        AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
        if (cell == null) {
            sendNotEnough(player, false);
            return false;
        }
        final int fluidNeed = TimeBusConfig.Wand.wandFluidCost;
        final IAEFluidStack request = AEFluidStack.fromFluidStack(new FluidStack(TimeBusFluids.TIME_FLUID, fluidNeed));
        final IAEFluidStack taken = cell.extractItems(request, Actionable.SIMULATE, null);
        if (taken == null || taken.getStackSize() < fluidNeed) {
            sendNotEnough(player, false);
            return false;
        }
        return true;
    }

    /** Commit the AE power + Time Fluid payment (call after hasEnoughCosts). */
    private void commitCosts(final ItemStack stack) {
        final double energyNeed = TimeBusConfig.Wand.wandEnergyCost;
        this.extractAEPower(stack, energyNeed, Actionable.MODULATE);
        final ICellInventoryHandler<IAEFluidStack> cell = AEApi.instance()
                .registries().cell()
                .getCellInventory(stack, null,
                        AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));
        if (cell != null) {
            final int fluidNeed = TimeBusConfig.Wand.wandFluidCost;
            final IAEFluidStack request = AEFluidStack.fromFluidStack(new FluidStack(TimeBusFluids.TIME_FLUID, fluidNeed));
            cell.extractItems(request, Actionable.MODULATE, null);
        }
    }

    /**
     * 命中位置上的 ME 导入/导出总线 part（判定与客户端预览渲染器
     * {@code WandPartPreviewRenderer} 一致）：先按点击面 (facing) 取 part；
     * 斜向点击时命中面不是 part 安装面会取不到，再用
     * {@code IPartHost.selectPartGlobal} 按射线命中点精确定位兜底。没有这个
     * 兜底时，总线能被预览高亮、点击却落入通用路径报"无法加速"。
     *
     * @return 总线 part，命中位置不是导入/导出总线时返回 null
     */
    @Nullable
    private IPart findImportExportBus(final net.minecraft.world.World world, final BlockPos pos,
                                      final net.minecraft.util.EnumFacing facing,
                                      final float hitX, final float hitY, final float hitZ) {
        IPart part = AEApi.instance().partHelper().getPart(world, pos, appeng.api.util.AEPartLocation.fromFacing(facing));
        if (part instanceof PartExportBus || part instanceof PartImportBus) {
            return part;
        }
        final appeng.api.parts.IPartHost host = AEApi.instance().partHelper().getPartHost(world, pos);
        if (host != null) {
            final appeng.api.parts.SelectedPart selected = host.selectPartGlobal(
                    new net.minecraft.util.math.Vec3d(pos.getX() + hitX, pos.getY() + hitY, pos.getZ() + hitZ));
            if (selected != null
                    && (selected.part instanceof PartExportBus || selected.part instanceof PartImportBus)) {
                return selected.part;
            }
        }
        return null;
    }

    /**
     * Shift right-click on an ME Import/Export Bus: consume Time Fluid + AE
     * and run the bus's tickingRequest N times (wandBatchSize), so one click
     * transfers many batches at once. Client predicts by checking the part type.
     */
    private EnumActionResult handleBusBatch(final net.minecraft.world.World world, final BlockPos pos,
                                            final net.minecraft.util.EnumFacing facing,
                                            final EntityPlayer player, final EnumHand hand,
                                            final float hitX, final float hitY, final float hitZ) {
        final IPart part = findImportExportBus(world, pos, facing, hitX, hitY, hitZ);
        if (part == null) {
            return EnumActionResult.PASS; // not a bus: let default behavior (open GUI etc.) run
        }

        if (world.isRemote) {
            return EnumActionResult.SUCCESS; // intercept client-side so the bus GUI does not open
        }

        final ItemStack stack = player.getHeldItem(hand);

        // Simulate the costs first (nothing is consumed yet), then probe one
        // work cycle: only pay and batch when the bus actually has something
        // to move. An idle import/export bus returns SLOWER (not SLEEP), so
        // "did work" means the FASTER result of this cycle.
        if (!hasEnoughCosts(player, stack)) {
            return EnumActionResult.FAIL;
        }
        final IGridTickable bus = (IGridTickable) part;
        try {
            if (bus.tickingRequest(null, 1) != TickRateModulation.FASTER) {
                sendBusIdle(player);
                return EnumActionResult.FAIL;
            }
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: bus tickingRequest failed at {}: {}", pos, e.toString());
            sendBusIdle(player);
            return EnumActionResult.FAIL;
        }
        commitCosts(stack);
        spawnBurstParticles(world, pos, getWandSpeed(stack));

        // Batch: run the remaining bus work calls (the probe already did one).
        // Clamp as a defensive backstop: the config range is already capped, but
        // an old cfg file may still hold a huge value; a single-tick naked loop
        // must never be allowed to stall the server.
        final int n = Math.max(1, Math.min(TimeBusConfig.Wand.wandBatchSize, 256));
        for (int i = 1; i < n; i++) {
            try {
                bus.tickingRequest(null, 1);
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: bus tickingRequest failed at {}: {}", pos, e.toString());
                break;
            }
        }
        return EnumActionResult.SUCCESS;
    }
    /**
     * 六面外冲的粒子爆发,由服务端广播(1.12.2 的 {@code WorldServer} 覆写了
     * 带 count 的 {@code spawnParticle} 重载,向 64 格内玩家发送
     * SPacketParticles,多人可见)。只在服务端成功执行后调用;客户端不再本地
     * 绘制,避免双份粒子与"其他玩家看不见"的问题。
     *
     * <p>注意服务端重载签名与客户端不同:位置后是 count、xOffset/yOffset/
     * zOffset(散布范围)与 particleSpeed(粒子速度);客户端按 offset 高斯
     * 散布位置、按 particleSpeed 随机方向出速度。逐粒子 count=1 调用把
     * 位置固定在方块面上,速度大小保留原外冲力度,方向由客户端随机化——
     * 视觉上是标准的向外爆散;每次点击最多 24 个包,带宽可忽略。
     */
    private void spawnBurstParticles(final net.minecraft.world.World world, final BlockPos pos, final int speed) {
        if (!(world instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        final net.minecraft.world.WorldServer server = (net.minecraft.world.WorldServer) world;
        final double cx = pos.getX() + 0.5;
        final double cy = pos.getY() + 0.5;
        final double cz = pos.getZ() + 0.5;
        final int count = Math.min(24, 6 + speed);
        for (int i = 0; i < count; i++) {
            // Pick one of the six faces, spawn on it, and push outward
            // along its normal (with a little jitter) so the burst is
            // clearly visible around the block surface.
            final int face = world.rand.nextInt(6);
            double px = cx, py = cy, pz = cz;
            final double jx = (world.rand.nextDouble() - 0.5) * 0.8;
            final double jy = (world.rand.nextDouble() - 0.5) * 0.8;
            final double jz = (world.rand.nextDouble() - 0.5) * 0.8;
            switch (face) {
                case 0: px = pos.getX() + 1.0; py = cy + jy; pz = cz + jz; break;
                case 1: px = pos.getX();       py = cy + jy; pz = cz + jz; break;
                case 2: py = pos.getY() + 1.0; px = cx + jx; pz = cz + jz; break;
                case 3: py = pos.getY();       px = cx + jx; pz = cz + jz; break;
                case 4: pz = pos.getZ() + 1.0; px = cx + jx; py = cy + jy; break;
                default: pz = pos.getZ();      px = cx + jx; py = cy + jy; break;
            }
            final double f = 0.14 + world.rand.nextDouble() * 0.18;
            server.spawnParticle(EnumParticleTypes.END_ROD, false, px, py, pz, 1, 0.0, 0.0, 0.0, f);
            // Sprinkle in a few PORTAL particles for a stronger time effect.
            if (i % 8 == 0) {
                server.spawnParticle(EnumParticleTypes.PORTAL, false, px, py, pz, 1, 0.0, 0.0, 0.0, f * 1.5);
            }
        }
    }
    // --- IStorageCell ---

    @Override
    public int getBytes(final ItemStack cellItem) {
        return TimeBusConfig.Wand.wandBytes;
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
            // AE2EL fluid cells store 8000 mB per byte (IStorageChannel.getUnitsPerByte);
            // the old hardcoded *1000 showed a capacity 8x too small.
            // (int) clamp: wandBytes 配置上限 1e9, 1e9 * 8000 远超 int 范围,强转
            // 会溢出成负容量;容量只用于显示,封顶到 Integer.MAX_VALUE 即可。
            final long capacity = inv == null ? 0 : Math.min(inv.getTotalBytes()
                    * AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class).getUnitsPerByte(),
                    Integer.MAX_VALUE);
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
