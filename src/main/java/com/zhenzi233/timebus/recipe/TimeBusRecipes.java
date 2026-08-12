package com.zhenzi233.timebus.recipe;

import appeng.api.AEApi;
import appeng.api.features.IInscriberRegistry;
import appeng.api.features.InscriberProcessType;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.item.ItemMachineParallelCard;
import com.zhenzi233.timebus.item.ItemTimeCircuitBoard;
import com.zhenzi233.timebus.item.ItemTimeInscriberTemplate;
import com.zhenzi233.timebus.item.ItemTimeProcessor;
import com.zhenzi233.timebus.item.ItemTimeWand;
import com.zhenzi233.timebus.part.ItemTimeBus;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.Collections;

/**
 * Time Bus inscriber recipes (AE2 Inscriber).
 *
 * <ul>
 *   <li>Time Circuit Board = Matter Ball (middle) + Time Inscriber Template
 *       (top &amp; bottom), INSCRIBE (the template is not consumed).</li>
 *   <li>Time Processor = Redstone (middle) + Time Circuit Board (top) +
 *       Silicon (bottom), PRESS (all three inputs are consumed).</li>
 * </ul>
 *
 * <p>Registered from {@code TimeBus.postInit} so the AE2 registries are
 * guaranteed to be available on both dedicated servers and integrated
 * (singleplayer) clients.
 */
public final class TimeBusRecipes {

    private TimeBusRecipes() {
    }

    public static void registerInscriberRecipes() {
        final IInscriberRegistry reg = AEApi.instance().registries().inscriber();

        final ItemStack template = new ItemStack(ItemTimeInscriberTemplate.ITEM);
        final ItemStack matterBall = AEApi.instance().definitions().materials().matterBall()
                .maybeStack(1).orElse(ItemStack.EMPTY);
        final ItemStack silicon = AEApi.instance().definitions().materials().siliconPrint()
                .maybeStack(1).orElse(ItemStack.EMPTY);

        // Time Circuit Board: Matter Ball (middle) + Time Inscriber Template (top & bottom).
        reg.addRecipe(reg.builder()
                .withInputs(Collections.singletonList(matterBall))
                .withTopOptional(Collections.singletonList(template))
                .withOutput(new ItemStack(ItemTimeCircuitBoard.ITEM))
                .withProcessType(InscriberProcessType.INSCRIBE)
                .build());

        // Time Processor: Redstone (middle) + Time Circuit Board (top) + Silicon (bottom).
        reg.addRecipe(reg.builder()
                .withInputs(Collections.singletonList(new ItemStack(Items.REDSTONE)))
                .withTopOptional(Collections.singletonList(new ItemStack(ItemTimeCircuitBoard.ITEM)))
                .withBottomOptional(Collections.singletonList(silicon))
                .withOutput(new ItemStack(ItemTimeProcessor.ITEM))
                .withProcessType(InscriberProcessType.PRESS)
                .build());

        // Time Inscriber Template (replication): Template (top, not consumed) + Block of Iron (middle) -> Template.
        reg.addRecipe(reg.builder()
                .withInputs(Collections.singletonList(new ItemStack(Blocks.IRON_BLOCK)))
                .withTopOptional(Collections.singletonList(template))
                .withOutput(new ItemStack(ItemTimeInscriberTemplate.ITEM))
                .withProcessType(InscriberProcessType.INSCRIBE)
                .build());

    }

    /**
     * Crafting table recipe for the Time Bus item:
     * <pre>
     *   t
     *  mcm
     *   p
     * </pre>
     * t = Time Processor, m = Matter Ball, c = Formation Core, p = Piston.
     */
    public static void registerCraftingRecipes() {
        final ItemStack processor = new ItemStack(ItemTimeProcessor.ITEM);
        final ItemStack matterBall = AEApi.instance().definitions().materials().matterBall()
                .maybeStack(1).orElse(ItemStack.EMPTY);
        final ItemStack formationCore = AEApi.instance().definitions().materials().formationCore()
                .maybeStack(1).orElse(ItemStack.EMPTY);
        final ItemStack annihilationCore = AEApi.instance().definitions().materials().annihilationCore()
                .maybeStack(1).orElse(ItemStack.EMPTY);

        GameRegistry.addShapedRecipe(new ResourceLocation(TimeBus.MOD_ID, "time_bus"), null,
                new ItemStack(ItemTimeBus.ITEM),
                " t ", "mcm", " p ",
                't', processor,
                'm', matterBall,
                'c', formationCore,
                'p', new ItemStack(Blocks.PISTON));

        final ItemStack iron = new ItemStack(Items.IRON_INGOT);
        final ItemStack advCard = AEApi.instance().definitions().materials().advCard()
                .maybeStack(1).orElse(ItemStack.EMPTY);
        final ItemStack inscriber = AEApi.instance().definitions().blocks().inscriber()
                .maybeStack(1).orElse(ItemStack.EMPTY);
        final ItemStack energyCell = AEApi.instance().definitions().blocks().energyCell()
                .maybeStack(1).orElse(ItemStack.EMPTY);
        final Item generatorItem = Item.getByNameOrId("timebus:time_generator");

        // Time Fluid Generator
        if (generatorItem != null) {
            GameRegistry.addShapedRecipe(new ResourceLocation(TimeBus.MOD_ID, "time_generator"), null,
                    new ItemStack(generatorItem),
                    "iti", "gmg", "iai",
                    'i', iron, 't', processor, 'g', "blockGlass", 'm', matterBall, 'a', inscriber);
        }

        // Time Wand
        GameRegistry.addShapedRecipe(new ResourceLocation(TimeBus.MOD_ID, "time_wand"), null,
                new ItemStack(ItemTimeWand.ITEM),
                "mo ", "ti ", "  i",
                'm', matterBall, 'o', energyCell, 't', processor, 'i', iron);

        // Machine Parallel Card (shapeless): Advanced Card + Time Processor
        GameRegistry.addShapelessRecipe(new ResourceLocation(TimeBus.MOD_ID, "machine_parallel_card"), null,
                new ItemStack(ItemMachineParallelCard.ITEM),
                net.minecraft.item.crafting.Ingredient.fromStacks(advCard),
                net.minecraft.item.crafting.Ingredient.fromStacks(processor));

        // Time Slow Bus: 与时间总线相同布局,中间用 AE 破坏核心(Annihilation Core)。
        GameRegistry.addShapedRecipe(new ResourceLocation(TimeBus.MOD_ID, "time_slow_bus"), null,
                new ItemStack(com.zhenzi233.timebus.part.ItemTimeSlowBus.ITEM),
                " t ", "mcm", " p ",
                't', processor, 'm', matterBall, 'c', annihilationCore, 'p', new ItemStack(Blocks.PISTON));
    }
}
