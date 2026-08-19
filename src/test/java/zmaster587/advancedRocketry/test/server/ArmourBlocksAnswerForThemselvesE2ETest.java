package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Armour that ANSWERS — the half of the contact seam that had a contract, a set of result states and
 * no block in the game which implemented it.
 *
 * <p>Every claim here is about a block deciding its own fate, which is the thing toughness alone can
 * never express: a plate cannot send a body somewhere else, cannot spend a charge of its own, and
 * cannot tell a beam from a slug. Each scenario gets its own LANE across the line of fire, because a
 * round rich enough to be interesting outlives its own target and would otherwise arrive in the next
 * one's arrangement.</p>
 */
public class ArmourBlocksAnswerForThemselvesE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 70, X = 2100;
    private static final int MIRROR_Z = 1200, MIRROR_SLUG_Z = 1220, BETTER_Z = 1240;
    private static final int REACTIVE_Z = 1260, REACTIVE_TWICE_Z = 1280, RAILGUN_Z = 1300;

    private static final double SPEED = 0.45D;
    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern VX = Pattern.compile("\"vx\":(-?[\\d.eE+-]+)");

    /**
     * A mirror returns a beam and lets a slug through, and the difference is the kind in the contact
     * and nothing else — the same block, the same face, the same energy.
     */
    @Test
    public void aMirrorReturnsABeamAndLetsASlugStraightThrough() throws Exception {
        prepare(MIRROR_Z);
        prepare(MIRROR_SLUG_Z);
        place("advancedrocketry:mirrorPlatingAluminium", MIRROR_Z);
        place("advancedrocketry:mirrorPlatingAluminium", MIRROR_SLUG_Z);

        // Well inside what an aluminium film can shed, so the plate survives to reflect.
        long beam = fire(MIRROR_Z, 3_000, "BEAM");
        assertTrue("the beam was refused", beam >= 0);
        assertTrue("a beam fired at a mirror was never sent back — the plate answered as if it were"
                + " ordinary hull: " + read(beam), awaitTurnedBack(beam));

        long slug = fire(MIRROR_SLUG_Z, 3_000, "KINETIC");
        assertTrue("the slug was refused", slug >= 0);
        assertTrue("a solid round bounced off glass and foil: a mirror has no opinion about a slug,"
                + " and the kind in the contact is the only thing that separates the two cases: "
                + read(slug), !awaitTurnedBack(slug));
        assertTrue("the mirror is still standing after a slug went through it", stillThere(MIRROR_SLUG_Z));
    }

    /**
     * A mirror dies by what it ABSORBS. Two tiers, the same beam: the worse one lets more of it into
     * its film and is gone; the better one lets less in and survives. No count, no stages — an optic
     * either is one or is not.
     */
    @Test
    public void aBetterMirrorSurvivesWhatKillsAWorseOne() throws Exception {
        prepare(BETTER_Z);
        place("advancedrocketry:mirrorPlatingAluminium", BETTER_Z);

        // Chosen against the film rather than against a number in a test: enough that a tenth of it
        // exceeds what the film sheds, and a thirtieth of it does not.
        int killsAluminium = 60_000;

        // CONTROL, and the test is worthless without it: the same energy as a SLUG must leave the
        // plate standing. Ordinary damage does not know one mirror from another, so if it were doing
        // the work below, this is where it would show — and the first cut of this test passed with the
        // whole responder switched off, which is exactly what this catches.
        long slug = fire(BETTER_Z, killsAluminium, "KINETIC");
        assertTrue("the control round was refused", slug >= 0);
        awaitGone(slug);
        assertTrue("a slug carrying what the beams below carry destroyed the plating: then what kills"
                + " a mirror here is ordinary damage, and nothing in this test is about mirrors",
                stillThere(BETTER_Z));
        long first = fire(BETTER_Z, killsAluminium, "BEAM");
        assertTrue("the beam was refused", first >= 0);
        awaitGone(first);
        assertTrue("an aluminium mirror survived a beam that put more into its film than the film can"
                + " shed: then nothing burns out and a mirror is unconditional armour",
                !stillThere(BETTER_Z));

        place("advancedrocketry:mirrorPlatingGold", BETTER_Z);
        long second = fire(BETTER_Z, killsAluminium, "BEAM");
        assertTrue("the second beam was refused", second >= 0);
        awaitGone(second);
        assertTrue("a gold mirror died to the same beam that killed an aluminium one: then the tiers"
                + " are not the reflectances and the ladder means nothing", stillThere(BETTER_Z));
    }

    /**
     * A reactive plate stops one shot and is gone; the second through the same spot is not stopped.
     * That is a property of the thing rather than a counter somebody keeps.
     */
    @Test
    public void aReactivePlateStopsOneShotAndIsThenNotThere() throws Exception {
        prepare(REACTIVE_Z);
        place("advancedrocketry:reactivePlate", REACTIVE_Z);
        // Behind it, an ordinary block: what a spent charge stops protecting.
        placeAt(X + 2, REACTIVE_Z, "minecraft:stone");

        long first = fire(REACTIVE_Z, 4_000, "KINETIC");
        assertTrue("the first round was refused", first >= 0);
        awaitGone(first);
        assertTrue("the charge is still standing after eating a round: a reactive plate spends ITSELF"
                + " or it is just a tough block", !stillThere(REACTIVE_Z));
        assertTrue("the block BEHIND the charge was hit through it: the charge did not stop the round"
                + " it spent itself on", clean(X + 2, REACTIVE_Z));

        long second = fire(REACTIVE_Z, 4_000, "KINETIC");
        assertTrue("the second round was refused", second >= 0);
        awaitGone(second);
        assertTrue("the second round through the same spot was stopped as well — then the charge was"
                + " never spent and reactive armour is free", !clean(X + 2, REACTIVE_Z));
    }

    /** Twice the plating eats more of the same impact — the ordering the volume rule exists for. */
    @Test
    public void twiceTheReactiveVolumeEatsMoreOfTheSameImpact() throws Exception {
        prepare(REACTIVE_TWICE_Z);
        prepare(RAILGUN_Z);
        place("advancedrocketry:reactivePlate", REACTIVE_TWICE_Z);
        placeAt(X + 2, REACTIVE_TWICE_Z, "minecraft:stone");
        place("advancedrocketry:reactiveBlock", RAILGUN_Z);
        placeAt(X + 2, RAILGUN_Z, "minecraft:stone");

        // More than one plate can swallow, less than a full block can.
        int between = 15_000;
        long throughPlate = fire(REACTIVE_TWICE_Z, between, "KINETIC");
        long intoBlock = fire(RAILGUN_Z, between, "KINETIC");
        assertTrue("both rounds must be admitted", throughPlate >= 0 && intoBlock >= 0);
        awaitGone(throughPlate);
        awaitGone(intoBlock);

        assertTrue("a round bigger than one plate can swallow was stopped by it anyway: then capacity"
                + " does not bound what a charge eats", !clean(X + 2, REACTIVE_TWICE_Z));
        assertTrue("the full block let through what it should have swallowed whole: then twice the"
                + " plating is not twice the protection and layering buys nothing",
                clean(X + 2, RAILGUN_Z));
    }

    // ---- driving

    private long fire(int lane, int energy, String kind) throws Exception {
        return idOf(exec("artest shot fire " + DIM + " " + (X - 3.0D) + " " + (Y + 0.5D) + " "
                + (lane + 0.5D) + " " + SPEED + " 0 0 " + energy + " 1200 " + kind + " 0.25 1.0"));
    }

    private void place(String block, int lane) throws Exception {
        placeAt(X, lane, block);
    }

    private void placeAt(int x, int lane, String block) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + lane + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + lane + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private void prepare(int lane) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " " + ((X - 16) >> 4)
                + " " + ((lane - 16) >> 4) + " " + ((X + 40) >> 4) + " " + ((lane + 16) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the lane", exec("artest fill " + DIM + " " + (X - 8) + " "
                + (Y - 2) + " " + (lane - 3) + " " + (X + 40) + " " + (Y + 4) + " " + (lane + 3)
                + " minecraft:air").contains("\"ok\":true"));
    }

    // ---- reading

    /** Did the round ever turn around? It is fired along +X, so a negative vx is the answer itself. */
    private boolean awaitTurnedBack(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            String state = read(id);
            if (!state.contains("\"present\":true")) {
                return false;
            }
            Matcher m = VX.matcher(state);
            if (m.find() && Double.parseDouble(m.group(1)) < 0.0D) {
                return true;
            }
            Thread.sleep(60L);
        }
        return false;
    }

    private void awaitGone(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline && read(id).contains("\"present\":true")) {
            Thread.sleep(100L);
        }
    }

    /** Is the armour block still where it was placed? */
    private boolean stillThere(int lane) throws Exception {
        return !exec("artest damage stage " + DIM + " " + X + " " + Y + " " + lane)
                .contains("\"block\":\"minecraft:air\"");
    }

    /** Is the block behind the armour untouched — never staged and never destroyed? */
    private boolean clean(int x, int lane) throws Exception {
        String state = exec("artest damage stage " + DIM + " " + x + " " + Y + " " + lane);
        if (state.contains("\"block\":\"minecraft:air\"") || state.contains("\"wasDestroyed\":true")) {
            return false;
        }
        Matcher m = Pattern.compile("\"stage\":(-?\\d+)").matcher(state);
        return m.find() && Integer.parseInt(m.group(1)) == 0;
    }

    private String read(long id) throws Exception {
        return exec("artest shot read " + DIM + " " + id);
    }

    private static long idOf(String json) {
        Matcher m = ID.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private static String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
