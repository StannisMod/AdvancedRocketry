package com.github.stannismod.affs.network;

import com.github.stannismod.affs.te.TileEntityShieldConsole;
import io.netty.buffer.ByteBuf;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketSetShieldResistanceBias implements IMessage {

    private BlockPos pos;
    private double bias;

    public PacketSetShieldResistanceBias() {
    }

    public static PacketSetShieldResistanceBias forConsole(BlockPos pos, double bias) {
        PacketSetShieldResistanceBias packet = new PacketSetShieldResistanceBias();
        packet.pos = pos;
        packet.bias = bias;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        bias = buf.readDouble();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        BlockPos safePos = pos == null ? BlockPos.ORIGIN : pos;
        buf.writeInt(safePos.getX());
        buf.writeInt(safePos.getY());
        buf.writeInt(safePos.getZ());
        buf.writeDouble(bias);
    }

    public static class Handler implements IMessageHandler<PacketSetShieldResistanceBias, IMessage> {

        @Override
        public IMessage onMessage(PacketSetShieldResistanceBias message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            ctx.getServerHandler().player.getServerWorld().addScheduledTask(() -> {
                World world = ctx.getServerHandler().player.world;
                if (world == null) {
                    return;
                }
                TileEntity te = world.getTileEntity(message.pos);
                if (te instanceof TileEntityShieldConsole) {
                    ((TileEntityShieldConsole) te).applyShieldEnergyResistanceBias(message.bias);
                }
            });
            return null;
        }
    }
}
