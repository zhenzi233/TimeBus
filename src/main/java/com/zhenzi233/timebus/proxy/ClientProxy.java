package com.zhenzi233.timebus.proxy;

import com.zhenzi233.timebus.item.ItemDebugWand;
import com.zhenzi233.timebus.part.ItemTimeBus;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

public class ClientProxy implements IProxy {
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        ItemTimeBus.ITEM = new ItemTimeBus();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemTimeBus.ITEM);

        ItemDebugWand.ITEM = new ItemDebugWand();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemDebugWand.ITEM);

        // Register item models in preInit. In 1.12.2 the model registry is baked before
        // init, so models must be registered here or the items render black/purple.
        registerItemModel(ItemTimeBus.ITEM, 0, "time_bus");
        registerItemModel(ItemDebugWand.ITEM, 0, "debug_wand");
        // The generator's ItemBlock shares the block's registry name.
        Item generatorItem = Item.getByNameOrId("timebus:time_generator");
        if (generatorItem != null) {
            registerItemModel(generatorItem, 0, "time_generator");
        }
    }

    private void registerItemModel(Item item, int meta, String path) {
        ModelLoader.setCustomModelResourceLocation(item, meta,
                new ModelResourceLocation("timebus:" + path, "inventory"));
    }

    @Override
    public void init(FMLInitializationEvent event) {
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
    }
}
