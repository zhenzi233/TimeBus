package com.zhenzi233.timebus.compat.jei;

import com.zhenzi233.timebus.item.ItemTimeInscriberTemplate;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.VanillaTypes;
import net.minecraft.item.ItemStack;

/**
 * JEI 联动（Had Enough Items 4.34.1 / JEI 4.x）。
 *
 * <p>与 AE2 的联动方式一致：在时间压印模板的 JEI 物品页追加"如何获得"文字
 * 说明（爆炸合成方法），不做自定义合成类别。JEI 缺失时本类不会被扫描加载，
 * 不构成依赖。
 */
@JEIPlugin
public class TimeBusJeiPlugin implements IModPlugin {

    @Override
    public void register(final IModRegistry registry) {
        registry.addIngredientInfo(new ItemStack(ItemTimeInscriberTemplate.ITEM), VanillaTypes.ITEM,
                "info.timebus.explosion_synthesis");
    }
}
