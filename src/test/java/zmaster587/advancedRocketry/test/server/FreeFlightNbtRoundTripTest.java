package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Save-compat contract for {@code EntityRocket} Free Flight state.
 *
 * <p>The FF attitude quaternion, flight mode, flight-assist toggle and velocity
 * setpoint are persisted by {@code writeEntityToNBT} / {@code readEntityFromNBT}
 * (each with its own legacy-default branch). This pins that a full save/load
 * cycle preserves them, and that a legacy save missing the FF keys degrades to
 * the documented defaults (upright identity attitude, flight-assist ON).
 *
 * <p>"saves must survive" is a project invariant: a read/write asymmetry (a key
 * written but not read, a swapped component, an un-normalised load, a wrong
 * default branch) would silently corrupt stored rockets with NO other test
 * failing. The {@code entity rocket-nbt-roundtrip} probe sets a canonical
 * non-default FF state, drives the real save path into a fresh peer, and also
 * reads a legacy (FF-keys-stripped) copy so both directions are covered.
 */
public class FreeFlightNbtRoundTripTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private static double num(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":(-?[0-9.eE+-]+)").matcher(json);
        assertTrue("response missing numeric key " + key + ": " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static boolean bool(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":(true|false)").matcher(json);
        assertTrue("response missing boolean key " + key + ": " + json, m.find());
        return Boolean.parseBoolean(m.group(1));
    }

    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":\"([^\"]*)\"").matcher(json);
        assertTrue("response missing string key " + key + ": " + json, m.find());
        return m.group(1);
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        String fillAir = ok(client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
                        + " minecraft:air"));
        assertTrue("pre-clear failed: " + fillAir, fillAir.contains("\"ok\":true"));

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = ok(client().execute("artest rocket list 0"));
        Matcher rim = ROCKET_LIST_ID.matcher(list);
        int lastId = -1;
        while (rim.find()) lastId = Integer.parseInt(rim.group(1));
        assertTrue("rocket list empty after assemble: " + list, lastId >= 0);
        return lastId;
    }

    @Test
    public void freeFlightStateSurvivesNbtRoundTrip() throws Exception {
        int id = buildAndAssemble(3300, 64, 700);

        String r = ok(client().execute("artest entity rocket-nbt-roundtrip 0 " + id));
        assertTrue("round-trip probe failed: " + r, r.contains("\"ok\":true"));

        // Flight mode survives.
        assertEquals("flight mode must survive save/load: " + r,
                "FREE_FLIGHT", str(r, "peerMode"));

        // Attitude quaternion survives component-for-component (double -> float
        // -> double on save, so allow float precision).
        double tol = 1e-3;
        assertEquals("quat W must survive: " + r, num(r, "srcQuatW"), num(r, "peerQuatW"), tol);
        assertEquals("quat X must survive: " + r, num(r, "srcQuatX"), num(r, "peerQuatX"), tol);
        assertEquals("quat Y must survive: " + r, num(r, "srcQuatY"), num(r, "peerQuatY"), tol);
        assertEquals("quat Z must survive: " + r, num(r, "srcQuatZ"), num(r, "peerQuatZ"), tol);
        // Sanity: the round-tripped attitude is genuinely non-identity, so the
        // assertions above are not trivially satisfied by an all-zero write.
        assertTrue("round-trip attitude must be non-identity: " + r,
                Math.abs(num(r, "peerQuatX")) + Math.abs(num(r, "peerQuatY"))
                        + Math.abs(num(r, "peerQuatZ")) > 0.1);

        // Flight-assist toggle + velocity setpoint survive.
        assertTrue("flight-assist ON must survive: " + r, bool(r, "peerFaOn"));
        assertEquals("FA fwd setpoint must survive: " + r, 0.3d, num(r, "peerFaFwd"), tol);
        assertEquals("FA right setpoint must survive: " + r, -0.2d, num(r, "peerFaRight"), tol);
        assertEquals("FA up setpoint must survive: " + r, 0.5d, num(r, "peerFaUp"), tol);
    }

    @Test
    public void legacySaveMissingFreeFlightKeysDefaultsSafely() throws Exception {
        int id = buildAndAssemble(3340, 64, 700);

        String r = ok(client().execute("artest entity rocket-nbt-roundtrip 0 " + id));
        assertTrue("round-trip probe failed: " + r, r.contains("\"ok\":true"));

        // A save with no ffQuat* keys must load as the upright identity attitude.
        double tol = 1e-3;
        assertEquals("legacy attitude must default to identity W: " + r, 1.0d, num(r, "legacyQuatW"), tol);
        assertEquals("legacy attitude must default to identity X: " + r, 0.0d, num(r, "legacyQuatX"), tol);
        assertEquals("legacy attitude must default to identity Y: " + r, 0.0d, num(r, "legacyQuatY"), tol);
        assertEquals("legacy attitude must default to identity Z: " + r, 0.0d, num(r, "legacyQuatZ"), tol);

        // A save with no flightAssistOn key must default flight-assist ON.
        assertTrue("legacy flight-assist must default ON: " + r, bool(r, "legacyFaOn"));
    }
}
