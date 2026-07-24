package com.github.stannismod.affs.network;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.client.ClientForceFieldRenderCache;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.world.FieldSource;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class PacketSyncActiveGenerators implements IMessage {

    private int dimension;
    private final List<Entry> entries = new ArrayList<>();

    public PacketSyncActiveGenerators() {
    }

    public static PacketSyncActiveGenerators fromWorld(World world) {
        PacketSyncActiveGenerators packet = new PacketSyncActiveGenerators();
        packet.dimension = world.provider.getDimension();
        int dimension = packet.dimension;
        for (TileEntityFieldGenerator generator : TileEntityFieldGenerator.getActiveGenerators()) {
            if (generator == null || generator.isInvalid() || !generator.isFieldPowered()) {
                continue;
            }
            World generatorWorld = generator.getWorld();
            if (generatorWorld == null || generatorWorld.provider.getDimension() != dimension) {
                continue;
            }
            packet.entries.add(new Entry(generator.getPos(), generator.getRadius()));
        }
        return packet;
    }

    public static void sendFullSnapshot(World world) {
        if (world == null || world.isRemote) {
            return;
        }
        AdvancedForceFieldSystem.NETWORK.sendToDimension(fromWorld(world), world.provider.getDimension());
    }

    public static void sendFullSnapshotToPlayer(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        World world = player.getServerWorld();
        if (world == null || world.isRemote) {
            return;
        }
        AdvancedForceFieldSystem.NETWORK.sendTo(fromWorld(world), player);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimension = buf.readInt();
        entries.clear();
        int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            int radius = buf.readInt();
            entries.add(new Entry(pos, radius));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeInt(entries.size());
        for (Entry entry : entries) {
            buf.writeInt(entry.pos.getX());
            buf.writeInt(entry.pos.getY());
            buf.writeInt(entry.pos.getZ());
            buf.writeInt(entry.radius);
        }
    }

    public static class Handler implements IMessageHandler<PacketSyncActiveGenerators, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncActiveGenerators message, MessageContext ctx) {
            if (!ctx.side.isClient()) {
                return null;
            }
            Minecraft.getMinecraft().addScheduledTask(() -> ClientForceFieldRenderCache.replaceSnapshot(message.dimension, message.entries));
            return null;
        }
    }

    public static final class Entry implements FieldSource {
        private final BlockPos pos;
        private final int radius;

        public Entry(BlockPos pos, int radius) {
            this.pos = pos;
            this.radius = radius;
        }

        @Override
        public BlockPos getPos() {
            return pos;
        }

        @Override
        public int getRadius() {
            return radius;
        }
    }
}
