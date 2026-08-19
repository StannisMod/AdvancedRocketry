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
    private static final int PRICE_Z = 1320;

    private static final double SPEED = 0.45D;
    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern VX = Pattern.compile("\"vx\":(-?[\\d.eE+-]+)");
    private static final Pattern STAGE_COST = Pattern.compile("\"stageCost\":(-?\\d+)");

    /**
     * A mirror returns a beam and is SMASHED by a solid round, and the difference is the kind in the
     * contact and nothing else — the same block, the same face, the same energy.
     *
     * <p>The second half used to read "lets a solid round straight through", and it was pinning a
     * defect. A mirror has no OPTICAL opinion about a solid round, and it said so by answering "passed
     * through, carrying everything" — which is not "no opinion", it is "through, for free". So the
     * round paid nothing, the film was untouched, and the one armour a beam could strip was the one
     * kinetic fire could not. The block now DECLINES, and declining hands the meeting to the ordinary
     * law: the film is priced off the table and the eighth of a voxel it fills, and it breaks like the
     * glass it is.</p>
     */
    @Test
    public void aMirrorReturnsABeamAndIsSmashedByASolidRound() throws Exception {
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
        assertTrue("a solid round bounced off glass and foil: a mirror has no OPTICAL opinion about"
                + " a solid round, and the kind in the contact is the only thing that separates the"
                + " two cases: " + read(slug), !awaitTurnedBack(slug));
        awaitGone(slug);
        assertTrue("the film is still standing after a solid round crossed it: then the round paid"
                + " nothing for it, and a mirror is armour that only the weapon it was built to stop"
                + " can remove", !stillThere(MIRROR_SLUG_Z));
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

        // CONTROL, and the test is worthless without it — the first cut of this test passed with
        // the whole responder switched off. It used to be a solid round carrying the same energy,
        // which had to leave the plate standing; that stopped being available the day such a round
        // started paying for the film and breaking it, which is correct and kills the old control.
        //
        // This is the stronger replacement, and it aims at the mechanism rather than at one sample:
        // ordinary damage prices the two tiers IDENTICALLY, so it cannot produce a difference between
        // them at all. Whatever separates aluminium from gold below is therefore the reflectance, and
        // can be nothing else.
        placeAt(X + 4, BETTER_Z, "advancedrocketry:mirrorPlatingGold");
        long aluminiumCost = costOf(exec("artest damage stage " + DIM + " " + X + " " + Y + " "
                + BETTER_Z));
        long goldCost = costOf(exec("artest damage stage " + DIM + " " + (X + 4) + " " + Y + " "
                + BETTER_Z));
        assertTrue("the two mirror tiers cost different amounts to break by ordinary damage"
                + " (aluminium=" + aluminiumCost + " gold=" + goldCost + "): then the ladder below can"
                + " be produced without any mirror law at all, and this test measures the toughness"
                + " table", aluminiumCost == goldCost);
        placeAt(X + 4, BETTER_Z, "minecraft:air");

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

    /**
     * A mirror film is priced as the glass and foil it is, not as the hull plate its MATERIAL says.
     *
     * <p>Both plating families are declared {@code Material.IRON} — which is what they are mined and
     * sounded like — and the damage table resolves by material when nothing has written a row. That
     * priced a mirror film as solid hull.</p>
     *
     * <p><b>The comparator is REACTIVE plating, and the choice is the whole test.</b> The obvious
     * comparison — a film against a solid block of iron — passes whether or not the mirror has a row
     * of its own, because a film fills an eighth of its voxel and the volume alone makes it cheaper.
     * It would measure the occupancy factor and report it as evidence about the table. Reactive
     * plating is the same class, the same thickness and the same declared material, and it
     * deliberately has NO row: its casing IS metal, and what makes it interesting is the charge rather
     * than what the charge is wrapped in. So the two differ in exactly one thing, and a difference in
     * price can come from exactly one place.</p>
     *
     * <p>Only the ORDERING is claimed. The numbers behind it are balance and will move; an assertion
     * on them would go red the first time anyone retunes the table without breaking anything a player
     * would notice.</p>
     */
    @Test
    public void aMirrorFilmCostsLessToBreakThanTheMetalItsMaterialClaims() throws Exception {
        prepare(PRICE_Z);
        place("advancedrocketry:mirrorPlatingAluminium", PRICE_Z);
        placeAt(X + 4, PRICE_Z, "advancedrocketry:reactivePlate");

        String film = exec("artest damage stage " + DIM + " " + X + " " + Y + " " + PRICE_Z);
        String metal = exec("artest damage stage " + DIM + " " + (X + 4) + " " + Y + " " + PRICE_Z);
        long filmCost = costOf(film), metalCost = costOf(metal);

        assertTrue("a mirror film costs what the identically shaped plating beside it costs (film="
                + filmCost + " reactive=" + metalCost + "): the two differ only in that one has a row"
                + " of its own, so this says the row is not being read at all and glass with foil on"
                + " it still resists like hull plate. film=" + film + " reactive=" + metal,
                filmCost < metalCost);
    }

    private static long costOf(String json) {
        Matcher m = STAGE_COST.matcher(json);
        assertTrue("no stageCost in: " + json, m.find());
        return Long.parseLong(m.group(1));
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
