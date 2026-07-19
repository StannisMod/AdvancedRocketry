package zmaster587.advancedRocketry.test.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import static org.junit.Assert.assertTrue;

/**
 * A seated pilot must be able to fly his ship WHILE THE SPACE SUBSYSTEM IS LIVE.
 *
 * <p>This is the same stimulus as the plain pilot-keys e2e - a real client player on the pilot seat
 * holding the real vertical-up key - with one difference: the production space subsystem is switched
 * ON. That difference is the whole point.</p>
 *
 * <p><b>Why this test has to exist separately.</b> The subsystem stands down whenever it detects a
 * test harness, so EVERY other tier-2 flight test on this branch runs with the branch's own headline
 * feature disabled. In real play it is the other way round: {@code enableSpaceSubsystem} defaults to
 * true and Valkyrien Skies is on by default for {@code runClient}/{@code runServer}, so a player is
 * always flying with the subsystem live. The flight computer's tick has subsystem-dependent branches
 * (a hyperspace park gate, a cell pose report, the entry on-ramp and the descent check), and NONE of
 * them are reachable in a harness run that leaves the subsystem down. The config flag seeded below
 * opts the production wiring back in, which is what makes this the configuration a player actually
 * runs.</p>
 *
 * <p>Manual server + client lifecycle rather than the shared base class, because the config has to be
 * written into the game directory BEFORE the server boots and the base class owns a throwaway root
 * it never exposes.</p>
 *
 * <p>Gated on real Valkyrien Skies - run with {@code -PwithVS}.</p>
 */
public class VSPilotKeysWithSpaceSubsystemE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 2800, BY = 64, BZ = 2800;
    private static final String BOT = "ForgeTestClient";

    /** The ship must gain at least this much altitude while the key is held, or it is not flying. */
    private static final double MIN_CLIMB = 1.0;

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

        root = Files.createTempDirectory("forge-pilot-with-space-");
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        // Opt the production space subsystem back in: without this the subsystem detects the harness
        // and stands down, and then this test would be a duplicate of the plain pilot-keys one.
        String cfg = "# seeded by VSPilotKeysWithSpaceSubsystemE2ETest\n"
                + "performance {\n"
                + "    B:spaceRegisterUnderTestHarness=true\n"
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
    public void aSeatedPilotCanStillFlyHisShipWhileTheSpaceSubsystemIsRegistered() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        // The subsystem must actually be up, or this test silently degrades into the plain
        // pilot-keys case and its green would mean nothing.
        String status = exec("artest space subsystem-status");
        assertTrue("the production space subsystem must be REGISTERED for this test to be about "
                + "anything - the seeded config is what opts it in: " + status,
                status.contains("\"registered\":true"));

        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        clientHarness.bot().waitTicks(10);

        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"ok\":true"));

        // Stand the client next to the ship so it stays loaded, then read its resting altitude.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        clientHarness.bot().waitTicks(20);

        double yBefore = Double.NaN;
        for (int attempt = 0; attempt < 40 && Double.isNaN(yBefore); attempt++) {
            clientHarness.bot().waitTicks(5);
            String info = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
            Matcher m = POS_Y.matcher(info);
            if (m.find()) {
                yBefore = Double.parseDouble(m.group(1));
            }
        }
        assertTrue("the ship must LOAD with the client present", !Double.isNaN(yBefore));

        String mountInfo = exec("artest vs seat-mount 0");
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("bot must mount the seat dummy: " + mount, mount.contains("\"mounted\":true"));
        clientHarness.bot().waitTicks(10);

        // The real key, through the real client input path, exactly as a player holds it.
        double yAfter = yBefore;
        clientHarness.bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        try {
            for (int attempt = 0; attempt < 40 && (yAfter - yBefore) < MIN_CLIMB; attempt++) {
                clientHarness.bot().waitTicks(5);
                Matcher m = POS_Y.matcher(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
                if (m.find()) {
                    yAfter = Double.parseDouble(m.group(1));
                }
            }
        } finally {
            clientHarness.bot().releaseKey(Keyboard.KEY_R);
        }

        assertTrue("a seated pilot holding the vertical-up key must lift his ship even with the space "
                        + "subsystem registered - this is the configuration every real player runs, and "
                        + "it is the ONLY tier-2 flight configuration no other test covers. "
                        + "yBefore=" + yBefore + " yAfter=" + yAfter + " subsystem=" + status,
                (yAfter - yBefore) >= MIN_CLIMB);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
