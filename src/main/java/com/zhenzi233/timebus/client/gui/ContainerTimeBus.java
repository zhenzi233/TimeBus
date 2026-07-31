package com.zhenzi233.timebus.client.gui;

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
        }
        super.detectAndSendChanges();
    }
}
