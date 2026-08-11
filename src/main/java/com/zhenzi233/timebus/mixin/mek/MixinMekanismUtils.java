/*
 * 虚拟速度卡方案（已暂停，改用 MixinMekanismCacheLoop 连拍加速）。
 *
 * <p>原理：Mek 的配方时长与能耗按 MekanismUtils.fractionUpgrades(tile, SPEED)
 * 计算（ticks = base * M^(-fraction)）。本 Mixin 在 fractionUpgrades 内拦截
 * getInstalledUpgrades(SPEED) 调用点，当机器正被 TimeBus 加速时返回
 * "真实卡数 + 虚拟卡数"，让机器按官方公式自行加速。
 *
 * <p>暂停原因：Mek CE 的配方时长实际由缓存字段 ticksRequired 决定，只在
 * recalculateUpgradables（升级变化/存档读取/网络同步）时用 fractionUpgrades
 * 重算；加速期间没有路径触发重算，虚拟卡在稳态下不生效。
 *
 * <p>注意：MekanismAccelerator.virtualCards 等辅助方法已在 v1.0.12 随旧方案
 * 一并删除，下方注释代码仅作历史记录，不能直接恢复运行。
 *
 * <p>恢复方法：把本文件恢复为正常类，并在 timebus.mek.mixin.json 的 mixins
 * 列表里同时注册 MixinMekanismUtils 与 MixinMekanismCacheLoop。
 */
//package com.zhenzi233.timebus.mixin.mek;
//
//import com.zhenzi233.timebus.config.TimeBusConfig;
//import com.zhenzi233.timebus.util.MekanismAccelerator;
//import mekanism.common.Upgrade;
//import mekanism.common.base.IUpgradeTile;
//import net.minecraft.tileentity.TileEntity;
//import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.injection.At;
//import org.spongepowered.asm.mixin.injection.Redirect;
//
///**
// * Mek 虚拟速度卡注入。
// *
// * <p>Mek 的配方时长与能耗都按 {@code MekanismUtils.fractionUpgrades(tile, SPEED)}
// * 计算（ticks = base * M^(-fraction)）。本 Mixin 在 {@code fractionUpgrades} 内
// * 拦截 {@code getInstalledUpgrades(SPEED)} 调用点，当机器正被 TimeBus 加速时
// * 返回"真实卡数 + 虚拟卡数"，让机器按官方公式自行加速。能耗会随虚拟卡按官方
// * 速度卡公式上升（每 tick M^(2Δ)、单次配方总耗电 M^Δ），与真实速度卡体验一致。
// *
// * <p>该 Mixin 只对 MekanismUtils 生效（targets 字符串），Mek 缺失或版本变化时
// * 由 mixin 配置（required=false）静默跳过。
// */
//@Mixin(targets = "mekanism.common.util.MekanismUtils")
//public abstract class MixinMekanismUtils {
//
//    @Redirect(
//            method = "fractionUpgrades",
//            at = @At(value = "INVOKE",
//                    target = "Lmekanism/common/base/IUpgradeTile;getInstalledUpgrades(Lmekanism/common/Upgrade;)I"),
//            remap = false)
//    // fractionUpgrades 是静态方法，@Redirect 回调必须同为 static，
//    // 否则 Mixin 抛 InvalidInjectionException 导致整个注入失败。
//    private static int timebus$addVirtualSpeedCards(final IUpgradeTile tile, final Upgrade upgrade) {
//        final int base = tile.getInstalledUpgrades(upgrade);
//        if (upgrade == Upgrade.SPEED && TimeBusConfig.Mek.mekAccelerationEnabled) {
//            final Integer speed = MekanismAccelerator.queryActive((TileEntity) tile);
//            if (speed != null && speed > 1) {
//                return base + MekanismAccelerator.virtualCards(speed);
//            }
//        }
//        return base;
//    }
//}
