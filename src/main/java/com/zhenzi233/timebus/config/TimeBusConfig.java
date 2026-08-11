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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.zhenzi233.timebus.TimeBus;

@Config(modid = TimeBus.MOD_ID)
@Config.LangKey("config." + TimeBus.MOD_ID + ".title")
public class TimeBusConfig {

    /** 配置类独立日志，避免静态初始化耦合主类（主类依赖 AE2 API，纯逻辑单测时不加载）。 */
    private static final Logger LOGGER = LogManager.getLogger("TimeBusConfig");

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
        "Power cost multiplier. Total cost per tick = max(idlePower, (effectiveSpeed + 2 * upgradeCount) * powerPerSpeed).",
        "Default: 0.5"
    })
    @RangeDouble(min = 0.0, max = 100.0)
    public static double powerPerSpeed = 0.5;

    @Name("Random Tick Calls per Speed Unit")
    @Comment({
        "How many Block.randomTick calls one speed unit performs on randomly-ticking",
        "blocks (crops, grass spread, ice melt, ...). At 32x speed the default 20 gives",
        "640 calls per block, which can consume a large share of the per-tick work",
        "budget; lower it if wide-range random-tick acceleration starves other blocks.",
        "Default: 20"
    })
    @RangeInt(min = 1, max = 10000)
    public static int randomTickCallsPerSpeed = 20;

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

    // --- Time Fluid Generator ---

    @Name("Matter Ball Unit Value")
    @Comment({
        "How many progress units one Matter Ball contributes.",
        "Default: 1"
    })
    @RangeInt(min = 1, max = 1000000000)
    public static int matterBallUnit = 1;

    @Name("Singularity Unit Value")
    @Comment({
        "How many progress units one Singularity contributes.",
        "A Singularity is worth 1000 Matter Balls, so progress is preserved",
        "when the input mode is switched mid-way.",
        "Default: 1000"
    })
    @RangeInt(min = 1, max = 1000000000)
    public static int singularityUnit = 1000;

    @Name("Units per Batch")
    @Comment({
        "Total progress units needed to produce one batch of Time Fluid.",
        "64000 units = 64000 Matter Balls or 64 Singularities.",
        "Default: 64000"
    })
    @RangeInt(min = 1, max = 1000000000)
    public static int unitsPerBatch = 64000;

    @Name("Time Fluid per Batch (mB)")
    @Comment({
        "How many millibuckets of Time Fluid one full batch produces.",
        "Default: 1000"
    })
    @RangeDouble(min = 1.0, max = 1000000000.0)
    public static double timeFluidPerBatch = 1000.0;

    @Name("Inputs Consumed per Update")
    @Comment({
        "How many input items the Time Fluid Generator consumes per update call.",
        "Normally one update per tick; a Time Bus accelerates the machine by",
        "calling update() multiple times per tick, so throughput scales with",
        "the bus speed. The input slot itself always stacks up to 64 items.",
        "Default: 64"
    })
    @RangeInt(min = 1, max = 64)
    public static int generatorConsumePerTick = 64;

    // --- Time Wand ---

    @Name("Wand Cell Size (Bytes)")
    @Comment({
        "Storage size of the Time Wand's fluid cell, in AE bytes.",
        "AE2 fluid storage holds 8000 mB per byte (AE2EL getUnitsPerByte), so 512 bytes",
        "= 4,096,000 mB = 4096 buckets by default.",
        "Default: 512"
    })
    @RangeInt(min = 1, max = 1000000000)
    public static int wandBytes = 512;

    @Name("Wand Speed Multipliers")
    @Comment({
        "Per-card speed multipliers for the Time Wand, comma-separated.",
        "Nth value = speed when N-1 speed cards are installed in the wand",
        "(cards are placed via an AE2 cell workbench).",
        "If more cards than values, the last value repeats.",
        "Example: 2,4,8,16,32 -> 0 cards=2x, 1 card=4x, 2 cards=8x, 3 cards=16x, 4 cards=32x",
        "This is independent from the Time Bus speedMultipliers."
    })
    public static String wandSpeedMultipliers = "2,4,8,16,32";

    @Name("Wand Fluid Cost (mB)")
    @Comment({
        "Millibuckets of Time Fluid consumed per wand use (shift + right-click).",
        "Default: 10"
    })
    @RangeInt(min = 1, max = 1000000)
    public static int wandFluidCost = 10;

    @Name("Wand Energy Cost (AE)")
    @Comment({
        "AE energy consumed per wand use (shift + right-click).",
        "Default: 1000"
    })
    @RangeInt(min = 1, max = 1000000000)
    public static int wandEnergyCost = 1000;

    @Name("Wand Bus Batch Size")
    @Comment({
        "How many transfer batches a right-click on an ME Import/Export Bus",
        "performs (each batch moves up to the bus's normal per-tick amount,",
        "scaled by its speed cards). This runs in a single tick, so large values",
        "can stall the server - keep it well below the Time Bus work budget.",
        "Default: 16, max: 256"
    })
    @RangeInt(min = 1, max = 256)
    public static int wandBatchSize = 16;

    // --- Modular Machinery Acceleration ---

    @Name("MM Acceleration Enabled")
    @Comment({
        "If true, the Time Bus accelerates Modular Machinery (CE) controllers by",
        "compressing the recipe duration in MM's own modifier system, instead of",
        "calling update() (which is blocked by the machines' restricted-tick",
        "anti-acceleration design).",
        "The multiplier follows the bus's speed cards (0 cards = 2x, 4 cards = 32x",
        "by default). Default: false"
    })
    public static boolean mmAccelerationEnabled = false;

    @Name("MM Keep Idle Threads")
    @Comment({
        "If true, Modular Machinery factory controllers that are currently being",
        "accelerated by a Time Bus keep their idle extra threads alive instead of",
        "letting MM recycle them. MM recycles idle threads after 200 ticks, which",
        "clears the injected speed modifier and briefly drops the thread back to",
        "normal speed until the Time Bus re-applies it.",
        "Default: true"
    })
    public static boolean mmKeepThreadsEnabled = true;

    @Name("MM Energy Cost Follows Speed")
    @Comment({
        "If true, machines accelerated by the Time Bus pay energy proportionally:",
        "per-tick energy consumption (input) and production (output) are",
        "multiplied by the same factor as the recipe-duration speed-up, so the",
        "total energy per recipe stays the same while the output rate increases.",
        "Requires MM Acceleration Enabled. Default: true"
    })
    public static boolean mmEnergyFollowsSpeed = true;

    @Name("MM Context Refresh Interval (ticks)")
    @Comment({
        "How often the Time Bus force re-applies its MM modifiers (remove+add,",
        "which triggers MM flushContextModifier). MM pools and reuses recipe",
        "crafting contexts, so the actually-applied modifiers can desync from",
        "the permanent modifier source; this periodic refresh heals that within",
        "one interval. 20 ticks = 1 second. Set to 0 to disable (threads may",
        "then stay unaccelerated until the speed changes). Default: 20"
    })
    @RangeInt(min = 0, max = 10000)
    public static int mmContextRefreshInterval = 20;

    // --- Mekanism Acceleration ---

    @Name("Mek Acceleration Enabled")
    @Comment({
        "If true, the Time Bus accelerates Mekanism (CE-Unofficial) machines by",
        "re-running the recipe tick multiple times per server tick (each extra",
        "run advances one tick of progress and draws one tick of energy, so total",
        "energy per recipe stays constant). Requires Mekanism-CE-Unofficial;",
        "missing or other versions are skipped softly. Default: false"
    })
    public static boolean mekAccelerationEnabled = false;


    // --- Parsed caches for the comma-separated list configs ---
    // The raw strings are parsed once and cached; the cache is invalidated on
    // config change. This keeps hot paths (per-tick speed/width lookups) free
    // of split+parse while staying fully compatible with existing cfg files
    // (an int[] field would fail to migrate an old string-typed config entry).
    private static int[] cachedSpeedMultipliers = null;
    private static int[] cachedCapacityWidths = null;
    private static int[] cachedWandSpeedMultipliers = null;

    public static int[] getSpeedMultipliers() {
        if (cachedSpeedMultipliers == null) {
            cachedSpeedMultipliers = parseList(speedMultipliers, new int[]{2, 4, 8, 16, 32});
        }
        return cachedSpeedMultipliers;
    }

    public static int[] getCapacityWidths() {
        if (cachedCapacityWidths == null) {
            cachedCapacityWidths = parseList(capacityWidths, new int[]{1, 3, 9, 15});
        }
        return cachedCapacityWidths;
    }

    public static int[] getWandSpeedMultipliers() {
        if (cachedWandSpeedMultipliers == null) {
            cachedWandSpeedMultipliers = parseList(wandSpeedMultipliers, new int[]{2, 4, 8, 16, 32});
        }
        return cachedWandSpeedMultipliers;
    }

    /**
     * 按升级卡数取配置数组（速度倍率/容量宽度）对应值：卡数越界回退到最后一项，
     * 空表返回 1。纯函数，可脱离 MC 运行时单测。
     */
    public static int valueForCardCount(final int cardCount, final int[] values) {
        if (values == null || values.length == 0) {
            return 1;
        }
        return Math.max(1, values[Math.max(0, Math.min(cardCount, values.length - 1))]);
    }

    /** Parse a comma-separated int list; fall back to {@code defaults} on bad input. */
    static int[] parseList(final String raw, final int[] defaults) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaults;
        }
        final String[] parts = raw.split(",");
        if (parts.length == 0) {
            return defaults;
        }
        try {
            final int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
            return out;
        } catch (NumberFormatException e) {
            LOGGER.warn("Time Bus: invalid numeric list in config '{}', using defaults", raw);
            return defaults;
        }
    }

    private static void invalidateCaches() {
        cachedSpeedMultipliers = null;
        cachedCapacityWidths = null;
        cachedWandSpeedMultipliers = null;
    }

    @Mod.EventBusSubscriber(modid = TimeBus.MOD_ID)
    private static class EventHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals(TimeBus.MOD_ID)) {
                ConfigManager.sync(TimeBus.MOD_ID, Config.Type.INSTANCE);
                invalidateCaches();
            }
        }
    }
}
