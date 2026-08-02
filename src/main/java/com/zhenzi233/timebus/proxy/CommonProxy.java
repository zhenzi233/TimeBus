package com.zhenzi233.timebus.proxy;

import com.zhenzi233.timebus.item.ItemDebugWand;
import com.zhenzi233.timebus.item.ItemMachineParallelCard;
import com.zhenzi233.timebus.item.ItemTimeWand;
import com.zhenzi233.timebus.part.ItemTimeBus;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

public class CommonProxy implements IProxy {
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        // Fluids are registered in TimeBus.preInit before the proxy runs.
        ItemTimeBus.ITEM = new ItemTimeBus();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemTimeBus.ITEM);

        ItemDebugWand.ITEM = new ItemDebugWand();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemDebugWand.ITEM);

        ItemTimeWand.ITEM = new ItemTimeWand();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemTimeWand.ITEM);

        ItemMachineParallelCard.ITEM = new ItemMachineParallelCard();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemMachineParallelCard.ITEM);
    }

    @Override
    public void init(FMLInitializationEvent event) {
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
    }
}
