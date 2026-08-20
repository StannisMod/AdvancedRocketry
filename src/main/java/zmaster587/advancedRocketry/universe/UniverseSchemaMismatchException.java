package zmaster587.advancedRocketry.universe;

/**
 * Thrown when a save cannot be opened under the world model this build would give it — a schema version
 * this jar does not carry, or a {@code <galaxyGen>} configuration that has been edited since the world
 * was made.
 *
 * <p><b>Why this is fatal rather than a warning.</b> The universe is derived, not stored: a save keeps
 * what has been touched and re-derives everything else from {@code (seed, cell)}. Continuing under a
 * different model does not corrupt the file — it quietly answers a DIFFERENT universe, and the player
 * finds out by flying to an address he wrote down and finding nothing there. A refusal to load is
 * recoverable from outside the game (restore the configuration, or install the build that carries the
 * version); a world silently regenerated around the player's notes is not.
 */
public class UniverseSchemaMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UniverseSchemaMismatchException(String message) {
        super(message);
    }
}
