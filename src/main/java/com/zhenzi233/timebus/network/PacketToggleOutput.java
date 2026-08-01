package com.zhenzi233.timebus.network;

import com.zhenzi233.timebus.client.gui.ContainerTimeGenerator;
import com.zhenzi233.timebus.tile.TileTimeGenerator;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Toggles the Time Fluid Generator's input mode between Matter Balls and
 * Singularity. Unlike AE2's PacketConfigButton this never cycles through the
 * TRASH (destroy) mode, so the generator only ever has the two real modes.
 */
public class PacketToggleOutput implements IMessage {

    public PacketToggleOutput() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // no payload
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // no payload
    }

    public static class Handler implements IMessageHandler<PacketToggleOutput, IMessage> {
        @Override
        public IMessage onMessage(PacketToggleOutput message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            if (player != null && player.openContainer instanceof ContainerTimeGenerator) {
                final TileTimeGenerator generator = ((ContainerTimeGenerator) player.openContainer).getGenerator();
                if (generator != null) {
                    generator.cycleOutput();
                }
            }
            return null;
        }
    }
}
