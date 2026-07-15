package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard (bug-report-workflow Step 3, Path B) for findings
 * C076 / FIND-204,FIND-042 (HIGH) and C045 / FIND-020 (MED): a solar
 * array/panel ticking in the space dimension ({@code ARConfiguration
 * .spaceDimId}, default {@code -2}) off any station must NOT crash.
 *
 * <p>Both tiles branch on {@code spaceDimId} and read
 * {@code SpaceObjectManager.getSpaceStationFromBlockCoords(this.pos)
 * .getInsolationMultiplier()}. That lookup is null when no station occupies
 * the tile's grid cell. Before the fix the deref was unguarded
 * ({@code TileSolarArray.java:138}, {@code TileSolarPanel.java:60}), so the
 * NPE escaped {@code World.updateEntities} and hard-crashed the dedicated
 * server every tick.</p>
 *
 * <p><b>Corrected contract, pinned here</b>: off-station in the space dim →
 * 0 insolation (mirrors the sibling {@code TileMicrowaveReciever}'s guarded
 * path), so the tile ticks to 0 RF and the server keeps running. This test
 * previously pinned the crash (its polarity was flipped when the null-guard
 * fix landed). Ledgered — see {@code .agent/history/known-bugs-ledger.md}
 * Batch #2.</p>
 *
 * <p>No station is created, so every grid cell in dim -2 resolves to a null
 * station. Position-isolated per method.</p>
 */
public class SolarTileSpaceDimUnresolvedStationNpeTest extends AbstractSharedServerTest {

    private static final int SPACE_DIM = -2;

    /** C076 — TileSolarArray ticks off-station in the space dim without NPE. */
    @Test
    public void solarArrayInSpaceDimOffStationTicksWithoutCrashing() throws Exception {
        int cx = 9100, cy = 100, cz = 9100;

        ok(client().execute("artest dim load " + SPACE_DIM));

        String fixture = join(client().execute("artest fixture multiblock solar-array "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz));
        assertTrue("fixture multiblock solar-array must build in dim " + SPACE_DIM
                + ": " + fixture, fixture.contains("\"ok\":true"));

        String tryComplete = join(client().execute("artest machine try-complete "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz));
        assertTrue("solar array must validate (isComplete=true) so update() reaches "
                + "the insolation branch: " + tryComplete,
                tryComplete.contains("\"isComplete\":true"));

        // Fixed: the off-station tick returns 0 insolation instead of NPEing.
        String tick = join(client().execute("artest tile force-tick "
                + SPACE_DIM + " " + cx + " " + cy + " " + cz + " 5"));
        assertTrue("TileSolarArray.update() off-station in the space dim must tick "
                + "without throwing (0 insolation, not a crash) after the null-guard "
                + "fix at TileSolarArray.java:138: " + tick,
                tick.contains("\"ok\":true"));
        assertTrue("server must survive the off-station solar-array tick",
                client().isAlive());
    }

    /** C045 — TileSolarPanel ticks off-station in the space dim without NPE. */
    @Test
    public void solarPanelInSpaceDimOffStationTicksWithoutCrashing() throws Exception {
        int x = 9300, y = 200, z = 9300;

        ok(client().execute("artest dim load " + SPACE_DIM));

        ok(client().execute("artest fill " + SPACE_DIM + " " + (x - 2) + " " + (y - 2)
                + " " + (z - 2) + " " + (x + 2) + " " + (y + 4) + " " + (z + 2)
                + " minecraft:air"));

        String place = join(client().execute("artest place " + SPACE_DIM
                + " " + x + " " + y + " " + z + " advancedrocketry:solarGenerator"));
        assertTrue("solar generator must place: " + place,
                place.contains("\"ok\":true") || place.contains("\"placed\":true"));

        client().execute("time set day");
        client().execute("weather clear 100000");

        String tick = join(client().execute("artest tile force-tick "
                + SPACE_DIM + " " + x + " " + y + " " + z + " 5"));
        assertTrue("TileSolarPanel.getPowerPerOperation() off-station in the space dim "
                + "must tick without throwing (0 insolation) after the null-guard fix "
                + "at TileSolarPanel.java:60: " + tick,
                tick.contains("\"ok\":true"));
        assertTrue("server must survive the off-station solar-panel tick",
                client().isAlive());
    }

    private static String ok(java.util.List<String> resp) {
        return join(resp);
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }
}
