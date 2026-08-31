package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.api.projectile.ShotEndReason;
import zmaster587.advancedRocketry.client.ClientShotTracker;
import zmaster587.libVulpes.network.BasePacket;

/**
 * Server&rarr;client: a round stopped here, for this reason.
 *
 * <p>The point matters as much as the fact. A client stepping its own copy of a flight has no way to
 * know a shell absorbed it or a hull stopped it — those are decisions taken in a world the client
 * does not simulate — so without this the round would sail on through the thing it hit, and the
 * player would watch a miss that was actually a kill. The reason travels with it because an impact
 * on a hull and an absorption at a shield want different effects, and deciding which is which from
 * the position alone is guesswork.</p>
 */
public class PacketShotEnd extends BasePacket {

    private long id;
    private double x, y, z;
    private byte reason;

    public PacketShotEnd() {
    }

    public static PacketShotEnd of(long id, Vec3d point, ShotEndReason reason) {
        PacketShotEnd packet = new PacketShotEnd();
        packet.id = id;
        packet.x = point.x;
        packet.y = point.y;
        packet.z = point.z;
        packet.reason = (byte) reason.ordinal();
        return packet;
    }

    @Override
    public void write(ByteBuf out) {
        PacketBuffer buffer = new PacketBuffer(out);
        buffer.writeLong(id);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeByte(reason);
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        id = buffer.readLong();
        x = buffer.readDouble();
        y = buffer.readDouble();
        z = buffer.readDouble();
        reason = buffer.readByte();
    }

    @Override
    public void read(ByteBuf in) {
        // never sent to the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        ShotEndReason[] reasons = ShotEndReason.values();
        ShotEndReason ended = reason >= 0 && reason < reasons.length ? reasons[reason]
                : ShotEndReason.EXPIRED;
        ClientShotTracker.end(id, new Vec3d(x, y, z), ended);
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
