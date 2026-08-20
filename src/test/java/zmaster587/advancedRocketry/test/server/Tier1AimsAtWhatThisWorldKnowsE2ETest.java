package zmaster587.advancedRocketry.test.server;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The loop the two discovery systems exist to close, driven on a real server: an address is carried
 * to a world, deposited there, and a tier-1 launch pad standing on that world may then be aimed at
 * it.
 *
 * <p>The unit and integration tiers pin the pieces - a body carries its own known-set, one body's
 * finds never reach its neighbour, a beacon teaches its own system. What only a server can answer is
 * whether the PRODUCTION gate a rocket asks agrees: `IPlanetDefiner.isPlanetKnown` reading the world
 * it is standing in, with the pack's authored set as the floor beneath it.</p>
 *
 * <p>Both assertions here are stated against dims the probe itself reports, never against a literal
 * this test wrote down: which worlds a survey resolves depends on the server's own sky.</p>
 *
 * <p>Position-isolated at x=4900-4960 (clear of the survey fixtures at 4300-4660 and the observatory
 * multiblock fixtures at 4000-4060).</p>
 */
public class Tier1AimsAtWhatThisWorldKnowsE2ETest extends AbstractSharedServerTest {

    private static final int CY = 64;
    private static final int CZ = 4900;
    private static final int X = 4900;

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }

    private String where() {
        return "0 " + X + " " + CY + " " + CZ;
    }

    /** Whether the gate a rocket standing in {@code standing} asks says {@code target} is known. */
    private boolean known(int standing, int target) throws Exception {
        String reply = exec("artest planet knowledge " + standing + " " + target);
        assertTrue("the knowledge probe failed: " + reply, reply.contains("\"known\":"));
        return reply.contains("\"known\":true");
    }

    /** The same reply's two halves, so a red test says WHICH source moved. */
    private String halves(int standing, int target) throws Exception {
        return exec("artest planet knowledge " + standing + " " + target);
    }

    /** A numeric field of a probe reply. */
    private static int intField(String json, String name) {
        String key = "\"" + name + "\":";
        int at = json.indexOf(key);
        assertTrue("probe reply has no field " + name + ": " + json, at >= 0);
        int from = at + key.length();
        int to = from;
        while (to < json.length() && "-0123456789".indexOf(json.charAt(to)) >= 0) {
            to++;
        }
        return Integer.parseInt(json.substring(from, to));
    }

    private static List<Integer> ints(String json, String field) {
        String key = "\"" + field + "\":[";
        int at = json.indexOf(key);
        assertTrue("probe reply has no array " + field + ": " + json, at >= 0);
        int end = json.indexOf(']', at);
        String body = json.substring(at + key.length(), end).trim();
        List<Integer> out = new ArrayList<>();
        if (!body.isEmpty()) {
            for (String piece : body.split(",")) {
                out.add(Integer.parseInt(piece.trim()));
            }
        }
        return out;
    }

    @Test
    public void withResearchOnAPadIsNotOfferedAWorldNobodyHasFoundHere() throws Exception {
        exec("artest config set planetsMustBeDiscovered true");

        assertTrue("the overworld must always be known - it is the floor every pack starts from",
                known(0, 0));

        // The target is MINTED for this test rather than picked out of the server's planet list: a
        // world that has just come into existence cannot be in anybody's known-set, so the assertion
        // below cannot be quietly satisfied by whatever another test taught this world earlier.
        try {
            String installed = exec("artest space gen-install 0.9 2000000 987654321");
            assertTrue("the procedural generator must install: " + installed,
                    installed.contains("\"ok\":true"));
            String found = exec("artest space find-procedural 4");
            assertTrue("a dense procedural galaxy must offer a landable body: " + found,
                    found.contains("\"ok\":true"));
            String cell = intField(found, "sx") + " " + intField(found, "sy") + " "
                    + intField(found, "sz");
            String realized = exec("artest space realize " + cell);
            assertTrue("realization must mint a world to ask about: " + realized,
                    realized.contains("\"ok\":true"));
            int fresh = intField(realized, "dim");

            String reply = halves(0, fresh);
            assertTrue("a freshly minted world must be in nobody's global set: " + reply,
                    reply.contains("\"global\":false"));
            assertTrue("nor known on the world we are standing on: " + reply,
                    reply.contains("\"local\":false"));
            assertFalse("and a pad here must therefore not be offered it: " + reply,
                    known(0, fresh));
        } finally {
            exec("artest space gen-reset");
        }
    }

    @Test
    public void withResearchOffThePlaceBoundSetGatesNothing() throws Exception {
        // The other half of the same knob, and the one a pack that does not want research at all
        // relies on: with the master switch off there must be NO new gate anywhere. Measured against
        // the hardest case - a world minted a moment ago, which by construction is in neither the
        // pack's authored set nor this world's own, and which must still be selectable.
        exec("artest config set planetsMustBeDiscovered false");
        try {
            String installed = exec("artest space gen-install 0.9 2000000 987654321");
            assertTrue("the procedural generator must install: " + installed,
                    installed.contains("\"ok\":true"));
            String found = exec("artest space find-procedural 4");
            assertTrue("a dense procedural galaxy must offer a landable body: " + found,
                    found.contains("\"ok\":true"));
            String realized = exec("artest space realize " + intField(found, "sx") + " "
                    + intField(found, "sy") + " " + intField(found, "sz"));
            assertTrue("realization must mint a world to ask about: " + realized,
                    realized.contains("\"ok\":true"));
            int fresh = intField(realized, "dim");

            String reply = halves(0, fresh);
            assertTrue("arrangement: nobody may have taught this world globally: " + reply,
                    reply.contains("\"global\":false"));
            assertTrue("arrangement: nor locally: " + reply, reply.contains("\"local\":false"));
            assertTrue("with research off a pad must still be offered it - the place-bound set is"
                    + " additive over a gate that is not there: " + reply, known(0, fresh));
        } finally {
            exec("artest space gen-reset");
            exec("artest config set planetsMustBeDiscovered true");
        }
    }

    @Test
    public void anAddressDepositedHereBecomesSomethingAPadHereCanBeAimedAt() throws Exception {
        // The sweep runs with research OFF, where what the instrument reaches is resolved outright -
        // the pacing is a different mechanic with its own test, and waiting for it here would only
        // make this fixture slower and flakier. The GATE is then asked with research ON, which is
        // the mode the whole question exists in.
        exec("artest config set planetsMustBeDiscovered false");
        exec("artest config set telescopeLimitingMagnitude 30");
        exec("artest config set telescopePassiveRadiusSteps 1");

        String placed = exec("artest telescope place " + where());
        assertTrue("could not place an observatory: " + placed, placed.contains("\"ok\":true"));
        String crystal = exec("artest telescope crystal " + where());
        assertTrue("the crystal must start blank: " + crystal, crystal.contains("\"addresses\":0"));

        // The instrument watching its own neighbourhood: what it resolves are the bodies of the
        // system this observatory is standing in, which are the ones that have worlds to fly to.
        String swept = exec("artest telescope passive " + where());
        assertTrue("the passive sweep did not start: " + swept, swept.contains("\"ok\":true"));
        String afterSweep = exec("artest telescope info " + where());
        assertFalse("the sweep must be finished with research off: " + afterSweep,
                afterSweep.contains("\"scanning\":true"));
        exec("artest config set planetsMustBeDiscovered true");

        String deposited = exec("artest telescope deposit " + where());
        List<Integer> landed = ints(deposited, "dims");
        assertFalse("the sweep resolved nothing with a world in it, so there is nothing to deposit"
                + " and this fixture proves nothing: " + deposited, landed.isEmpty());

        for (int dim : landed) {
            String reply = halves(0, dim);
            assertTrue("a deposited address must be known to a pad standing here: " + reply,
                    reply.contains("\"known\":true"));
            assertTrue("and it must be known LOCALLY - the deposit may not touch the global floor: "
                    + reply, reply.contains("\"local\":true"));
        }

        String depositedAgain = exec("artest telescope deposit " + where());
        assertEquals("depositing the same crystal twice must land the same addresses, not more",
                landed, ints(depositedAgain, "dims"));
    }
}
