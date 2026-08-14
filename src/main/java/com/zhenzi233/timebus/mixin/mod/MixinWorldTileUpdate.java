package com.zhenzi233.timebus.mixin.mod;

import com.zhenzi233.timebus.util.TileSlowdownTable;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 时间减速总线核心拦截：让被减速的方块跳过 {@code ITickable.update()} 调用。
 *
 * <p>1.12.2 中所有 ITickable tile（熔炉/酿造台/漏斗/普通 mod 机器/MM/Mek 等）
 * 的 update 调用只有 {@code World.updateEntities} 一处入口（服务端与客户端
 * 同路径）。本 Mixin 用 @Redirect 拦截该调用点，查询
 * {@link TileSlowdownTable}——命中且本次 tick 该跳过时不调用 update
 * （燃料/进度同步冻结），否则照常调用。
 *
 * <p>与方法名混淆的处理与 {@code MixinSlotRestrictedInput} 相同：MC 方法运行期
 * 是 SRG 名、dev 是 MCP 名，两个 @Redirect 各 require=0（不匹配者静默跳过）。
 */
@Mixin(World.class)
public abstract class MixinWorldTileUpdate {

    @Unique
    private void timebus$maybeSkipTileUpdate(final ITickable tickable) {
        // 全局短路：全服没有任何减速方块时只付一次 volatile 读，跳过
        // instanceof / getWorld / 同步查表（World.updateEntities 是服务端
        // 最热的路径之一）。
        if (TileSlowdownTable.isActive() && tickable instanceof TileEntity) {
            final TileEntity te = (TileEntity) tickable;
            final World world = te.getWorld();
            if (world != null && TileSlowdownTable.shouldSkip(world, te.getPos(), world.getTotalWorldTime())) {
                return; // 被减速:跳过本次 update
            }
        }
        tickable.update();
    }

    // dev: MCP name
    @Redirect(method = "updateEntities", remap = false, require = 0,
              at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ITickable;update()V", remap = false))
    private void timebus$maybeSkipMCP(final ITickable tickable) {
        this.timebus$maybeSkipTileUpdate(tickable);
    }

    // released jar: SRG name
    @Redirect(method = "func_72939_s", remap = false, require = 0,
              at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ITickable;func_73660_a()V", remap = false))
    private void timebus$maybeSkipSRG(final ITickable tickable) {
        this.timebus$maybeSkipTileUpdate(tickable);
    }
}
