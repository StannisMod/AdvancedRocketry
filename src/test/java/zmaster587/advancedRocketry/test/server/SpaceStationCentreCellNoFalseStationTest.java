package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard (finding L5) for the reverse-index radius-0
 * fix in {@code SpaceObjectManager.getSpaceStationFromBlockCoords}.
 *
 * <p>The spiral index formula {@code (2*radius-1)^2 + x + radius} evaluates to
 * {@code (-1)^2 = 1} at {@code radius == 0}, so the centre grid cell (0,0) collided
 * with grid (-1,-1) on spiral index 1 — a position in the central inter-station void
 * falsely resolved to station 1 (the first-allocated station) instead of null.
 * Station id 0 (grid (0,0)) is never allocated ({@code getNextStationId} starts at
 * 1), so the central cell must map to no station. The fix special-cases
 * {@code radius == 0 → getSpaceStation(0)} (spiral centre index 0 → null).</p>
 *
 * <p>Pins the corrected contract: with one real station present, a position at the
 * station's own spawn resolves to that station (control — the reverse map still
 * works), while a position in the central grid cell resolves to no station (was
 * falsely station 1 before the fix). Fresh server per method (station registration
 * is a global mutation). The {@code station at} probe reports exactly what
 * {@code getSpaceStationFromBlockCoords} resolves.</p>
 */
public class SpaceStationCentreCellNoFalseStationTest extends AbstractHeadlessServerTest {

    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern SPAWN_X = Pattern.compile("\"spawnX\":(-?\\d+)");
    private static final Pattern SPAWN_Y = Pattern.compile("\"spawnY\":(-?\\d+)");
    private static final Pattern SPAWN_Z = Pattern.compile("\"spawnZ\":(-?\\d+)");

    @Test
    public void centreGridCellResolvesToNoStationNotFalselyStationOne() throws Exception {
        String create = exec("artest station create 0");
        assertTrue("station must create: " + create, create.contains("\"ok\":true"));
        int stationId = extract(ID, create);

        String info = exec("artest station info " + stationId);
        int spawnX = extract(SPAWN_X, info);
        int spawnY = extract(SPAWN_Y, info);
        int spawnZ = extract(SPAWN_Z, info);

        // Control: the station's own spawn must resolve back to it — proves the
        // reverse map still finds real on-station positions after the radius-0 fix.
        String atSpawn = exec("artest station at " + spawnX + " " + spawnY + " " + spawnZ);
        assertTrue("control: the station spawn must resolve to its own station id " + stationId
                        + " (spawn=" + spawnX + "," + spawnZ + "): " + atSpawn,
                atSpawn.contains("\"stationAtPos\":" + stationId));

        // L5: a position in the central grid cell (0,0) — station id 0 is never
        // allocated, so it must resolve to NO station (was falsely station 1 before
        // the radius-0 fix). (100,·,100) reverse-maps to grid (0,0), distinct from
        // the created station's grid (-1,-1).
        String atCentre = exec("artest station at 100 64 100");
        assertTrue("PIN L5: the central grid cell must resolve to no station (radius-0 index fix) — it "
                        + "collided with grid (-1,-1) on index 1 = station 1 via (2*0-1)^2. Got: " + atCentre,
                atCentre.contains("\"stationAtPos\":null"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extract(Pattern p, String s) {
        Matcher m = p.matcher(s);
        assertTrue("pattern " + p + " not found in: " + s, m.find());
        return Integer.parseInt(m.group(1));
    }
}
