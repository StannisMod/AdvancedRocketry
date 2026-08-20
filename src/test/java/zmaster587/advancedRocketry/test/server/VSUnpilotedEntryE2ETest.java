package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.TestTimeouts;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * E2E: crossing OUT of an atmosphere is a PHYSICAL event, so it does not ask who is holding a key.
 *
 * <p>This is {@code VSShipEntryE2ETest}'s piloted climb minus one line — its {@code ff-input}
 * arrangement — and that difference is the whole subject: a pilot is the only thing that changes
 * between the two, and the outcome must not depend on it. The mode it protects is the mod's own
 * autopilot on the ordinary path: with no input the flight computer keeps commanding the retained
 * velocity setpoint, so a ship under cruise is physically flying while the deleted {@code flying} flag
 * read false for it — the one flight mode where nobody is watching was exactly the one that could not
 * leave a planet.</p>
 *
 * <h2>Why this is its own class, and not a third method next door</h2>
 *
 * <p>It lives alone because of a defect in something else, measured 2026-08-11 and NOT in this
 * subject: <b>the THIRD {@code entry-setup} in one server boot cannot enter space</b>, whichever
 * scenario occupies that position. Three readings establish it:</p>
 *
 * <ul>
 *   <li>added as a third method next door, this leg fails <b>2/2 cache-busted runs</b> with
 *       {@code {"pending":0,"ships":0}} — a status that structurally excludes every settle outcome, so
 *       the entry was never requested;</li>
 *   <li>run <b>ALONE it passes</b>, so the unpiloted crossing itself works and the subject is
 *       innocent;</li>
 *   <li>before this leg existed the same failure landed on the <b>piloted sibling</b> instead — the
 *       class has no {@code @FixMethodOrder}, so JUnit's hash order reshuffles when a method is added
 *       or renamed, and the red follows the third POSITION rather than any particular test.</li>
 * </ul>
 *
 * <p>Narrowing the probe stack's slot set to its own slots (the binder used to see the whole pool) did
 * NOT fix it, which rules that hypothesis out by control. A server JVM is started per CLASS here
 * ({@code @BeforeClass}/{@code @AfterClass}), so a class of its own gives this leg a boot in which it is
 * the FIRST consumer — the home the bug ledger asked for rather than a red shipped or an order pinned
 * to hide which scenario lands third.</p>
 *
 * <p>Gated on the server's real VS presence (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class VSUnpilotedEntryE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** Poll iterations (250 ms apart) for the async crossing, stretched by the build's fork factor. */
    private static final int SETTLE_POLLS = (int) Math.ceil(120 * TestTimeouts.factor());

    /** Where the ship is built — its own region, clear of every other server-tier fixture. */
    private static final int SRC_X = 6800, SRC_Y = 80, SRC_Z = 6800;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;

    @After
    public void cleanup() throws Exception {
        if (serverHasVs()) {
            exec("artest space entry-clear");
            exec("artest vs permaload false");
        }
    }

    @Test
    public void anUnpilotedShipClimbingPastTheCeilingStillEntersSpace() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        String control = exec("artest space entry-status");
        assertEquals("witness sensitivity control — no ship must be ledgered before the climb: " + control,
                0, extractInt(control, "ships"));

        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        String launch = exec("artest space launch-cell 0");
        assertTrue("launch-cell resolve failed: " + launch, launch.contains("\"ok\":true"));
        String expectedCell = extractString(launch, "cellKey");
        assertTrue("launch dim resolved to no cell: " + launch, expectedCell != null);

        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");

        // THE ONE DIFFERENCE from the piloted leg, and it is ASSERTED rather than assumed: this ship's
        // own flight computer holds no pilot input. The probe reports the state it left behind, so
        // "nobody is at the controls" is a reading rather than a hope — the earlier form of this line
        // scrubbed a world-wide static, which said nothing about THIS ship.
        String hands = exec("artest vs ff-input-by-id 0 " + extractString(srcInfo, "id"));
        assertTrue("this ship's flight computer must resolve, and hold NO pilot input, or the climb"
                + " below is the piloted leg again: " + hands,
                hands.contains("\"afcResolved\":true") && hands.contains("\"input\":\"null\""));

        String tp = exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        assertTrue("climb teleport failed: " + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);

        String status = "";
        boolean settled = false;
        for (int i = 0; i < SETTLE_POLLS; i++) {
            status = exec("artest space entry-status");
            if (extractInt(status, "ships") >= 1 && "SETTLED".equals(extractString(status, "state"))) {
                settled = true;
                break;
            }
            loadAllEntrySlots(setup);
            Thread.sleep(250);
        }
        assertTrue("a ship with NOBODY at the controls must still cross out of the atmosphere — the"
                + " crossing is world plus geometry, and an atmosphere does not check whose hands are"
                + " on the stick; last status=" + status, settled);
        assertEquals("entry settled in a different cell than the launch resolver answers", expectedCell,
                extractString(status, "cellKey"));
    }

    // --- helpers (byte-identical to VSShipEntryE2ETest's, as the server-tier classes keep them) ------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private void loadAllEntrySlots(String setup) throws Exception {
        Matcher m = Pattern.compile("\"dims\":\\[(-?\\d+),(-?\\d+)]").matcher(setup);
        if (m.find()) {
            exec("artest vs load-ships " + m.group(1));
            exec("artest vs load-ships " + m.group(2));
        }
    }

    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = extractInt(exec("artest vs ship-count " + dim), "count");
                if (loaded >= 1) {
                    return loaded;
                }
            }
            Thread.sleep(250);
        }
        return 0;
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20) + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
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
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
