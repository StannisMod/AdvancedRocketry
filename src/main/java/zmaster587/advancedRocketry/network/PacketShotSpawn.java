package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.api.projectile.ShotSpec;
import zmaster587.advancedRocketry.client.ClientShotTracker;
import zmaster587.libVulpes.network.BasePacket;

/**
 * Server&rarr;client: a round has left a muzzle, and here is everything needed to draw its whole
 * flight.
 *
 * <h3>One packet per shot, not one per tick</h3>
 * <p>A shot's path is completely determined by where it started, how fast it was going and what acts
 * on it — the server integrates exactly that and nothing else. So the client is told once and steps
 * its own copy with the same arithmetic, instead of being sent a position twenty times a second for
 * a minute of flight. A round that is deflected or stopped early gets a
 * {@link PacketShotEnd}; until one arrives, the client's copy is right because it is running the
 * same integration on the same numbers.</p>
 *
 * <h3>It is a drawing, and it is honest about that</h3>
 * <p>Nothing the client does with this affects the game: the client has no registry, steps nothing
 * the server reads back, and resolves no hit. A client that never received one of these plays the
 * same game — worse-looking, not different.</p>
 */
public class PacketShotSpawn extends BasePacket {

    private long id;
    private double x, y, z;
    private double vx, vy, vz;
    private float radius;
    private int lifetimeTicks;
    private double gravityPerTickSquared;

    public PacketShotSpawn() {
    }

    public static PacketShotSpawn of(long id, ShotSpec spec) {
        PacketShotSpawn packet = new PacketShotSpawn();
        packet.id = id;
        Vec3d origin = spec.getOrigin();
        Vec3d velocity = spec.getVelocity();
        packet.x = origin.x;
        packet.y = origin.y;
        packet.z = origin.z;
        packet.vx = velocity.x;
        packet.vy = velocity.y;
        packet.vz = velocity.z;
        packet.radius = (float) spec.getRadius();
        packet.lifetimeTicks = spec.getLifetimeTicks();
        packet.gravityPerTickSquared = spec.getEnvironment().getGravityPerTickSquared();
        return packet;
    }

    @Override
    public void write(ByteBuf out) {
        PacketBuffer buffer = new PacketBuffer(out);
        buffer.writeLong(id);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeDouble(vx);
        buffer.writeDouble(vy);
        buffer.writeDouble(vz);
        buffer.writeFloat(radius);
        buffer.writeInt(lifetimeTicks);
        buffer.writeDouble(gravityPerTickSquared);
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        id = buffer.readLong();
        x = buffer.readDouble();
        y = buffer.readDouble();
        z = buffer.readDouble();
        vx = buffer.readDouble();
        vy = buffer.readDouble();
        vz = buffer.readDouble();
        radius = buffer.readFloat();
        lifetimeTicks = buffer.readInt();
        gravityPerTickSquared = buffer.readDouble();
    }

    @Override
    public void read(ByteBuf in) {
        // never sent to the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        ClientShotTracker.spawn(id, new Vec3d(x, y, z), new Vec3d(vx, vy, vz), radius, lifetimeTicks,
                gravityPerTickSquared);
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
