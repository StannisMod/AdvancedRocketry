package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Repro (bug-report-workflow) for finding L2 — UNFIXED unguarded NPE at
 * {@code TileGuidanceComputer.getTransBodyInjection(...):currentSpaceStation
 * .getOrbitingPlanetId()} (~line 296).
 *
 * <p>Reasonable use: a classic-mode rocket carrying a guidance computer with a
 * PLANET destination chip runs its in-space launch-burn calc while positioned in
 * the empty grid cell just past the +X/+Z confinement wall (the block-reach
 * sliver, worldX ≥ 2048k+1024). {@code getSpaceStationFromBlockCoords} returns
 * null for that off-slot position, the {@code destinationSpaceStation}/{@code
 * INVALID_PLANET} short-circuit is bypassed because the destination is a real
 * planet, and the unguarded {@code currentSpaceStation.getOrbitingPlanetId()}
 * dereferences null.</p>
 *
 * <p>The {@code guidance launch-seq} probe drives the real
 * {@code getLaunchSequence(spaceDimId, offSlotPos)} on a placed guidance computer
 * (faithful — the burn calc depends only on currentDim/currentPos/slot-0 chip,
 * not on being rocket-embedded) and catches the NPE so the server survives. This
 * pins the CURRENT (unfixed) crash; a null-guard fix flips {@code threw} to
 * false and returns a burn value. Edge reachability (the sliver), but a real
 * survival-reachable NPE.</p>
 */
public class TileGuidanceComputerOffSlotBurnNpeTest extends AbstractHeadlessServerTest {

    private static final int SPACE_DIM = -2;
    private static final Pattern AR_DIMS = Pattern.compile("\"arDimensions\":\\[([^\\]]*)\\]");

    @Test
    public void offSlotPlanetLaunchBurnInSpaceDimNpEs() throws Exception {
        ok(exec("artest dim load " + SPACE_DIM));

        int destDim = firstPlanetDim();
        Assume.assumeTrue("needs a registered AR planet dim as launch destination", destDim != Integer.MIN_VALUE);

        // A station exists somewhere (models 'the player has a station'); it does
        // NOT occupy the off-slot cell we launch from.
        String create = exec("artest station create 0");
        assertTrue("station must create: " + create, create.contains("\"ok\":true"));

        // Off-slot: worldX=1030 → round(1030/2048)=1 (empty neighbor cell), worldZ=512 → cell 0. No station there.
        int x = 1030, y = 100, z = 512;
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
                        + "is bypassed and the deref is reached: " + r,
                r.contains("\"chipDim\":" + destDim));
        assertTrue("PIN L2 (UNFIXED): off-slot in-space launch-burn must currently throw — "
                        + "TileGuidanceComputer derefs a null currentSpaceStation. When guarded, flip to threw:false. "
                        + "Got: " + r,
                r.contains("\"threw\":true"));
        assertTrue("the throw must be a NullPointerException: " + r,
                r.contains("\"exception\":\"NullPointerException\""));
        assertTrue("probe caught it — server survives", client().isAlive());
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
