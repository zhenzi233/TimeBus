package com.zhenzi233.timebus;

import appeng.api.AEApi;
import appeng.api.config.Upgrades;
import appeng.api.definitions.IItemDefinition;
import appeng.core.sync.GuiWrapper;
import com.zhenzi233.timebus.block.BlockTimeGenerator;
import com.zhenzi233.timebus.client.gui.GuiHandler;
import com.zhenzi233.timebus.client.gui.TimeBusGui;
import com.zhenzi233.timebus.fluid.TimeBusFluids;
import com.zhenzi233.timebus.part.ItemTimeBus;
import com.zhenzi233.timebus.proxy.IProxy;
import com.zhenzi233.timebus.util.ModularMachineryAccelerator;
import com.zhenzi233.timebus.recipe.TimeBusRecipes;
import com.zhenzi233.timebus.tile.TileTimeGenerator;
import net.minecraft.block.Block;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION,
     dependencies = "required-after:appliedenergistics2@[rv6-stable-7,);")
public class TimeBus {

    // 版本等元数据统一由 java-templates/Reference.java（blossom 注入 gradle.properties
    // 的 mod_version）提供；这里不再写死，避免构建产物 @Mod 版本与文件名/mcmod.info
    // 不一致（此前 VERSION 停留在 1.0.5 导致服务器要求客户端装 1.0.5）。
    public static final String MOD_ID = Reference.MOD_ID;
    public static final String MOD_NAME = Reference.MOD_NAME;
    public static final String VERSION = Reference.VERSION;

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    @Mod.Instance(MOD_ID)
    public static TimeBus instance;

    /** SimpleNetworkWrapper for mod-internal packets (e.g. generator mode toggle). */
    public static net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper NETWORK;

    @SidedProxy(modId = MOD_ID, clientSide = "com.zhenzi233.timebus.proxy.ClientProxy", serverSide = "com.zhenzi233.timebus.proxy.CommonProxy")
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} is loading...", MOD_NAME);

        // Register the generator's two-mode toggle packet (server side only)
        NETWORK = net.minecraftforge.fml.common.network.NetworkRegistry.INSTANCE.newSimpleChannel(MOD_ID);
        NETWORK.registerMessage(com.zhenzi233.timebus.network.PacketToggleOutput.Handler.class,
                com.zhenzi233.timebus.network.PacketToggleOutput.class, 0, net.minecraftforge.fml.relauncher.Side.SERVER);

        // Register external GUI for Time Bus
        GuiWrapper.INSTANCE.registerExternalGuiHandler(TimeBusGui.ID, new GuiWrapper.Opener() {
            @Override
            public <T extends GuiWrapper.IExternalGui> void open(T obj, GuiWrapper.GuiContext ctx) {
                ctx.player.openGui(TimeBus.instance, GuiHandler.GUI_TIME_BUS,
                        ctx.world, ctx.pos.getX(), ctx.pos.getY(), ctx.pos.getZ());
            }
        });
        // Register external GUI for Time Slow Bus
        GuiWrapper.INSTANCE.registerExternalGuiHandler(com.zhenzi233.timebus.client.gui.TimeSlowBusGui.ID, new GuiWrapper.Opener() {
            @Override
            public <T extends GuiWrapper.IExternalGui> void open(T obj, GuiWrapper.GuiContext ctx) {
                ctx.player.openGui(TimeBus.instance, GuiHandler.GUI_TIME_SLOW_BUS,
                        ctx.world, ctx.pos.getX(), ctx.pos.getY(), ctx.pos.getZ());
            }
        });
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());

        // Register Time Fluid (must run before proxy.preInit)
        TimeBusFluids.register();

        // Register Time Fluid Generator block + tile entity
        Block blockTimeGenerator = new BlockTimeGenerator();
        GameRegistry.findRegistry(Block.class).register(blockTimeGenerator);
        GameRegistry.findRegistry(net.minecraft.item.Item.class).register(
                new ItemBlock(blockTimeGenerator).setRegistryName(blockTimeGenerator.getRegistryName()));
        GameRegistry.registerTileEntity(TileTimeGenerator.class, new ResourceLocation(MOD_ID, "time_generator"));

        proxy.preInit(event);

        // Register upgrades for the Time Bus item
        IItemDefinition timeBusDef = makeItemDef("timebus", ItemTimeBus.ITEM);
        Upgrades.SPEED.registerItem(timeBusDef, 4);
        Upgrades.CAPACITY.registerItem(timeBusDef, 3);
        Upgrades.REDSTONE.registerItem(timeBusDef, 1);
        Upgrades.FUZZY.registerItem(timeBusDef, 1);

        // Register upgrades for the Time Wand item (Speed Cards only).
        Upgrades.SPEED.registerItem(makeItemDef("time_wand", com.zhenzi233.timebus.item.ItemTimeWand.ITEM), 4);

        // Register upgrades for the Time Slow Bus item.
        IItemDefinition slowBusDef = makeItemDef("time_slow_bus", com.zhenzi233.timebus.part.ItemTimeSlowBus.ITEM);
        Upgrades.SPEED.registerItem(slowBusDef, 4);
        Upgrades.CAPACITY.registerItem(slowBusDef, 3);
        Upgrades.REDSTONE.registerItem(slowBusDef, 1);

        // Register AE2 part models
        AEApi.instance().registries().partModels().registerModels(
            new ResourceLocation(MOD_ID, "part/time_bus_base"),
            new ResourceLocation(MOD_ID, "part/time_bus_off"),
            new ResourceLocation(MOD_ID, "part/time_bus_on"),
            new ResourceLocation(MOD_ID, "part/time_bus_has_channel"),
            new ResourceLocation(MOD_ID, "part/time_slow_bus_base"),
            new ResourceLocation(MOD_ID, "part/time_slow_bus_off"),
            new ResourceLocation(MOD_ID, "part/time_slow_bus_on"),
            new ResourceLocation(MOD_ID, "part/time_slow_bus_has_channel")
        );
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    /**
     * AE2 升级卡物品定义，时间总线与时间杖共用。两者仅 identifier 与指向的
     * Item 不同，统一工厂避免复制粘贴导致 isSameAs/maybe* 语义漂移。
     */
    private static IItemDefinition makeItemDef(final String id, final net.minecraft.item.Item item) {
        return new IItemDefinition() {
            @Override public String identifier() { return id; }
            @Override public java.util.Optional<net.minecraft.item.Item> maybeItem() { return java.util.Optional.of(item); }
            @Override public java.util.Optional<net.minecraft.item.ItemStack> maybeStack(int stackSize) { return java.util.Optional.of(new net.minecraft.item.ItemStack(item, stackSize)); }
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isSameAs(net.minecraft.item.ItemStack comparableStack) {
                return !comparableStack.isEmpty() && comparableStack.getItem() == item;
            }
        };
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
        // Recipes must be registered after AE2 is fully initialized.
        TimeBusRecipes.registerInscriberRecipes();
        TimeBusRecipes.registerCraftingRecipes();
    }

    @Mod.EventHandler
    public void serverStopping(final FMLServerStoppingEvent event) {
        // FMLServerStoppingEvent 在服务器开始保存世界之前触发：先摘掉所有 MM
        // 注入的配方时长 modifier，防止其被写进存档（拆除总线之外的兜底清理）。
        ModularMachineryAccelerator.restoreAll();
    }

    /** Forge 总线事件：世界卸载时兜底清理 MM 注入的 modifier，防止维度卸载/关服时残留。 */
    @Mod.EventBusSubscriber
    public static class ForgeEvents {

        @SubscribeEvent
        public static void onWorldUnload(final WorldEvent.Unload event) {
            ModularMachineryAccelerator.restoreAllForWorld(event.getWorld());
        }

        /**
         * 区块单独卸载时兜底清理：世界还在运行、某个区块被卸载时，如果不清理，
         * 注入的 MM modifier 会随区块存档写入磁盘，重启后机器永久加速。
         * （区块卸载与整体卸载是两条独立事件路径；世界整体卸载仍由 onWorldUnload 兜底。）
         */
        @SubscribeEvent
        public static void onChunkUnload(final ChunkEvent.Unload event) {
            if (event.getWorld() != null && !event.getWorld().isRemote) {
                ModularMachineryAccelerator.restoreAllForChunk(event.getWorld(), event.getChunk());
            }
        }

        /**
         * 爆炸合成：奇点 + 铁锭/铁块物品实体在爆炸时转化为时间压印模板
         * （机制参照 AE2 量子缠绕态奇点；Detonate 在实体伤害前触发，原料仍存活）。
         */
        @SubscribeEvent
        public static void onExplosionDetonate(final net.minecraftforge.event.world.ExplosionEvent.Detonate event) {
            com.zhenzi233.timebus.recipe.ExplosionSynthesis.onExplosionDetonate(event);
        }

    }
}
