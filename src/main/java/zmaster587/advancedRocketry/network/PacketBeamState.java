package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * A beam is a PATH and not a segment, because something can turn it.
     *
     * <p>Two points for the ordinary beam. More where a mirror sent it back: the corner is a real
     * point on the line the beam occupies, and a bent beam drawn muzzle-to-end would be drawn
     * straight through the very plating that turned it. The cap is a wire bound and matches the
     * server's own segment budget, so a path that reaches it is drawn as far as it was resolved.</p>
     */
    private static final int MAX_POINTS = 9;

    private long gun;
    private boolean lit;
    private final List<Vec3d> path = new ArrayList<Vec3d>(2);

    public PacketBeamState() {
    }

    public static PacketBeamState of(BlockPos gun, List<Vec3d> path, boolean lit) {
        PacketBeamState packet = new PacketBeamState();
        packet.gun = gun.toLong();
        packet.lit = lit && path != null && path.size() >= 2;
        if (packet.lit) {
            for (Vec3d point : path) {
                if (point == null || packet.path.size() >= MAX_POINTS) {
                    break;
                }
                packet.path.add(point);
            }
            packet.lit = packet.path.size() >= 2;
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
        buffer.writeByte(path.size());
        for (Vec3d point : path) {
            buffer.writeDouble(point.x);
            buffer.writeDouble(point.y);
            buffer.writeDouble(point.z);
        }
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        gun = buffer.readLong();
        lit = buffer.readBoolean();
        if (!lit) {
            return;
        }
        int count = Math.min(MAX_POINTS, buffer.readUnsignedByte());
        path.clear();
        for (int i = 0; i < count; i++) {
            path.add(new Vec3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        }
        lit = path.size() >= 2;
    }

    @Override
    public void read(ByteBuf in) {
        // never sent to the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        if (lit) {
            ClientBeamTracker.lit(gun, path);
        } else {
            ClientBeamTracker.extinguished(gun);
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
