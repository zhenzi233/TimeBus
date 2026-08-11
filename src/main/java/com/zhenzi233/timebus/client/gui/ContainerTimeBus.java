package com.zhenzi233.timebus.client.gui;

import appeng.api.config.Upgrades;
import appeng.container.guisync.GuiSync;
import appeng.container.implementations.ContainerUpgradeable;
import com.zhenzi233.timebus.part.PartTimeBus;
import net.minecraft.entity.player.InventoryPlayer;

public class ContainerTimeBus extends ContainerUpgradeable {

    // Budget usage synced from the server-side part via AE2's @GuiSync mechanism.
    // detectAndSendChanges refreshes these from the part; AE2 pushes changes to the
    // client and the GUI reads them directly. Channels 10/11 are unused by AE2.
    @GuiSync(10)
    public int budgetUsed = 0;
    @GuiSync(11)
    public int budgetTotal = 0;

    // 加速倍率 / 范围 / 耗电 / 流体速率显示同步：客户端 part 的升级缓存不随插卡
    // 失效（upgradesChanged 只在服务端触发），GUI 直接读服务端 part 的实时值，
    // 避免显示与功能脱节。通道 12-17 未被 AE2 使用。
    @GuiSync(12)
    public int syncedSpeed = 2;
    @GuiSync(13)
    public int syncedSpeedCards = 0;
    @GuiSync(14)
    public int syncedCapacityCards = 0;
    @GuiSync(15)
    public int syncedCapacityWidth = 1;
    @GuiSync(16)
    public int syncedPowerDraw = 100; // 0.01 AE/t
    @GuiSync(17)
    public int syncedFluidRate = 100; // 0.01 mB/t

    public ContainerTimeBus(InventoryPlayer ip, appeng.api.implementations.IUpgradeableHost te) {
        super(ip, te);
    }

    @Override
    protected void setupConfig() {
        this.setupUpgrades();
    }

    @Override
    public void detectAndSendChanges() {
        // Refresh the budget values from the server-side part before AE2 syncs them.
        if (this.getUpgradeable() instanceof PartTimeBus) {
            PartTimeBus part = (PartTimeBus) this.getUpgradeable();
            this.budgetUsed = part.getBudgetUsedLastTick();
            this.budgetTotal = part.getBudgetTotalLastTick();
            this.syncedSpeed = part.getEffectiveSpeed();
            this.syncedSpeedCards = part.getInstalledUpgrades(Upgrades.SPEED);
            this.syncedCapacityCards = part.getInstalledUpgrades(Upgrades.CAPACITY);
            this.syncedCapacityWidth = part.getCapacityWidth();
            // AE2 @GuiSync 只支持 int/boolean/String（double 会 ClassCastException），
            // 耗电/流体速率乘 100 以 0.01 精度同步。
            this.syncedPowerDraw = (int) Math.round(part.getPowerDraw() * 100);
            this.syncedFluidRate = (int) Math.round(part.getFluidRate() * 100);
        }
        super.detectAndSendChanges();
    }
}
