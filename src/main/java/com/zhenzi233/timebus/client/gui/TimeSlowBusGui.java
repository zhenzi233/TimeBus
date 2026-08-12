package com.zhenzi233.timebus.client.gui;

import appeng.core.sync.GuiWrapper;
import com.zhenzi233.timebus.TimeBus;
import net.minecraft.util.ResourceLocation;

public class TimeSlowBusGui implements GuiWrapper.IExternalGui {
    public static final ResourceLocation ID = new ResourceLocation(TimeBus.MOD_ID, "time_slow_bus");
    public static final TimeSlowBusGui INSTANCE = new TimeSlowBusGui();

    @Override
    public ResourceLocation getID() {
        return ID;
    }
}
