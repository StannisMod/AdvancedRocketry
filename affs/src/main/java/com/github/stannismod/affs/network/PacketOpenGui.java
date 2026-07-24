package com.github.stannismod.affs.network;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketOpenGui implements IMessage {

    private int guiId;
    private BlockPos pos;

    public PacketOpenGui() {
    }

    public static PacketOpenGui forBlock(int guiId, BlockPos pos) {
        PacketOpenGui packet = new PacketOpenGui();
        packet.guiId = guiId;
        packet.pos = pos;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        guiId = buf.readInt();
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(guiId);
        BlockPos targetPos = pos == null ? BlockPos.ORIGIN : pos;
        buf.writeInt(targetPos.getX());
        buf.writeInt(targetPos.getY());
        buf.writeInt(targetPos.getZ());
    }

    public static class Handler implements IMessageHandler<PacketOpenGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenGui message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> AdvancedForceFieldSystem.openAffsGui(
                    player,
                    message.guiId,
                    player.getServerWorld(),
                    message.pos == null ? 0 : message.pos.getX(),
                    message.pos == null ? 0 : message.pos.getY(),
                    message.pos == null ? 0 : message.pos.getZ()
            ));
            return null;
        }
    }
}
