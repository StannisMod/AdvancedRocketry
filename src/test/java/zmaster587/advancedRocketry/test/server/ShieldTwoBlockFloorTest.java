package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * P1 floor for the vendored AFFS shield: a shield network needs no cable.
 *
 * <p>The trimmed topology ({@code ShieldNetworkManager}) forms a network from block-adjacency of ALL
 * shield nodes, and the max-flow links a source's supply port directly to an adjacent sink's demand
 * port. So a shield <em>generator</em> touching a field <em>emitter</em> — two blocks, no cable, no
 * console, no accumulator — is a working shield.</p>
 *
 * <p><b>What this pins.</b> The positive method charges a generator that is directly adjacent to an
 * emitter and asserts the emitter powers up (energy crossed the cable-less edge). The control places
 * the same two blocks with a one-block gap and no cable between them: they fall into two disconnected
 * components, no flow crosses the gap, and the emitter never powers — proving it is the adjacency edge,
 * not incidental ticking, that lights the positive case.</p>
 *
 * <p>The network solve lives in a {@code WorldTickEvent} handler, which a command (running inside a
 * server tick) cannot advance; the test drives it deterministically via {@code /artest shield tick}.</p>
 */
public class ShieldTwoBlockFloorTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;
    private static final int CHARGE_ITERATIONS = 60;
    private static final int FE_PER_ITERATION = 4000;

    @Test
    public void twoBlockShieldPowersWithoutCable() throws Exception {
        // Generator at G, emitter directly adjacent along +X. No cable, console or accumulator.
        int gx = 900, gz = 760;
        int ex = gx + 1, ez = gz;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, ez);

        chargeAndSolve(gx, gz);

        String emitter = exec("artest shield read " + DIM + " " + ex + " " + Y + " " + ez);
        assertTrue("adjacent generator+emitter (no cable) failed to power — the cable-less edge did not "
                        + "carry shield energy:\n" + emitter,
                emitter.contains("\"powered\":true"));
    }

    @Test
    public void nonAdjacentPairNeverPowers() throws Exception {
        // Same two blocks, but a one-block gap and no cable: two disconnected components.
        int gx = 920, gz = 760;
        int ex = gx + 2, ez = gz; // gap at gx+1
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, ez);

        chargeAndSolve(gx, gz);

        String emitter = exec("artest shield read " + DIM + " " + ex + " " + Y + " " + ez);
        assertTrue("disconnected emitter (one-block gap, no cable) powered anyway — a spurious edge is "
                        + "carrying energy across the gap:\n" + emitter,
                emitter.contains("\"powered\":false"));
    }

    /** Feed the generator FE and run one network solve per iteration. */
    private void chargeAndSolve(int gx, int gz) throws Exception {
        for (int i = 0; i < CHARGE_ITERATIONS; i++) {
            exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
            exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
            exec("artest shield tick " + DIM);
        }
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
