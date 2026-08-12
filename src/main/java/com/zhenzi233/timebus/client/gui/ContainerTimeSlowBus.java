package com.zhenzi233.timebus.client.gui;

import appeng.api.config.Upgrades;
import appeng.container.guisync.GuiSync;
import appeng.container.implementations.ContainerUpgradeable;
import com.zhenzi233.timebus.part.PartTimeSlowBus;
import net.minecraft.entity.player.InventoryPlayer;

public class ContainerTimeSlowBus extends ContainerUpgradeable {

    // 减速档位/卡数/范围/耗电同步:通道 18-21 未被 AE2 使用。
    @GuiSync(18)
    public int syncedSlowdown = 2;
    @GuiSync(19)
    public int syncedSpeedCards = 0;
    @GuiSync(20)
    public int syncedCapacityCards = 0;
    @GuiSync(21)
    public int syncedCapacityWidth = 1;
    @GuiSync(22)
    public int syncedPowerDraw = 100; // 0.01 AE/t

    public ContainerTimeSlowBus(InventoryPlayer ip, appeng.api.implementations.IUpgradeableHost te) {
        super(ip, te);
    }

    @Override
    protected void setupConfig() {
        this.setupUpgrades();
    }

    @Override
    public void detectAndSendChanges() {
        if (this.getUpgradeable() instanceof PartTimeSlowBus) {
            PartTimeSlowBus part = (PartTimeSlowBus) this.getUpgradeable();
            this.syncedSlowdown = part.getEffectiveSlowdown();
            this.syncedSpeedCards = part.getInstalledUpgrades(Upgrades.SPEED);
            this.syncedCapacityCards = part.getInstalledUpgrades(Upgrades.CAPACITY);
            this.syncedCapacityWidth = part.getCapacityWidth();
            this.syncedPowerDraw = (int) Math.round(part.getPowerDraw() * 100);
        }
        super.detectAndSendChanges();
    }
}
