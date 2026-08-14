package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * The shipped default, on a real client: <b>a bed on a planet sets your spawn and wakes you up, and
 * the morning does not come.</b>
 *
 * <p>A planet's day is the turning of a body in an orbit. Fast-forwarding it from a mattress is a
 * Minecraft convenience, not a planetary one, so past the atmosphere it is off — and the player is
 * TOLD, because silence is what makes a locked bed read as a broken bed.</p>
 *
 * <h2>Why the chat line is the load-bearing assertion, not decoration</h2>
 * "The clock did not jump" is satisfied by a player who never fell asleep at all — a failed
 * right-click, a mob in range, a bed the server refused. The message is emitted from INSIDE the
 * sleep-completion branch, so its arrival proves the sleep completed AND that the lock is what
 * stopped the skip. It is asserted before the clock is, for that reason.
 *
 * <h2>Why the night is staged through the flag</h2>
 * Staging needs {@code /time set} to reach the planet, which is the very thing under test. So the
 * flag is turned on for the staging and off again before the player sleeps — the same runtime flip
 * the server-tier {@code /time} test uses. That the staging LANDED is asserted; without it the test
 * would sleep in the daytime and prove nothing.
 *
 * <p>The other side of this flag — with the arcade mechanic opted back in, the skip still landing on
 * the PLANET's own dawn rather than vanilla's 24000 rounding — is {@link PlanetBedSleepE2ETest}.
 * Neither class is a witness on its own: a build that ignored the flag entirely would satisfy
 * whichever of the two happened to match its hard-coded behaviour.</p>
 */
public class PlanetBedSleepLockedE2ETest {

    private static final int DIM = 9502;
    private static final int ROTATIONAL_PERIOD = 30000;
    private static final String PLAYER = "ForgeTestClient";

    /** Mid-air stone platform well above worldgen terrain — no mobs, flat, deterministic. */
    private static final int PLAT_Y = 150;
    private static final int BED_X = 8, BED_Y = PLAT_Y + 1, BED_FOOT_Z = 9, BED_HEAD_Z = 10;

    /** Planet-night for a 30000-tick day: phase 0.67, well clear of both dawn and dusk. */
    private static final long STAGED_NIGHT = 20000L;

    /** How far the clock may advance on its own while the player sleeps and the polls run. */
    private static final long DRIFT_ALLOWANCE = 2000L;

    /** The needle of the locked-skip message, as the player reads it (i18n already resolved). */
    private static final String LOCKED_NEEDLE = "turns at its own rate";

    private Path workDir;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-client-bed-locked-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"LockedPlanet\" DIMID=\"" + DIM + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>100</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>" + ROTATIONAL_PERIOD + "</rotationalPeriod>\n"
                + "            <atmosphereDensity>100</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n"
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
        // The shipped default, written out rather than relied upon: a test whose premise is "the
        // default is false" should fail loudly if that default ever changes, not quietly measure
        // something else.
        Files.write(arConfigDir.resolve("advancedRocketry.cfg"),
                ("# seeded by PlanetBedSleepLockedE2ETest\n"
                        + "planet {\n"
                        + "    B:allowTimeSkipOnPlanets=false\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startupException) {
            try {
                serverHarness.close();
            } catch (Exception cleanup) {
                startupException.addSuppressed(cleanup);
            }
            serverHarness = null;
            throw startupException;
        }
    }

    @After
    public void stopBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                deferred = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (deferred == null) deferred = e;
                else deferred.addSuppressed(e);
            }
            serverHarness = null;
        }
        if (deferred != null) throw deferred;
    }

    @Test
    public void sleepingOnAPlanetSetsSpawnAndWakesYouWithoutBringingMorning() throws Exception {
        clientHarness.bot().waitForWorld();
        serverHarness.client().execute("gamerule doMobSpawning false");

        serverHarness.client().execute("artest weather set " + DIM + " clear 12000");
        serverHarness.client().execute("artest fill " + DIM + " 4 " + PLAT_Y + " 4 12 " + PLAT_Y
                + " 12 minecraft:stone");
        serverHarness.client().execute("artest place " + DIM + " " + BED_X + " " + BED_Y + " "
                + BED_FOOT_Z + " minecraft:bed 0");
        serverHarness.client().execute("artest place " + DIM + " " + BED_X + " " + BED_Y + " "
                + BED_HEAD_Z + " minecraft:bed 8");

        serverHarness.client().execute("artest tp " + DIM);
        waitForClientDim(DIM);
        awaitPlayerBesideBed();

        // Stage planet-night THROUGH the flag, then lock it again before anyone sleeps.
        serverHarness.client().execute("artest config set allowTimeSkipOnPlanets true");
        serverHarness.client().execute("time set " + STAGED_NIGHT);
        serverHarness.client().execute("artest config set allowTimeSkipOnPlanets false");
        clientHarness.bot().waitTicks(30); // skylightSubtracted has to catch up for trySleep

        long staged = dimTime(DIM);
        assertTrue("ARRANGEMENT: the staging must have reached the planet's own clock, or the"
                        + " player sleeps in daylight and nothing below is a measurement. got="
                        + staged, staged >= STAGED_NIGHT && staged < STAGED_NIGHT + DRIFT_ALLOWANCE);

        JsonObject click = clientHarness.bot().interactBlock(BED_X, BED_Y, BED_FOOT_Z);
        assertTrue("bed right-click must not error: " + click, click.has("result"));

        // A full vanilla sleep completes 100 ticks after everyone is in bed; poll past that.
        String seen = pollForLockedMessage();
        assertTrue("THE LOAD-BEARING ONE: the player must be TOLD that this world's morning is not"
                        + " coming, and that line is only ever sent from inside the completed-sleep"
                        + " branch — so its absence means either the lock did not engage or the"
                        + " player never actually slept, and this test refuses to tell those two"
                        + " apart by assuming. The reading that DOES tell them apart follows"
                        + " (taken on this failing path only): sleep-state=" + sleepState()
                        + " chat=" + seen,
                seen.contains(LOCKED_NEEDLE));

        long after = dimTime(DIM);
        assertTrue("THE CONTRACT: a bed may not fast-forward a planet's day. The clock was at "
                        + staged + " before the sleep and " + after + " after it — a jump to this"
                        + " planet's next dawn would be " + ROTATIONAL_PERIOD + ", six times the"
                        + " allowance",
                after - staged <= DRIFT_ALLOWANCE);
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The client's own chat, i18n resolved — what the player actually reads. */
    private String pollForLockedMessage() throws Exception {
        StringBuilder last = new StringBuilder();
        for (int attempt = 0; attempt < 30; attempt++) {
            clientHarness.bot().waitTicks(20);
            last.setLength(0);
            JsonArray lines = clientHarness.bot().reportChat(20).getAsJsonArray("lines");
            if (lines != null) {
                for (JsonElement line : lines) {
                    last.append(line.getAsString()).append(" | ");
                }
            }
            if (last.indexOf(LOCKED_NEEDLE) >= 0) {
                break;
            }
        }
        return last.toString();
    }

    /**
     * Stand the player on the platform beside the bed, and do not proceed until that is MEASURED.
     *
     * <p>One {@code /tp} onto the platform is not a guarantee: measured 2026-08-14, the first one
     * issued after the planet arrival does not hold — the player is found on the planet's natural
     * terrain sixty-odd blocks below, with the platform still solid under the bed. A player that far
     * away cannot click the bed, and the resulting silence reads exactly like a bed that refused. So
     * the staging is re-issued until the server agrees the player is inside vanilla's own bed range,
     * and a failure carries the reading that says why.</p>
     */
    private void awaitPlayerBesideBed() throws Exception {
        String last = "";
        for (int attempt = 0; attempt < 12; attempt++) {
            serverHarness.client().execute("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
            clientHarness.bot().waitTicks(10);
            last = sleepState();
            int brace = last.indexOf('{');
            if (brace < 0) {
                continue;
            }
            JsonObject state = new JsonParser().parse(last.substring(brace)).getAsJsonObject();
            if (state.get("onGround").getAsBoolean() && state.get("distanceToBed").getAsDouble() <= 3.0) {
                return;
            }
        }
        throw new AssertionError("ARRANGEMENT: the player could not be stood beside the bed, so"
                + " nothing below would be a measurement of the bed at all. Last reading: " + last);
    }

    /**
     * Every input the bed path consumes, read off the server on the FAILING path only. Asked about
     * the bed HEAD — the position {@code BlockBed} normalizes a click to, and the one the provider's
     * own sleep gate is asked about.
     */
    private String sleepState() {
        try {
            return String.join("\n", serverHarness.client().execute(
                    "artest player sleep-state " + BED_X + " " + BED_Y + " " + BED_HEAD_Z));
        } catch (Exception e) {
            return "<probe failed: " + e + ">";
        }
    }

    private long dimTime(int dim) throws Exception {
        String raw = String.join("\n", serverHarness.client().execute("artest dim time " + dim));
        int start = raw.indexOf('{');
        assertTrue("dim time probe must return JSON: " + raw, start >= 0);
        JsonObject json = new JsonParser().parse(raw.substring(start)).getAsJsonObject();
        return json.get("worldTime").getAsLong();
    }

    /** Same shape as the sibling's: the client's own weather report names the dim it is rendering. */
    private void waitForClientDim(int expectedDim) throws Exception {
        for (int waited = 0; waited < 200; waited += 10) {
            clientHarness.bot().waitTicks(10);
            JsonObject w = clientHarness.bot().reportWeather();
            if (w != null && w.has("dim") && w.get("dim").getAsInt() == expectedDim) {
                return;
            }
        }
        throw new AssertionError("client never reached dim " + expectedDim
                + " (last weather report: " + clientHarness.bot().reportWeather() + ")");
    }
}
