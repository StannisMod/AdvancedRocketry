package zmaster587.advancedRocketry.client;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's own copy of which beams are burning, kept only so they can be drawn.
 *
 * <h3>It simulates nothing the game reads</h3>
 * <p>Every beam here is a picture of a line the server told this client about. Nothing in the mod
 * asks this class a question: no damage is resolved from it and no state is derived from it, which
 * is what makes it safe for it to be a tick or two out of date.</p>
 *
 * <h3>A beam that stops being mentioned goes out</h3>
 * <p>A held beam ends for reasons a client cannot see — the trigger released, the feed run dry, the
 * gun destroyed, the chunk unloaded, the player having walked out of range while it burned. Some of
 * those send an "it went out" packet and some cannot, so the drawing is kept alive by the server
 * repeating itself: a beam nobody has mentioned for {@link #STALE_TICKS} ticks is dropped. That
 * makes the worst case a beam drawn for a fraction of a second too long, instead of one burning
 * across the sky until the player relogs.</p>
 */
public final class ClientBeamTracker {

    /**
     * How long a beam is drawn without being mentioned again. Comfortably more than two heartbeats
     * of {@code BeamReplication.REFRESH_TICKS}, so a single dropped or delayed packet does not make
     * a burning beam blink.
     */
    private static final int STALE_TICKS = 25;

    /** Keyed by the gun's packed position: a gun holds at most one beam. */
    private static final Map<Long, ClientBeam> BEAMS = new ConcurrentHashMap<>();

    private ClientBeamTracker() {
    }

    /** This gun's beam is burning along this PATH, as of now. */
    public static void lit(long gun, List<Vec3d> path) {
        if (path == null || path.size() < 2) {
            return;
        }
        ClientBeam beam = BEAMS.get(gun);
        if (beam == null) {
            BEAMS.put(gun, new ClientBeam(path));
            return;
        }
        beam.refresh(path);
    }

    /** This gun's beam has gone out. */
    public static void extinguished(long gun) {
        BEAMS.remove(gun);
    }

    /** Every beam the client currently believes is burning. Read by the renderer, and by nothing else. */
    public static Collection<ClientBeam> burning() {
        return BEAMS.values();
    }

    /** How many beams the client is drawing. The observable a client test can ask about. */
    public static int count() {
        return BEAMS.size();
    }

    /**
     * How many of them have a CORNER in them — a beam something turned.
     *
     * <p>The second observable, and it exists because the first cannot see the thing that goes
     * wrong here. A bent beam sent to a client as two ends is still one drawn beam, so a count of
     * beams is green whether the corner arrived or not; what a player would see is a laser drawn
     * straight through the mirror that turned it.</p>
     */
    public static int bentCount() {
        int bent = 0;
        for (ClientBeam beam : BEAMS.values()) {
            if (beam.isBent()) {
                bent++;
            }
        }
        return bent;
    }

    public static void clear() {
        BEAMS.clear();
    }

    /**
     * How long a beam is drawn after the last time it was mentioned.
     *
     * <p>Readable because it is half of a two-sided arrangement: the server's heartbeat has to be
     * quicker than this or a beam that is still burning blinks out and back. A test that pins that
     * relationship should read both numbers rather than repeat either.</p>
     */
    public static int stalenessTicks() {
        return STALE_TICKS;
    }

    /** Age every drawing one tick and drop the ones nobody has mentioned lately. */
    public static void tick() {
        BEAMS.values().removeIf(ClientBeam::ageAndCheckStale);
    }

    /**
     * One drawn beam: the path it occupies, in world coordinates, muzzle first.
     *
     * <p>Two points for the ordinary beam, more where something turned it. {@link #getFrom} and
     * {@link #getTo} are kept because the ends are what most readers want, and because a beam that
     * has not been bent is exactly its two ends.</p>
     */
    public static final class ClientBeam {

        private List<Vec3d> path;
        private int sinceHeard;

        private ClientBeam(List<Vec3d> path) {
            this.path = new ArrayList<Vec3d>(path);
        }

        private void refresh(List<Vec3d> newPath) {
            path = new ArrayList<Vec3d>(newPath);
            sinceHeard = 0;
        }

        private boolean ageAndCheckStale() {
            return ++sinceHeard > STALE_TICKS;
        }

        /** Every point of the line, muzzle first. Never fewer than two. */
        public List<Vec3d> getPath() {
            return Collections.unmodifiableList(path);
        }

        /** Whether something turned this beam, which is the only case the path has a corner. */
        public boolean isBent() {
            return path.size() > 2;
        }

        public Vec3d getFrom() {
            return path.get(0);
        }

        public Vec3d getTo() {
            return path.get(path.size() - 1);
        }
    }
}
