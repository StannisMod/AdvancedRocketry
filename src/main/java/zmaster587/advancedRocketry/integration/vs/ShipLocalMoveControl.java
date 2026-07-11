package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.entity.Entity;

/**
 * Experiment control for taking over {@link Entity#move} on the server for ONE entity.
 *
 * <p>Why this exists: a crew member walking on a rotated ship cannot be collided correctly in the
 * world frame - his box is upright and the deck is not. The ship's blocks, however, also exist
 * unrotated and axis-aligned in the ship's own subspace, so his movement can be resolved there with
 * ordinary rules and mapped back. The physics mod already claims {@code Entity.move} at HEAD
 * (cancellably) to do its own world-frame resolution, so AR must run first and suppress it.</p>
 *
 * <p>The modes exist to keep the two questions separable:</p>
 * <ul>
 *   <li>{@link Mode#OBSERVE} - fire, change nothing. Proves the injection exists (a failed
 *       {@code @Inject} is silent in a dev workspace).</li>
 *   <li>{@link Mode#CANCEL} - fire and cancel. Proves AR runs before the physics mod, because a
 *       cancelled callback suppresses every callback queued behind it.</li>
 *   <li>{@link Mode#SHIP_FRAME} - fire, resolve movement in the ship's frame, cancel. The real path.</li>
 * </ul>
 *
 * <p><b>Inert by default</b>, then only for the one entity id it was given, and only server-side.
 * All state is {@code volatile}: written from a command thread, read on the server tick.</p>
 */
public final class ShipLocalMoveControl {

    private ShipLocalMoveControl() {}

    /** What the {@code Entity.move} hook does for the target entity. */
    public enum Mode { OFF, OBSERVE, CANCEL, SHIP_FRAME }

    private static volatile Mode mode = Mode.OFF;
    /** The one entity whose move is intercepted; -1 means none. */
    private static volatile int targetEntityId = -1;
    /** How many times the hook has fired since the last {@link #enable}. */
    private static volatile int fires = 0;
    /**
     * The entity's position in its ship's frame. In {@link Mode#SHIP_FRAME} this is the
     * AUTHORITATIVE position: the world position is derived from it through the ship transform every
     * tick, which is what makes the entity ride the ship instead of being left behind by it.
     */
    private static volatile double[] shipFramePos = null;

    /** Arm the hook for {@code entityId} in {@code newMode}. */
    public static void enable(int entityId, Mode newMode) {
        targetEntityId = entityId;
        fires = 0;
        shipFramePos = null;
        mode = newMode == null ? Mode.OFF : newMode;
    }

    /** Disarm; the hook becomes a no-op again. */
    public static void disable() {
        mode = Mode.OFF;
        targetEntityId = -1;
        shipFramePos = null;
    }

    /**
     * Whether the hook should act for {@code entity}. Server-side only: the client keeps predicting
     * with vanilla rules, so a mistake here cannot strand a player, only desync him for a tick.
     */
    public static boolean shouldTakeOver(Entity entity) {
        if (mode == Mode.OFF || entity == null || entity.world == null || entity.world.isRemote) {
            return false;
        }
        return entity.getEntityId() == targetEntityId;
    }

    public static Mode getMode() {
        return mode;
    }

    public static void markFired() {
        fires++;
    }

    public static boolean isEnabled() {
        return mode != Mode.OFF;
    }

    public static int getTargetEntityId() {
        return targetEntityId;
    }

    public static int getFires() {
        return fires;
    }

    /** The authoritative ship-frame position, or null before the first resolved tick. */
    public static double[] getShipFramePos() {
        return shipFramePos;
    }

    public static void setShipFramePos(double x, double y, double z) {
        shipFramePos = new double[]{x, y, z};
    }
}
