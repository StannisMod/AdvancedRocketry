package zmaster587.advancedRocketry.projectile;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.libVulpes.network.BasePacket;
import zmaster587.libVulpes.network.PacketHandler;

import java.util.function.Supplier;

/**
 * Tell the players who could actually see a thing about it, and nobody else.
 *
 * <h3>Geometry, not a subscription</h3>
 * <p>Weapon fire is server state with no entity and no chunk behind it, so a client draws only what
 * it was told about. Telling everybody would put a battery's whole rate of fire on every connection
 * in the world, including players on the far side of a planet; the test is therefore how near the
 * player is to the LINE the thing occupies — a round's forward path, a beam's lit length — rather
 * than to its origin. A muzzle-distance filter gets the case that matters most exactly backwards:
 * the person being shot at from four kilometres away is the one who most needs to see it.</p>
 *
 * <h3>Built once, sent many times</h3>
 * <p>The packet is a {@link Supplier} because most calls tell nobody: in an empty region the loop
 * runs and nothing is ever constructed. It is built on the first player who passes the test and
 * reused for the rest.</p>
 */
final class ProximityBroadcast {

    private ProximityBroadcast() {
    }

    /**
     * Send to every player whose position lies within {@code radius} of the segment {@code from..to}.
     *
     * <p>A radius of zero sends to nobody — that is how the config switch turns a drawing channel
     * off without touching the mechanic that feeds it.</p>
     */
    static void sendNearSegment(World world, Vec3d from, Vec3d to, int radius,
                                Supplier<? extends BasePacket> packet) {
        if (world == null || world.isRemote || from == null || to == null || radius <= 0) {
            return;
        }
        double radiusSq = (double) radius * radius;
        BasePacket built = null;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) {
                continue;
            }
            if (distanceSqToSegment(player.posX, player.posY, player.posZ, from, to) > radiusSq) {
                continue;
            }
            if (built == null) {
                built = packet.get();
            }
            PacketHandler.sendToPlayer(built, (EntityPlayerMP) player);
        }
    }

    /** Squared distance from a point to the segment {@code from..to}. */
    static double distanceSqToSegment(double px, double py, double pz, Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        double t = 0.0D;
        if (lengthSq > 1.0E-9D) {
            t = ((px - from.x) * dx + (py - from.y) * dy + (pz - from.z) * dz) / lengthSq;
            t = Math.max(0.0D, Math.min(1.0D, t));
        }
        double cx = from.x + dx * t - px;
        double cy = from.y + dy * t - py;
        double cz = from.z + dz * t - pz;
        return cx * cx + cy * cy + cz * cz;
    }
}
