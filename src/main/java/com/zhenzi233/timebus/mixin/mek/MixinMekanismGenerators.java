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
 * Mek 发电机"开挂产电"加速（统一注入点）。
 *
 * <p>Mek 所有发电机（风力 / 燃气 / 生物 / 太阳能 / 大型风机 / 大型燃气）最终都
 * 在 {@code onAsyncUpdateServer()}（异步线程每 tick 发电点）通过
 * {@code MachineEnergyContainer.insert(amount, EXECUTE, INTERNAL)} 插入能量；先进
 * 太阳能（AdvancedSolarGenerator）继承太阳能类，自动覆盖。把该调用点的电量参数
 * 放大 N 倍（N = 总线速度倍率），即每 tick 产电 N 倍——纯多产电、能量不守恒，
 * 刻意开挂（独立配置项 {@code mekGeneratorAccelerationEnabled}，默认开启）。
 *
 * <p>SIMULATE 调用（容量预检）保持原值，避免干扰 canOperate 判断；仅 EXECUTE
 * 放大。燃料/岩浆等消耗不受影响（效率翻倍），这正是"开挂"语义。
 *
 * <p>Mek 缺失或版本变化时由 mixin 配置（required=false）静默跳过。
 */
@Mixin(targets = {
        "mekanism.generators.common.tile.TileEntityWindGenerator",
        "mekanism.generators.common.tile.TileEntityGasGenerator",
        "mekanism.generators.common.tile.TileEntityBioGenerator",
        "mekanism.generators.common.tile.TileEntitySolarGenerator",
        "mekanism.multiblockmachine.common.tile.generator.TileEntityLargeWindGenerator",
        "mekanism.multiblockmachine.common.tile.generator.TileEntityLargeGasGenerator"
})
public abstract class MixinMekanismGenerators {

    @Redirect(
            method = "onAsyncUpdateServer",
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
