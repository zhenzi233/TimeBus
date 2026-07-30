package com.zhenzi233.timebus.client.gui;

import appeng.core.sync.GuiWrapper;
import net.minecraft.util.ResourceLocation;
import com.zhenzi233.timebus.TimeBus;

public class TimeBusGui implements GuiWrapper.IExternalGui {
    public static final ResourceLocation ID = new ResourceLocation(TimeBus.MOD_ID, "time_bus");
    public static final TimeBusGui INSTANCE = new TimeBusGui();

    @Override
    public ResourceLocation getID() {
        return ID;
    }
}
