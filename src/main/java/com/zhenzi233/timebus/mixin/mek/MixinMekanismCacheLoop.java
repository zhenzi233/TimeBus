package com.zhenzi233.timebus.mixin.mek;

import com.zhenzi233.timebus.util.MekanismAccelerator;
import mekanism.common.recipe.cache.CachedRecipe;
import mekanism.common.recipe.cache.IRecipeLookupHandler;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mek 连拍加速（统一层，覆盖所有走 CachedRecipe 配方推进的机器）。
 *
 * <p>Mek 的配方时长由缓存字段 {@code ticksRequired} 决定，只在升级重算
 * （recalculateUpgradables）时更新，虚拟速度卡在稳态下无法生效。改用连拍：
 * 在 {@code RecipeCacheLookupMonitor.updateAndProcess()} 的
 * {@code CachedRecipe.process()} 调用点循环执行 N 次（N = 活跃表登记的
 * speed），每次 process 推进 1 刻，即每 tick 推进 N 刻。能耗随推进次数
 * 同倍放大，单次配方总耗电守恒。
 *
 * <p>选择这一层而非逐台机器（ElectricMachine/ChanceMachine/化学机器等）：
 * 所有"配方推进"机器（普通电机器、机会机、双输入机、高级机、化学系列、
 * PRC、冶金灌注机、工厂的每个工艺槽、旋转冷凝机、太阳能中子活化机、
 * 环境蓄能器、热蒸发控制器等）最终都走这里，一个注入点覆盖全部，且避免
 * 多级 onAsyncUpdateServer super 链导致的重复推进。
 *
 * <p>已知特例：反质子核合成机的 monitor 覆盖了带参 updateAndProcess 并自己
 * 直接调用了一次 process()，该机器实际推进 N+1 次（可接受）。
 *
 * <p>Mek 缺失或版本变化时由 mixin 配置（required=false）静默跳过。
 */
@Mixin(targets = "mekanism.common.recipe.cache.RecipeCacheLookupMonitor")
public abstract class MixinMekanismCacheLoop {

    /** RecipeCacheLookupMonitor 持有的配方查找 handler（实际就是机器 tile）。 */
    @Shadow(remap = false)
    private IRecipeLookupHandler handler;

    @Redirect(
            method = "updateAndProcess()Z",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/recipe/cache/CachedRecipe;process()V"),
            remap = false)
    private void timebus$loopProcess(final CachedRecipe recipe) {
        final Integer speed = MekanismAccelerator.queryActive((TileEntity) (Object) handler);
        final int times = (speed != null && speed > 1) ? speed : 1;
        for (int i = 0; i < times; i++) {
            recipe.process();
        }
    }
}
