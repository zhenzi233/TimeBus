package com.zhenzi233.timebus.mixin.mek;

import com.zhenzi233.timebus.config.TimeBusConfig;
import com.zhenzi233.timebus.util.MekanismAccelerator;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mek 热力发电机的"开挂产电"加速。
 *
 * <p>热力发电机不走异步路径（{@code supportsAsync() = false}），产电在同步的
 * {@code simulateGeneratorHeat()}（温差热转换）里 insert。与其它发电机一样，
 * 把 EXECUTE insert 的电量放大 N 倍，燃料/岩浆消耗不变，效率翻倍（刻意开挂，
 * 配置项 {@code mekGeneratorAccelerationEnabled} 控制，默认开启）。
 */
@Mixin(targets = "mekanism.generators.common.tile.TileEntityHeatGenerator")
public abstract class MixinMekanismHeatGenerator {

    @Redirect(
            method = "simulateGeneratorHeat",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/capabilities/energy/MachineEnergyContainer;insert(DLmekanism/api/Action;Lmekanism/api/AutomationType;)D"),
            remap = false)
    private double timebus$scaleInsert(final MachineEnergyContainer container, final double amount,
                                       final Action action, final AutomationType type) {
        double toInsert = amount;
        if (action == Action.EXECUTE && TimeBusConfig.Mek.mekGeneratorAccelerationEnabled) {
            final Integer speed = MekanismAccelerator.queryActive((TileEntity) (Object) this);
            if (speed != null && speed > 1) {
                toInsert = amount * speed;
            }
        }
        // @Redirect 会完全替换原调用：必须在这里手动执行 insert，否则能量
        // 永远不会被插入（机器照常运转但储能不涨）。
        return container.insert(toInsert, action, type);
    }
}
