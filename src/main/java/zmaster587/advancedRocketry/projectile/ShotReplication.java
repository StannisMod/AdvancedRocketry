package zmaster587.advancedRocketry.projectile;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.projectile.ShotEndReason;
import zmaster587.advancedRocketry.api.projectile.ShotSpec;
import zmaster587.advancedRocketry.network.PacketShotEnd;
import zmaster587.advancedRocketry.network.PacketShotSpawn;
import zmaster587.libVulpes.network.PacketHandler;

/**
 * Who gets told about a round, and who does not.
 *
 * <h3>Told once, and only if it goes past you</h3>
 * <p>A shot is a server record; a client can only draw one it was told about. Telling everybody
 * about every round would put a battery's whole rate of fire on every connection in the world,
 * including players on the far side of a planet who will never see it — so the test is geometric:
 * a player is told when the round's PATH passes within {@code shotVisibilityRadius} of them, not
 * when its muzzle does. That is what makes a round fired from four kilometres away visible to the
 * person it is fired AT, which is the case that matters most and the one a muzzle-distance filter
 * gets exactly backwards.</p>
 *
 * <h3>Two packets for a minute of flight</h3>
 * <p>The path is determined by the numbers in the first packet, so there is no per-tick stream: the
 * client integrates its own copy and is corrected once, at the end. A player who came into range
 * mid-flight sees nothing — a gap that costs one missed tracer and saves a per-tick proximity scan
 * of every player against every round in the air.</p>
 */
public final class ShotReplication {

    /**
     * How far along its path a round is considered for visibility, in ticks of flight. A round with
     * a minute of lifetime would otherwise be tested against a segment tens of thousands of blocks
     * long, most of which it will never reach because something stops it first.
     */
    private static final int PATH_HORIZON_TICKS = 200;

    private ShotReplication() {
    }

    /** Tell everybody whose view the round will pass through. */
    public static void announceSpawn(World world, long id, ShotSpec spec) {
        int radius = ARConfiguration.getCurrentConfig().shotVisibilityRadius;
        if (world == null || world.isRemote || spec == null || radius <= 0) {
            return;
        }
        Vec3d origin = spec.getOrigin();
        int horizon = Math.min(spec.getLifetimeTicks(), PATH_HORIZON_TICKS);
        Vec3d far = origin.add(spec.getVelocity().scale(horizon));
        double radiusSq = (double) radius * radius;

        PacketShotSpawn packet = null;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) {
                continue;
            }
            if (distanceSqToSegment(player.posX, player.posY, player.posZ, origin, far) > radiusSq) {
                continue;
            }
            if (packet == null) {
                packet = PacketShotSpawn.of(id, spec);
            }
            PacketHandler.sendToPlayer(packet, (EntityPlayerMP) player);
        }
    }

    /**
     * Tell everybody near where it stopped. Deliberately keyed on the END point rather than on who
     * was told about the launch: a player far enough away to be out of range here cannot see the
     * impact either, and their own copy of the round ages out on its stated lifetime.
     */
    public static void announceEnd(World world, long id, Vec3d point, ShotEndReason reason) {
        int radius = ARConfiguration.getCurrentConfig().shotVisibilityRadius;
        if (world == null || world.isRemote || point == null || radius <= 0) {
            return;
        }
        double radiusSq = (double) radius * radius;
        PacketShotEnd packet = null;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP)) {
                continue;
            }
            double dx = player.posX - point.x;
            double dy = player.posY - point.y;
            double dz = player.posZ - point.z;
            if (dx * dx + dy * dy + dz * dz > radiusSq) {
                continue;
            }
            if (packet == null) {
                packet = PacketShotEnd.of(id, point, reason);
            }
            PacketHandler.sendToPlayer(packet, (EntityPlayerMP) player);
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
