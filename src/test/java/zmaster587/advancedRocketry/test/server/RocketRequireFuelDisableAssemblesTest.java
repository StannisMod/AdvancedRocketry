package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Disableability contract for {@code rocketRequireFuel} at rocket-assembly time.
 *
 * <p>{@code rocketRequireFuel=false} means "fuel is not required to fly". The
 * player-facing promise is that a valid rocket (engines + guidance) then
 * assembles regardless of fuel adequacy — the assembly fuel-capacity gate is
 * skipped entirely.</p>
 *
 * <p>This pins a regression introduced by the weight-system merge: it added a
 * {@code getBaseFuelRate() <= 0 -> NOFUEL} guard to {@code hasEnoughFuel}. With
 * {@code rocketRequireFuel=false}, {@link
 * zmaster587.advancedRocketry.api.StatsRocket#getBaseFuelRate} returns 0 by
 * design (the rocket burns no fuel), so the new guard turned every
 * {@code rocketRequireFuel=false} build into {@code NOFUEL} — no number of fuel
 * tanks could satisfy it (the gate fails on the engine fuel <i>rate</i>, not on
 * tank capacity). Before the merge the same path divided by that zero rate and
 * accidentally passed via a {@code +Infinity} burn time.</p>
 *
 * <p>The {@code simple} fixture assembles to SUCCESS on the default
 * {@code rocketRequireFuel=true} (pinned by {@code RocketAssemblySmokeTest}); the
 * contract here is that flipping the flag off does not break that.</p>
 */
public class RocketRequireFuelDisableAssemblesTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z_]+)\"");

    private String cmd(String c) throws Exception {
        return String.join("\n", client().execute(c));
    }

    /** Build the simple fixture at the given pad and return the raw assemble response. */
    private String buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        client().execute("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        client().execute("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air");
        String fixture = cmd("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple");
        assertTrue("fixture build failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("no builderPos: " + fixture, bp.find());
        return cmd("artest rocket assemble 0 "
                + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    private static String status(String assembleResponse) {
        Matcher m = STATUS.matcher(assembleResponse);
        return m.find() ? m.group(1) : "<none>";
    }

    @Test
    public void validRocketAssemblesWhenFuelNotRequired() throws Exception {
        try {
            // Positive control: the simple fixture assembles on the default.
            // The assemble probe reports "ok":true only when the SCAN status was
            // SUCCESS; the "status" field it echoes is the POST-assemble status
            // (ALREADY_ASSEMBLED), so we gate on "ok":true, not status==SUCCESS.
            assertTrue(cmd("artest config set rocketRequireFuel true").contains("\"ok\":true"));
            String on = buildAndAssemble(3400, 64, 3400);
            assertTrue("simple fixture must assemble on rocketRequireFuel=true (scan SUCCESS): " + on,
                    on.contains("\"ok\":true"));

            // Contract: flipping fuel off must NOT block assembly. Pre-fix the
            // scan returned NOFUEL (the regression) and "ok":true was absent.
            assertTrue(cmd("artest config set rocketRequireFuel false").contains("\"ok\":true"));
            String off = buildAndAssemble(3460, 64, 3400);
            assertTrue("with rocketRequireFuel=false a valid rocket must still assemble "
                    + "(no fuel-adequacy gate); scan status was " + status(off) + ": " + off,
                    off.contains("\"ok\":true"));
        } finally {
            // Restore the shared-harness default for any later test in this JVM.
            client().execute("artest config set rocketRequireFuel true");
        }
    }
}
