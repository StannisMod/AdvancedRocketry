package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Issue #61 ("[BUG] Railgun does not work") — source-side firing contract.
 *
 * <p>The reporter said the railgun "just does not fire with a linker that has
 * the cords of another railgun". {@link RailgunCargoReceiveContractTest} only
 * pins the receiver endpoint on a SOLO railgun; nothing exercised the full
 * source-side path
 * ({@link zmaster587.advancedRocketry.tile.multiblock.TileRailgun#attemptCargoTransfer}),
 * which needs TWO assembled railguns at linked positions.</p>
 *
 * <p>This test builds two railguns in the SAME dimension, programs a libVulpes
 * Linker on the source pointing at the destination controller, loads a cargo
 * stack into the source's input port, and drives {@code attemptCargoTransfer}
 * via the {@code artest infra railgun-fire} probe. The basic same-dimension
 * case MUST fire: cargo leaves the source input and lands in the destination
 * output. If this passes, the field report is an environmental failure
 * (destination dimension unloaded, missing output hatch, redstone, or power) —
 * not a logic bug in the firing gate; if it fails, the gate itself is broken.</p>
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

    // Separate source for the cross-dimension case (shared server JVM).
    private static final int UX = 5020;
    private static final int UZ = 4900;

    /** An id that is not registered/loaded on the test server, so production's
     *  {@code net.minecraftforge.common.DimensionManager.getWorld(id)} returns
     *  null — the exact unloaded-destination condition behind issue #61. */
    private static final int UNLOADED_DIM = 31337;

    private static final int CARGO = 16;

    private static final Pattern FIRED =
            Pattern.compile("\"fired\":(true|false)");
    private static final Pattern DEST_MATCHED =
            Pattern.compile("\"destMatched\":(\\d+)");
    private static final Pattern SRC_REMAINING =
            Pattern.compile("\"srcInputRemaining\":(\\d+)");

    @Test
    public void railgunFiresCargoToLinkedRailgunInSameDimension() throws Exception {
        buildAndComplete(SX, SY, SZ);
        buildAndComplete(DX, DY, DZ);

        String fire = exec("artest infra railgun-fire 0 " + SX + " " + SY + " " + SZ
                + " 0 " + DX + " " + DY + " " + DZ + " minecraft:cobblestone " + CARGO);
        assertTrue("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("railgun MUST fire to a linked railgun in the same dimension "
                        + "(issue #61 baseline); fire=" + fire,
                "true".equals(extractStr(fire, FIRED)));

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
     * Issue #61 — the most likely field failure: the destination railgun is in
     * a dimension that is not currently loaded (e.g. sender on planet A,
     * receiver on planet B, player standing on A). Production resolves the
     * destination with {@code net.minecraftforge.common.DimensionManager
     * .getWorld(destDim)}, which returns null for an unloaded dim; the railgun
     * only chunk-loads its OWN chunk, never the destination's. The result is a
     * SILENT no-op: nothing fires, no feedback. This test characterizes that
     * behavior — and crucially pins that cargo is NOT lost when the shot fails.
     */
    @Test
    public void railgunSilentlyFailsWhenDestinationDimensionUnloaded() throws Exception {
        buildAndComplete(UX, SY, UZ);

        String fire = exec("artest infra railgun-fire 0 " + UX + " " + SY + " " + UZ
                + " " + UNLOADED_DIM + " 0 64 0 minecraft:cobblestone " + CARGO);
        assertTrue("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("railgun must NOT fire when the destination dimension is "
                        + "unloaded (issue #61 root cause); fire=" + fire,
                "false".equals(extractStr(fire, FIRED)));
        assertTrue("destination dimension must be reported unloaded "
                        + "(production getWorld returns null); fire=" + fire,
                fire.contains("\"destLoaded\":false"));

        int srcRemaining = extractInt(fire, SRC_REMAINING);
        assertTrue("cargo must be preserved in the source input on a failed "
                        + "shot — never silently consumed (remaining="
                        + srcRemaining + " expected " + CARGO + "); fire=" + fire,
                srcRemaining == CARGO);
    }

    // -- helpers ----------------------------------------------------------

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
        assertTrue("pattern not found in: " + src, m.find());
        return m.group(1);
    }

    private static int extractInt(String src, Pattern pattern) {
        return Integer.parseInt(extractStr(src, pattern));
    }
}
