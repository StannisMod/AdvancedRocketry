package com.github.stannismod.affs.network;

import com.github.stannismod.affs.item.ItemCodeDevice;
import com.github.stannismod.affs.te.TileEntityContourInjector;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.te.TileEntityShieldConsole;
import com.github.stannismod.affs.util.CodeUtils;
import com.github.stannismod.affs.world.shield.ShieldNetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketSyncCodeValue implements IMessage {

    public static final byte TARGET_ITEM = 0;
    public static final byte TARGET_NETWORK_NODE = 1;

    private byte target;
    private int handOrdinal;
    private BlockPos pos;
    private String code;

    public PacketSyncCodeValue() {
    }

    public static PacketSyncCodeValue forItem(EnumHand hand, String code) {
        PacketSyncCodeValue packet = new PacketSyncCodeValue();
        packet.target = TARGET_ITEM;
        packet.handOrdinal = hand.ordinal();
        packet.code = CodeUtils.normalize(code);
        return packet;
    }

    public static PacketSyncCodeValue forNetworkNode(BlockPos pos, String code) {
        PacketSyncCodeValue packet = new PacketSyncCodeValue();
        packet.target = TARGET_NETWORK_NODE;
        packet.pos = pos;
        packet.code = CodeUtils.normalize(code);
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        target = buf.readByte();
        handOrdinal = buf.readInt();
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        code = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(target);
        buf.writeInt(handOrdinal);
        BlockPos targetPos = pos == null ? BlockPos.ORIGIN : pos;
        buf.writeInt(targetPos.getX());
        buf.writeInt(targetPos.getY());
        buf.writeInt(targetPos.getZ());
        ByteBufUtils.writeUTF8String(buf, code == null ? "" : code);
    }

    public static class Handler implements IMessageHandler<PacketSyncCodeValue, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncCodeValue message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                switch (message.target) {
                    case TARGET_ITEM:
                        EnumHand hand = EnumHand.values()[Math.max(0, Math.min(EnumHand.values().length - 1, message.handOrdinal))];
                        ItemStack stack = player.getHeldItem(hand);
                        if (stack.getItem() instanceof ItemCodeDevice) {
                            CodeUtils.setCode(stack, message.code);
                            player.inventory.markDirty();
                            player.openContainer.detectAndSendChanges();
                        }
                        break;
                    case TARGET_NETWORK_NODE:
                        if (!player.getServerWorld().isBlockLoaded(message.pos)) {
                            return;
                        }
                        TileEntity te = player.getServerWorld().getTileEntity(message.pos);
                        if (te instanceof TileEntityFieldGenerator) {
                            ((TileEntityFieldGenerator) te).applyNetworkCode(message.code);
                        } else if (te instanceof TileEntityShieldConsole) {
                            ((TileEntityShieldConsole) te).applyNetworkCode(message.code);
                        }
                        if (te instanceof TileEntityFieldGenerator || te instanceof TileEntityShieldConsole || te instanceof TileEntityContourInjector) {
                            ShieldNetworkManager.setNetworkCode(player.getServerWorld(), message.pos, message.code);
                        }
                        break;
                    default:
                        break;
                }
            });
            return null;
        }
    }
}
