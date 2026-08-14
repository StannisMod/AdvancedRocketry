package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard (finding L2) for the null-station guard at
 * {@code TileGuidanceComputer.getTransBodyInjection(...)} (~line 296).
 *
 * <p>Reasonable use: a classic-mode rocket carrying a guidance computer with a
 * PLANET destination chip runs its in-space launch-burn calc while positioned in
 * an empty grid cell not over any station (the inter-station void). {@code
 * getSpaceStationFromBlockCoords} returns null for that off-station position, and
 * the {@code destinationSpaceStation}/{@code INVALID_PLANET} short-circuit is
 * bypassed because the destination is a real planet. Formerly the unguarded {@code
 * currentSpaceStation.getOrbitingPlanetId()} dereferenced null and crashed; the fix
 * folds {@code currentSpaceStation == null} into the early-return, degrading to no
 * trans-body burn (the base launch-clearance burn still applies). (After the C076
 * grid-mapping fix the perimeter reach-sliver now maps to its own station, so this
 * null path is reached only when genuinely off-station, as here.)</p>
 *
 * <p>The {@code guidance launch-seq} probe drives the real
 * {@code getLaunchSequence(spaceDimId, offSlotPos)} on a placed guidance computer
 * (faithful — the burn calc depends only on currentDim/currentPos/slot-0 chip,
 * not on being rocket-embedded). This pins the corrected contract: off-slot in-space
 * launch-burn no longer throws and returns a real (non-sentinel) burn value. Edge
 * reachability (the sliver), but a real survival-reachable path.</p>
 */
public class TileGuidanceComputerOffSlotBurnNpeTest extends AbstractHeadlessServerTest {

    private static final int SPACE_DIM = -2;
    private static final Pattern AR_DIMS = Pattern.compile("\"arDimensions\":\\[([^\\]]*)\\]");
    private static final Pattern BURN = Pattern.compile("\"burn\":(-?\\d+)");

    @Test
    public void offSlotPlanetLaunchBurnInSpaceDimDegradesToBaseBurn() throws Exception {
        ok(exec("artest dim load " + SPACE_DIM));

        int destDim = firstPlanetDim();
        Assume.assumeTrue("needs a registered AR planet dim as launch destination", destDim != Integer.MIN_VALUE);

        // A station exists somewhere (models 'the player has a station'); it does
        // NOT occupy the off-slot cell we launch from.
        String create = exec("artest station create 0");
        assertTrue("station must create: " + create, create.contains("\"ok\":true"));

        // Off-station: an empty grid cell far from the created station. After the C076
        // grid-mapping fix, getSpaceStationFromBlockCoords(4608,·,4608) reverse-maps to grid
        // (2,2) → spiral index 18 → no station → null (the created station sits at index 1).
        int x = 4608, y = 100, z = 4608;
        ok(exec("artest fill " + SPACE_DIM + " " + (x - 1) + " " + (y - 1) + " " + (z - 1)
                + " " + (x + 1) + " " + (y + 1) + " " + (z + 1) + " minecraft:air"));
        String place = exec("artest place " + SPACE_DIM + " " + x + " " + y + " " + z
                + " advancedrocketry:guidanceComputer");
        assertTrue("guidance computer must place: " + place,
                place.contains("\"ok\":true") || place.contains("\"placed\":true"));

        String r = exec("artest guidance launch-seq " + SPACE_DIM + " " + x + " " + y + " " + z + " " + destDim);
        assertTrue("probe must run: " + r, r.contains("\"ok\":true"));
        assertTrue("launch position must be off any station (proves the null path): " + r,
                r.contains("\"stationAtPos\":null"));
        assertTrue("chip must be programmed to the real planet dim so the INVALID_PLANET short-circuit "
                        + "is bypassed and the guarded null-station path is reached: " + r,
                r.contains("\"chipDim\":" + destDim));
        assertTrue("L2 null-station guard: off-slot in-space launch-burn must NOT throw — "
                        + "TileGuidanceComputer folds a null currentSpaceStation into the early return. Got: " + r,
                r.contains("\"threw\":false"));
        int burn = extractInt(BURN, r);
        assertTrue("a real burn must be returned (not the probe's Integer.MIN_VALUE 'did not run' sentinel), "
                        + "and it must be non-negative — the base launch-clearance burn with no trans-body "
                        + "contribution: got burn=" + burn + " in " + r,
                burn != Integer.MIN_VALUE && burn >= 0);
        assertTrue("server survives", client().isAlive());
    }

    private static int extractInt(Pattern p, String s) {
        Matcher m = p.matcher(s);
        assertTrue("pattern " + p + " not found in: " + s, m.find());
        return Integer.parseInt(m.group(1));
    }

    private int firstPlanetDim() throws Exception {
        String list = exec("artest dim list");
        Matcher m = AR_DIMS.matcher(list);
        if (m.find()) {
            for (String s : m.group(1).split(",")) {
                s = s.trim();
                if (s.isEmpty()) continue;
                int d = Integer.parseInt(s);
                if (d != 0 && d != -1 && d != SPACE_DIM) return d;
            }
        }
        return Integer.MIN_VALUE;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static String ok(String resp) {
        return resp;
    }
}
