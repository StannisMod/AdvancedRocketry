package zmaster587.advancedRocketry.client;

import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.api.projectile.ShotEndReason;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's own copy of what is in the air, kept only so it can be drawn.
 *
 * <h3>It simulates nothing the game reads</h3>
 * <p>Every round here is a picture. It is stepped with the same arithmetic the server uses, from the
 * numbers the server sent, and if the two ever disagree the server is right and this one is simply
 * wrong for a few frames until an end packet corrects it. Nothing in the mod asks this class a
 * question — that is what makes it safe for it to be approximate, and it is why the shot layer's
 * own comment that a client "never steps one" is still true: it steps a drawing, not a shot.</p>
 *
 * <h3>Held here rather than in the world</h3>
 * <p>A client shot has no block, no entity and no chunk, so there is nowhere in the world for it to
 * live. It is cleared when the player leaves a world, because a round from the last dimension drawn
 * over the new one is worse than no round at all.</p>
 */
public final class ClientShotTracker {

    /** How long a spent round's flash is kept before it stops being drawn. */
    private static final int IMPACT_FLASH_TICKS = 10;

    private static final Map<Long, ClientShot> SHOTS = new ConcurrentHashMap<>();
    private static final List<Impact> IMPACTS = Collections.synchronizedList(new ArrayList<Impact>());

    private ClientShotTracker() {
    }

    public static void spawn(long id, Vec3d origin, Vec3d velocity, float radius, int lifetimeTicks,
                             double gravityPerTickSquared) {
        SHOTS.put(id, new ClientShot(origin, velocity, radius, lifetimeTicks, gravityPerTickSquared));
    }

    /** A round the server says is over: stop drawing the flight, start drawing the flash. */
    public static void end(long id, Vec3d point, ShotEndReason reason) {
        SHOTS.remove(id);
        IMPACTS.add(new Impact(point, reason));
    }

    /** Everything the client currently believes is up. Read by the renderer, and by nothing else. */
    public static Collection<ClientShot> inFlight() {
        return SHOTS.values();
    }

    public static List<Impact> impacts() {
        return IMPACTS;
    }

    /** How many rounds the client is drawing. The observable a client test can ask about. */
    public static int count() {
        return SHOTS.size();
    }

    public static void clear() {
        SHOTS.clear();
        IMPACTS.clear();
    }

    /**
     * Advance every drawing one tick. Called from the client tick; a round that outlives what it was
     * told is dropped, because a server that never sent an end packet is a server whose end packet
     * did not reach this player.
     */
    public static void tick() {
        SHOTS.values().removeIf(ClientShot::stepAndCheckExpired);
        synchronized (IMPACTS) {
            IMPACTS.removeIf(Impact::ageAndCheckDone);
        }
    }

    /** One drawn round. Position and velocity in world coordinates, exactly as the server's are. */
    public static final class ClientShot {

        private Vec3d position;
        private Vec3d velocity;
        private Vec3d previous;
        private final float radius;
        private final int lifetimeTicks;
        private final double gravity;
        private int age;

        private ClientShot(Vec3d origin, Vec3d velocity, float radius, int lifetimeTicks, double gravity) {
            this.position = origin;
            this.previous = origin;
            this.velocity = velocity;
            this.radius = radius;
            this.lifetimeTicks = lifetimeTicks;
            this.gravity = gravity;
        }

        private boolean stepAndCheckExpired() {
            age++;
            if (gravity > 0.0D) {
                velocity = velocity.addVector(0.0D, -gravity, 0.0D);
            }
            previous = position;
            position = position.add(velocity);
            return age > lifetimeTicks;
        }

        public Vec3d getPosition() {
            return position;
        }

        /** Where it was last tick — the tail end of the streak a fast round is drawn as. */
        public Vec3d getPrevious() {
            return previous;
        }

        public Vec3d getVelocity() {
            return velocity;
        }

        public float getRadius() {
            return radius;
        }
    }

    /** A spent round's flash, and what spent it. */
    public static final class Impact {

        private final Vec3d point;
        private final ShotEndReason reason;
        private int age;

        private Impact(Vec3d point, ShotEndReason reason) {
            this.point = point;
            this.reason = reason;
        }

        private boolean ageAndCheckDone() {
            return ++age > IMPACT_FLASH_TICKS;
        }

        public Vec3d getPoint() {
            return point;
        }

        public ShotEndReason getReason() {
            return reason;
        }

        /** 1 at the moment of impact, falling to 0 as the flash fades. */
        public float getIntensity() {
            return Math.max(0.0F, 1.0F - (float) age / IMPACT_FLASH_TICKS);
        }
    }
}
