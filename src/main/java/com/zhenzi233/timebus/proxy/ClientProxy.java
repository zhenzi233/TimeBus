package com.zhenzi233.timebus.proxy;

import com.zhenzi233.timebus.item.ItemDebugWand;
import com.zhenzi233.timebus.item.ItemMachineParallelCard;
import com.zhenzi233.timebus.item.ItemTimeCircuitBoard;
import com.zhenzi233.timebus.item.ItemTimeInscriberTemplate;
import com.zhenzi233.timebus.item.ItemTimeProcessor;
import com.zhenzi233.timebus.item.ItemTimeWand;
import com.zhenzi233.timebus.part.ItemTimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
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

        ItemTimeWand.ITEM = new ItemTimeWand();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemTimeWand.ITEM);

        ItemMachineParallelCard.ITEM = new ItemMachineParallelCard();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemMachineParallelCard.ITEM);

        ItemTimeInscriberTemplate.ITEM = new ItemTimeInscriberTemplate();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemTimeInscriberTemplate.ITEM);

        ItemTimeCircuitBoard.ITEM = new ItemTimeCircuitBoard();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemTimeCircuitBoard.ITEM);

        ItemTimeProcessor.ITEM = new ItemTimeProcessor();
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(ItemTimeProcessor.ITEM);

        // Register item models in preInit. In 1.12.2 the model registry is baked before
        // init, so models must be registered here or the items render black/purple.
        registerItemModel(ItemTimeBus.ITEM, 0, "time_bus");
        registerItemModel(ItemDebugWand.ITEM, 0, "debug_wand");
        registerItemModel(ItemTimeWand.ITEM, 0, "time_wand");
        registerItemModel(ItemMachineParallelCard.ITEM, 0, "machine_parallel_card");
        registerItemModel(ItemTimeInscriberTemplate.ITEM, 0, "time_inscriber_template");
        registerItemModel(ItemTimeCircuitBoard.ITEM, 0, "time_circuit_board");
        registerItemModel(ItemTimeProcessor.ITEM, 0, "time_processor");
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
        // 配置描述本地化：Forge 1.12.2 的 @Config.Comment 不走 lang key，
        // 在语言资源加载完成后把 cfg 注释替换为当前语言的文本。
        TimeBusConfig.localizeComments();
    }
}
