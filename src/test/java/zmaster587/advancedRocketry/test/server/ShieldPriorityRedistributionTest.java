package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * P4 (D134-5): redistribution priority — under an energy deficit the network fills higher-priority
 * emitters first, so a player can pour a starved supply into the emitters that matter ("all power to the
 * rear shields"). One generator feeds two emitters through the middle; it produces enough for only one,
 * so the two are in genuine competition.
 *
 * <p>The load-bearing, DFS-order-independent proof is that the fed emitter <b>follows the priority
 * setting</b>: raise emitter A and A is the one that powers while B starves; flip the priority to B and
 * the outcome flips. A plain max-flow with equal priority could favour either arbitrarily, so only the
 * flip proves priority — not the layout — decides who is fed.</p>
 *
 * <p>The network solve lives in a {@code WorldTickEvent} handler a command cannot advance from inside a
 * server tick; the test drives it via {@code /artest shield tick}.</p>
 */
public class ShieldPriorityRedistributionTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;
    private static final int FE_PER_ITERATION = 4000;
    private static final Pattern STORED = Pattern.compile("\"shieldStored\":(-?\\d+)");

    @Test
    public void underDeficitTheHigherPriorityEmitterIsFedAndFollowsTheSetting() throws Exception {
        // A - generator - B, all adjacent: one network, the generator between the two emitters. It
        // converts 4000/tick — enough to fill one emitter's 4000/tick intake, not both.
        int ax = 1060, z = 820;
        int gx = ax + 1, bx = ax + 2;
        place("affs:field_generator", ax, z);
        place("affs:shield_generator", gx, z);
        place("affs:field_generator", bx, z);

        // Phase 1: A outranks B. The scarce supply must fill A while B starves.
        setPriority(ax, z, 1);
        setPriority(bx, z, 0);
        chargeBoth(gx, z, 9);

        assertPoweredAndStarved("A(high)", ax, "B(low)", bx, z);

        // Phase 2: flip. Zero both coils, swap the priorities, and the FED emitter must swap to B — proving
        // priority, not the layout or the solver's traversal order, decides who is fed.
        exec("artest shield charge " + DIM + " " + ax + " " + Y + " " + z + " 0");
        exec("artest shield charge " + DIM + " " + bx + " " + Y + " " + z + " 0");
        setPriority(ax, z, 0);
        setPriority(bx, z, 1);
        chargeBoth(gx, z, 9);

        assertPoweredAndStarved("B(high)", bx, "A(low)", ax, z);
    }

    private void assertPoweredAndStarved(String fedName, int fedX, String starvedName, int starvedX, int z)
            throws Exception {
        String fed = read(fedX, z);
        String starved = read(starvedX, z);
        long fedStored = readStored(fed);
        long starvedStored = readStored(starved);
        assertTrue("the higher-priority emitter " + fedName + " was not powered under the deficit — the "
                + "scarce supply did not go to it first:\n" + fed, fed.contains("\"powered\":true"));
        assertTrue("the lower-priority emitter " + starvedName + " should starve while " + fedName
                + " is fed (fed=" + fedStored + " starved=" + starvedStored + "): priority did not "
                + "redistribute the deficit:\n" + starved, fedStored > starvedStored + 15_000L);
    }

    private void chargeBoth(int gx, int gz, int iterations) throws Exception {
        for (int i = 0; i < iterations; i++) {
            exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
            exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
            exec("artest shield tick " + DIM);
        }
    }

    private void setPriority(int x, int z, int value) throws Exception {
        String resp = exec("artest shield priority " + DIM + " " + x + " " + Y + " " + z + " " + value);
        assertTrue("failed to set priority " + value + " at " + x + ": " + resp,
                resp.contains("\"priority\":" + value));
    }

    private String read(int x, int z) throws Exception {
        return exec("artest shield read " + DIM + " " + x + " " + Y + " " + z);
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private static long readStored(String json) {
        Matcher m = STORED.matcher(json);
        assertTrue("no shieldStored in probe response: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
