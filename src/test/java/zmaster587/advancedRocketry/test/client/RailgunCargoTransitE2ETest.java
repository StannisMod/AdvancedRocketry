package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Issue #61 ("[BUG] Railgun does not work") — client e2e for the railgun
 * cargo-transit mechanic, the mandatory player-truth guard. Pins the FIXED
 * behaviour with a REAL client connected.
 *
 * <p>The railgun is a paired item TELEPORT: a source railgun pulls a stack
 * from its input port and dispatches it to a linked destination railgun
 * ({@link zmaster587.advancedRocketry.tile.multiblock.TileRailgun#attemptCargoTransfer}).
 * {@link zmaster587.advancedRocketry.test.server.RailgunFiringContractTest}
 * pins these contracts on a dedicated server (plus the registered-but-unloaded
 * dimension-load branch, a server-internal mechanism); THIS test re-pins the
 * player-visible ones with a live client, so a client/server desync in the
 * teleport path would surface here where the server-only test is blind.</p>
 *
 * <p>Per the AR client-test convention (see
 * {@code GasChargePadFillsPressureTankE2ETest}), setup and observation run
 * through server-side {@code artest} probes; the client bot is the live
 * harness anchor. Headless: runs under {@code xvfb-run} / a dedicated
 * {@code DISPLAY}; auto-skips when no display is available.</p>
 */
public class RailgunCargoTransitE2ETest extends AbstractClientE2ETest {

    private static final int SX = 100;
    private static final int SY = 64;
    private static final int SZ = 100;

    private static final int DX = 160;
    private static final int DY = 64;
    private static final int DZ = 100;

    /** Not registered on the harness server → production cannot load it. */
    private static final int UNREGISTERED_DIM = 31337;

    private static final int CARGO = 16;

    private static final Pattern FIRED =
            Pattern.compile("\"fired\":(true|false)");
    private static final Pattern DEST_MATCHED =
            Pattern.compile("\"destMatched\":(\\d+)");
    private static final Pattern SRC_REMAINING =
            Pattern.compile("\"srcInputRemaining\":(\\d+)");
    private static final Pattern FIRE_STATUS =
            Pattern.compile("\"fireStatus\":\"([A-Z_]+)\"");
    private static final Pattern DEST_LOADED =
            Pattern.compile("\"destLoaded\":(true|false)");

    /**
     * Same-dimension shot fires with a real client connected: cargo leaves the
     * source input and arrives at the destination output (status FIRED) — the
     * player-visible "railgun works" contract for #61.
     */
    @Test
    public void cargoTransitsBetweenLinkedRailgunsClientSide() throws Exception {
        bot().waitForWorld();
        forceloadFootprints();

        buildAndComplete(SX, SY, SZ);
        buildAndComplete(DX, DY, DZ);

        String fire = exec("artest infra railgun-fire 0 " + SX + " " + SY + " " + SZ
                + " 0 " + DX + " " + DY + " " + DZ + " minecraft:cobblestone " + CARGO);
        assertTrue("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("railgun MUST fire to a linked railgun in the same dimension "
                        + "with a client connected (issue #61 baseline); fire=" + fire,
                "true".equals(extractStr(fire, FIRED)));
        assertTrue("status must read FIRED after a successful shot; fire=" + fire,
                "FIRED".equals(extractStr(fire, FIRE_STATUS)));

        int destMatched = extractInt(fire, DEST_MATCHED);
        assertTrue("destination output port must contain >= " + CARGO
                        + " cobblestone after firing; fire=" + fire,
                destMatched >= CARGO);

        int srcRemaining = extractInt(fire, SRC_REMAINING);
        assertTrue("source input port must be drained after firing "
                        + "(remaining=" + srcRemaining + "); fire=" + fire,
                srcRemaining == 0);
    }

    /**
     * Under a live client, a genuinely unavailable destination (an unregistered
     * dimension that cannot be loaded) does NOT fire and REPORTS the reason
     * (TARGET_UNAVAILABLE) — the #61 fix's "no more silent no-op" — with the
     * cargo preserved.
     */
    @Test
    public void railgunReportsUnavailableForUnloadableDestinationClientSide() throws Exception {
        bot().waitForWorld();
        forceloadFootprints();

        buildAndComplete(SX, SY, SZ);

        String fire = exec("artest infra railgun-fire 0 " + SX + " " + SY + " " + SZ
                + " " + UNREGISTERED_DIM + " 0 64 0 minecraft:cobblestone " + CARGO);
        assertTrue("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("railgun must NOT fire at an unloadable (unregistered) "
                        + "destination; fire=" + fire,
                "false".equals(extractStr(fire, FIRED)));
        assertTrue("unregistered dim cannot be loaded → destLoaded:false; "
                        + "fire=" + fire, "false".equals(extractStr(fire, DEST_LOADED)));
        assertTrue("status must report TARGET_UNAVAILABLE (not a silent no-op); "
                        + "fire=" + fire,
                "TARGET_UNAVAILABLE".equals(extractStr(fire, FIRE_STATUS)));

        int srcRemaining = extractInt(fire, SRC_REMAINING);
        assertTrue("cargo must be preserved on a failed shot (remaining="
                        + srcRemaining + " expected " + CARGO + "); fire=" + fire,
                srcRemaining == CARGO);
    }

    // -- helpers ----------------------------------------------------------

    /** Force-load the chunks covering both railgun footprints so the dedicated
     *  server can place + tick them. */
    private void forceloadFootprints() throws Exception {
        for (int cx = 5; cx <= 11; cx++) {
            for (int cz = 5; cz <= 7; cz++) {
                exec("artest chunk forceload 0 " + cx + " " + cz);
            }
        }
    }

    private void buildAndComplete(int x, int y, int z) throws Exception {
        String fixture = exec("artest fixture multiblock railgun 0 "
                + x + " " + y + " " + z);
        assertTrue("fixture multiblock railgun failed at " + x + "," + y + "," + z
                + ": " + fixture, fixture.contains("\"ok\":true"));

        String tryComplete = exec("artest machine try-complete 0 "
                + x + " " + y + " " + z);
        assertTrue("railgun must validate at " + x + "," + y + "," + z
                + ": " + tryComplete, tryComplete.contains("\"isComplete\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private static String extractStr(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern + " not found in: " + src, m.find());
        return m.group(1);
    }

    private static int extractInt(String src, Pattern pattern) {
        return Integer.parseInt(extractStr(src, pattern));
    }
}
