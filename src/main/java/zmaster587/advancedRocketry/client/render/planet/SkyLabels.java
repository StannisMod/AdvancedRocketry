package zmaster587.advancedRocketry.client.render.planet;

import zmaster587.advancedRocketry.api.ARConfiguration;

/**
 * Whether the cell sky writes a body's name and distance beside it (C14 CON-C14-17).
 *
 * <p>The label is a diagnostic first and a player affordance second: it is how a human confirms that
 * a body really is receding, without a probe. It defaults ON for that reason.</p>
 *
 * <p>Two switches, and they are not the same switch:</p>
 * <ul>
 *   <li>the {@code skyBodyLabels} CONFIG flag — a hard disable. Off means no label is drawn anywhere,
 *       ever, by anyone: a flag has to REMOVE the thing it names rather than dim it, or a player who
 *       turned it off is still looking at what he turned off.</li>
 *   <li>the per-console toggle — a pilot's preference, carried on the navigation computer's own
 *       synced state and applied CLIENT-side. Nothing new goes on the wire for it: the console
 *       already ships its state to the clients that can see it.</li>
 * </ul>
 *
 * <p><b>The known limit, stated rather than hidden:</b> the render decision is per-CLIENT while the
 * console is per-SHIP, so in a cell holding two ships the last console to update wins for everyone in
 * that world. The audience C14 CON-C14-06 names — a passenger, a crew member on the hull, a tier-1
 * craft — owns no navigation computer at all, and for them the default is the only setting there is.
 * That is the shape C14 proposes; a per-player channel is a bigger change than the affordance is
 * worth today.</p>
 */
public final class SkyLabels {

    private static volatile boolean consoleEnabled = true;

    private SkyLabels() {
    }

    /** Whether a label may be drawn at all right now — the config flag AND the console toggle. */
    public static boolean enabled() {
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();
        return (cfg == null || cfg.skyBodyLabels) && consoleEnabled;
    }

    /** What the console toggle currently says, ignoring the config flag. */
    public static boolean consoleEnabled() {
        return consoleEnabled;
    }

    /** Apply a navigation computer's toggle to this client. */
    public static void setConsoleEnabled(boolean enabled) {
        consoleEnabled = enabled;
    }

    /** Back to the default (client disconnect / world unload), so a setting cannot outlive its ship. */
    public static void reset() {
        consoleEnabled = true;
    }
}
