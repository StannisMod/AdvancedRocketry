package zmaster587.advancedRocketry.test.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import static org.junit.Assert.assertTrue;

/**
 * A REFUSED space entry must cost the pilot nothing but a message: he stays IN HIS SEAT, in the
 * launch world, told why — never dismounted, never dropped, never silently ignored.
 *
 * <p>The refusal under test is real pool pressure: the space-cell pool is seeded to ONE slot and
 * that slot is held by a foreign cell before the flight, so when the pilot climbs his ship through
 * the orbit line the entry's own cell materialization is refused. The historical defect here was
 * an order-of-operations one — the crew was captured (dismounted, mounts retired) BEFORE the pool
 * was asked, so a refusal left the pilot standing (or falling) beside a ship that never went
 * anywhere. The contract is refusal-shaped: the crossing may unseat nobody until it is actually
 * granted.</p>
 *
 * <p><b>What is asserted, all client-observed:</b> (1) the refusal message reaches the pilot's own
 * chat (i18n resolved — what the player reads); (2) he is STILL riding his seat mount after the
 * refusal (two consecutive positive samples); (3) his client is still in the launch dimension —
 * the ship never crossed; (4) nothing was ledgered into space. An in-run control leg first proves
 * plain flight works at all, and a second occupy proves the pool really is exhausted before the
 * climb starts.</p>

 * <p>Setup shortcuts, named: the bot boards via the {@code vs seat-mount} probe + mount-entity
 * (the harness cannot right-click a post-assembly ship-subspace block); pool pressure comes from a
 * probe-held cell occupant, not ten real ships. Neither changes the refusal path under test —
 * the flight computer's own tick fires the entry against the production subsystem.</p>
 *
 * <p>Manual server + client lifecycle: the config (pool size, orbit line) must be written into the
 * game directory BEFORE the server boots. Gated on real Valkyrien Skies — run with
 * {@code -PwithVS}.</p>
 */
public class VSShipEntryRefusedKeepsPilotSeatedE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    /** The refused-entry discriminator: the seat's own delivery counters, sampled across the climb. */
    private static final Pattern RECEIVED = Pattern.compile("\"received\":(\\d+)");
    private static final Pattern DELIVERED = Pattern.compile("\"delivered\":(\\d+)");
    private static final Pattern LEDGER = Pattern.compile("\"ledger\":(-?\\d+)");
    private static final Pattern SHIP_ID = Pattern.compile("\"id\":\"([0-9a-fA-F-]+)\"");
    private static final Pattern VEL_Y = Pattern.compile("\"velY\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 3000, BY = 64, BZ = 3000;

    /** The seeded atmosphere ceiling: the config key's minimum, so the climb stays short. */
    private static final int ORBIT_LINE = 255;

    /** Control leg: the ship must demonstrably fly at all before the refusal leg means anything. */
    private static final double MIN_CONTROL_CLIMB = 1.0;

    /** The refusal message's stable needle (en_US: "Space is saturated - the ship cannot enter
     *  orbit right now. Descend and try again later."). */
    private static final String REFUSAL_NEEDLE = "space is saturated";

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

        root = Files.createTempDirectory("forge-entry-refused-");
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        // Pull the orbit line down to the config minimum so the climb is seconds, and shrink the cell
        // pool to a SINGLE slot so one probe-held occupant exhausts it.
        String cfg = "# seeded by VSShipEntryRefusedKeepsPilotSeatedE2ETest\n"
                + "performance {\n"
                + "    I:spaceCellPoolSize=1\n"
                + "}\n"
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
    public void aRefusedEntryLeavesThePilotSeatedWithAMessage() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        String status = exec("artest space subsystem-status");
        assertTrue("ARRANGEMENT: the production space subsystem must be REGISTERED - the seeded "
                + "config opts it in: " + status, status.contains("\"registered\":true"));

        // Fill the single-slot pool with a foreign cell, then PROVE it is full: a second occupy
        // must come back exhausted, or a later "refused" observation is unattributable.
        String occupy = exec("artest space occupy 90 0 0");
        assertTrue("ARRANGEMENT: the pool's single slot must accept the occupant: " + occupy,
                occupy.contains("\"ok\":true"));
        String occupy2 = exec("artest space occupy 91 0 0");
        assertTrue("ARRANGEMENT (instrument control): with the slot held, a second occupy must be "
                + "REFUSED - else the pool is not actually exhausted and the entry would be "
                + "granted: " + occupy2, occupy2.contains("\"exhausted\":true"));

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
        String shipInfo = "";
        for (int attempt = 0; attempt < budget && Double.isNaN(yRest); attempt++) {
            bot().waitTicks(5);
            shipInfo = shipInfoAtBase();
            Matcher m = POS_Y.matcher(shipInfo);
            if (m.find()) {
                yRest = Double.parseDouble(m.group(1));
            }
        }
        assertTrue("ARRANGEMENT: the ship must LOAD with the client present", !Double.isNaN(yRest));

        // The ship's own identity, so the gate readout below is about THIS craft rather than
        // whichever one a position lookup reaches.
        Matcher sid = SHIP_ID.matcher(shipInfo);
        assertTrue("ARRANGEMENT: ship-info must name the ship: " + shipInfo, sid.find());
        String shipUuid = sid.group(1);

        // Board post-assembly (the proven path - boarding variants have their own test).
        String mountInfo = exec("artest vs seat-mount 0");
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("ARRANGEMENT: seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("ARRANGEMENT: bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10);

        // ---- CONTROL LEG: plain flight works far below the line, or the refusal leg is void. ----
        double yControl = yRest;
        String refusalLine = null;
        double maxShipY = yRest;
        StringBuilder climb = new StringBuilder(64);
        StringBuilder diag = new StringBuilder(64);
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
                            + "refusal leg can indict the entry. yRest=" + yRest + " yControl="
                            + yControl, (yControl - yRest) >= MIN_CONTROL_CLIMB);

            // ---- REFUSAL LEG: keep climbing until the refusal message lands in the CLIENT chat.
            // The exhausted pool refuses the entry the moment the ship crosses the line; the
            // pilot's own chat is where the player reads it (i18n already resolved).
            //
            // The gate is sampled along the way, sparsely (every ~100 ticks - the readout scans the
            // ship's subspace yard, so a per-poll read would be a load source in the very climb it
            // is watching). Without it a missing message has four indistinguishable explanations:
            // the ship never reached the line, the trigger declined, the entry was declined, or the
            // message was sent to nobody. The last sample and the highest altitude seen are what
            // the assertion below reports.
            int climbBudget = (int) (800 * TestTimeouts.factor());
            for (int attempt = 0; attempt < climbBudget && refusalLine == null; attempt++) {
                bot().waitTicks(5);
                refusalLine = chatLineContaining(REFUSAL_NEEDLE);
                if (attempt % 10 == 0) {
                    // The ship's own state, asked BY IDENTITY and off the registry - it neither
                    // force-loads the ship's subspace yard nor touches a chunk, so the climb it is
                    // watching gets exactly the resources it would have got unwatched.
                    String s = exec("artest vs ship-info 0 id " + shipUuid);
                    // THE DISCRIMINATOR for the refused-entry defect, sampled ACROSS the dying climb rather than
                    // after it. Three candidate causes, and the climb trace alone cannot separate
                    // them: the tile instance is being replaced under the ship (afcIdentity changes),
                    // the computer is not ticking at all (controllerTicks flat), or the packet
                    // arrives and is refused at the seat's pilot guard (received climbs while
                    // delivered does not). Sampled at the same cadence as the altitude so the two
                    // timelines line up tick for tick.
                    if (diag.length() < 900) {
                        String d = exec("artest vs seat-delivery");
                        diag.append(' ').append(attempt).append(":recv=")
                                .append(firstGroupOr(RECEIVED, d, "?"))
                                .append("/deliv=").append(firstGroupOr(DELIVERED, d, "?"));
                    }
                    Matcher py = POS_Y.matcher(s);
                    Matcher vy = VEL_Y.matcher(s);
                    if (py.find()) {
                        double y = Double.parseDouble(py.group(1));
                        maxShipY = Math.max(maxShipY, y);
                        // A bounded timeline, not a last-value snapshot: a climb that stops is a
                        // shape, and the tick it changed shape at is the whole question. The
                        // VERTICAL VELOCITY rides along because an altitude that stops rising
                        // cannot say whether the ship is being held, braked or simply not pushed.
                        if (climb.length() < 900) {
                            climb.append(' ').append(attempt).append(':')
                                    .append(String.format(Locale.ROOT, "%.1f", y))
                                    .append('/')
                                    .append(vy.find() ? vy.group(1) : "?");
                        }
                    }
                }
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        // The gate is read HERE, on the failing path only, for the same reason the climb is sampled
        // passively above: it resolves the ship through its subspace yard, which force-loads chunks.
        // A missing refusal has four explanations - the ship never reached the line, the trigger
        // declined, the entry declined, or nobody was there to tell - and these two readings
        // separate all four. `lastDecision` NEVER-ASKED with a maxShipY under the ceiling is the
        // first of them, and it exonerates every part of the entry path.
        assertTrue("a pilot whose entry is refused (pool exhausted) must be TOLD so in his own "
                        + "chat - a silent refusal reads as a dead ship. chat="
                        + bot().reportChat(8) + " subsystem=" + exec("artest space subsystem-status")
                        + " maxShipY=" + maxShipY
                        + " delivery(attempt:recv/deliv)=[" + diag.toString().trim() + "]"
                        + " climb(attempt:y/velY)=[" + climb.toString().trim()
                        + "] gate=" + exec("artest space entry-gate 0 " + shipUuid),
                refusalLine != null);

        // Still seated: two consecutive positive samples (a lost seat can read riding=true for a
        // packet-lag moment, never twice with a wait between).
        JsonObject riding = bot().reportRidingEntity();
        boolean prev = isRiding(riding);
        boolean seatedTwice = false;
        int settleBudget = (int) (20 * TestTimeouts.factor());
        for (int attempt = 0; attempt < settleBudget && !seatedTwice; attempt++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
            seatedTwice = prev && isRiding(riding);
            prev = isRiding(riding);
        }
        assertTrue("a REFUSED entry must leave the pilot IN HIS SEAT - the crossing may unseat "
                        + "nobody until it is granted. riding=" + riding
                        + " delivery=" + exec("artest vs seat-delivery"), seatedTwice);

        // Still in the launch world: the ship never crossed, and neither did the pilot.
        JsonObject weather = bot().reportWeather();
        int clientDim = weather.has("dim") ? weather.get("dim").getAsInt() : Integer.MIN_VALUE;
        assertTrue("after a refusal the pilot's client must still be in the LAUNCH dimension "
                + "(0), not a space cell. clientDim=" + clientDim, clientDim == 0);

        // And nothing entered space: the refusal was a refusal, not a half-crossing.
        Matcher lm = LEDGER.matcher(exec("artest space subsystem-status"));
        assertTrue("subsystem-status must report the ledger", lm.find());
        assertTrue("a refused entry must ledger NOTHING into space (ledger="
                + lm.group(1) + ")", Integer.parseInt(lm.group(1)) == 0);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private ClientBot bot() {
        return clientHarness.bot();
    }

    /** First capture group of {@code p} in {@code s}, or {@code fallback} — a missing field must read
     *  as "not answered" and never as a number, which is how a dead probe reads as a real zero. */
    private static String firstGroupOr(Pattern p, String s, String fallback) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : fallback;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String shipInfoAtBase() throws Exception {
        return exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
    }

    /** The newest client chat line containing {@code needle} (case-insensitive), or null. */
    private String chatLineContaining(String needle) throws Exception {
        JsonArray lines = bot().reportChat(8).getAsJsonArray("lines");
        if (lines == null) {
            return null;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).getAsString();
            if (line.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                return line;
            }
        }
        return null;
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
