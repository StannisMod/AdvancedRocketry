package zmaster587.advancedRocketry.projectile;

import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.projectile.ShotEndReason;
import zmaster587.advancedRocketry.api.projectile.ShotSpec;
import zmaster587.advancedRocketry.network.PacketShotEnd;
import zmaster587.advancedRocketry.network.PacketShotSpawn;

import java.util.function.Supplier;

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
    public static void announceSpawn(World world, final long id, final ShotSpec spec) {
        if (world == null || world.isRemote || spec == null) {
            return;
        }
        Vec3d origin = spec.getOrigin();
        int horizon = Math.min(spec.getLifetimeTicks(), PATH_HORIZON_TICKS);
        Vec3d far = origin.add(spec.getVelocity().scale(horizon));
        ProximityBroadcast.sendNearSegment(world, origin, far,
                ARConfiguration.getCurrentConfig().shotVisibilityRadius,
                new Supplier<PacketShotSpawn>() {
                    @Override
                    public PacketShotSpawn get() {
                        return PacketShotSpawn.of(id, spec);
                    }
                });
    }

    /**
     * Tell everybody near where it stopped. Deliberately keyed on the END point rather than on who
     * was told about the launch: a player far enough away to be out of range here cannot see the
     * impact either, and their own copy of the round ages out on its stated lifetime.
     */
    public static void announceEnd(World world, final long id, final Vec3d point,
                                   final ShotEndReason reason) {
        if (world == null || world.isRemote || point == null) {
            return;
        }
        ProximityBroadcast.sendNearSegment(world, point, point,
                ARConfiguration.getCurrentConfig().shotVisibilityRadius,
                new Supplier<PacketShotEnd>() {
                    @Override
                    public PacketShotEnd get() {
                        return PacketShotEnd.of(id, point, reason);
                    }
                });
    }

}
