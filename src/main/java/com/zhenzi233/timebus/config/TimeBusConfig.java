package com.zhenzi233.timebus.config;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.Config.Comment;
import net.minecraftforge.common.config.Config.Name;
import net.minecraftforge.common.config.Config.RangeDouble;
import net.minecraftforge.common.config.Config.RangeInt;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import com.zhenzi233.timebus.TimeBus;

@Config(modid = TimeBus.MOD_ID)
@Config.LangKey("config." + TimeBus.MOD_ID + ".title")
public class TimeBusConfig {

    @Name("Speed Multipliers")
    @Comment({
        "Per-card speed multipliers, comma-separated.",
        "Nth value = speed when N-1 cards are installed.",
        "If more cards than values, the last value repeats.",
        "Example: 2,4,8,16,32 -> 0 cards=2x, 1 card=4x, 2 cards=8x, 3 cards=16x, 4 cards=32x"
    })
    public static String speedMultipliers = "2,4,8,16,32";

    @Name("Idle Power Draw (AE/t)")
    @Comment("Power drawn per tick when idle (no acceleration happening). Default: 1.0")
    @RangeDouble(min = 0.0, max = 1000.0)
    public static double idlePower = 1.0;

    @Name("Power Cost per Speed Unit")
    @Comment({
        "Power cost multiplier. Total cost per tick = max(idlePower, effectiveSpeed * powerPerSpeed).",
        "Default: 0.5"
    })
    @RangeDouble(min = 0.0, max = 100.0)
    public static double powerPerSpeed = 0.5;

    // --- Fluid Consumption Mode ---

    @Name("Fluid Mode Enabled")
    @Comment("If true, the Time Bus consumes fluid from the ME network to operate, instead of AE power.")
    public static boolean fluidMode = false;

    @Name("Consumed Fluid")
    @Comment("The registry name of the fluid to consume (e.g. 'water', 'lava'). Default: water")
    public static String fluidName = "water";

    @Name("Fluid per Tick (mB)")
    @Comment("Millibuckets of fluid consumed per tick when accelerating. Supports decimals (e.g. 0.1 = 1 mB per 10 ticks). Default: 1.0")
    @RangeDouble(min = 0.001, max = 100000.0)
    public static double fluidPerTick = 1.0;

    @Name("Fluid Consumption Multiplier (per card)")
    @Comment({
        "Each speed card multiplies fluid consumption by this value.",
        "Actual rate = fluidPerTick * (this ^ cardCount).",
        "Default: 2.0"
    })
    @RangeDouble(min = 1.0, max = 100.0)
    public static double fluidConsumeMultiplier = 2.0;

    @Name("Minimum Fluid (mB)")
    @Comment("Minimum amount of fluid in the ME network before the Time Bus will start consuming it. Default: 1000 (= 1 bucket)")
    @RangeInt(min = 0, max = 1000000)
    public static int minFluid = 1000;

    // --- Capacity Card ---

    @Name("Capacity Widths")
    @Comment({
        "Per-card acceleration widths, comma-separated.",
        "Nth value = width when N-1 cards are installed (1 = single block).",
        "If more cards than values, the last value repeats.",
        "Example: 1,3,9,15 -> 0 cards=1, 1 card=3, 2 cards=9, 3 cards=15"
    })
    public static String capacityWidths = "1,3,9,15";

    @Name("Max Calls per Tick (Work Budget)")
    @Comment({
        "Maximum number of acceleration calls (tile updates + random ticks) executed per server tick.",
        "Work beyond this budget is carried over to the next tick, keeping the server tick smooth",
        "even with max speed cards and a wide range. Default: 128"
    })
    @RangeInt(min = 1, max = 100000)
    public static int maxCallsPerTick = 128;

    @Mod.EventBusSubscriber(modid = TimeBus.MOD_ID)
    private static class EventHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals(TimeBus.MOD_ID)) {
                ConfigManager.sync(TimeBus.MOD_ID, Config.Type.INSTANCE);
            }
        }
    }
}
