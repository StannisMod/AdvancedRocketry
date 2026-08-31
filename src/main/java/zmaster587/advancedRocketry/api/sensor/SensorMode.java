package zmaster587.advancedRocketry.api.sensor;

/**
 * The two ways a fire-control sensor can find something, and the whole of the trade between them.
 *
 * <h3>Why there are two</h3>
 * <p>A ship that shuts everything down to avoid being found must still be able to shoot, or "go dark"
 * is a button that disarms you. And a target that has itself gone dark must be hard to hit, or going
 * dark buys nothing. Both are true at once only if aiming has a listening mode and an illuminating
 * one:</p>
 *
 * <ul>
 *   <li>{@link #PASSIVE} — the sensor listens. It emits nothing, so a dark ship can fight; but the
 *       quality of what it gets is bounded by what the target itself radiates, so a cold, quiet
 *       target barely resolves at all.</li>
 *   <li>{@link #ACTIVE} — the sensor illuminates. Steady, good quality against anything inside its
 *       radius including a cold one — and a standing emission of its own, which is to say the end of
 *       your own silence.</li>
 * </ul>
 *
 * <p>The rule a player learns without being taught it: <em>you can shoot in the dark, but only at
 * things that are themselves lit; to shoot at someone who is hiding, you must stop hiding.</em></p>
 */
public enum SensorMode {

    PASSIVE,
    ACTIVE;

    /**
     * Whether running in this mode is itself something another sensor can hear.
     *
     * <p>Nothing consumes this yet — the EM-signature layer that turns a standing emission into
     * somebody else's contact is a separate subsystem. It is stated here rather than left implicit
     * because it is the entire cost of the active mode, and because the target-side "you are being
     * locked" warning is supposed to fall out of it rather than being authored: a passive lock is
     * silent, so being tracked passively is undetectable, which is correct.</p>
     */
    public boolean isEmitting() {
        return this == ACTIVE;
    }
}
