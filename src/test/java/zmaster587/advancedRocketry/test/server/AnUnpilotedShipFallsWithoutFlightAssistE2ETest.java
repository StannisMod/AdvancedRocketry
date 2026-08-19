package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Flight Assist is the unmanned mode switch: with it ON an unpiloted craft holds, with it OFF the
 * craft is released and falls.
 *
 * <h2>Why this needs saying at all</h2>
 *
 * <p>A ship left at altitude with nobody at the controls used not to fall — and not because anything
 * was holding it up. The solver steps only bodies whose physics has been switched on, and that switch
 * was thrown by the flight computer only after a craft had been FLOWN once. A newly built ship
 * therefore hung in the air, and the state read from outside as station-keeping while in fact the
 * craft was not being simulated at all.</p>
 *
 * <p>So there were three states where the design has two, and the discriminator was "has anyone ever
 * flown this" rather than the pilot's own on/off choice. This pins the two the design names.</p>
 *
 * <h2>Both directions, in one run</h2>
 *
 * <p>"It fell" alone would pass on a build where a craft can no longer hold at all — which would be a
 * worse defect than the one being fixed, and invisible to a one-sided test. So the same craft is held
 * with Flight Assist ON first and asserted NOT to fall, then released and asserted to fall. The
 * control comes first deliberately: if the hold is already broken, the test says so instead of
 * reporting a successful fall.</p>
 *
 * <p>Needs the physics mod: without it there is no ship, and nothing to drop.</p>
 */
public class AnUnpilotedShipFallsWithoutFlightAssistE2ETest extends AbstractHeadlessServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int BASE_X = 11000, BASE_Z = 11000, BUILD_Y = 80;

    /** Clear sky, well above any terrain, so a fall has room and nothing to land on for a while. */
    private static final int SKY_Y = 200;

    /**
     * How far the craft must sink to count as falling, in blocks. Deliberately far above the noise a
     * settling pose produces and far below what free fall covers in the sampling window: at one
     * standard gravity a body clears this in well under a second, so the assertion is about WHETHER
     * the craft is released, not about how fast — the rate is a balance number and not pinned here.
     */
    private static final double FELL = 4.0;

    /** How much a HELD craft may drift, same units. A hold that leaks this much is not a hold. */
    private static final double HELD_TOLERANCE = 2.0;

    private static final int SAMPLE_TICKS = 60;

    @Test
    public void flightAssistDecidesWhetherAnUnpilotedCraftHoldsOrFalls() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        exec("artest vs permaload true");
        buildShip();
        String shipUuid = theOneShip();

        // Lift it into clear sky and let the solver have it. Rigid teleport parks the craft, so the
        // unpark is what hands it back to physics.
        double startY = liftToSky(shipUuid);

        // --- control: Flight Assist ON must HOLD -------------------------------------------------
        assertTrue("could not reach this ship's flight computer to arm the control leg",
                exec("artest vs fa-by-id 0 " + shipUuid + " true").contains("\"afcResolved\":true"));
        Thread.sleep(SAMPLE_TICKS * 50L);
        double heldY = shipY(shipUuid);
        assertTrue("ARRANGEMENT/CONTROL: with Flight Assist ON an unpiloted craft must keep station,"
                        + " and this one sank from " + startY + " to " + heldY + ". Without a working"
                        + " hold, the fall asserted below would say nothing - a craft that cannot hold"
                        + " falls whatever the mode switch does.",
                startY - heldY <= HELD_TOLERANCE);

        // --- the subject: Flight Assist OFF must RELEASE ------------------------------------------
        assertTrue("could not reach this ship's flight computer to release it",
                exec("artest vs fa-by-id 0 " + shipUuid + " false").contains("\"afcResolved\":true"));
        double releasedFrom = shipY(shipUuid);
        Thread.sleep(SAMPLE_TICKS * 50L);
        double afterY = shipY(shipUuid);

        assertTrue("with Flight Assist OFF and nobody at the controls the craft must be RELEASED and"
                        + " fall, but it sank only " + (releasedFrom - afterY) + " blocks in "
                        + SAMPLE_TICKS + " ticks (from " + releasedFrom + " to " + afterY + "). Either"
                        + " the controller is still commanding a hover with the assist off, or the"
                        + " craft's physics was never switched on - the two produce the same reading"
                        + " from here and both mean the mode switch is not the mode switch.",
                releasedFrom - afterY >= FELL);
    }

    // --- arrangement --------------------------------------------------------------------------------

    private void buildShip() throws Exception {
        clearArea();
        String coords = placeFixture("with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with the physics mod an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the craft never became a loaded ship", waitUntilLoaded());
    }

    /** Rigid-teleport the craft into clear sky and unpark it; returns its Y once it is there. */
    private double liftToSky(String shipUuid) throws Exception {
        String info = exec("artest vs ship-info 0 id " + shipUuid);
        assertTrue("the ship stopped being managed before it could be lifted: " + info,
                info.contains("\"managed\":true"));
        int x = (int) extractDouble(info, "posX"), y = (int) extractDouble(info, "posY"),
                z = (int) extractDouble(info, "posZ");
        assertTrue("climb teleport failed",
                exec("artest vs teleport-ship 0 " + x + " " + y + " " + z
                        + " " + x + " " + SKY_Y + " " + z).contains("\"ok\":true"));
        exec("artest vs unpark 0 " + x + " " + SKY_Y + " " + z);
        Thread.sleep(1000);
        return shipY(shipUuid);
    }

    // --- observation --------------------------------------------------------------------------------

    /** The craft's own Y, by identity — never "whichever ship is nearest", which a fall would outrun. */
    private double shipY(String shipUuid) throws Exception {
        String info = exec("artest vs ship-info 0 id " + shipUuid);
        assertTrue("the ship under test stopped answering: " + info, info.contains("\"managed\":true"));
        return extractDouble(info, "posY");
    }

    private String theOneShip() throws Exception {
        String info = exec("artest vs ship-info 0 " + BASE_X + " " + BUILD_Y + " " + BASE_Z);
        assertTrue("no ship answered near the build site: " + info, info.contains("\"managed\":true"));
        String id = extractString(info, "id");
        assertTrue("the ship answered without an identity: " + info, id != null);
        return id;
    }

    private boolean waitUntilLoaded() throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count 0"), "count") >= 1) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private void clearArea() throws Exception {
        int cx1 = (BASE_X - 4) >> 4, cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (BASE_X + 20) >> 4, cz2 = (BASE_Z + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (BASE_X - 4) + " " + (BUILD_Y - 2) + " " + (BASE_Z - 4)
                + " " + (BASE_X + 20) + " " + (BUILD_Y + 12) + " " + (BASE_Z + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + BASE_X + " " + BUILD_Y + " " + BASE_Z + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
