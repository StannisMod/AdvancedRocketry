package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P2 (D134-3/D134-4): the emitter is the shield's per-zone regeneration organ, with a finite,
 * tier-scaled throughput, and zones drop/regenerate independently. These pin the player-visible
 * contracts on the vendored AFFS network:
 *
 * <ul>
 *   <li><b>Throughput is tier-scaled.</b> A higher-tier emitter recharges its zone faster than a
 *       lower-tier one — the progression hook of D134-3.</li>
 *   <li><b>Regeneration is throughput-capped.</b> With a bulk source holding far more than one tick's
 *       worth, a single emitter still refills its coil by no more than its throughput per solve — a big
 *       generator/accumulator behind a small emitter cannot over-regenerate.</li>
 *   <li><b>Zones drop independently.</b> A starved emitter collapses (its field powers off and drops
 *       out of the composite surface) while a separately-fed emitter holds — there is no cross-zone
 *       auto-rescue (D134-4).</li>
 *   <li><b>Responsible area = nearest emitter.</b> A surface point is owned by its nearest active
 *       emitter (the D134-3 Voronoi partition), exposed through {@code /artest shield zone}.</li>
 * </ul>
 *
 * <p>The network solve lives in a {@code WorldTickEvent} handler a command cannot advance from inside a
 * server tick; the test drives it deterministically via {@code /artest shield tick}. Emitter
 * self-drain (passive maintenance) happens in the emitter's own {@code update()}, advanced with
 * {@code /artest tile force-tick}.</p>
 */
public class ShieldZoneThroughputTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;
    private static final int FE_PER_ITERATION = 4000;
    private static final Pattern STORED = Pattern.compile("\"shieldStored\":(-?\\d+)");
    private static final Pattern SHIELD_MAX = Pattern.compile("\"shieldMax\":(-?\\d+)");
    private static final Pattern THROUGHPUT = Pattern.compile("\"throughput\":(-?\\d+)");
    private static final Pattern REQUESTED = Pattern.compile("\"requested\":(-?\\d+)");
    private static final Pattern TIER = Pattern.compile("\"tier\":(-?\\d+)");

    @Test
    public void throughputIsTierScaled() throws Exception {
        // Two emitters, Tier 0 and Tier 1 (block meta = tier). Throughput is a pure function of tier, so
        // no energy is needed to read it. A higher tier must recharge faster (D134-3 progression hook).
        int t0x = 1000, t1x = 1004, z = 800;
        placeMeta("affs:field_generator", t0x, z, 0);
        placeMeta("affs:field_generator", t1x, z, 1);

        String tier0 = read(t0x, z);
        String tier1 = read(t1x, z);
        assertEquals("Tier 0 emitter did not report tier 0:\n" + tier0, 0, readInt(TIER, tier0));
        assertEquals("Tier 1 emitter did not report tier 1:\n" + tier1, 1, readInt(TIER, tier1));

        long tp0 = readInt(THROUGHPUT, tier0);
        long tp1 = readInt(THROUGHPUT, tier1);
        assertTrue("a higher-tier emitter must have strictly greater recharge throughput (tier0=" + tp0
                + " tier1=" + tp1 + "): the tier progression does not scale throughput", tp1 > tp0);
    }

    @Test
    public void regenerationIsThroughputCapped() throws Exception {
        // Generator + accumulator build a bulk reserve, then an emitter attaches to the accumulator (not
        // adjacent to the generator). The reserve holds vastly more than one tick's throughput — yet the
        // emitter still only asks the network for at most its throughput per tick, so the network can
        // never route more than throughput/tick into the coil. That demand cap IS the D134-3 mechanism
        // that stops a big generator/accumulator behind a small emitter from over-regenerating its zone.
        //
        // We assert the demand cap directly (not a per-tick inflow delta): the shared server ticks its
        // worlds in the background, so a "count exactly one solve" measurement is not deterministic, but
        // the invariant "requested == min(free, throughput)" is tick-independent. With a nearly-empty
        // coil (free >> throughput) the cap binds and requested == throughput regardless of source size.
        int gx = 1010, gz = 800;
        int ax = gx + 1;   // accumulator east of the generator
        int ez = gz - 1;   // emitter on the accumulator's -Z face (not adjacent to the generator)
        place("affs:shield_generator", gx, gz);
        place("affs:shield_accumulator", ax, gz);

        for (int i = 0; i < 80; i++) {
            chargeIteration(gx, gz);
        }
        long reserveBefore = readInt(STORED, read(ax, gz));
        assertTrue("precondition: accumulator did not build a bulk reserve (stored=" + reserveBefore + ")",
                reserveBefore > 150_000L);

        place("affs:field_generator", ax, ez);
        // Ensure the coil has ample free space (drain a little via self-drain, no refill needed), so the
        // throughput cap — not the coil being nearly full — is what bounds the demand.
        exec("artest tile force-tick " + DIM + " " + ax + " " + Y + " " + ez + " 3");

        String emitter = read(ax, ez);
        long throughput = readInt(THROUGHPUT, emitter);
        long requested = readInt(REQUESTED, emitter);
        long stored = readInt(STORED, emitter);
        long free = readInt(SHIELD_MAX, emitter) - stored;

        assertTrue("precondition: the reserve must dwarf one tick's throughput to prove the source is not "
                + "the limiter (reserve=" + reserveBefore + " throughput=" + throughput + ")",
                reserveBefore > throughput * 10);
        assertTrue("precondition: the coil must have more free space than one tick's throughput so the "
                + "cap is the binding limiter (free=" + free + " throughput=" + throughput + ")",
                free > throughput);
        assertEquals("the emitter asked the network for more (or less) than its throughput while a bulk "
                + "source stood behind it (requested=" + requested + " throughput=" + throughput
                + "): regeneration is not capped at the emitter throughput, so a big source "
                + "over-regenerates the zone", throughput, requested);
    }

    @Test
    public void starvedZoneCollapsesWhileFedZoneHolds() throws Exception {
        // Two INDEPENDENT single-emitter networks. Charge both to powered, then drain one emitter's coil
        // (passive maintenance, no refill) while the other keeps its charge. The starved one collapses;
        // the other holds — per-emitter drop independence, no cross-zone auto-rescue (D134-4).
        int aGx = 1020, bGx = 1030, z = 800;
        int aEx = aGx + 1, bEx = bGx + 1;
        place("affs:shield_generator", aGx, z);
        place("affs:field_generator", aEx, z);
        place("affs:shield_generator", bGx, z);
        place("affs:field_generator", bEx, z);

        for (int i = 0; i < 14; i++) {
            chargeIteration(aGx, z);
            chargeIteration(bGx, z);
        }

        assertTrue("precondition: emitter A did not power up:\n" + read(aEx, z),
                read(aEx, z).contains("\"powered\":true"));
        assertTrue("precondition: emitter B did not power up:\n" + read(bEx, z),
                read(bEx, z).contains("\"powered\":true"));

        // Starve B: advance ITS emitter (self-drain only, no solve → no refill) far enough to empty a full
        // coil. A is untouched (no solve drains it), so A holds its charge.
        exec("artest tile force-tick " + DIM + " " + bEx + " " + Y + " " + z + " 90");

        String starved = read(bEx, z);
        String held = read(aEx, z);
        assertTrue("the starved emitter's zone did not collapse — it is still powered after draining its "
                + "coil with no refill:\n" + starved, starved.contains("\"powered\":false"));
        assertTrue("the separately-charged emitter's zone must hold when a different zone collapses — "
                + "there is no cross-zone rescue, but this one lost power too:\n" + held,
                held.contains("\"powered\":true"));
    }

    @Test
    public void nearestActiveEmitterOwnsTheZone() throws Exception {
        // Two powered emitters; a surface point is owned by its nearest one (D134-3 responsible area).
        int aGx = 1041, aEx = 1040, bGx = 1051, bEx = 1050, z = 800;
        place("affs:field_generator", aEx, z);
        place("affs:shield_generator", aGx, z);
        place("affs:field_generator", bEx, z);
        place("affs:shield_generator", bGx, z);

        for (int i = 0; i < 14; i++) {
            chargeIteration(aGx, z);
            chargeIteration(bGx, z);
        }
        assertTrue("precondition: emitter A not powered:\n" + read(aEx, z),
                read(aEx, z).contains("\"powered\":true"));
        assertTrue("precondition: emitter B not powered:\n" + read(bEx, z),
                read(bEx, z).contains("\"powered\":true"));

        // A point two blocks from A (and eight from B) is owned by A; the mirror point by B.
        String nearA = zone(aEx + 2, z);
        String nearB = zone(bEx - 2, z);
        assertTrue("a point nearest emitter A must be owned by A (ownerX=" + aEx + "):\n" + nearA,
                nearA.contains("\"owned\":true") && nearA.contains("\"ownerX\":" + aEx));
        assertTrue("a point nearest emitter B must be owned by B (ownerX=" + bEx + "):\n" + nearB,
                nearB.contains("\"owned\":true") && nearB.contains("\"ownerX\":" + bEx));
    }

    // helpers -----------------------------------------------------------------

    private void chargeIteration(int gx, int gz) throws Exception {
        exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
        exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
        exec("artest shield tick " + DIM);
    }

    private String read(int x, int z) throws Exception {
        return exec("artest shield read " + DIM + " " + x + " " + Y + " " + z);
    }

    private String zone(int x, int z) throws Exception {
        return exec("artest shield zone " + DIM + " " + x + " " + Y + " " + z);
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private void placeMeta(String block, int x, int z, int meta) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block + " " + meta);
        assertTrue("failed to place " + block + " (meta " + meta + ") at " + x + "," + Y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private static long readInt(Pattern pattern, String json) {
        Matcher m = pattern.matcher(json);
        assertTrue("no " + pattern.pattern() + " field in probe response: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
