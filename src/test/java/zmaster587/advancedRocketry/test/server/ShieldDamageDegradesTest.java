package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What being shot does to a shield, other than draining it.
 *
 * <p>Three claims, and the first two are a pair that only mean something together. A damaged emitter
 * <b>covers less ground</b> — the shell draws in and a stretch of hull it used to hold stops being
 * held — and it is <b>still billed for the field it was told to project</b>, because a shield that
 * got cheaper the more it was shot would make taking fire a way to save energy. Then the third: a
 * <b>neighbour that still reaches closes the hole</b>, and one that does not leaves it open, which is
 * nothing anybody implemented — it is what a smooth union of spheres already does, and the test
 * exists so it stays true.</p>
 *
 * <p>Everything here is driven through production's own doors: the damage arrives as a declared
 * impact through the damage engine, never as a probe writing a radius, and the coverage is read from
 * the emitter's own predicate rather than recomputed by the test.</p>
 */
public class ShieldDamageDegradesTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;
    private static final int Z = 830;
    private static final long TIMEOUT_MS = 25_000L;

    /** How many stages one impact is allowed to buy, sized from the block's OWN stage cost. */
    private static final double STAGES_PER_IMPACT = 1.2D;

    private static final Pattern RADIUS = Pattern.compile("\"radius\":(-?\\d+)");
    private static final Pattern DECLARED = Pattern.compile("\"declaredRadius\":(-?\\d+)");
    private static final Pattern CYCLE_COST = Pattern.compile("\"cycleCost\":(-?\\d+)");
    private static final Pattern CONVERSION = Pattern.compile("\"conversionPerTick\":(-?\\d+)");
    private static final Pattern THROUGHPUT = Pattern.compile("\"throughput\":(-?\\d+)");
    private static final Pattern CAPACITY = Pattern.compile("\"shieldMaxEffective\":(-?\\d+)");
    private static final Pattern STAGE = Pattern.compile("\"stage\":(-?\\d+)");
    private static final Pattern STAGE_COST = Pattern.compile("\"stageCost\":(-?\\d+)");

    /**
     * Impact identities, never reused — the service refuses a repeat and answers DUPLICATE_IMPACT,
     * which silently ends a scenario. STATIC because JUnit builds a fresh instance per method: a
     * per-instance counter restarts at the same number for every test in the class, and every method
     * after the first would be shooting ids the first one already spent.
     */
    private static int nextImpactId = 7000;

    @Test
    public void aDamagedEmitterCoversLessAndIsStillBilledForWhatItDeclared() throws Exception {
        int gx = 1200, ex = 1201;
        clearSite(gx - 6, gx + 12);
        place("affs:shield_generator", gx);
        place("affs:field_generator", ex);
        powerUp(gx, ex);

        String pristine = readShield(ex);
        int declared = (int) readInt(DECLARED, pristine);
        int radiusBefore = (int) readInt(RADIUS, pristine);
        long costBefore = readInt(CYCLE_COST, pristine);
        assertEquals("precondition: an undamaged emitter must project the field it declared:\n"
                + pristine, declared, radiusBefore);

        // A block on the shell's edge: covered while the emitter is whole, and the first thing it
        // drops when the field draws in. Derived from the radius the emitter reports, not guessed.
        int edge = ex + radiusBefore;
        assertTrue("precondition: the edge of a pristine field must be covered, or the loss below is"
                + " about nothing:\n" + readZone(edge), covered(edge));

        int radiusAfter = shootUntilFieldShrinks(ex, radiusBefore);
        String damaged = readShield(ex);
        assertTrue("a damaged emitter must project a SMALLER field than it did pristine (" + radiusAfter
                + " vs " + radiusBefore + "): the consequence a player is supposed to SEE coming did"
                + " not happen:\n" + damaged, radiusAfter < radiusBefore);

        // It must still be lit, or "no longer covered" would be about the power, not the radius.
        assertTrue("the shield went dark, so the coverage assertions below would pass for the wrong"
                + " reason:\n" + damaged, damaged.contains("\"powered\":true"));
        assertTrue("the shell drew in and the hull it uncovered is still reported as covered:\n"
                + readZone(edge), !covered(edge));

        assertEquals("damage moved the DECLARED radius: the setting is the player's, and a repair has"
                + " nothing to restore to if a shell can edit it:\n" + damaged,
                declared, (int) readInt(DECLARED, damaged));
        assertEquals("a shrunken emitter was billed less than the field it declared (" + costBefore
                + " -> " + readInt(CYCLE_COST, damaged) + "): being shot at now SAVES energy, which"
                + " is a reward wearing a consequence's clothes:\n" + damaged,
                costBefore, readInt(CYCLE_COST, damaged));

        // The other half of "re-read, never accumulated" — that mending the block gives the field
        // back — is pinned one tier down, in ShieldConditionTest. It cannot be driven here: no shield
        // block has a crafting recipe, and the welder prices a repair out of one, so it answers
        // NO_RECIPE for every block in this subsystem.
    }

    @Test
    public void aNeighbourThatStillReachesClosesTheHoleAndOneThatDoesNotLeavesIt() throws Exception {
        // Two independent single-emitter shields eight blocks apart, so their fields just meet.
        int aGx = 1229, aEx = 1230, bEx = 1238, bGx = 1239;
        clearSite(aGx - 8, bGx + 8);
        place("affs:shield_generator", aGx);
        place("affs:field_generator", aEx);
        place("affs:field_generator", bEx);
        place("affs:shield_generator", bGx);
        powerUp(aGx, aEx);
        powerUp(bGx, bEx);

        int radiusBefore = (int) readInt(RADIUS, readShield(aEx));
        int between = aEx + radiusBefore;          // on A's edge, and inside B's reach
        int outboard = aEx - radiusBefore;         // on A's edge, and nowhere near B
        assertTrue("precondition: the point between the two emitters must start covered:\n"
                + readZone(between), covered(between));
        assertTrue("precondition: the point on A's far side must start covered:\n" + readZone(outboard),
                covered(outboard));

        int radiusAfter = shootUntilFieldShrinks(aEx, radiusBefore);
        assertTrue("precondition: emitter A's field never shrank (" + radiusAfter + " vs "
                + radiusBefore + "), so neither point below was ever uncovered by anything:\n"
                + readShield(aEx), radiusAfter < radiusBefore);
        assertTrue("precondition: emitter B must still be lit for its coverage to mean anything:\n"
                + readShield(bEx), readShield(bEx).contains("\"powered\":true"));

        assertTrue("a hole left by a damaged emitter must be closed by the neighbour that still"
                + " reaches it — the field is one blended surface, not a set of private bubbles:\n"
                + readZone(between), covered(between));
        assertTrue("the far side, which only the damaged emitter ever reached, is still reported as"
                + " covered: then nothing was actually lost and the shrink costs a player nothing:\n"
                + readZone(outboard), !covered(outboard));
    }

    @Test
    public void aDamagedGeneratorCableAndAccumulatorEachDeliverLess() throws Exception {
        int genX = 1260, cableX = 1268, accX = 1276;
        clearSite(genX - 6, accX + 6);
        place("affs:shield_generator", genX);
        place("affs:shield_cable", cableX);
        place("affs:shield_accumulator", accX);

        long conversionBefore = readInt(CONVERSION, readShield(genX));
        long throughputBefore = readInt(THROUGHPUT, readShield(cableX));
        long capacityBefore = readInt(CAPACITY, readShield(accX));

        shootUntilStaged(genX);
        shootUntilStaged(cableX);
        shootUntilStaged(accX);

        long conversionAfter = readInt(CONVERSION, readShield(genX));
        long throughputAfter = readInt(THROUGHPUT, readShield(cableX));
        long capacityAfter = readInt(CAPACITY, readShield(accX));

        assertTrue("a damaged shield generator must convert less than an intact one ("
                + conversionAfter + " vs " + conversionBefore + "):\n" + readShield(genX),
                conversionAfter < conversionBefore);
        assertTrue("a damaged cable must carry less than an intact one (" + throughputAfter + " vs "
                + throughputBefore + "):\n" + readShield(cableX), throughputAfter < throughputBefore);
        assertTrue("a damaged accumulator must hold less than an intact one (" + capacityAfter
                + " vs " + capacityBefore + "):\n" + readShield(accX), capacityAfter < capacityBefore);
    }

    // ---- driving the world

    /**
     * Shoot the emitter's own block until the field it projects draws in, and answer with the radius
     * it settled at. Bounded: an emitter that never shrinks fails on the caller's assertion with the
     * numbers in hand rather than hanging here.
     */
    private int shootUntilFieldShrinks(int ex, int radiusBefore) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        for (int shot = 0; shot < 12 && System.currentTimeMillis() < deadline; shot++) {
            int radius = (int) readInt(RADIUS, readShield(ex));
            if (radius < radiusBefore) {
                return radius;
            }
            // Stop one rung short of destruction and let the CALLER's claim fail with the numbers in
            // hand. Shooting on until the block is gone would replace "the field never shrank" with
            // "there is no emitter", which is a different sentence and a worse one to read.
            String damage = readStage(ex);
            if (readInt(STAGE, damage) >= readInt(Pattern.compile("\"maxStage\":(-?\\d+)"), damage) - 1) {
                break;
            }
            hit(ex);
            // The emitter re-reads its own condition on its tick, and a lit field costs energy to
            // hold, so keep feeding it: a dark shield covers nothing for reasons unrelated to damage.
            // The FEED is the point — an emitter left to drain goes dark on a slow loop, and this
            // test then fails about power while claiming to be about radius (seen under parallel load
            // 2026-08-17, green serially).
            exec("artest energy inject " + DIM + " " + (ex - 1) + " " + Y + " " + Z + " 8000");
            exec("artest tile force-tick " + DIM + " " + (ex - 1) + " " + Y + " " + Z + " 1");
            exec("artest tile force-tick " + DIM + " " + ex + " " + Y + " " + Z + " 2");
            exec("artest shield tick " + DIM);
        }
        return (int) readInt(RADIUS, readShield(ex));
    }

    /** Shoot a block until the world records a stage against it. */
    private void shootUntilStaged(int x) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        for (int shot = 0; shot < 12 && System.currentTimeMillis() < deadline; shot++) {
            if (readInt(STAGE, readStage(x)) > 0) {
                return;
            }
            hit(x);
        }
        assertTrue("the block at " + x + " never took a stage, so nothing below is about damage:\n"
                + readStage(x), readInt(STAGE, readStage(x)) > 0);
    }

    /**
     * One declared impact against the block at {@code x}, from the -Z side at its own height: that
     * block is the first solid thing the ray meets, and what is behind it is cleared air, so no
     * neighbour is quietly damaged by the leftover budget.
     */
    private void hit(int x) throws Exception {
        int budget = (int) Math.ceil(readInt(STAGE_COST, readStage(x)) * STAGES_PER_IMPACT);
        String resp = exec("artest damage impact " + DIM + " " + (x + 0.5D) + " " + (Y + 0.5D) + " "
                + (Z - 2.5D) + " 0 0 1 " + budget + " KINETIC " + (nextImpactId++));
        assertTrue("the impact was refused, so the block is not being damaged at all: " + resp,
                resp.contains("\"ok\":true"));
        assertTrue("the impact spent nothing — it is not reaching the block, and every assertion"
                + " after this would be about an undamaged one: " + resp,
                readInt(Pattern.compile("\"spent\":(-?\\d+)"), resp) > 0);
        assertTrue("the block was destroyed rather than damaged, so there is nothing left to degrade: "
                + readStage(x), !readStage(x).contains("\"wasDestroyed\":true"));
    }

    /** Feed the generator until its emitter lights up. */
    private void powerUp(int gx, int ex) throws Exception {
        for (int i = 0; i < 16 && !readShield(ex).contains("\"powered\":true"); i++) {
            exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + Z + " 4000");
            exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + Z + " 1");
            exec("artest shield tick " + DIM);
        }
        assertTrue("precondition: the shield at " + ex + " never powered up:\n" + readShield(ex),
                readShield(ex).contains("\"powered\":true"));
    }

    private void clearSite(int minX, int maxX) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " " + (minX >> 4) + " "
                + ((Z - 8) >> 4) + " " + (maxX >> 4) + " " + ((Z + 8) >> 4)).contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill " + DIM + " " + minX + " " + (Y - 2)
                + " " + (Z - 6) + " " + maxX + " " + (Y + 6) + " " + (Z + 6) + " minecraft:air")
                .contains("\"ok\":true"));
    }

    // ---- reading the world

    /** Whether ANY live emitter still holds the block at {@code x} — the emitter's own predicate. */
    private boolean covered(int x) throws Exception {
        return readZone(x).contains("\"covered\":true");
    }

    private String readZone(int x) throws Exception {
        return exec("artest shield zone " + DIM + " " + x + " " + Y + " " + Z);
    }

    private String readShield(int x) throws Exception {
        return exec("artest shield read " + DIM + " " + x + " " + Y + " " + Z);
    }

    private String readStage(int x) throws Exception {
        return exec("artest damage stage " + DIM + " " + x + " " + Y + " " + Z);
    }

    private void place(String block, int x) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + Z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + Z + ": " + resp,
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
