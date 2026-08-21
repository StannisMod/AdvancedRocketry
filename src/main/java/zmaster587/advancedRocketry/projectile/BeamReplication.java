package zmaster587.advancedRocketry.projectile;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.network.PacketBeamState;

import java.util.function.Supplier;

/**
 * Who gets told that a beam is burning, and how often.
 *
 * <h3>A held thing is told by STATE, not by events</h3>
 * <p>A round is announced twice in a whole flight because its path is determined by the numbers in
 * the first packet. A beam has no such determinism: it is wherever its gun is pointing THIS tick, it
 * can end on a wall that was not there a second ago, and it stops the instant the trigger is released
 * or the feed runs dry. So what is replicated is its current segment, and the client holds that
 * segment until it is told otherwise or until it goes stale.</p>
 *
 * <h3>Sent when it changed, and repeated so it cannot get stuck</h3>
 * <p>Sending every tick would put twenty packets a second per lit beam on every nearby connection for
 * a picture that mostly does not change; sending only on change would leave a beam drawn forever on
 * the client of a player whose "it went out" packet never arrived — because the gun was blown up, the
 * chunk unloaded, or they were out of range at the moment it stopped. So both: a packet whenever the
 * segment moves or the light goes on or off, plus a heartbeat every {@link #REFRESH_TICKS} ticks
 * while it burns. The client drops a beam it has not heard about for a while, which is what makes the
 * heartbeat the thing keeping it alive rather than a decoration.</p>
 *
 * <p><b>Peak, not average</b>: a beam whose aim is moving costs one packet per nearby player per
 * tick; one held steady on a spot costs one per nearby player per {@link #REFRESH_TICKS} ticks. The
 * heartbeats of different guns are spread by a phase taken from each gun's own position, so a
 * broadside that lit on the same tick does not pulse on the same tick forever.</p>
 *
 * <h3>The channel belongs to the gun</h3>
 * <p>There is no registry of live beams and no static map keyed by position: a beam's owner is the
 * gun holding it — the emission has no existence apart from that — so the little state this needs,
 * namely what the client was last told, lives in a {@link Channel} the gun keeps. A gun that unloads
 * takes its channel with it, which is exactly the lifetime a beam has.</p>
 */
public final class BeamReplication {

    /** Ticks between heartbeats for a beam that is burning without changing. */
    static final int REFRESH_TICKS = 10;

    /** How far either end must move before the change is worth a packet of its own, in blocks. */
    static final double MOVE_EPSILON = 0.2D;

    private BeamReplication() {
    }

    /**
     * What one gun has told the players around it about its beam.
     *
     * <p>Not persisted and not synchronised: it records packets SENT, so a copy that is lost costs
     * one redundant packet and nothing else.</p>
     */
    public static final class Channel {

        /** Whether the last thing said was "it is burning". */
        private boolean announcedLit;
        private List<Vec3d> announcedPath = Collections.emptyList();

        /**
         * Say what the beam is doing this tick, if it is worth saying.
         *
         * <p>Cheap to call every tick for a gun that has no beam at all: a dark gun already
         * announced dark costs one boolean test.</p>
         */
        public void update(World world, final BlockPos gun, final List<Vec3d> path, boolean lit) {
            if (world == null || world.isRemote || gun == null) {
                return;
            }
            final boolean burning = lit && path != null && path.size() >= 2;
            List<Vec3d> lastPath = announcedPath;
            if (!offer(world.getTotalWorldTime(), phaseOf(gun), burning, path)) {
                // The common case by a wide margin — an idle gun, or a steady beam between
                // heartbeats — so nothing above this line may allocate.
                return;
            }
            // Announced along the line it occupies NOW, or — going out — along the line it last
            // occupied: those are the players holding a drawing of it, and nobody else has anything
            // to correct. A gun that never lit falls back to its own block.
            final List<Vec3d> announce = burning ? path : lastPath;
            Vec3d near = announce.isEmpty() ? centre(gun) : announce.get(0);
            Vec3d far = announce.isEmpty() ? centre(gun) : announce.get(announce.size() - 1);
            ProximityBroadcast.sendNearSegment(world, near, far,
                    ARConfiguration.getCurrentConfig().shotVisibilityRadius,
                    new Supplier<PacketBeamState>() {
                        @Override
                        public PacketBeamState get() {
                            return PacketBeamState.of(gun, path, burning);
                        }
                    });
        }

        /**
         * The decision and the record of it: should this tick's state go out, and if so, remember
         * that it did.
         *
         * <p>Separated from the sending so the state machine can be driven without a world. The
         * rules ARE the mechanic — silence while dark, a packet on every transition, a packet when
         * the segment moves, and a heartbeat while it burns so that no client's copy of a beam can
         * outlive the beam itself.</p>
         *
         * @param time  the world tick, which the heartbeat is counted against
         * @param phase this gun's heartbeat offset, so that guns do not beat in unison
         */
        public boolean offer(long time, int phase, boolean lit, List<Vec3d> path) {
            boolean send = decide(time, phase, lit, path);
            if (send) {
                announcedLit = lit;
                announcedPath = lit && path != null
                        ? new ArrayList<Vec3d>(path) : Collections.<Vec3d>emptyList();
            }
            return send;
        }

        private boolean decide(long time, int phase, boolean lit, List<Vec3d> path) {
            if (!lit) {
                // Nothing to say about a beam that was already dark last time anybody was told.
                return announcedLit;
            }
            if (!announcedLit) {
                return true;
            }
            // The whole path, not only its ends: a beam turned by a mirror can keep both ends while
            // its corner walks along the plating, and a client told only about the ends would draw a
            // straight line through it.
            if (path == null || path.size() != announcedPath.size()) {
                return true;
            }
            for (int i = 0; i < path.size(); i++) {
                if (moved(announcedPath.get(i), path.get(i))) {
                    return true;
                }
            }
            return Math.floorMod(time + phase, (long) REFRESH_TICKS) == 0L;
        }

        private static boolean moved(Vec3d was, Vec3d now) {
            if (was == null || now == null) {
                return was != now;
            }
            return was.squareDistanceTo(now) > MOVE_EPSILON * MOVE_EPSILON;
        }
    }

    /**
     * A stable, well-spread heartbeat phase for one gun.
     *
     * <p>Taken from the gun's own position rather than from a counter: a battery whose guns all lit
     * on the same tick would otherwise heartbeat on the same tick for as long as they burn, which is
     * the peak the period was chosen to avoid.</p>
     */
    public static int phaseOf(BlockPos gun) {
        return (int) Math.floorMod(gun.toLong() * 2654435761L, (long) REFRESH_TICKS);
    }

    private static Vec3d firstNonNull(Vec3d first, Vec3d fallback) {
        return first == null ? fallback : first;
    }

    private static Vec3d centre(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }
}
