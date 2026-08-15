package zmaster587.advancedRocketry.api.projectile;

/**
 * What acts on a shot between one tick and the next — the whole of the physics a travelling round is
 * subject to, declared at the muzzle rather than looked up mid-flight.
 *
 * <p>It is declared because a shot outlives the loaded state of the place it is crossing. Asking the
 * world "what is the gravity here" every tick would mean the answer changes when a region unloads,
 * and a round that curves differently depending on whether anybody happens to be watching is not a
 * round anybody can aim. So the shooter states the environment once and the shot carries it.</p>
 *
 * <p>Only gravity is modelled. Drag is not, and no field is reserved for it: a shot that needs air
 * resistance needs a decision about what "air" means at 2000 blocks up, and that decision is not
 * made yet.</p>
 */
public final class ShotEnvironment {

    /** Nothing acts. The path is a straight line — space, and the band ships fly in. */
    public static final ShotEnvironment VACUUM = new ShotEnvironment(0.0D);

    private final double gravityPerTickSquared;

    private ShotEnvironment(double gravityPerTickSquared) {
        this.gravityPerTickSquared = gravityPerTickSquared;
    }

    /**
     * Constant downward acceleration, in blocks per tick squared. Vanilla's own projectile gravity is
     * around 0.03 at the surface; a body's planet scales it.
     */
    public static ShotEnvironment gravity(double perTickSquared) {
        double g = Math.max(0.0D, perTickSquared);
        return g == 0.0D ? VACUUM : new ShotEnvironment(g);
    }

    /** Downward acceleration in blocks per tick squared; 0 in vacuum. */
    public double getGravityPerTickSquared() {
        return gravityPerTickSquared;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShotEnvironment
                && Double.compare(((ShotEnvironment) other).gravityPerTickSquared,
                        gravityPerTickSquared) == 0;
    }

    @Override
    public int hashCode() {
        return Double.valueOf(gravityPerTickSquared).hashCode();
    }

    @Override
    public String toString() {
        return gravityPerTickSquared == 0.0D ? "vacuum" : "gravity=" + gravityPerTickSquared;
    }
}
