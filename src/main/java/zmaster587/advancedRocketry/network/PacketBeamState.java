package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.client.ClientBeamTracker;
import zmaster587.libVulpes.network.BasePacket;

/**
 * Server&rarr;client: a gun's beam is burning between these two points, or it has gone out.
 *
 * <h3>One packet for a state, not for an event</h3>
 * <p>A beam has no launch and no impact to announce — it is a line that exists while a trigger is
 * held, so what travels is the line itself. The gun's own position is the key: a gun holds at most
 * one beam, which makes "this gun's beam" the whole identity and saves inventing ids for something
 * with no lifetime worth naming.</p>
 *
 * <p>The endpoints are written only when it is lit. Going out is the common case for the smaller
 * packet, and a client being told "not burning" has no use for coordinates.</p>
 *
 * <h3>It is a drawing, and it is honest about that</h3>
 * <p>Nothing the client does with this affects the game: no damage is resolved from it, nothing
 * reads it back, and a player who received none of them plays the same game — worse-looking, not
 * different.</p>
 */
public class PacketBeamState extends BasePacket {

    private long gun;
    private boolean lit;
    private double fromX, fromY, fromZ;
    private double toX, toY, toZ;

    public PacketBeamState() {
    }

    public static PacketBeamState of(BlockPos gun, Vec3d from, Vec3d to, boolean lit) {
        PacketBeamState packet = new PacketBeamState();
        packet.gun = gun.toLong();
        packet.lit = lit && from != null && to != null;
        if (packet.lit) {
            packet.fromX = from.x;
            packet.fromY = from.y;
            packet.fromZ = from.z;
            packet.toX = to.x;
            packet.toY = to.y;
            packet.toZ = to.z;
        }
        return packet;
    }

    @Override
    public void write(ByteBuf out) {
        PacketBuffer buffer = new PacketBuffer(out);
        buffer.writeLong(gun);
        buffer.writeBoolean(lit);
        if (!lit) {
            return;
        }
        buffer.writeDouble(fromX);
        buffer.writeDouble(fromY);
        buffer.writeDouble(fromZ);
        buffer.writeDouble(toX);
        buffer.writeDouble(toY);
        buffer.writeDouble(toZ);
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        gun = buffer.readLong();
        lit = buffer.readBoolean();
        if (!lit) {
            return;
        }
        fromX = buffer.readDouble();
        fromY = buffer.readDouble();
        fromZ = buffer.readDouble();
        toX = buffer.readDouble();
        toY = buffer.readDouble();
        toZ = buffer.readDouble();
    }

    @Override
    public void read(ByteBuf in) {
        // never sent to the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        if (lit) {
            ClientBeamTracker.lit(gun, new Vec3d(fromX, fromY, fromZ), new Vec3d(toX, toY, toZ));
        } else {
            ClientBeamTracker.extinguished(gun);
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
