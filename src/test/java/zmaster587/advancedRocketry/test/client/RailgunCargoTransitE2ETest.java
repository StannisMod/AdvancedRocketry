package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Issue #61 ("[BUG] Railgun does not work") — client e2e for the railgun
 * cargo-transit mechanic, the mandatory player-truth guard required by
 * {@code sops/development/bug-report-workflow.md}.
 *
 * <p>The railgun is a paired item TELEPORT: a source railgun pulls a stack
 * from its input port and dispatches it to a linked destination railgun,
 * whose {@code onReceiveCargo} deposits it in the output port
 * ({@link zmaster587.advancedRocketry.tile.multiblock.TileRailgun#attemptCargoTransfer}).
 * {@link zmaster587.advancedRocketry.test.server.RailgunFiringContractTest}
 * pins the same contracts on a dedicated server; THIS test re-pins them with
 * a REAL client connected to the server, so a client/server desync in the
 * teleport path (cargo that moves server-side but never syncs to a connected
 * player) would surface here where the server-only test is blind.</p>
 *
 * <p>Per the AR client-test convention (see
 * {@code GasChargePadFillsPressureTankE2ETest}), setup and observation run
 * through server-side {@code artest} probes; the client bot is the live
 * harness anchor. The new {@code artest infra railgun-fire} probe drives the
 * source-side path and reports where the cargo ended up.</p>
 *
 * <p>Headless: runs under {@code xvfb-run} / a dedicated {@code DISPLAY};
 * auto-skips when no display is available.</p>
 */
public class RailgunCargoTransitE2ETest extends AbstractClientE2ETest {

    private static final int SX = 100;
    private static final int SY = 64;
    private static final int SZ = 100;

    private static final int DX = 160;
    private static final int DY = 64;
    private static final int DZ = 100;

    /** Not registered/loaded on the harness server → production
     *  {@code net.minecraftforge.common.DimensionManager.getWorld(id)} is null. */
    private static final int UNLOADED_DIM = 31337;

    private static final int CARGO = 16;

    private static final Pattern FIRED =
            Pattern.compile("\"fired\":(true|false)");
    private static final Pattern DEST_MATCHED =
            Pattern.compile("\"destMatched\":(\\d+)");
    private static final Pattern SRC_REMAINING =
            Pattern.compile("\"srcInputRemaining\":(\\d+)");

    /**
     * Same-dimension shot fires with a real client connected: cargo leaves
     * the source input and arrives at the destination output — the
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
     * The #61 root-cause mode under a live client: firing at a destination in
     * an unloaded dimension is a SILENT no-op and the cargo is preserved.
     */
    @Test
    public void railgunDoesNotFireToUnloadedDestinationClientSide() throws Exception {
        bot().waitForWorld();
        forceloadFootprints();

        buildAndComplete(SX, SY, SZ);

        String fire = exec("artest infra railgun-fire 0 " + SX + " " + SY + " " + SZ
                + " " + UNLOADED_DIM + " 0 64 0 minecraft:cobblestone " + CARGO);
        assertTrue("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("railgun must NOT fire when the destination dimension is "
                        + "unloaded (issue #61 root cause); fire=" + fire,
                "false".equals(extractStr(fire, FIRED)));
        assertTrue("destination dimension must be reported unloaded; fire=" + fire,
                fire.contains("\"destLoaded\":false"));

        int srcRemaining = extractInt(fire, SRC_REMAINING);
        assertTrue("cargo must be preserved in the source input on a failed "
                        + "shot (remaining=" + srcRemaining + " expected " + CARGO
                        + "); fire=" + fire,
                srcRemaining == CARGO);
    }

    // -- helpers ----------------------------------------------------------

    /** Force-load the chunks covering both railgun footprints so the
     *  dedicated server can place + tick them (they sit well outside the
     *  spawn-loaded area). Footprint per railgun: x[cx-4..cx+4],
     *  z[cz-1..cz+7]; both share z-chunk 6, x-chunks 6..10. */
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
        assertTrue("pattern not found in: " + src, m.find());
        return m.group(1);
    }

    private static int extractInt(String src, Pattern pattern) {
        return Integer.parseInt(extractStr(src, pattern));
    }
}
