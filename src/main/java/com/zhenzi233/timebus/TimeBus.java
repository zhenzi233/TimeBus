package com.zhenzi233.timebus;

import appeng.api.AEApi;
import appeng.api.config.Upgrades;
import appeng.api.definitions.IItemDefinition;
import appeng.core.sync.GuiWrapper;
import com.zhenzi233.timebus.client.gui.GuiHandler;
import com.zhenzi233.timebus.client.gui.TimeBusGui;
import com.zhenzi233.timebus.fluid.TimeBusFluids;
import com.zhenzi233.timebus.part.ItemTimeBus;
import com.zhenzi233.timebus.proxy.IProxy;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = TimeBus.MOD_ID, name = TimeBus.MOD_NAME, version = TimeBus.VERSION,
     dependencies = "required-after:appliedenergistics2@[rv6-stable-7,);")
public class TimeBus {

    public static final String MOD_ID = "timebus";
    public static final String MOD_NAME = "Time Bus";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    @Mod.Instance(MOD_ID)
    public static TimeBus instance;

    @SidedProxy(modId = MOD_ID, clientSide = "com.zhenzi233.timebus.proxy.ClientProxy", serverSide = "com.zhenzi233.timebus.proxy.CommonProxy")
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} is loading...", MOD_NAME);

        // Register external GUI for Time Bus
        GuiWrapper.INSTANCE.registerExternalGuiHandler(TimeBusGui.ID, new GuiWrapper.Opener() {
            @Override
            public <T extends GuiWrapper.IExternalGui> void open(T obj, GuiWrapper.GuiContext ctx) {
                ctx.player.openGui(TimeBus.instance, GuiHandler.GUI_TIME_BUS,
                        ctx.world, ctx.pos.getX(), ctx.pos.getY(), ctx.pos.getZ());
            }
        });
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());

        // Register Time Fluid (must run before proxy.preInit)
        TimeBusFluids.register();

        proxy.preInit(event);

        // Register upgrades for the Time Bus item
        IItemDefinition timeBusDef = new IItemDefinition() {
            @Override public String identifier() { return "timebus"; }
            @Override public java.util.Optional<net.minecraft.item.Item> maybeItem() { return java.util.Optional.of(ItemTimeBus.ITEM); }
            @Override public java.util.Optional<net.minecraft.item.ItemStack> maybeStack(int stackSize) { return java.util.Optional.of(new net.minecraft.item.ItemStack(ItemTimeBus.ITEM, stackSize)); }
            @Override public boolean isEnabled() { return true; }
            @Override public boolean isSameAs(net.minecraft.item.ItemStack comparableStack) {
                return !comparableStack.isEmpty() && comparableStack.getItem() == ItemTimeBus.ITEM;
            }
        };
        Upgrades.SPEED.registerItem(timeBusDef, 4);
        Upgrades.CAPACITY.registerItem(timeBusDef, 3);
        Upgrades.REDSTONE.registerItem(timeBusDef, 1);
        Upgrades.FUZZY.registerItem(timeBusDef, 1);

        // Register AE2 part models
        AEApi.instance().registries().partModels().registerModels(
            new ResourceLocation(MOD_ID, "part/time_bus_base"),
            new ResourceLocation(MOD_ID, "part/time_bus_off"),
            new ResourceLocation(MOD_ID, "part/time_bus_on"),
            new ResourceLocation(MOD_ID, "part/time_bus_has_channel")
        );
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }
}
