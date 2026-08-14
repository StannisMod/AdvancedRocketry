package zmaster587.advancedRocketry.test.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import static org.junit.Assert.assertTrue;

/**
 * A pilot who flies his ship through the atmosphere ceiling UNDER HIS OWN POWER must arrive in the
 * space cell still seated, not falling, and still in control.
 *
 * <p>This is the first test in which a ship reaches the entry line by climbing rather than by a
 * probe or a teleport - the full production on-ramp: a real client on the pilot seat holds the real
 * vertical-up key, the flight computer's own tick notices the ship above the dimension's orbit
 * line, the entry crossing cuts the ship out of the launch world and re-assembles it in a slot
 * cell, and the crew transfer carries the seated pilot across. Every earlier entry test started
 * the crossing artificially, so nothing ever verified the seam a player actually flies through.</p>
 *
 * <p><b>What the arrival assertions pin.</b> After a granted entry, measured from what the CLIENT
 * itself renders: (1) the client's own world is a space-subsystem slot dimension; (2) the pilot is
 * STILL riding his seat mount - crossing a dimension seam must never stand him up; (3) he is NOT
 * in free fall at the arrival pose; (4) holding the vertical-up key again lifts the arrived ship -
 * the control chain survived the crossing onto the re-assembled ship's fresh seat binding; (5) he
 * carries the durable aboard record that a logout in space depends on. A
 * play-tested failure of this seam reported exactly the inverse: the pilot off his ship, falling,
 * in a black cell. Whether the cell LOOKS right on screen stays a manual check - pixels are out of
 * scope here; the mechanical claims are not.</p>
 *
 * <p><b>Arrangement.</b> The orbit line is seeded to the config minimum (255) so the powered climb
 * is seconds, not minutes - the trigger predicate is the same whatever the number. An in-run
 * control leg first proves plain flight works at all (a short climb far below the line), so a red
 * on the entry leg indicts the crossing, not the cockpit. All wait budgets scale with the harness
 * load factor and exit early, so the test is parallel-gate-safe by construction.</p>
 *
 * <p>Manual server + client lifecycle: the config must be written into the game directory BEFORE
 * the server boots. Gated on real Valkyrien Skies - run with {@code -PwithVS}.</p>
 */
public class VSShipPoweredEntryE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern LEDGER = Pattern.compile("\"ledger\":(-?\\d+)");
    private static final Pattern SLOT_DIMS = Pattern.compile("\"slotDims\":\\[([0-9,\\-]*)]");

    /** The account every client harness launches under; the server keys his player data by it. */
    private static final String BOT = "ForgeTestClient";

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 2800, BY = 64, BZ = 2800;

    /** The seeded atmosphere ceiling: the config key's minimum, so the climb stays short. */
    private static final int ORBIT_LINE = 255;

    /** Control leg: the ship must demonstrably fly at all before the entry leg means anything. */
    private static final double MIN_CONTROL_CLIMB = 1.0;

    /** Arrival free-fall discriminator: two seconds of genuine free fall drop ~20 blocks; a parked
     *  ship's settle jitter is well under this. */
    private static final double MAX_ARRIVAL_SINK = 5.0;

    private Path root;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue("Server harness disabled - set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled - set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        root = Files.createTempDirectory("forge-powered-entry-");
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        // Pull the orbit line down to the config minimum so the powered climb is seconds, not minutes.
        String cfg = "# seeded by VSShipPoweredEntryE2ETest\n"
                + "rockets {\n"
                + "    I:orbitHeight=" + ORBIT_LINE + "\n"
                + "}\n";
        Files.write(arConfigDir.resolve("advancedRocketry.cfg"), cfg.getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startFailed) {
            serverHarness.close();
            serverHarness = null;
            throw startFailed;
        }
    }

    @After
    public void stopBoth() throws Exception {
        Exception first = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                first = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
            serverHarness = null;
        }
        if (first != null) {
            throw first;
        }
    }

    @Test
    public void aPilotWhoClimbsThroughTheCeilingArrivesSeatedAndInControl() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        String status = exec("artest space subsystem-status");
        assertTrue("ARRANGEMENT: the production space subsystem must be REGISTERED - the seeded "
                + "config opts it in: " + status, status.contains("\"registered\":true"));

        // Build + assemble the craft, stand the client beside it so it stays loaded.
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("ARRANGEMENT: a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"ok\":true"));
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        int budget = (int) (40 * TestTimeouts.factor());
        double yRest = Double.NaN;
        for (int attempt = 0; attempt < budget && Double.isNaN(yRest); attempt++) {
            bot().waitTicks(5);
            Matcher m = POS_Y.matcher(shipInfoAtBase());
            if (m.find()) {
                yRest = Double.parseDouble(m.group(1));
            }
        }
        assertTrue("ARRANGEMENT: the ship must LOAD with the client present", !Double.isNaN(yRest));

        // Board post-assembly (the proven path - boarding variants have their own test).
        String mountInfo = exec("artest vs seat-mount 0");
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("ARRANGEMENT: seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("ARRANGEMENT: bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10);

        // ---- CONTROL LEG: plain flight works far below the line, or the entry leg is void. ----
        double yControl = yRest;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < budget && (yControl - yRest) < MIN_CONTROL_CLIMB; attempt++) {
                bot().waitTicks(5);
                Matcher m = POS_Y.matcher(shipInfoAtBase());
                if (m.find()) {
                    yControl = Double.parseDouble(m.group(1));
                }
            }
            assertTrue("ARRANGEMENT (control leg): the pilot must be able to fly AT ALL before the "
                            + "entry leg can indict the crossing. yRest=" + yRest + " yControl="
                            + yControl, (yControl - yRest) >= MIN_CONTROL_CLIMB);
            System.out.println("[GATE-STATS after control leg] " + clientGateStats());

            // ---- ENTRY LEG: keep climbing until the ledger records the settled entry. ---------
            // While the crossing runs, the origin-world ship vanishes (the cut), so posY going
            // silent is progress, not failure; the ledger is the single source of arrival truth.
            int climbBudget = (int) (800 * TestTimeouts.factor());
            int ledger = 0;
            double lastY = yControl;
            for (int attempt = 0; attempt < climbBudget && ledger < 1; attempt++) {
                bot().waitTicks(5);
                if (attempt % 4 == 3) {
                    Matcher lm = LEDGER.matcher(exec("artest space subsystem-status"));
                    if (lm.find()) {
                        ledger = Integer.parseInt(lm.group(1));
                    }
                } else {
                    Matcher m = POS_Y.matcher(shipInfoAtBase());
                    if (m.find()) {
                        lastY = Double.parseDouble(m.group(1));
                    }
                }
            }
            System.out.println("[GATE-STATS after entry leg] " + clientGateStats());
            System.out.println("[ARRIVAL-TRACE entry leg] " + exec("artest vs arrival-trace"));
            assertTrue("a ship climbing under its own power past the orbit line (" + ORBIT_LINE
                            + ") must be taken by the entry crossing and SETTLE in a cell - the whole "
                            + "on-ramp a real player flies. lastSeenY=" + lastY
                            + " ledger=" + ledger + " delivery=" + exec("artest vs seat-delivery")
                            + " subsystem=" + exec("artest space subsystem-status"),
                    ledger >= 1);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }

        // ---- ARRIVAL: the three play-reported symptoms, measured from the client. -------------
        String statusAfter = exec("artest space subsystem-status");
        Matcher sd = SLOT_DIMS.matcher(statusAfter);
        assertTrue("ARRANGEMENT: subsystem-status must list slot dims: " + statusAfter, sd.find());
        String slotDims = "," + sd.group(1) + ",";

        // (1) The client's OWN world is a slot dim (the client followed the crossing).
        int clientDim = Integer.MIN_VALUE;
        int arrivalBudget = (int) (40 * TestTimeouts.factor());
        for (int attempt = 0; attempt < arrivalBudget; attempt++) {
            bot().waitTicks(5);
            JsonObject weather = bot().reportWeather();
            if (weather.has("dim")) {
                clientDim = weather.get("dim").getAsInt();
                if (slotDims.contains("," + clientDim + ",")) {
                    break;
                }
            }
        }
        // The two reads that separate this red's causes, in the MESSAGE rather than only on stdout:
        // the settle can report a finished entry (ledger >= 1) with the pilot still in the launch
        // world in two unrelated ways - the crew capture found nobody to carry, or it found him and
        // the re-seat is still blocked on a seat/transform that has not come up. `seat-delivery`
        // carries the re-seat's own block reason; the arrival trace says whether a dimension
        // transfer was ever attempted for him. Without them a red here says only "he is in dim 0".
        assertTrue("after a granted entry the CLIENT itself must be in a space-cell dimension - "
                        + "the pilot follows his ship through the seam. clientDim=" + clientDim
                        + " slotDims=[" + sd.group(1) + "] status=" + statusAfter
                        + " delivery=" + exec("artest vs seat-delivery")
                        + " trace=" + exec("artest vs arrival-trace"),
                slotDims.contains("," + clientDim + ","));

        // (2) Still seated: two consecutive positive samples (a lost seat reads riding=true for a
        // packet-lag moment, never twice with a wait between).
        JsonObject riding = bot().reportRidingEntity();
        boolean prev = isRiding(riding);
        boolean seatedTwice = false;
        for (int attempt = 0; attempt < arrivalBudget && !seatedTwice; attempt++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
            seatedTwice = prev && isRiding(riding);
            prev = isRiding(riding);
        }
        // Position-writer timeline for the arrival, printed win-or-lose: the harness deletes its
        // child workdirs on close, so the only way to read the writers post-run is through the
        // test's own stdout. Server ring via the probe; client ring via a static-field read.
        System.out.println("[ARRIVAL-TRACE server] " + exec("artest vs arrival-trace"));
        System.out.println("[ARRIVAL-TRACE client] " + bot().readStaticField(
                "zmaster587.advancedRocketry.space.ArrivalTrace", "CLIENT"));
        assertTrue("the pilot who FLEW his ship into space must still be in his seat on arrival - "
                        + "a crossing must never stand him up. riding=" + riding
                        + " delivery=" + exec("artest vs seat-delivery"),
                seatedTwice);

        // (3) Not falling: over a two-second window the client-rendered altitude must not sink
        // like a body in free fall.
        double y0 = clientPlayerY();
        bot().waitTicks(40);
        double y1 = clientPlayerY();
        assertTrue("the arrived pilot must NOT be in free fall (clientY " + y0 + " -> " + y1
                        + " over 40 ticks; free fall sinks ~20). riding=" + bot().reportRidingEntity(),
                (y0 - y1) < MAX_ARRIVAL_SINK);

        // (4) Still in control: the key lifts the ARRIVED ship - measured from the rider's own
        // client-rendered altitude (the pilot rides what the key moves).
        double before = clientPlayerY();
        double after = before;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < budget && (after - before) < MIN_CONTROL_CLIMB; attempt++) {
                bot().waitTicks(5);
                after = clientPlayerY();
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        assertTrue("the pilot must still CONTROL his ship after the crossing - the fresh seat "
                        + "binding on the re-assembled ship must carry his input. clientY " + before
                        + " -> " + after + " (need +" + MIN_CONTROL_CLIMB + ")"
                        + " delivery=" + exec("artest vs seat-delivery"),
                (after - before) >= MIN_CONTROL_CLIMB);

        // (5) He carries the durable aboard record. That record - not his dimension id, which is a
        // per-boot slot number - is what a logout in space is restored from; without it the login
        // lands him at his overworld spawn with no message while his ship stays in orbit. The pilot
        // who boarded on the PLANET and flew up is precisely the route that never produced one while
        // the record was written only by the mount transition. Polled: it is maintained from state on
        // the server tick, so it becomes true some ticks after the arrival rather than at it.
        String tag = "";
        for (int attempt = 0; attempt < arrivalBudget && !tag.contains("\"tagged\":true"); attempt++) {
            tag = exec("artest space aboard-tag " + BOT);
            if (!tag.contains("\"tagged\":true")) {
                bot().waitTicks(5);
            }
        }
        assertTrue("a pilot who flew his own ship into a cell must carry a durable aboard record - "
                        + "it is the only evidence the login restore has that he was ever aboard. "
                        + "tag=" + tag + " riding=" + bot().reportRidingEntity()
                        + " status=" + exec("artest space subsystem-status"),
                tag.contains("\"tagged\":true"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private ClientBot bot() {
        return clientHarness.bot();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String shipInfoAtBase() throws Exception {
        return exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
    }

    private double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    /** The client-side pilot-input gate discriminators (the delivery chain's CLIENT half). */
    private String clientGateStats() throws Exception {
        String cls = "zmaster587.advancedRocketry.client.KeyBindings";
        return "open=" + bot().readStaticField(cls, "shipGateOpenTicks").get("value").getAsString()
                + " closed=" + bot().readStaticField(cls, "shipGateClosedTicks").get("value").getAsString()
                + " sends=" + bot().readStaticField(cls, "shipInputSendCount").get("value").getAsString()
                + " clientTicks=" + bot().readStaticField(
                        "com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap",
                        "CLIENT_TICKS").get("value").getAsString()
                + " wallMs=" + System.currentTimeMillis()
                + " shipData=" + exec("artest vs player-ship-data")
                + " riding=" + bot().reportRidingEntity();
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("ARRANGEMENT: chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("ARRANGEMENT: pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("ARRANGEMENT: fixture (" + variant + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("ARRANGEMENT: fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
