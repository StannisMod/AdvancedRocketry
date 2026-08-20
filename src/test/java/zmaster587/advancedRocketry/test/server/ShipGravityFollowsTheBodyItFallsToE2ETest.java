package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Gravity is a property of the world a craft is in, and its MAGNITUDE is that world's own
 * gravitational multiplier: a released ship over a quarter-gravity body sinks a quarter as far in the
 * same time as one released over Earth.
 *
 * <h2>What was already witnessed, and what was not</h2>
 *
 * <p>{@code AnUnpilotedShipFallsWithoutFlightAssistE2ETest} pins that a released craft falls at all —
 * on Earth. That leaves the whole point of a per-world field unobserved: every craft could be falling
 * at one standard gravity everywhere and that test would be just as green. A field that does not vary
 * is a constant, and thrust-to-weight, hover cost and stopping distance are all statements ABOUT a
 * varying field.</p>
 *
 * <h2>Two worlds at once, not one world twice</h2>
 *
 * <p>The two craft are released in the same window, in two worlds, and read separately. Doing it as
 * two sequential runs in one world — flip the multiplier, drop again — would pass equally well on a
 * build where gravity is a single global that the last write wins, which is precisely the defect this
 * mechanic exists to rule out. Two ships falling simultaneously at different rates cannot be produced
 * by any global.</p>
 *
 * <p>The measurement is a RATIO, and that is what makes it a contract test rather than a pin on
 * today's numbers. The solver adds {@code gravity x mass x dt} and then scales velocity by a drag
 * factor, so the fall is linear in the field and mass-invariant: two craft of different builds,
 * different masses, over bodies differing only in multiplier, cover distances in exactly that ratio
 * whatever the drag constant and whatever the tick rate happen to be. Nothing here needs to know what
 * one standard gravity is worth.</p>
 *
 * <h2>The premises are gated before the subject is measured</h2>
 *
 * <p>In order: the physics mod is present; the low-gravity body really carries the multiplier asked
 * for; both craft became ships; both HOLD with Flight Assist on; and the Earth craft, once released,
 * really falls. Only then is the low-gravity craft's fall compared. Without the last two, "it barely
 * moved" is the reading a craft gives when it was never simulated, when it was never released, and
 * when the field is genuinely small — three different states with one appearance.</p>
 *
 * <p>The hold is asserted as an ABSOLUTE drift, in both directions, on purpose. The solver and the
 * flight computer's feed-forward ask the same function precisely so that they agree; if the
 * feed-forward were left on Earth's field while the solver used the body's, a held craft over a
 * quarter-gravity body would CLIMB by the difference — which is most of the field — and a one-sided
 * "did it sink" gate would wave that through.</p>
 */
public class ShipGravityFollowsTheBodyItFallsToE2ETest extends AbstractHeadlessServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern DIM_LINE = Pattern.compile("DIM(\\d+):");

    private static final int BASE_X = 11000, BASE_Z = 11000, BUILD_Y = 80;

    /** Clear sky, well above any terrain either world generates, so the fall lands on nothing. */
    private static final int SKY_Y = 200;

    /**
     * The gravity the authored body is given, as a fraction of the configured field. A quarter is far
     * enough from one that no plausible tolerance can hide it, and far enough from zero that the
     * craft must still visibly fall — a body a test cannot tell apart from a void would witness
     * nothing about magnitude.
     */
    private static final double LOW_GRAVITY = 0.25;

    /**
     * How wide a ratio band counts as agreement, as a factor either side of {@link #LOW_GRAVITY}.
     * Two is generous against sampling jitter (the two readings are taken a few milliseconds apart
     * over a three-second window) and still refuses everything this test exists to catch: an
     * unscaled field lands at 1.0, four times the upper bound, and a multiplier applied twice lands
     * at 0.0625, half the lower one.
     */
    private static final double RATIO_SLACK = 2.0;

    /**
     * How far the Earth craft must sink for the comparison to mean anything, in blocks. Free fall
     * covers several times this in the window; a craft that is held, parked or unsimulated covers
     * none of it.
     */
    private static final double EARTH_FELL_MIN = 10.0;

    /** How much a HELD craft may drift either way, same units. A hold that leaks this much is not one. */
    private static final double HELD_TOLERANCE = 2.0;

    /**
     * How long each craft is watched, in ticks — sized against the MEASURED fall rate, not against
     * the textbook one. A craft released at one standard gravity covers about 110 blocks in 60 ticks
     * here (measured 2026-08-19), which from the release altitude would put it into the ground and
     * turn the Earth reading into a landing rather than a fall. Forty ticks leaves the Earth craft
     * around fifty blocks down with clear air beneath it, and still drops the low-gravity craft far
     * enough that its fall cannot be confused with a hold's drift.
     */
    private static final int SAMPLE_TICKS = 40;

    @Test
    public void aCraftOverALowGravityBodyFallsInProportionToThatBodysGravity() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        exec("artest vs permaload true");

        int lowGravityDim = authorLowGravityBody();

        Craft earth = buildAndLift(0);
        Craft low = buildAndLift(lowGravityDim);

        // --- premise: both craft are simulated, controllable, and at rest ---------------------------
        hold(earth);
        hold(low);
        Thread.sleep(SAMPLE_TICKS * 50L);
        double earthHeldY = shipY(earth), lowHeldY = shipY(low);
        assertHeld(earth, earthHeldY);
        assertHeld(low, lowHeldY);
        // From here the held reading is the release point: it is the last one taken while the craft
        // was demonstrably at rest, so the distance measured below is a fall and not the tail of
        // whatever the craft was doing when it arrived.

        // --- release both in the same window --------------------------------------------------------
        release(earth);
        release(low);
        Thread.sleep(SAMPLE_TICKS * 50L);
        double earthFell = earthHeldY - shipY(earth);
        double lowFell = lowHeldY - shipY(low);

        // What the run actually measured, so a GREEN is auditable too and not only a red. Visible
        // with -Pshow_testing_output=true; a bare pass otherwise says nothing about how far either
        // craft moved.
        System.out.println("[gravity witness] earth fell " + earthFell + " blocks, dim "
                + low.dim + " (gravity " + LOW_GRAVITY + ") fell " + lowFell + " blocks in "
                + SAMPLE_TICKS + " ticks - ratio " + (lowFell / earthFell));

        // --- control: the Earth craft must fall, or the comparison below is between two non-events --
        assertTrue("ARRANGEMENT/CONTROL: released over Earth the craft must fall, and this one moved "
                        + earthFell + " blocks in " + SAMPLE_TICKS + " ticks. Until a released craft"
                        + " demonstrably falls here, the low-gravity craft holding still would say"
                        + " nothing about gravity - it is what a craft that was never released, or"
                        + " never simulated, looks like too.",
                earthFell >= EARTH_FELL_MIN);

        // --- the subject: the same release over a quarter-gravity body ------------------------------
        double ratio = lowFell / earthFell;
        assertTrue("a craft released over a body of gravity " + LOW_GRAVITY + " must fall that"
                        + " fraction of what the same release covers over Earth, but it fell "
                        + lowFell + " blocks against Earth's " + earthFell + " - a ratio of " + ratio
                        + ", outside [" + (LOW_GRAVITY / RATIO_SLACK) + ", "
                        + (LOW_GRAVITY * RATIO_SLACK) + "]. A ratio near 1 means the body's"
                        + " multiplier never reached the solver and every world is still one standard"
                        + " gravity; a ratio near 0 means the craft over the low-gravity body is not"
                        + " falling at all, which is a different defect from a weak field.",
                ratio >= LOW_GRAVITY / RATIO_SLACK && ratio <= LOW_GRAVITY * RATIO_SLACK);
    }

    // --- arrangement --------------------------------------------------------------------------------

    /**
     * A freshly generated planet, given a known gravity through the ordinary planet command. The
     * generator rolls a multiplier at random, so the body is authored rather than searched for: a
     * test whose discriminating power depends on what the world generator happened to produce is
     * not a test.
     */
    private int authorLowGravityBody() throws Exception {
        Set<Integer> before = arDims();
        // Two arguments, not five: a planet is DERIVED from its star and its index now, so the
        // old randomness factors are gone from the command.
        exec("ar planet generate 0 LowGravityWitness");
        Set<Integer> fresh = arDims();
        fresh.removeAll(before);
        assertEquals("planet generate must add exactly one dim - got " + fresh, 1, fresh.size());
        int dim = fresh.iterator().next();

        String load = exec("artest dim load " + dim);
        assertTrue("the authored body never loaded: " + load, load.contains("\"loaded\":true"));

        // The SHIPPED command, not a test-only setter: it is what an operator would use, it refuses
        // a dimension that is not a registered body instead of silently writing to Earth's
        // properties, and it publishes the change the way production does.
        exec("ar planet set " + dim + " gravitationalMultiplier " + LOW_GRAVITY);
        // Read it back off the planet registry rather than trusting the command's own reply: what
        // the solver will ask is the registry, and this is the one moment the arrangement can be
        // checked against the same source.
        double gravity = extractDouble(exec("artest planet info " + dim), "gravity");
        assertEquals("the authored body does not carry the gravity it was given", LOW_GRAVITY, gravity, 1e-4);
        return dim;
    }

    /** Build the craft in {@code dim}, then rigid-teleport it into clear sky and hand it to physics. */
    private Craft buildAndLift(int dim) throws Exception {
        clearArea(dim);
        String coords = placeFixture(dim, "with-pilot-seat");
        String asm = exec("artest rocket assemble " + dim + " " + coords);
        assertTrue("with the physics mod an AFC-bearing build must become a ship, not a rocket (dim "
                + dim + "): " + asm, asm.contains("\"rocketCount\":0"));
        assertTrue("the craft in dim " + dim + " never became a loaded ship", waitUntilLoaded(dim));

        String info = exec("artest vs ship-info " + dim + " " + BASE_X + " " + BUILD_Y + " " + BASE_Z);
        assertTrue("no ship answered near the build site in dim " + dim + ": " + info,
                info.contains("\"managed\":true"));
        String id = extractString(info, "id");
        assertTrue("the ship in dim " + dim + " answered without an identity: " + info, id != null);
        int x = (int) extractDouble(info, "posX"), y = (int) extractDouble(info, "posY"),
                z = (int) extractDouble(info, "posZ");
        assertTrue("climb teleport failed in dim " + dim,
                exec("artest vs teleport-ship " + dim + " " + x + " " + y + " " + z
                        + " " + x + " " + SKY_Y + " " + z).contains("\"ok\":true"));
        // A rigid teleport parks the craft; the unpark is what hands it back to the solver.
        exec("artest vs unpark " + dim + " " + x + " " + SKY_Y + " " + z);
        Thread.sleep(1000);

        Craft craft = new Craft(dim, id);
        // Where the craft actually ARRIVED, not where it was aimed: a ship's reported position is
        // its centre of mass, which sits wherever the hull puts it, and measuring a hold against the
        // teleport target would charge that offset to the hold.
        craft.startY = shipY(craft);
        return craft;
    }

    private void hold(Craft craft) throws Exception {
        assertTrue("could not reach the flight computer of the craft in dim " + craft.dim,
                exec("artest vs fa-by-id " + craft.dim + " " + craft.id + " true")
                        .contains("\"afcResolved\":true"));
    }

    private void release(Craft craft) throws Exception {
        assertTrue("could not release the craft in dim " + craft.dim,
                exec("artest vs fa-by-id " + craft.dim + " " + craft.id + " false")
                        .contains("\"afcResolved\":true"));
    }

    private void assertHeld(Craft craft, double heldY) {
        assertTrue("ARRANGEMENT/CONTROL: with Flight Assist on the craft in dim " + craft.dim
                        + " must keep station, and this one moved to " + heldY + " from "
                        + craft.startY + ". Drift DOWN means it is not being held at all, so the fall measured"
                        + " afterwards would not be caused by the release; drift UP means the flight"
                        + " computer is cancelling a field larger than the one the solver applies,"
                        + " which is exactly the disagreement the shared gravity function exists to"
                        + " prevent.",
                Math.abs(craft.startY - heldY) <= HELD_TOLERANCE);
    }

    // --- observation --------------------------------------------------------------------------------

    /** The craft's own Y, by identity — never "whichever ship is nearest", which a fall would outrun. */
    private double shipY(Craft craft) throws Exception {
        String info = exec("artest vs ship-info " + craft.dim + " id " + craft.id);
        assertTrue("the craft in dim " + craft.dim + " stopped answering: " + info,
                info.contains("\"managed\":true"));
        return extractDouble(info, "posY");
    }

    private boolean waitUntilLoaded(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count " + dim), "count") >= 1) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    // --- helpers ------------------------------------------------------------------------------------

    /** One craft under test: its world and its identity, so no reading can be about the other one. */
    private static final class Craft {
        final int dim;
        final String id;
        /** Where it settled after the lift — the baseline the hold is measured against. */
        double startY;

        Craft(int dim, String id) {
            this.dim = dim;
            this.id = id;
        }
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private Set<Integer> arDims() throws Exception {
        Set<Integer> ids = new HashSet<>();
        Matcher m = DIM_LINE.matcher(exec("ar planet list"));
        while (m.find()) ids.add(Integer.parseInt(m.group(1)));
        return ids;
    }

    private void clearArea(int dim) throws Exception {
        int cx1 = (BASE_X - 4) >> 4, cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (BASE_X + 20) >> 4, cz2 = (BASE_Z + 20) >> 4;
        assertTrue("chunk warmup failed in dim " + dim,
                exec("artest chunk warmup " + dim + " " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed in dim " + dim,
                exec("artest fill " + dim + " " + (BASE_X - 4) + " " + (BUILD_Y - 2) + " " + (BASE_Z - 4)
                        + " " + (BASE_X + 20) + " " + (BUILD_Y + 12) + " " + (BASE_Z + 20)
                        + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int dim, String variant) throws Exception {
        String fixture = exec("artest fixture rocket " + dim + " " + BASE_X + " " + BUILD_Y + " "
                + BASE_Z + " " + variant);
        assertTrue("fixture (" + variant + ") failed in dim " + dim + ": " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos in dim " + dim + ": " + fixture,
                bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
