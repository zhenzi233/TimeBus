package com.zhenzi233.timebus.recipe;

import appeng.api.AEApi;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.config.TimeBusConfig;
import com.zhenzi233.timebus.item.ItemTimeInscriberTemplate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.oredict.OreDictionary;

/**
 * 爆炸合成：奇点 + 铁（锭/块）物品实体 → 时间压印模板。
 *
 * <p>机制参照 AE2 的量子缠绕态奇点合成（{@code EntitySingularity.doExplosion}）：
 * 把奇点与铁锭/铁块丢在地上，用 TNT 等引爆炸药——爆炸结算的 Detonate 阶段
 * （实体伤害之前）检查受影响实体：奇点物品（EntitySingularity 是 EntityItem
 * 子类，天然覆盖）与铁（ore dict {@code ingotIron}/{@code blockIron}）同时在场
 * 时消耗并产出。新生成的模板实体不在爆炸伤害列表中，不会被炸毁。
 *
 * <p>奇点实体对爆炸本身免疫（周围无末影尘时不会消失），铁物品实体在
 * Detonate 阶段也尚未受伤，因此两种原料都能被可靠检查到。与 AE2 自身的
 * 末影尘合成互不干扰：若周围同时有末影尘，AE2 合成会先消耗奇点。
 *
 * <p>不消耗 AE 能量，纯材料转换（与时间流体发生器一致）。
 */
public final class ExplosionSynthesis {

    private ExplosionSynthesis() {
    }

    /** 爆炸结算时触发（Forge 在实体伤害之前调用 Detonate，原料实体此时仍存活）。 */
    public static void onExplosionDetonate(final ExplosionEvent.Detonate event) {
        final net.minecraft.world.World world = event.getWorld();
        if (world.isRemote) {
            return;
        }
        if (!TimeBusConfig.Explosion.explosionSynthesisEnabled) {
            return;
        }
        final int singularityCost = TimeBusConfig.Explosion.singularityCost;
        final int ironCost = TimeBusConfig.Explosion.ironCost;
        final int outputCount = TimeBusConfig.Explosion.outputCount;
        if (singularityCost <= 0 || ironCost <= 0 || outputCount <= 0) {
            return;
        }

        // 在受影响实体中定位原料（支持堆叠：按数量配对循环合成）。
        EntityItem singularityEntity = null;
        EntityItem ironEntity = null;
        int singularityCount = 0;
        int ironCount = 0;
        for (final Entity e : event.getAffectedEntities()) {
            if (!(e instanceof EntityItem)) {
                continue;
            }
            final ItemStack stack = ((EntityItem) e).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (isSingularity(stack)) {
                singularityEntity = (EntityItem) e;
                singularityCount += stack.getCount();
            } else if (isIron(stack)) {
                ironEntity = (EntityItem) e;
                ironCount += stack.getCount();
            }
        }
        if (singularityEntity == null || ironEntity == null) {
            return;
        }

        // 配对合成：奇点与铁各按消耗数配对，每次产出 outputCount 个模板。
        int pairs = Math.min(singularityCount / singularityCost, ironCount / ironCost);
        int crafted = 0;
        while (pairs-- > 0 && !singularityEntity.isDead && !ironEntity.isDead) {
            if (!consume(singularityEntity, singularityCost) || !consume(ironEntity, ironCost)) {
                break;
            }
            final ItemStack output = new ItemStack(ItemTimeInscriberTemplate.ITEM, outputCount);
            final EntityItem result = new EntityItem(world,
                    singularityEntity.posX, singularityEntity.posY, singularityEntity.posZ, output);
            result.setDefaultPickupDelay();
            world.spawnEntity(result);
            crafted++;
        }
        if (crafted > 0) {
            TimeBus.LOGGER.info("Time Bus: explosion synthesis crafted {} Time Inscriber Template(s) at {}",
                    crafted, singularityEntity.getPosition());
        }
    }

    /** 从物品实体消耗 {@code amount} 个物品；数量耗尽则销毁实体。 */
    private static boolean consume(final EntityItem entity, final int amount) {
        final ItemStack stack = entity.getItem();
        if (stack.getCount() < amount) {
            return false;
        }
        stack.shrink(amount);
        if (stack.isEmpty()) {
            entity.setDead();
        }
        return true;
    }

    /** true 如果该物品是 AE2 奇点（与 {@code EntitySingularity} 的物品一致）。 */
    private static boolean isSingularity(final ItemStack stack) {
        return AEApi.instance().definitions().materials().singularity().isSameAs(stack);
    }

    /** true 如果该物品是铁锭或铁块（ore dict ingotIron / blockIron）。 */
    private static boolean isIron(final ItemStack stack) {
        return matchesAnyOre(stack, "ingotIron") || matchesAnyOre(stack, "blockIron");
    }

    private static boolean matchesAnyOre(final ItemStack stack, final String oreName) {
        for (final ItemStack ore : OreDictionary.getOres(oreName)) {
            if (OreDictionary.itemMatches(stack, ore, false)) {
                return true;
            }
        }
        return false;
    }
}
