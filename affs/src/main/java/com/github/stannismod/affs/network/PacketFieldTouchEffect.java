package com.github.stannismod.affs.network;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.client.ClientFieldTouchEffectCache;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketFieldTouchEffect implements IMessage {

    private int dimension;
    private BlockPos generatorPos;
    private double touchX;
    private double touchY;
    private double touchZ;

    public PacketFieldTouchEffect() {
    }

    public static PacketFieldTouchEffect of(World world, BlockPos generatorPos, Vec3d touchPoint) {
        PacketFieldTouchEffect packet = new PacketFieldTouchEffect();
        packet.dimension = world.provider.getDimension();
        packet.generatorPos = generatorPos;
        packet.touchX = touchPoint.x;
        packet.touchY = touchPoint.y;
        packet.touchZ = touchPoint.z;
        return packet;
    }

    public static void send(World world, BlockPos generatorPos, Vec3d touchPoint) {
        if (world == null || world.isRemote) {
            return;
        }
        BlockPos safePos = generatorPos == null ? BlockPos.ORIGIN : generatorPos;
        AdvancedForceFieldSystem.NETWORK.sendToAllAround(
            of(world, safePos, touchPoint == null ? new Vec3d(safePos.getX(), safePos.getY(), safePos.getZ()) : touchPoint),
            new NetworkRegistry.TargetPoint(
                world.provider.getDimension(),
                safePos.getX() + 0.5D,
                safePos.getY() + 0.5D,
                safePos.getZ() + 0.5D,
                32.0D
            )
        );
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimension = buf.readInt();
        generatorPos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        touchX = buf.readDouble();
        touchY = buf.readDouble();
        touchZ = buf.readDouble();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        BlockPos safePos = generatorPos == null ? BlockPos.ORIGIN : generatorPos;
        buf.writeInt(safePos.getX());
        buf.writeInt(safePos.getY());
        buf.writeInt(safePos.getZ());
        buf.writeDouble(touchX);
        buf.writeDouble(touchY);
        buf.writeDouble(touchZ);
    }

    public static class Handler implements IMessageHandler<PacketFieldTouchEffect, IMessage> {

        @Override
        public IMessage onMessage(PacketFieldTouchEffect message, MessageContext ctx) {
            if (!ctx.side.isClient()) {
                return null;
            }
            Minecraft.getMinecraft().addScheduledTask(() -> ClientFieldTouchEffectCache.addEffect(
                message.dimension,
                message.generatorPos,
                new Vec3d(message.touchX, message.touchY, message.touchZ),
                Minecraft.getMinecraft().world == null ? -1L : Minecraft.getMinecraft().world.getTotalWorldTime()
            ));
            return null;
        }
    }
}
