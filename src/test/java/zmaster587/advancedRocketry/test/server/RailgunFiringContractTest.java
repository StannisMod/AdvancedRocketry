package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Issue #61 ("[BUG] Railgun does not work") — source-side firing contract,
 * now pinning the FIXED behaviour.
 *
 * <p>The railgun is a paired item TELEPORT: a source railgun pulls a stack
 * from its input port and dispatches it to a linked destination railgun,
 * whose {@code onReceiveCargo} deposits it in the output port
 * ({@link zmaster587.advancedRocketry.tile.multiblock.TileRailgun#attemptCargoTransfer}).</p>
 *
 * <p>The #61 fix does two things: (1) when the destination dimension is
 * registered but not currently loaded, {@code attemptCargoTransfer} now
 * {@code initDimension}s it (the railgun only chunk-loads its OWN chunk, so a
 * receiver on an idle planet used to resolve to null and fail SILENTLY); and
 * (2) every non-firing outcome now sets a {@code FireStatus} surfaced to the
 * player, instead of a silent no-op. These tests pin all of that. A live
 * client variant lives in {@code RailgunCargoTransitE2ETest}.</p>
 *
 * <p>Position-isolated at x=4900 (source) / x=4960 (destination) — clear of
 * RailgunMultiblockTest (x=4500..4560) and RailgunCargoReceiveContractTest
 * (x=4700).</p>
 */
public class RailgunFiringContractTest extends AbstractSharedServerTest {

    private static final int SX = 4900;
    private static final int SY = 64;
    private static final int SZ = 4900;

    private static final int DX = 4960;
    private static final int DY = 64;
    private static final int DZ = 4900;

    // Separate sources for the cross-dimension cases (shared server JVM).
    private static final int UX = 5020;
    private static final int LX = 5080;
    private static final int XZ = 4900;

    /** An id that is not registered on the harness server, so production cannot
     *  load it — the genuinely-unavailable destination case. */
    private static final int UNREGISTERED_DIM = 31337;
    /** Fresh asteroid dim id for the registered-but-unloaded case. */
    private static final int FRESH_DIM = 60931;

    private static final int CARGO = 16;

    private static final Pattern FIRED =
            Pattern.compile("\"fired\":(true|false)");
    private static final Pattern DEST_MATCHED =
            Pattern.compile("\"destMatched\":(\\d+)");
    private static final Pattern SRC_REMAINING =
            Pattern.compile("\"srcInputRemaining\":(\\d+)");
    private static final Pattern FIRE_STATUS =
            Pattern.compile("\"fireStatus\":\"([A-Z_]+)\"");
    private static final Pattern DEST_LOADED_BEFORE =
            Pattern.compile("\"destLoadedBefore\":(true|false)");
    private static final Pattern DEST_LOADED =
            Pattern.compile("\"destLoaded\":(true|false)");
    private static final Pattern AR_DIMS_ARRAY =
            Pattern.compile("\"arDimensions\":\\[([^]]*)]");

    /**
     * Same-dimension shot fires: cargo leaves the source input and arrives at
     * the destination output, and the status reads FIRED.
     */
    @Test
    public void railgunFiresCargoToLinkedRailgunInSameDimension() throws Exception {
        buildAndComplete(SX, SY, SZ);
        buildAndComplete(DX, DY, DZ);

        String fire = exec("artest infra railgun-fire 0 " + SX + " " + SY + " " + SZ
                + " 0 " + DX + " " + DY + " " + DZ + " minecraft:cobblestone " + CARGO);
        assertTrue("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("railgun MUST fire to a linked railgun in the same dimension; "
                        + "fire=" + fire, "true".equals(extractStr(fire, FIRED)));
        assertTrue("status must read FIRED after a successful shot; fire=" + fire,
                "FIRED".equals(extractStr(fire, FIRE_STATUS)));

        int destMatched = extractInt(fire, DEST_MATCHED);
        assertTrue("destination output port must contain >= " + CARGO
                        + " cobblestone after firing; fire=" + fire,
                destMatched >= CARGO);

        int srcRemaining = extractInt(fire, SRC_REMAINING);
        assertTrue("source input port must be drained after firing "
                        + "(remaining=" + srcRemaining + "); fire=" + fire,
                srcRemaining == 0);
    }

    /**
     * The #61 fix: firing at a destination dimension that is registered but
     * not loaded now LOADS it (instead of silently bailing). A fresh asteroid
     * dim is registered-and-unloaded; after the shot it is loaded
     * (destLoadedBefore=false &rarr; destLoaded=true). No railgun exists there, so
     * the shot still doesn't deliver and reports TARGET_UNAVAILABLE — but the
     * dimension-load branch is proven, which (composed with the same-dimension
     * delivery test) is the cross-planet firing the bug was about.
     */
    @Test
    public void railgunLoadsRegisteredButUnloadedDestinationDimension() throws Exception {
        int template = firstNonOverworldArDimOrSkip();
        String create = exec("artest worldgen create-asteroid-dim "
                + FRESH_DIM + " " + template);
        assertTrue("create-asteroid-dim must succeed: " + create,
                create.contains("\"ok\":true"));

        buildAndComplete(LX, SY, XZ);

        String fire = exec("artest infra railgun-fire 0 " + LX + " " + SY + " " + XZ
                + " " + FRESH_DIM + " 0 64 0 minecraft:cobblestone " + CARGO);
        assertTrue("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        Assume.assumeTrue("destination dim was already loaded — can't prove the "
                        + "load branch; fire=" + fire,
                "false".equals(extractStr(fire, DEST_LOADED_BEFORE)));
        assertTrue("firing at a registered-but-unloaded dim MUST load it "
                        + "(issue #61 fix); fire=" + fire,
                "true".equals(extractStr(fire, DEST_LOADED)));
        // No railgun at the target -> no delivery, reported (not silent).
        assertTrue("no railgun at the freshly-loaded target -> must not fire; "
                        + "fire=" + fire, "false".equals(extractStr(fire, FIRED)));
        assertTrue("status must report TARGET_UNAVAILABLE; fire=" + fire,
                "TARGET_UNAVAILABLE".equals(extractStr(fire, FIRE_STATUS)));

        int srcRemaining = extractInt(fire, SRC_REMAINING);
        assertTrue("cargo must be preserved when nothing is delivered "
                        + "(remaining=" + srcRemaining + "); fire=" + fire,
                srcRemaining == CARGO);
    }

    /**
     * A genuinely unavailable destination (an unregistered dim that cannot be
     * loaded) does NOT fire and now REPORTS it (TARGET_UNAVAILABLE) instead of
     * the old silent no-op — and the cargo is preserved.
     */
    @Test
    public void railgunReportsUnavailableForUnloadableDestination() throws Exception {
        buildAndComplete(UX, SY, XZ);

        String fire = exec("artest infra railgun-fire 0 " + UX + " " + SY + " " + XZ
                + " " + UNREGISTERED_DIM + " 0 64 0 minecraft:cobblestone " + CARGO);
        assertTrue("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("must NOT fire at an unloadable (unregistered) destination; "
                        + "fire=" + fire, "false".equals(extractStr(fire, FIRED)));
        assertTrue("unregistered dim cannot be loaded -> destLoaded:false; "
                        + "fire=" + fire, "false".equals(extractStr(fire, DEST_LOADED)));
        assertTrue("status must report TARGET_UNAVAILABLE (not a silent no-op); "
                        + "fire=" + fire,
                "TARGET_UNAVAILABLE".equals(extractStr(fire, FIRE_STATUS)));

        int srcRemaining = extractInt(fire, SRC_REMAINING);
        assertTrue("cargo must be preserved on a failed shot (remaining="
                        + srcRemaining + " expected " + CARGO + "); fire=" + fire,
                srcRemaining == CARGO);
    }

    // -- helpers ----------------------------------------------------------

    /** First registered non-overworld AR dimension, to clone as an asteroid
     *  template; skips the test if none exist on the harness. */
    private int firstNonOverworldArDimOrSkip() throws Exception {
        String joined = exec("artest dim list");
        Assume.assumeFalse("No AR dimensions registered — skipping",
                joined.contains("\"arDimensions\":[]"));
        Matcher m = AR_DIMS_ARRAY.matcher(joined);
        assertTrue("could not parse arDimensions: " + joined, m.find());
        for (String part : m.group(1).split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            int dim = Integer.parseInt(t);
            if (dim != 0 && dim != FRESH_DIM) return dim;
        }
        Assume.assumeTrue("Only overworld registered — skipping", false);
        return -1;
    }

    private void buildAndComplete(int x, int y, int z) throws Exception {
        String fixture = exec("artest fixture multiblock railgun 0 "
                + x + " " + y + " " + z);
        assertTrue("fixture multiblock railgun failed at " + x + "," + y + "," + z
                + ": " + fixture, fixture.contains("\"ok\":true"));

        String tryComplete = exec("artest machine try-complete 0 "
                + x + " " + y + " " + z);
        assertTrue("railgun must validate at " + x + "," + y + "," + z
                + ": " + tryComplete, tryComplete.contains("\"isComplete\":true"));
    }

    private static String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static String extractStr(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern + " not found in: " + src, m.find());
        return m.group(1);
    }

    private static int extractInt(String src, Pattern pattern) {
        return Integer.parseInt(extractStr(src, pattern));
    }
}
