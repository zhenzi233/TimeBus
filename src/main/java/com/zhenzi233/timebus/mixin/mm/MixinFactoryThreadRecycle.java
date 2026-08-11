package com.zhenzi233.timebus.mixin.mm;

import com.zhenzi233.timebus.config.TimeBusConfig;
import com.zhenzi233.timebus.util.ModularMachineryAccelerator;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 被时间总线/时间杖加速的 MM 工厂控制器不回收空闲线程。
 *
 * <p>MM 的 {@code TileFactoryController.cleanIdleTimeoutThread()} 每 20 tick
 * 回收 idleTime 达到 200（约 10 秒）的额外线程，回收时会调用
 * {@code FactoryRecipeThread.invalidate()} 清空 permanentModifiers（其中包含
 * TimeBus 注入的加速 modifier），导致该线程短暂跌回原速、直到 TimeBus 下个
 * tick 重新注入。本 Mixin 在控制器仍被加速且配置开启时直接跳过回收，
 * 让额外线程常驻复用。
 *
 * <p>该 Mixin 只对 MM 工厂控制器生效（targets 字符串），MM 缺失或版本变化时
 * 由 mixin 配置（required=false）静默跳过，不影响 TimeBus 其他功能。
 */
@Mixin(targets = "hellfirepvp.modularmachinery.common.tiles.TileFactoryController")
public abstract class MixinFactoryThreadRecycle {

    // MM 是第三方 mod（不混淆），方法名在运行期就是 MCP 名；remap=false
    // 让注解处理器跳过混淆映射查找，否则它会因找不到映射而报错。
    @Inject(method = "cleanIdleTimeoutThread", at = @At("HEAD"), cancellable = true, remap = false)
    private void timebus$keepThreadsWhileAccelerated(final CallbackInfo ci) {
        if (TimeBusConfig.MM.mmKeepThreadsEnabled
                && ModularMachineryAccelerator.isAccelerated((TileEntity) (Object) this)) {
            ci.cancel();
        }
    }
}
