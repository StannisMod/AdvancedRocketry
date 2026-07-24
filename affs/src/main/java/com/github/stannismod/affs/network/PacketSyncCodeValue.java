package com.github.stannismod.affs.network;

import com.github.stannismod.affs.item.ItemCodeDevice;
import com.github.stannismod.affs.util.CodeUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client -> server sync of the access code carried on a held {@link ItemCodeDevice}. The code is then
 * stamped onto a shield node by using the device on it (Layer-3 credential; grouping is topology-only).
 */
public class PacketSyncCodeValue implements IMessage {

    private int handOrdinal;
    private String code;

    public PacketSyncCodeValue() {
    }

    public static PacketSyncCodeValue forItem(EnumHand hand, String code) {
        PacketSyncCodeValue packet = new PacketSyncCodeValue();
        packet.handOrdinal = hand.ordinal();
        packet.code = CodeUtils.normalize(code);
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        handOrdinal = buf.readInt();
        code = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(handOrdinal);
        ByteBufUtils.writeUTF8String(buf, code == null ? "" : code);
    }

    public static class Handler implements IMessageHandler<PacketSyncCodeValue, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncCodeValue message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                EnumHand hand = EnumHand.values()[Math.max(0, Math.min(EnumHand.values().length - 1, message.handOrdinal))];
                ItemStack stack = player.getHeldItem(hand);
                if (stack.getItem() instanceof ItemCodeDevice) {
                    CodeUtils.setCode(stack, message.code);
                    player.inventory.markDirty();
                    player.openContainer.detectAndSendChanges();
                }
            });
            return null;
        }
    }
}
