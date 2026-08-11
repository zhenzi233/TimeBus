package com.zhenzi233.timebus.mixin.mek;

import com.zhenzi233.timebus.util.MekanismAccelerator;
import mekanism.api.TileNetworkList;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mek 连拍加速的耗能显示同步（仅显示，不改实际扣电）。
 *
 * <p>连拍加速下机器每 tick 实际耗电 = N × energyPerTick，但 Mek 机器 GUI
 * 的 "Using" 读的是 tile 的 {@code energyPerTick} 字段（基础值），显示偏低。
 * {@code energyPerTick} 本来就在 Mek 的网络同步数据里（
 * TileEntityMachine.getNetworkedData 发送、客户端 handlePacketData 读取），
 * 本 Mixin 直接在服务端发包时把该值乘上当前加速倍率（ordinal=1 的
 * TileNetworkList.add 调用点，即 energyPerTick），客户端读到即加速值：
 * <ul>
 *   <li>不改协议（仍是 double，长度不变）；</li>
 *   <li>不改服务端字段（实际扣电仍走基础值，总耗电守恒）；</li>
 *   <li>客户端零改动，且不会被外层 handlePacketData 覆盖（旧方案在 BasicBlock
 *       改字段会被 Machine.handlePacketData 读回基础值覆盖）。</li>
 * </ul>
 */
@Mixin(targets = "mekanism.common.tile.prefab.TileEntityMachine")
public abstract class MixinMekanismSync {

    /** 服务端发包：把 energyPerTick 的同步值乘上当前加速倍率（无加速 = 原值）。 */
    @Redirect(
            method = "getNetworkedData",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/api/TileNetworkList;add(Ljava/lang/Object;)Z",
                    ordinal = 1),
            remap = false)
    private boolean timebus$scaleSyncEnergy(final TileNetworkList data, final Object value) {
        if (value instanceof Double) {
            final Integer speed = MekanismAccelerator.queryActive((TileEntity) (Object) this);
            if (speed != null && speed > 1) {
                return data.add(((Double) value) * speed);
            }
        }
        return data.add(value);
    }
}
