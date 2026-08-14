package com.zhenzi233.timebus.config;

import com.zhenzi233.timebus.TimeBus;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.Config.Comment;
import net.minecraftforge.common.config.Config.LangKey;
import net.minecraftforge.common.config.Config.Name;
import net.minecraftforge.common.config.Config.RangeDouble;
import net.minecraftforge.common.config.Config.RangeInt;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * TimeBus 配置文件（Forge 1.12.2 @Config），按功能分类组织。
 *
 * <p>分类：Bus（时间总线）/ TimeGenerator（时间流体发生器）/ Wand（时间杖）/
 * MM（Modular Machinery）/ Mek（Mekanism）。分类后 cfg 键带分类前缀
 * （如 {@code bus."Speed Multipliers"}）；旧版平铺在 general 分类下的键会残留
 * 但不生效，升级玩家需要重新设置一次配置。
 */
// 本类只是配置容器：真正带 @Config 注解的是下面的五个分类类（FML 会逐个收集，
// 包括嵌套类的注解），因此这里不再标注 @Config，避免多出一个空配置入口。
public class TimeBusConfig {

    /** 配置类独立日志，避免静态初始化耦合主类（主类依赖 AE2 API，纯逻辑单测时不加载）。 */
    private static final Logger LOGGER = LogManager.getLogger("TimeBusConfig");

    /** 方块黑白名单模式（时间总线与减速总线各自独立配置）。 */
    public enum ListMode {
        /** 黑名单：名单内的方块禁止被加速/减速，其余放行。 */
        BLACKLIST,
        /** 白名单：只有名单内的方块允许被加速/减速，其余禁止。 */
        WHITELIST
    }

    // 列表配置默认值单一来源：字段初始字符串与解析 fallback 都来自这里，
    // 改默认值只改常量，避免两处字面量漂移。
    private static final int[] DEFAULT_SPEED_MULTIPLIERS = {2, 4, 8, 16, 32};
    /** 时间杖默认倍率：一次性点击加速,起步更高(0 卡 = 32x,满配 = 512x)。 */
    private static final int[] DEFAULT_WAND_SPEED_MULTIPLIERS = {32, 64, 128, 256, 512};
    /** 时间减速总线默认档位：每 N tick 才执行一次 update(0 卡 = 半速,满配 = 1/32 速)。 */
    private static final int[] DEFAULT_SLOWDOWN_MULTIPLIERS = {2, 4, 8, 16, 32};
    private static final int[] DEFAULT_CAPACITY_WIDTHS = {1, 3, 9, 15};

    /** 时间总线（Time Bus，ME 线缆部件）配置。 */
    @Config(modid = TimeBus.MOD_ID, category = "bus")
    @Config.LangKey("config.timebus.category.bus")
    public static class Bus {

        @Name("Speed Multipliers")
        @LangKey("config.timebus.bus.speedMultipliers")
        @Comment({
            "Per-card speed multipliers, comma-separated.",
            "Nth value = speed when N-1 cards are installed.",
            "If more cards than values, the last value repeats.",
            "Example: 2,4,8,16,32 -> 0 cards=2x, 1 card=4x, 2 cards=8x, 3 cards=16x, 4 cards=32x"
        })
        public static String speedMultipliers = join(DEFAULT_SPEED_MULTIPLIERS);

        @Name("Idle Power Draw (AE/t)")
        @LangKey("config.timebus.bus.idlePower")
        @Comment("Power drawn per tick when idle (no acceleration happening). Default: 1.0")
        @RangeDouble(min = 0.0, max = 1000.0)
        public static double idlePower = 1.0;

        @Name("Power Cost per Speed Unit")
        @LangKey("config.timebus.bus.powerPerSpeed")
        @Comment({
            "Power cost multiplier. Total cost per tick = max(idlePower, (effectiveSpeed + 2 * upgradeCount) * powerPerSpeed).",
            "Default: 0.5"
        })
        @RangeDouble(min = 0.0, max = 100.0)
        public static double powerPerSpeed = 0.5;

        @Name("Random Tick Calls per Speed Unit")
        @LangKey("config.timebus.bus.randomTickCallsPerSpeed")
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
        @LangKey("config.timebus.bus.fluidMode")
        @Comment("If true, the Time Bus consumes fluid from the ME network to operate, instead of AE power.")
        public static boolean fluidMode = false;

        @Name("Consumed Fluid")
        @LangKey("config.timebus.bus.fluidName")
        @Comment("The registry name of the fluid to consume (e.g. 'water', 'lava'). Default: water")
        public static String fluidName = "water";

        @Name("Fluid per Tick (mB)")
        @LangKey("config.timebus.bus.fluidPerTick")
        @Comment("Millibuckets of fluid consumed per tick when accelerating. Supports decimals (e.g. 0.1 = 1 mB per 10 ticks). Default: 1.0")
        @RangeDouble(min = 0.001, max = 100000.0)
        public static double fluidPerTick = 1.0;

        @Name("Fluid Consumption Multiplier (per card)")
        @LangKey("config.timebus.bus.fluidConsumeMultiplier")
        @Comment({
            "Each speed card multiplies fluid consumption by this value.",
            "Actual rate = fluidPerTick * (this ^ cardCount).",
            "Default: 2.0"
        })
        @RangeDouble(min = 1.0, max = 100.0)
        public static double fluidConsumeMultiplier = 2.0;

        @Name("Minimum Fluid (mB)")
        @LangKey("config.timebus.bus.minFluid")
        @Comment("Minimum amount of fluid in the ME network before the Time Bus will start consuming it. Default: 1000 (= 1 bucket)")
        @RangeInt(min = 0, max = 1000000)
        public static int minFluid = 1000;

        // --- Capacity Card ---

        @Name("Capacity Widths")
        @LangKey("config.timebus.bus.capacityWidths")
        @Comment({
            "Per-card acceleration widths, comma-separated.",
            "Nth value = width when N-1 cards are installed (1 = single block).",
            "If more cards than values, the last value repeats.",
            "Example: 1,3,9,15 -> 0 cards=1, 1 card=3, 2 cards=9, 3 cards=15"
        })
        public static String capacityWidths = join(DEFAULT_CAPACITY_WIDTHS);

        @Name("Max Calls per Tick (Work Budget)")
        @LangKey("config.timebus.bus.maxCallsPerTick")
        @Comment({
            "Maximum number of acceleration calls (tile updates + random ticks) executed per server tick.",
            "Work beyond this budget is carried over to the next tick, keeping the server tick smooth",
            "even with max speed cards and a wide range. Default: 128"
        })
        @RangeInt(min = 1, max = 100000)
        public static int maxCallsPerTick = 128;

        // --- Block List (blacklist / whitelist) ---

        @Name("Block List Enabled")
        @LangKey("config.timebus.bus.busListEnabled")
        @Comment({
            "If true, the Time Bus consults a block list before accelerating each block.",
            "Blocks are matched by registry name only (tile NBT is ignored).",
            "Default: false"
        })
        public static boolean busListEnabled = false;

        @Name("Block List Mode")
        @LangKey("config.timebus.bus.busListMode")
        @Comment({
            "BLACKLIST: blocks in the list are never accelerated, everything else is.",
            "WHITELIST: only blocks in the list are accelerated, everything else is not.",
            "Default: BLACKLIST"
        })
        public static ListMode busListMode = ListMode.BLACKLIST;

        @Name("Block List")
        @LangKey("config.timebus.bus.busBlockList")
        @Comment({
            "Comma-separated block registry names. 'modid:*' matches every block of a mod;",
            "a bare name without namespace defaults to 'minecraft:'.",
            "Examples: 'minecraft:furnace,minecraft:chest' or 'appliedenergistics2:*,mekanism:*'",
            "Default: (empty)"
        })
        public static String busBlockList = "";

        // int[] 缓存字段不会被 Forge 当配置项（不支持数组类型，自动跳过）。
        static int[] cachedSpeedMultipliers = null;
        static int[] cachedCapacityWidths = null;
        static BlockListFilter cachedBusFilter = null;

        public static int[] getSpeedMultipliers() {
            if (cachedSpeedMultipliers == null) {
                cachedSpeedMultipliers = parseList(speedMultipliers, DEFAULT_SPEED_MULTIPLIERS);
            }
            return cachedSpeedMultipliers;
        }

        public static int[] getCapacityWidths() {
            if (cachedCapacityWidths == null) {
                cachedCapacityWidths = parseList(capacityWidths, DEFAULT_CAPACITY_WIDTHS);
            }
            return cachedCapacityWidths;
        }

        /** 时间总线黑白名单（enabled/mode/list 解析一次并缓存，onConfigChanged 失效）。 */
        public static BlockListFilter getBusFilter() {
            refreshIfFileChanged();
            if (cachedBusFilter == null) {
                cachedBusFilter = new BlockListFilter(busListEnabled, busListMode == ListMode.WHITELIST, busBlockList);
            }
            return cachedBusFilter;
        }
    }

    /** 时间流体发生器配置。 */
    @Config(modid = TimeBus.MOD_ID, category = "timeGenerator")
    @Config.LangKey("config.timebus.category.timeGenerator")
    public static class TimeGenerator {

        @Name("Matter Ball Unit Value")
        @LangKey("config.timebus.timeGenerator.matterBallUnit")
        @Comment({
            "How many progress units one Matter Ball contributes.",
            "Default: 1"
        })
        @RangeInt(min = 1, max = 1000000000)
        public static int matterBallUnit = 1;

        @Name("Singularity Unit Value")
        @LangKey("config.timebus.timeGenerator.singularityUnit")
        @Comment({
            "How many progress units one Singularity contributes.",
            "A Singularity is worth 1000 Matter Balls, so progress is preserved",
            "when the input mode is switched mid-way.",
            "Default: 1000"
        })
        @RangeInt(min = 1, max = 1000000000)
        public static int singularityUnit = 1000;

        @Name("Units per Batch")
        @LangKey("config.timebus.timeGenerator.unitsPerBatch")
        @Comment({
            "Total progress units needed to produce one batch of Time Fluid.",
            "64000 units = 64000 Matter Balls or 64 Singularities.",
            "Default: 64000"
        })
        @RangeInt(min = 1, max = 1000000000)
        public static int unitsPerBatch = 64000;

        @Name("Time Fluid per Batch (mB)")
        @LangKey("config.timebus.timeGenerator.timeFluidPerBatch")
        @Comment({
            "How many millibuckets of Time Fluid one full batch produces.",
            "Default: 1000"
        })
        @RangeDouble(min = 1.0, max = 1000000000.0)
        public static double timeFluidPerBatch = 1000.0;

        @Name("Inputs Consumed per Update")
        @LangKey("config.timebus.timeGenerator.generatorConsumePerTick")
        @Comment({
            "How many input items the Time Fluid Generator consumes per update call.",
            "Normally one update per tick; a Time Bus accelerates the machine by",
            "calling update() multiple times per tick, so throughput scales with",
            "the bus speed. The input slot itself always stacks up to 64 items.",
            "Default: 64"
        })
        @RangeInt(min = 1, max = 64)
        public static int generatorConsumePerTick = 64;
    }

    /** 时间杖配置。 */
    @Config(modid = TimeBus.MOD_ID, category = "wand")
    @Config.LangKey("config.timebus.category.wand")
    public static class Wand {

        @Name("Wand Cell Size (Bytes)")
        @LangKey("config.timebus.wand.wandBytes")
        @Comment({
            "Storage size of the Time Wand's fluid cell, in AE bytes.",
            "AE2 fluid storage holds 8000 mB per byte (AE2EL getUnitsPerByte), so 512 bytes",
            "= 4,096,000 mB = 4096 buckets by default.",
            "Default: 512"
        })
        @RangeInt(min = 1, max = 1000000000)
        public static int wandBytes = 512;

        @Name("Wand Speed Multipliers")
        @LangKey("config.timebus.wand.wandSpeedMultipliers")
        @Comment({
            "Per-card speed multipliers for the Time Wand, comma-separated.",
            "Nth value = speed when N-1 speed cards are installed in the wand",
            "(cards are placed via an AE2 cell workbench).",
            "If more cards than values, the last value repeats.",
            "Example: 32,64,128,256,512 -> 0 cards=32x, 1 card=64x, 2 cards=128x, 3 cards=256x, 4 cards=512x",
            "This is independent from the Time Bus speedMultipliers."
        })
        public static String wandSpeedMultipliers = join(DEFAULT_WAND_SPEED_MULTIPLIERS);

        @Name("Wand Fluid Cost (mB)")
        @LangKey("config.timebus.wand.wandFluidCost")
        @Comment({
            "Millibuckets of Time Fluid consumed per wand use (shift + right-click).",
            "Default: 10"
        })
        @RangeInt(min = 1, max = 1000000)
        public static int wandFluidCost = 10;

        @Name("Wand Energy Cost (AE)")
        @LangKey("config.timebus.wand.wandEnergyCost")
        @Comment({
            "AE energy consumed per wand use (shift + right-click).",
            "Default: 1000"
        })
        @RangeInt(min = 1, max = 1000000000)
        public static int wandEnergyCost = 1000;

        @Name("Wand Bus Batch Size")
        @LangKey("config.timebus.wand.wandBatchSize")
        @Comment({
            "How many transfer batches a right-click on an ME Import/Export Bus",
            "performs (each batch moves up to the bus's normal per-tick amount,",
            "scaled by its speed cards). This runs in a single tick, so large values",
            "can stall the server - keep it well below the Time Bus work budget.",
            "Default: 16, max: 256"
        })
        @RangeInt(min = 1, max = 256)
        public static int wandBatchSize = 16;

        static int[] cachedWandSpeedMultipliers = null;

        public static int[] getWandSpeedMultipliers() {
            if (cachedWandSpeedMultipliers == null) {
                cachedWandSpeedMultipliers = parseList(wandSpeedMultipliers, DEFAULT_WAND_SPEED_MULTIPLIERS);
            }
            return cachedWandSpeedMultipliers;
        }
    }

    /** Modular Machinery（CE）加速配置。 */
    @Config(modid = TimeBus.MOD_ID, category = "mm")
    @Config.LangKey("config.timebus.category.mm")
    public static class MM {

        @Name("MM Acceleration Enabled")
        @LangKey("config.timebus.mm.mmAccelerationEnabled")
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
        @LangKey("config.timebus.mm.mmKeepThreadsEnabled")
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
        @LangKey("config.timebus.mm.mmEnergyFollowsSpeed")
        @Comment({
            "If true, machines accelerated by the Time Bus pay energy proportionally:",
            "per-tick energy consumption (input) and production (output) are",
            "multiplied by the same factor as the recipe-duration speed-up, so the",
            "total energy per recipe stays the same while the output rate increases.",
            "Requires MM Acceleration Enabled. Default: true"
        })
        public static boolean mmEnergyFollowsSpeed = true;

        @Name("MM Context Refresh Interval (ticks)")
        @LangKey("config.timebus.mm.mmContextRefreshInterval")
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
    }

    /** Mekanism（CE-Unofficial）加速配置。 */
    @Config(modid = TimeBus.MOD_ID, category = "mek")
    @Config.LangKey("config.timebus.category.mek")
    public static class Mek {

        @Name("Mek Acceleration Enabled")
        @LangKey("config.timebus.mek.mekAccelerationEnabled")
        @Comment({
            "If true, the Time Bus accelerates Mekanism (CE-Unofficial) machines by",
            "re-running the recipe tick multiple times per server tick (each extra",
            "run advances one tick of progress and draws one tick of energy, so total",
            "energy per recipe stays constant). Requires Mekanism-CE-Unofficial;",
            "missing or other versions are skipped softly. Default: false"
        })
        public static boolean mekAccelerationEnabled = false;

        @Name("Generator Acceleration Enabled")
        @LangKey("config.timebus.mek.mekGeneratorAccelerationEnabled")
        @Comment({
            "If true, the Time Bus / Time Wand speeds up Mekanism generators",
            "(wind, gas, bio, solar, advanced solar, heat, and large multiblock):",
            "while accelerated they insert N times more energy per tick (N = bus",
            "speed multiplier) without consuming extra fuel - pure free power",
            "generation, intentionally unbalanced. Mek's own restricted-tick anti-",
            "acceleration design is bypassed on purpose. Requires Mekanism-CE-",
            "Unofficial; missing or other versions are skipped softly. Default: true"
        })
        public static boolean mekGeneratorAccelerationEnabled = true;
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

    /** Comma-join an int array into the config string form (defaults single source). */
    private static String join(final int[] values) {
        final StringBuilder sb = new StringBuilder(values.length * 3);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static void invalidateCaches() {
        Bus.cachedSpeedMultipliers = null;
        Bus.cachedCapacityWidths = null;
        Bus.cachedBusFilter = null;
        Wand.cachedWandSpeedMultipliers = null;
        SlowBus.cachedSlowdownMultipliers = null;
        SlowBus.cachedSlowBusFilter = null;
    }

    /**
     * 客户端：Forge 1.12.2 的 @Config.Comment 是英文硬编码且不走 lang key，
     * 这里把 cfg 中已注册字段的注释替换为当前语言的本地化文本
     * （key = 字段 @LangKey + ".comment"，仅中文语言文件提供；英文语言下
     * hasKey 为 false，保持原英文注释不变）。
     */
    public static void localizeComments() {
        if (FMLCommonHandler.instance().getEffectiveSide().isClient()) {
            final net.minecraftforge.common.config.Configuration cfg = getConfigInstance();
            if (cfg == null) {
                return;
            }
            for (String catName : cfg.getCategoryNames()) {
                final net.minecraftforge.common.config.ConfigCategory cat = cfg.getCategory(catName);
                for (net.minecraftforge.common.config.Property prop : cat.getOrderedValues()) {
                    final String langKey = prop.getLanguageKey();
                    if (langKey == null) {
                        continue;
                    }
                    final String commentKey = langKey + ".comment";
                    if (net.minecraft.client.resources.I18n.hasKey(commentKey)) {
                        prop.setComment(net.minecraft.client.resources.I18n.format(commentKey));
                    }
                }
            }
        }
    }

    /** 爆炸合成配置（奇点 + 铁 → 时间压印模板，机制参照 AE2 量子缠绕态奇点）。 */
    @Config(modid = TimeBus.MOD_ID, category = "explosion")
    @Config.LangKey("config.timebus.category.explosion")
    public static class Explosion {

        @Name("Enabled")
        @LangKey("config.timebus.explosion.enabled")
        @Comment({"是否启用爆炸合成:奇点与铁锭/铁块(物品实体,丢在地上)同时被爆炸波及",
                "时转化为时间压印模板(默认 1 奇点 + 1 铁 → 1 模板)。默认 true"})
        public static boolean explosionSynthesisEnabled = true;

        @Name("Singularity Cost")
        @LangKey("config.timebus.explosion.singularityCost")
        @Comment("每次合成消耗的奇点数量。默认 1")
        @RangeInt(min = 1, max = 64)
        public static int singularityCost = 1;

        @Name("Iron Cost")
        @LangKey("config.timebus.explosion.ironCost")
        @Comment("每次合成消耗的铁数量（铁锭或铁块均计入，ore dict ingotIron/blockIron）。默认 1")
        @RangeInt(min = 1, max = 64)
        public static int ironCost = 1;

        @Name("Output Count")
        @LangKey("config.timebus.explosion.outputCount")
        @Comment("每次合成产出的时间压印模板数量。默认 1")
        @RangeInt(min = 1, max = 64)
        public static int outputCount = 1;
    }

    /** 时间减速总线配置（让正面 ITickable 方块每 N tick 才执行一次 update）。 */
    @Config(modid = TimeBus.MOD_ID, category = "slowBus")
    @Config.LangKey("config.timebus.category.slowBus")
    public static class SlowBus {

        @Name("Slowdown Multipliers")
        @LangKey("config.timebus.slowBus.slowdownMultipliers")
        @Comment({
            "Per-card slowdown levels, comma-separated.",
            "Nth value = every N ticks the targeted block tick runs once when N-1 cards are installed.",
            "Example: 2,4,8,16,32 -> 0 cards=1/2 speed, 1 card=1/4 speed, 2 cards=1/8 speed, 3 cards=1/16 speed, 4 cards=1/32 speed"
        })
        public static String slowdownMultipliers = join(DEFAULT_SLOWDOWN_MULTIPLIERS);

        @Name("Idle Power Draw (AE/t)")
        @LangKey("config.timebus.slowBus.idlePower")
        @Comment("Power drawn per tick when idle (no slowdown happening). Default: 1.0")
        @RangeDouble(min = 0.0, max = 1000.0)
        public static double idlePower = 1.0;

        @Name("Power Cost per Slowdown Unit")
        @LangKey("config.timebus.slowBus.powerPerSpeed")
        @Comment({
            "Power cost multiplier. Total cost per tick = max(idlePower, (slowdownLevel + 2 * upgradeCount) * powerPerSpeed).",
            "Default: 0.5"
        })
        @RangeDouble(min = 0.0, max = 100.0)
        public static double powerPerSpeed = 0.5;

        // --- Block List (blacklist / whitelist) ---

        @Name("Block List Enabled")
        @LangKey("config.timebus.slowBus.slowBusListEnabled")
        @Comment({
            "If true, the Slow Bus consults a block list before slowing down each block.",
            "Blocks are matched by registry name only (tile NBT is ignored).",
            "Default: false"
        })
        public static boolean slowBusListEnabled = false;

        @Name("Block List Mode")
        @LangKey("config.timebus.slowBus.slowBusListMode")
        @Comment({
            "BLACKLIST: blocks in the list are never slowed down, everything else is.",
            "WHITELIST: only blocks in the list are slowed down, everything else is not.",
            "Default: BLACKLIST"
        })
        public static ListMode slowBusListMode = ListMode.BLACKLIST;

        @Name("Block List")
        @LangKey("config.timebus.slowBus.slowBusBlockList")
        @Comment({
            "Comma-separated block registry names. 'modid:*' matches every block of a mod;",
            "a bare name without namespace defaults to 'minecraft:'.",
            "Default: (empty)"
        })
        public static String slowBusBlockList = "";

        // int[] 缓存字段不会被 Forge 当配置项（不支持数组类型，自动跳过）。
        static int[] cachedSlowdownMultipliers = null;
        static BlockListFilter cachedSlowBusFilter = null;

        public static int[] getSlowdownMultipliers() {
            if (cachedSlowdownMultipliers == null) {
                cachedSlowdownMultipliers = parseList(slowdownMultipliers, DEFAULT_SLOWDOWN_MULTIPLIERS);
            }
            return cachedSlowdownMultipliers;
        }

        /** 减速总线黑白名单（enabled/mode/list 解析一次并缓存，onConfigChanged 失效）。 */
        public static BlockListFilter getSlowBusFilter() {
            refreshIfFileChanged();
            if (cachedSlowBusFilter == null) {
                cachedSlowBusFilter = new BlockListFilter(slowBusListEnabled,
                        slowBusListMode == ListMode.WHITELIST, slowBusBlockList);
            }
            return cachedSlowBusFilter;
        }
    }

    /** ConfigManager.getConfiguration 是包私有方法，通过反射获取当前 cfg 实例。 */
    private static java.lang.reflect.Method cachedGetConfigMethod = null;

    private static net.minecraftforge.common.config.Configuration getConfigInstance() {
        try {
            if (cachedGetConfigMethod == null) {
                cachedGetConfigMethod = Class.forName("net.minecraftforge.common.config.ConfigManager")
                        .getDeclaredMethod("getConfiguration", String.class, String.class);
                cachedGetConfigMethod.setAccessible(true);
            }
            return (net.minecraftforge.common.config.Configuration) cachedGetConfigMethod.invoke(null, TimeBus.MOD_ID, "timebus");
        } catch (Exception e) {
            LOGGER.warn("Time Bus: cannot resolve config instance: {}", e.toString());
            return null;
        }
    }

    // --- cfg 文件热重载（手动编辑文件无需重启即可生效） ---
    // Forge 1.12.2 @Config 的 GUI 保存链路在部分环境（Cleanroom fork / 多 @Config 类
    // 共享单文件）下不可靠：GUI 保存后字段/界面可能与磁盘脱节。这里兜底：检测
    // cfg 文件 mtime 变化 → 从磁盘重读 Configuration → 同步静态字段 → 失效缓存，
    // 玩家直接改文件（或 GUI 保存成功但内存未同步）后 1 秒内生效，无需重启。
    private static long lastCfgCheckTime = 0;
    private static long lastCfgMtime = -1;

    private static void refreshIfFileChanged() {
        final long now = System.currentTimeMillis();
        if (now - lastCfgCheckTime < 1000) {
            return; // 节流：最多每秒 stat 一次文件
        }
        lastCfgCheckTime = now;
        try {
            final net.minecraftforge.common.config.Configuration cfg = getConfigInstance();
            if (cfg == null) {
                return;
            }
            final long mtime = cfg.getConfigFile().lastModified();
            if (lastCfgMtime < 0) {
                lastCfgMtime = mtime;
                return; // 首次记录基线
            }
            if (mtime != lastCfgMtime) {
                lastCfgMtime = mtime;
                cfg.load(); // 从磁盘重读（恢复与文件的脱节）
                ConfigManager.sync(TimeBus.MOD_ID, Config.Type.INSTANCE); // 字段 ← Configuration
                invalidateCaches();
                LOGGER.info("Time Bus: config file changed on disk, reloaded.");
            }
        } catch (Exception e) {
            LOGGER.debug("Time Bus: config refresh check failed: {}", e.toString());
        }
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
