package zmaster587.advancedRocketry.api.weapon;

/**
 * What the traverse drive is doing, including all the ways it can be doing nothing.
 *
 * <h3>Why a failure has a NAME instead of a boolean</h3>
 * <p>"Broken" is not one behaviour. A drive that seized points somewhere definite forever; a drive
 * whose brake failed drifts; a drive a player locked is aimed exactly where they left it and is not
 * a fault at all. The aim path reads the bearing as truth in every one of those cases, so the
 * difference between them has to survive as far as the code that decides where the round goes —
 * which it can only do if it is a value rather than an absence.</p>
 */
public enum TurretDriveState {

    /** Full commanded rate available. */
    WORKING(true, 1.0D),

    /** Damaged but still driven — it will get there, slowly. */
    DERATED(true, 0.35D),

    /**
     * Seized where it stands. It still AIMS — at whatever bearing it stopped at — and a gun whose
     * target happens to walk into that bearing will hit it. That is the point of naming this
     * separately from dead.
     */
    JAMMED(false, 0.0D),

    /**
     * The brake is gone: it holds no bearing and drifts. It cannot be commanded, and where it
     * points is not a decision anybody made.
     */
    FREEWHEELING(false, 0.0D),

    /** Deliberately held by a player or console. Not a fault; the bearing is exactly as left. */
    LOCKED(false, 0.0D),

    /** No drive at all. The mount does not aim and the gun does not fire. */
    DEAD(false, 0.0D);

    private final boolean drivable;
    private final double rateFactor;

    TurretDriveState(boolean drivable, double rateFactor) {
        this.drivable = drivable;
        this.rateFactor = rateFactor;
    }

    /** Whether a commanded bearing moves the mount at all. */
    public boolean isDrivable() {
        return drivable;
    }

    /** Fraction of the declared traverse rate this state actually delivers. */
    public double getRateFactor() {
        return rateFactor;
    }

    /**
     * Whether the gun may fire in this state. A jammed mount still fires — down its stuck bearing —
     * because a gun that cannot turn is not a gun that cannot shoot, and pretending otherwise would
     * quietly delete a whole class of desperate defence.
     */
    public boolean permitsFiring() {
        return this != DEAD;
    }
}
