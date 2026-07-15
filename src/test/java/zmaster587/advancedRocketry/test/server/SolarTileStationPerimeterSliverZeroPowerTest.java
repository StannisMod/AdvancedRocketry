package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard (bug-report-workflow, finding C076) for the grid-mapping fix in
 * {@code SpaceObjectManager.getSpaceStationFromBlockCoords}.
 *
 * <p>Stations spawn at {@code 2*stationSize*gridX + stationSize/2} — a half-cell
 * offset from the grid point. The reverse lookup formerly reverse-mapped with a bare
 * {@code round(worldX/(2*stationSize))}, which did NOT subtract that offset, so a
 * solar tile built in the block-placement-reach sliver a few blocks past the +X/+Z
 * confinement wall (a position a real player reaches at the station perimeter)
 * mis-mapped to the empty neighbouring grid cell → {@code
 * getSpaceStationFromBlockCoords} returned null → 0 RF on a panel physically sitting
 * on a real, powered station. The fix subtracts the {@code stationSize/2} spawn
 * offset before rounding, so the whole habitable footprint (plus the reach sliver)
 * maps back to the owning cell.</p>
 *
 * <p>This pins the corrected contract: a control panel at the station spawn center
 * generates &gt; 0, and an identical panel on the +X perimeter sliver of the SAME
 * station also generates &gt; 0. The only difference between the two placements is
 * worldX (grid cell), isolating the reverse-mapping offset as the variable under
 * test. If the offset correction regresses, the sliver drops back to 0 and this
 * fails.</p>
 *
 * <p>Fresh server per method (station registration is a global mutation);
 * getInsolationMultiplier's own null-planet guard is exercised separately by
 * {@code test/unit/SpaceStationInsolationUnresolvedPlanetTest}.</p>
 */
public class SolarTileStationPerimeterSliverZeroPowerTest extends AbstractHeadlessServerTest {

    private static final int SPACE_DIM = -2;
    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern SPAWN_X = Pattern.compile("\"spawnX\":(-?\\d+)");
    private static final Pattern SPAWN_Z = Pattern.compile("\"spawnZ\":(-?\\d+)");
    private static final Pattern ENERGY = Pattern.compile("\"energyStored\":(-?\\d+)");

    @Test
    public void perimeterSliverSolarOnRealStationGeneratesPower() throws Exception {
        ok(exec("artest dim load " + SPACE_DIM));

        String create = exec("artest station create 0");
        assertTrue("station must create: " + create, create.contains("\"ok\":true"));
        int stationId = extract(ID, create);

        // Wire the station to orbit the overworld so the control panel is legitimately powered.
        String setParent = exec("artest station set-parent " + stationId + " 0");
        assertTrue("station set-parent must succeed: " + setParent, setParent.contains("\"ok\":true"));

        String info = exec("artest station info " + stationId);
        int spawnX = extract(SPAWN_X, info);
        int spawnZ = extract(SPAWN_Z, info);
        int gridX = Math.round(spawnX / 2048f);

        int cx = spawnX, cz = spawnZ;                 // control = station center
        int sx = gridX * 2048 + 1024 + 4, sz = spawnZ; // sliver = a few blocks past the +X wall, empty neighbor cell
        int y = 200;

        long controlDelta = powerDeltaOver100Ticks(cx, y, cz);
        long sliverDelta = powerDeltaOver100Ticks(sx, y, sz);

        assertTrue("control solar at the station center must generate power (>0); got " + controlDelta
                        + " (station=" + stationId + " spawn=" + spawnX + "," + spawnZ + " info=" + info + ")",
                controlDelta > 0);
        assertTrue("C076 grid-mapping fix: an identical solar panel on the +X perimeter sliver of the SAME "
                        + "real, powered station must ALSO generate power (>0). worldX=" + sx + " now maps back to "
                        + "the owning grid cell (stationGridX=" + gridX + ") because the reverse lookup subtracts "
                        + "the stationSize/2 spawn offset before rounding. Only X differs from the control; got "
                        + sliverDelta + " (0 would mean the offset correction regressed → null station → 0 insolation).",
                sliverDelta > 0);
    }

    /** Place a solar generator, force-tick 100, return energyStored delta. */
    private long powerDeltaOver100Ticks(int x, int y, int z) throws Exception {
        ok(exec("artest fill " + SPACE_DIM + " " + (x - 2) + " " + (y - 2) + " " + (z - 2)
                + " " + (x + 2) + " " + (y + 4) + " " + (z + 2) + " minecraft:air"));
        String place = exec("artest place " + SPACE_DIM + " " + x + " " + y + " " + z
                + " advancedrocketry:solarGenerator");
        assertTrue("solar generator must place at " + x + "," + y + "," + z + ": " + place,
                place.contains("\"ok\":true") || place.contains("\"placed\":true"));
        long before = extractLong(ENERGY, exec("artest energy stored " + SPACE_DIM + " " + x + " " + y + " " + z));
        String tick = exec("artest tile force-tick " + SPACE_DIM + " " + x + " " + y + " " + z + " 100");
        assertTrue("force-tick must not throw (C076 crash-guard still holds): " + tick,
                tick.contains("\"ok\":true"));
        long after = extractLong(ENERGY, exec("artest energy stored " + SPACE_DIM + " " + x + " " + y + " " + z));
        return after - before;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static String ok(String resp) {
        return resp;
    }

    private static int extract(Pattern p, String s) {
        Matcher m = p.matcher(s);
        assertTrue("pattern " + p + " not found in: " + s, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static long extractLong(Pattern p, String s) {
        Matcher m = p.matcher(s);
        assertTrue("pattern " + p + " not found in: " + s, m.find());
        return Long.parseLong(m.group(1));
    }
}
