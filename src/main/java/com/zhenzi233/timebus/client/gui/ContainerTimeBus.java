package com.zhenzi233.timebus.client.gui;

import appeng.container.implementations.ContainerUpgradeable;
import net.minecraft.entity.player.InventoryPlayer;

public class ContainerTimeBus extends ContainerUpgradeable {

    public ContainerTimeBus(InventoryPlayer ip, appeng.api.implementations.IUpgradeableHost te) {
        super(ip, te);
    }

    @Override
    protected void setupConfig() {
        this.setupUpgrades();
    }
}
