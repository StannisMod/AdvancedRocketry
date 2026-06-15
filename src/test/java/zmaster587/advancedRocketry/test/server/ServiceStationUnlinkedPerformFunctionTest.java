package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Regression guard for the standalone-repair null-deref invariant (PR #23
 * review note #5).
 *
 * <p>{@code TileRocketServiceStation.tryStandaloneRepair()} dereferences
 * {@code ((EntityRocket) linkedRocket).storage} with no null/type check. That
 * is safe <i>by construction</i>: {@code tryStandaloneRepair} is only ever
 * reached from {@code performFunction()}'s {@code if (linkedRocket instanceof
 * EntityRocket)} branch, and {@code unlinkRocket()} additionally clears
 * {@code partsToRepair} — so the standalone path can never run with a null (or
 * non-rocket) {@code linkedRocket}.</p>
 *
 * <p>This pins that invariant directly: driving {@code performFunction} on a
 * powered but UNLINKED service station must be a safe no-op — it must not reach
 * the standalone-repair path and must not throw. The {@code service-perform-
 * function} probe wraps the call in try/catch and reports {@code "performFunction
 * threw"} on any {@link RuntimeException}, so a regression (the {@code
 * instanceof} guard removed, or {@code tryStandaloneRepair} hoisted out of it)
 * surfaces here as a failed {@code "ok":true} assertion rather than a silent
 * NPE in production.</p>
 */
public class ServiceStationUnlinkedPerformFunctionTest extends AbstractSharedServerTest {

    // Isolated lane, clear of the other service-station fixtures.
    private static final int X = 16400;
    private static final int Y = 70;
    private static final int Z = 15900;

    @Test
    public void performFunctionOnUnlinkedPoweredStationIsSafeNoOp() throws Exception {
        int cx = X >> 4, cz = Z >> 4;
        exec("artest chunk warmup 0 " + cx + " " + cz + " " + cx + " " + cz);
        exec("artest fill 0 " + X + " " + Y + " " + Z + " " + X + " " + (Y + 1) + " "
                + Z + " minecraft:air");

        String place = exec("artest place 0 " + X + " " + Y + " " + Z
                + " advancedrocketry:serviceStation");
        assertTrue("service station place failed: " + place,
                place.contains("\"placed\":true"));

        // Power it (performFunction's getEquivalentPower gate) but DO NOT link a
        // rocket — linkedRocket stays null.
        exec("artest place 0 " + X + " " + (Y + 1) + " " + Z + " minecraft:redstone_block");

        // Sanity: truly unlinked, empty repair queue.
        String pre = exec("artest infra service-state 0 " + X + " " + Y + " " + Z);
        assertTrue("station must be unlinked: " + pre, pre.contains("\"linkedRocketId\":-1"));
        assertTrue("repair queue must be empty: " + pre, pre.contains("\"partsToRepairCount\":0"));

        // The concern: performFunction must NOT reach tryStandaloneRepair's
        // ((EntityRocket) linkedRocket).storage with a null linkedRocket.
        String pf = exec("artest infra service-perform-function 0 " + X + " " + Y + " " + Z);
        assertTrue("performFunction on an unlinked powered station must be a safe "
                + "no-op (no NPE/CCE reaching the standalone-repair path): " + pf,
                pf.contains("\"ok\":true"));

        // State still sane after the no-op.
        String post = exec("artest infra service-state 0 " + X + " " + Y + " " + Z);
        assertTrue("repair queue still empty after no-op performFunction: " + post,
                post.contains("\"partsToRepairCount\":0"));
    }
}
