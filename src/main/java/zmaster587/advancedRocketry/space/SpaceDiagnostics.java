package zmaster587.advancedRocketry.space;

/**
 * The reset point for the subsystem's ungated diagnostic counters — the "last X" reports and the
 * cumulative tallies a probe reads back after a scenario.
 *
 * <p>These are statics on purpose: they are written by the one path that produces them and read by
 * nobody who decides anything, and a harness child JVM has no test mode to gate them behind. What
 * they lacked was an OWNER — nothing cleared them, so a counter set by one scenario was still
 * standing when the next scenario read it, and "the last re-seat was blocked at X" could describe a
 * jump two scenarios old. A single-writer diagnostic may stay a static on exactly two terms, and
 * both are met here: the cross-scenario leak is stated where each field is declared, and somebody
 * resets them — which is what this class is.
 *
 * <p>Called from the server-stop teardown, so their lifetime is the server's — the same lifetime as
 * the stack whose behaviour they describe.</p>
 */
public final class SpaceDiagnostics {

    private SpaceDiagnostics() { }

    /** Clear every subsystem diagnostic. Server main thread only. */
    public static void reset() {
        CrewTransfer.resetDiagnostics();
        VSShipCrosser.resetDiagnostics();
        HyperspaceVoid.resetDiagnostics();
        AssemblyCrewRebind.resetDiagnostics();
    }
}
