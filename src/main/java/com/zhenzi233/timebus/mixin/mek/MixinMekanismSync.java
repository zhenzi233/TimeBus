package com.zhenzi233.timebus.mixin.mek;

import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.util.MekanismAccelerator;
import io.netty.buffer.ByteBuf;
import mekanism.api.TileNetworkList;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Mek 连拍加速的耗能显示同步（仅显示，不改实际扣电）。
 *
 * <p>连拍加速下机器每 tick 实际耗电 = N × energyPerTick，但 Mek 机器 GUI
 * 的 "Using" 读的是 tile 的 {@code energyPerTick} 字段（基础值），显示偏低。
 * 本 Mixin 在 Mek 自带的网络同步通道（getNetworkedData / handlePacketData）
 * 末尾附带一个 int speed：
 * <ul>
 *   <li>服务端发包时追加当前加速倍率（无加速 = 1）；</li>
 *   <li>客户端收包时读到倍率，把显示用的 {@code energyPerTick} 字段乘以倍率。</li>
 * </ul>
 * 实际扣电走服务端 CachedRecipe 的 perTickEnergy supplier（服务端字段未改），
 * 因此只影响 GUI 显示，玩家可以看到加速导致的耗电变化。
 */
@Mixin(targets = "mekanism.common.tile.prefab.TileEntityBasicBlock")
public abstract class MixinMekanismSync {

    /** energyPerTick 字段（TileEntityMachine，public），反射访问避免 import 继承链上的 IC2 类型。 */
    private static final Field ENERGY_PER_TICK = resolveEnergyPerTick();

    private static Field resolveEnergyPerTick() {
        try {
            return Class.forName("mekanism.common.tile.prefab.TileEntityMachine").getField("energyPerTick");
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: could not resolve Mek energyPerTick field, display scaling disabled: {}", e.toString());
            return null;
        }
    }

    /** 服务端发包：在数据流末尾追加当前加速倍率（未加速 = 1）。 */
    @Inject(method = "getNetworkedData", at = @At("RETURN"), remap = false)
    private void timebus$appendSpeed(final TileNetworkList data, final CallbackInfoReturnable<TileNetworkList> cir) {
        if (((TileEntity) (Object) this).getWorld().isRemote) {
            return;
        }
        final Integer speed = MekanismAccelerator.queryActive((TileEntity) (Object) this);
        data.add(speed == null || speed <= 1 ? 1 : speed);
    }

    /** 客户端收包：读倍率，把显示用的 energyPerTick 字段乘以倍率（仅显示，不改实际扣电）。 */
    @Inject(method = "handlePacketData", at = @At("RETURN"), remap = false)
    private void timebus$scaleDisplayEnergy(final ByteBuf dataStream, final CallbackInfo ci) {
        if (!((TileEntity) (Object) this).getWorld().isRemote || ENERGY_PER_TICK == null || dataStream.readableBytes() < 4) {
            return;
        }
        final int speed = dataStream.readInt();
        if (speed <= 1) {
            return;
        }
        try {
            final double base = ENERGY_PER_TICK.getDouble(this);
            ENERGY_PER_TICK.setDouble(this, base * speed);
        } catch (Exception e) {
            TimeBus.LOGGER.debug("Time Bus: could not scale Mek energy display at {}: {}",
                    ((TileEntity) (Object) this).getPos(), e.toString());
        }
    }
}
