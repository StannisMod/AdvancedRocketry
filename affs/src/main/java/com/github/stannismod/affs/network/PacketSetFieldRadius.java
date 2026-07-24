package com.github.stannismod.affs.network;

import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketSetFieldRadius implements IMessage {

    private BlockPos pos;
    private int radius;

    public PacketSetFieldRadius() {
    }

    public PacketSetFieldRadius(BlockPos pos, int radius) {
        this.pos = pos;
        this.radius = radius;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        radius = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeInt(radius);
    }

    public static class Handler implements IMessageHandler<PacketSetFieldRadius, IMessage> {
        @Override
        public IMessage onMessage(PacketSetFieldRadius message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!player.getServerWorld().isBlockLoaded(message.pos)) {
                    return;
                }

                TileEntity te = player.getServerWorld().getTileEntity(message.pos);
                if (te instanceof TileEntityFieldGenerator) {
                    // Сервер валидирует радиус и пересобирает поле сам.
                    ((TileEntityFieldGenerator) te).setRadius(message.radius);
                }
            });
            return null;
        }
    }
}
