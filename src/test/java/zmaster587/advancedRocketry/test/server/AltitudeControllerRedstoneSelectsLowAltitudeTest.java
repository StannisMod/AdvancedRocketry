package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Repro (finding C142) for the station-altitude redstone
 * control range.
 *
 * <p>{@code TileStationAltitudeController.update()}'s redstone branch set
 * {@code targetOrbitalDistance = Math.max(power*13+4, 190)}. Because the GUI slider's
 * maximum ({@code getTotalProgress()}) is 190, every redstone signal 0..14 floored to
 * 190 and only signal 15 reached 199 — so a redstone signal could never command a LOW
 * altitude. The fix uses {@code Math.min}, so the target spans 4..190 across the
 * signal range (matching the GUI's own 0..190).</p>
 *
 * <p>This drives the controller in redstone ON with no wiring ({@code
 * getStrongPower == 0}): the fixed {@code Math.min(4, 190) = 4} (a low altitude); the
 * buggy {@code Math.max(4, 190) = 190} (floored). Pins the contract "a low/absent
 * redstone signal selects a low altitude, below the 190 floor". Server-tier: the
 * effect is a server-side station state field, no client surface.</p>
 */
public class AltitudeControllerRedstoneSelectsLowAltitudeTest extends AbstractSharedServerTest {

    private static final int SPACE_DIM = -2;
    private static final Pattern STATION_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern SPAWN_X = Pattern.compile("\"spawnX\":(-?\\d+)");
    private static final Pattern SPAWN_Z = Pattern.compile("\"spawnZ\":(-?\\d+)");
    private static final Pattern TARGET_ORBITAL = Pattern.compile("\"targetOrbitalDistance\":(-?\\d+)");

    @Test
    public void redstoneOnWithNoSignalSelectsLowAltitudeNotFloored190() throws Exception {
        exec("artest dim load " + SPACE_DIM);

        String create = exec("artest station create 0");
        assertTrue("station must create: " + create, create.contains("\"ok\":true"));
        int stationId = extract(STATION_ID, create);

        String info = exec("artest station info " + stationId);
        int cx = extract(SPAWN_X, info), cy = 128, cz = extract(SPAWN_Z, info);

        exec("artest fill " + SPACE_DIM + " " + (cx - 1) + " " + cy + " " + (cz - 1)
                + " " + (cx + 1) + " " + cy + " " + (cz + 1) + " minecraft:air");
        String place = exec("artest place " + SPACE_DIM + " " + cx + " " + cy + " " + cz
                + " advancedrocketry:altitudeController");
        assertTrue("altitude controller must place: " + place, place.contains("\"placed\":true"));

        // Put the controller into redstone-ON mode (default is OFF). With no redstone
        // wiring around it, getStrongPower(pos) == 0.
        String setRs = exec("artest station controller-set-redstone " + SPACE_DIM + " "
                + cx + " " + cy + " " + cz + " ON");
        assertTrue("controller-set-redstone must succeed: " + setRs, setRs.contains("\"ok\":true"));

        // A few ticks: the redstone branch writes targetOrbitalDistance = f(power=0) each tick.
        exec("artest tile force-tick " + SPACE_DIM + " " + cx + " " + cy + " " + cz + " 3");

        String postInfo = exec("artest station info " + stationId);
        int target = extract(TARGET_ORBITAL, postInfo);

        assertTrue("C142: with redstone ON and no signal (power 0), the altitude target must be "
                        + "a LOW altitude (Math.min gives 4), not floored to the GUI max 190 by the old "
                        + "Math.max. Got targetOrbitalDistance=" + target + " info=" + postInfo,
                target < 190);
    }

    private static int extract(Pattern p, String s) {
        Matcher m = p.matcher(s);
        assertTrue("pattern " + p + " not found in: " + s, m.find());
        return Integer.parseInt(m.group(1));
    }
}
