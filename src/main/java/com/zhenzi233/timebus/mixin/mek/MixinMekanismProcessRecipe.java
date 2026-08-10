package com.zhenzi233.timebus.mixin.mek;

import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.util.MekanismAccelerator;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Method;

/**
 * Mek 连拍加速（试验版，替代虚拟速度卡方案）。
 *
 * <p>Mek CE 的配方时长由缓存字段 {@code ticksRequired} 决定，只在升级重算
 * （recalculateUpgradables）时更新，虚拟速度卡在稳态下无法生效。改用连拍：
 * 拦截 {@code TileEntityElectricMachine.onAsyncUpdateServer} 里的
 * {@code processRecipe()} 调用点，当机器正被 TimeBus 加速时循环调用 N 次
 * （N = 活跃表登记的 speed），每次 process 推进 1 刻，即每 tick 推进 N 刻。
 * 能耗随推进次数同倍放大，单次配方总耗电守恒。
 *
 * <p>目前只覆盖普通电机器（ElectricMachine 子类中未覆盖 onAsyncUpdateServer
 * 的机器）；工厂/化学机器等有各自的实现，后续按需扩展。
 *
 * <p>Mixin 类内不 import Mek 类型（targets 字符串 + 反射调 processRecipe），
 * Mek 缺失或版本变化时由 mixin 配置（required=false）静默跳过。
 */
@Mixin(targets = "mekanism.common.tile.prefab.TileEntityElectricMachine")
public abstract class MixinMekanismProcessRecipe {

    /** processRecipe()V 的反射句柄（protected，跨包不可直接调用）。 */
    private static final Method PROCESS_RECIPE = resolveProcessRecipe();

    private static Method resolveProcessRecipe() {
        try {
            final Method m = Class.forName("mekanism.common.tile.prefab.TileEntityBasicMachine")
                    .getDeclaredMethod("processRecipe");
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: could not resolve Mek processRecipe, acceleration disabled: {}", e.toString());
            return null;
        }
    }

    @Redirect(
            method = "onAsyncUpdateServer",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/tile/prefab/TileEntityElectricMachine;processRecipe()V"),
            remap = false)
    // @Redirect handler 必须把调用目标实例作为第一个参数（即使原方法无参）；
    // 参数类型需与目标精确匹配，用 @Coerce 允许 MC 基类 TileEntity 通过可赋值检查，
    // 避免 import 精确 Mek 类型（其继承链依赖 IC2 API，不在编译 classpath 上）。
    private void timebus$loopProcessRecipe(final @Coerce TileEntity tile) {
        final Integer speed = MekanismAccelerator.queryActive(tile);
        final int times = (speed != null && speed > 1) ? speed : 1;
        if (PROCESS_RECIPE == null) {
            TimeBus.LOGGER.warn("Time Bus: Mek processRecipe handle missing, acceleration skipped");
            return;
        }
        for (int i = 0; i < times; i++) {
            try {
                PROCESS_RECIPE.invoke(tile);
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: Mek processRecipe acceleration failed: {}", e.toString());
                break;
            }
        }
    }
}
