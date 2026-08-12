package com.zhenzi233.timebus.client.handler;

import appeng.api.AEApi;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.SelectedPart;
import appeng.api.util.AEPartLocation;
import appeng.parts.BusCollisionHelper;
import appeng.parts.automation.PartExportBus;
import appeng.parts.automation.PartImportBus;
import com.zhenzi233.timebus.TimeBus;
import com.zhenzi233.timebus.item.ItemTimeWand;
import com.zhenzi233.timebus.util.AccelerateHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * 手持时间杖时的 part 加速预览。
 *
 * <p><b>为什么不用 {@code DrawBlockHighlightEvent}：</b>在 Cleanroom +
 * Kirino 渲染引擎环境下，Kirino 接管了 {@code EntityRenderer.renderWorld}，
 * outline 段（{@code ForgeHooksClient.onDrawBlockHighlight} 的调用点）对
 * multipart 方块（AE2 cable bus）不执行，导致指向 cable bus 时该事件根本
 * 不触发。改用 {@link RenderWorldLastEvent}（Kirino 路径里保留触发，每帧
 * 渲染后必然调用），并<b>自己从玩家视线发射线</b>判定目标，不依赖
 * {@code mc.objectMouseOver}（渲染过程中它可能被改写/清空）。
 *
 * <p>判定逻辑与 {@link ItemTimeWand#onItemUse} 保持一致，并补充 AE2 的
 * 精确命中定位：
 * <ol>
 *   <li>优先按命中面判定：命中面 {@code sideHit} 上存在 part（
 *       {@code getPart(world, pos, fromFacing(sideHit))} 非 null）→ 画该 part；</li>
 *   <li>否则用 {@code IPartHost.selectPartGlobal(hitVec)} 按射线命中点精确定位
 *       part（与 AE2 的 {@code RenderBlockOutlineHook} 同款）。</li>
 * </ol>
 * 高亮范围与魔杖实际互动一致：导入/导出总线画部件线框（批量传输）；
 * 其他可加速方块（熔炉、作物、AE2 机器、MM 控制器等）画整体线框，
 * 判定复用 {@code AccelerateHelper.canAccelerate}——高亮 = 点击必有效果。
 * 电缆（{@link AEPartLocation#INTERNAL}）与不可加速目标（石头、ME 接口等）跳过。
 *
 * <p>渲染方式仿照 AE2 的 {@code RenderBlockOutlineHook}：两次深度 pass
 * （一次穿透方块、一次正常遮挡），线框颜色使用时间流体主题的青色，与 AE2
 * 默认的白色选中框区分。
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = TimeBus.MOD_ID, value = Side.CLIENT)
public class WandPartPreviewRenderer {

    /** 预览线框颜色（时间流体主题青色）。 */
    private static final float COLOR_R = 0.0F;
    private static final float COLOR_G = 0.85F;
    private static final float COLOR_B = 1.0F;
    /** 外部（正常遮挡）pass 的透明度。 */
    private static final float ALPHA_OUTSIDE = 0.6F;
    /** 内部（穿透方块）pass 的透明度。 */
    private static final float ALPHA_INSIDE = 0.2F;
    /** 自发射线距离(生存模式方块交互距离,4.5 格)。 */
    private static final double RAY_DISTANCE = 4.5D;

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (player == null || mc.world == null) {
            return;
        }

        // 只有手持时间杖才显示加速预览。
        ItemStack stack = player.getHeldItemMainhand();
        if (!(stack.getItem() instanceof ItemTimeWand)) {
            return;
        }
        // 潜行右键才是加速交互;非潜行时右键走默认行为(打开 GUI 等),不显示预览。
        if (!player.isSneaking()) {
            return;
        }

        float partialTicks = event.getPartialTicks();

        // 自己从玩家视线发射线（不依赖 mc.objectMouseOver，它在渲染过程中可能被改写/清空）。
        Vec3d start = player.getPositionEyes(partialTicks);
        Vec3d look = player.getLook(partialTicks);
        Vec3d end = start.add(look.x * RAY_DISTANCE, look.y * RAY_DISTANCE, look.z * RAY_DISTANCE);
        RayTraceResult hit = mc.world.rayTraceBlocks(start, end, false, false, false);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK || hit.getBlockPos() == null) {
            return;
        }

        BlockPos pos = hit.getBlockPos();
        if (mc.world.getBlockState(pos).getBlock() == Blocks.AIR) {
            return;
        }

        // 1. 与 ItemTimeWand.onItemUse 的判定一致：命中面（sideHit）上有 part。
        AEPartLocation side = AEPartLocation.fromFacing(hit.sideHit);
        IPart part = AEApi.instance().partHelper().getPart(mc.world, pos, side);

        // 2. cable bus 的碰撞箱是 parts 并集，斜向看时 sideHit 可能不是 part 所在面，
        //    此时用 AE2 的精确命中定位（selectPartGlobal）兜底。
        if (part == null) {
            IPartHost host = AEApi.instance().partHelper().getPartHost(mc.world, pos);
            if (host != null && hit.hitVec != null) {
                SelectedPart selected = host.selectPartGlobal(hit.hitVec);
                // INTERNAL 是电缆（不占面，onItemUse 也不会加速它），跳过。
                if (selected != null && selected.part != null && selected.side != AEPartLocation.INTERNAL) {
                    part = selected.part;
                    side = selected.side;
                }
            }
        }

        // 魔杖互动判定:命中导入/导出总线 → 高亮总线部件(批量传输);
        // 其他可加速方块(熔炉、作物、AE2 机器、MM 控制器等) → 高亮整个方块。
        // 判定与 ItemTimeWand 服务端扣费前的 AccelerateHelper.canAccelerate
        // 完全一致,保证"高亮 = 点击必有效果"。
        if (part instanceof PartExportBus || part instanceof PartImportBus) {
            renderPartPreview(part, pos, side, player, partialTicks);
            return;
        }
        if (AccelerateHelper.canAccelerate(mc.world, pos)) {
            renderBlockPreview(pos, player, partialTicks);
        }
    }

    /** 可加速普通方块的整体线框预览(两次深度 pass,与 part 预览同款样式)。 */
    private static void renderBlockPreview(BlockPos pos, EntityPlayer player, float partialTicks) {
        final List<AxisAlignedBB> boxes = new ArrayList<>();
        // getSelectedBoundingBox 返回世界坐标盒:先减去 pos 转成本地盒,
        // 与 part 预览的偏移管线(offsetBoxes 再统一 offset 到相机空间)一致,
        // 否则会双重偏移把线框画到错误位置。
        final AxisAlignedBB worldBox = Minecraft.getMinecraft().world.getBlockState(pos)
                .getSelectedBoundingBox(Minecraft.getMinecraft().world, pos);
        boxes.add(worldBox.offset(-pos.getX(), -pos.getY(), -pos.getZ()));
        offsetBoxes(boxes, pos, player, partialTicks);
        renderBoxes(boxes, true);
        renderBoxes(boxes, false);
    }

    /** 渲染两次深度 pass：先穿透方块，再正常遮挡。 */
    private static void renderPartPreview(IPart part, BlockPos pos, AEPartLocation side, EntityPlayer player, float partialTicks) {
        renderPass(part, pos, side, player, partialTicks, true);
        renderPass(part, pos, side, player, partialTicks, false);
    }

    private static void renderPass(IPart part, BlockPos pos, AEPartLocation side, EntityPlayer player, float partialTicks, boolean insideBlock) {
        List<AxisAlignedBB> boxes = new ArrayList<>();
        IPartCollisionHelper helper = new BusCollisionHelper(boxes, side, player, true);
        part.getBoxes(helper);
        offsetBoxes(boxes, pos, player, partialTicks);
        renderBoxes(boxes, insideBlock);
    }

    /** 将本地盒子偏移到相机插值位置并略微放大，防止与方块表面 z-fighting。 */
    private static void offsetBoxes(List<AxisAlignedBB> boxes, BlockPos pos, EntityPlayer player, float partialTicks) {
        double dX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double dY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        double dZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        boxes.replaceAll(box -> box.offset(pos.getX() - dX, pos.getY() - dY, pos.getZ() - dZ).grow(0.002D));
    }

    /**
     * 以半透明线框绘制一组盒子。
     *
     * @param insideBlock 为 true 时关闭深度测试（可穿透方块看到），颜色更深
     */
    private static void renderBoxes(List<AxisAlignedBB> boxes, boolean insideBlock) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.glLineWidth(2.0F);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);

        if (insideBlock) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }

        float alpha = insideBlock ? ALPHA_INSIDE : ALPHA_OUTSIDE;
        for (AxisAlignedBB box : boxes) {
            RenderGlobal.drawSelectionBoundingBox(box, COLOR_R, COLOR_G, COLOR_B, alpha);
        }

        if (insideBlock) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }

        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }
}
