package com.zhenzi233.timebus.mixin.mek;

import com.zhenzi233.timebus.config.TimeBusConfig;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.util.MekanismAccelerator;
import mekanism.common.Upgrade;
import mekanism.common.base.IUpgradeTile;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mek 虚拟速度卡注入。
 *
 * <p>Mek 的配方时长与能耗都按 {@code MekanismUtils.fractionUpgrades(tile, SPEED)}
 * 计算（ticks = base * M^(-fraction)）。本 Mixin 在 {@code fractionUpgrades} 内
 * 拦截 {@code getInstalledUpgrades(SPEED)} 调用点，当机器正被 TimeBus 加速时
 * 返回"真实卡数 + 虚拟卡数"，让机器按官方公式自行加速。能耗会随虚拟卡按官方
 * 速度卡公式上升（每 tick M^(2Δ)、单次配方总耗电 M^Δ），与真实速度卡体验一致。
 *
 * <p>该 Mixin 只对 MekanismUtils 生效（targets 字符串），Mek 缺失或版本变化时
 * 由 mixin 配置（required=false）静默跳过。
 */
@Mixin(targets = "mekanism.common.util.MekanismUtils")
public abstract class MixinMekanismUtils {

    @Redirect(
            method = "fractionUpgrades",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/common/base/IUpgradeTile;getInstalledUpgrades(Lmekanism/common/Upgrade;)I"),
            remap = false)
    private int timebus$addVirtualSpeedCards(final IUpgradeTile tile, final Upgrade upgrade) {
        final int base = tile.getInstalledUpgrades(upgrade);
        if (upgrade == Upgrade.SPEED && TimeBusConfig.mekAccelerationEnabled) {
            final Integer speed = MekanismAccelerator.queryActive((TileEntity) tile);
            if (speed != null && speed > 1) {
                final int virtual = MekanismAccelerator.virtualCards(speed);
                // 验证用日志（debug 级别，latest.log 不刷屏；验证完成后移除）。
                TimeBus.LOGGER.debug("Time Bus: Mek mixin hit: base speed cards={}, virtual={}, total={} at {}",
                        base, virtual, base + virtual, ((TileEntity) tile).getPos());
                return base + virtual;
            }
        }
        return base;
    }
}
