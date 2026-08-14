package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * P5 tier-1 (D134-2): the cooperative strike seam AR turrets will implement. A weapon declares a
 * {@code ShieldStrike} (energy + kind + ray) and {@code ShieldStrikeService.resolve} absorbs it precisely
 * through the {@code FieldFrame} seam. This pins the two contractual outcomes the seam guarantees:
 *
 * <ul>
 *   <li>a charged shield <b>fully absorbs</b> a strike it can afford (stopped at the shell, no residual,
 *       coil paid the cost) — and a shield with no charge is <b>no impediment</b> (the strike passes,
 *       nothing spent), the D134-2 "shield is a barrier only while up" rule;</li>
 *   <li>a strike that <b>outmatches</b> the shield is <b>gracefully penetrated</b>: the shield spends
 *       everything it has, the remainder passes downstream, and the shield drops toward zero — the same
 *       "shields fall" degrade as the kinetic path;</li>
 *   <li>a fully absorbed kinetic strike that <b>declares a travelling body</b> is <b>reflected</b> — the
 *       body leaves along the outward normal and no faster than it arrived — while an otherwise
 *       identical strike carrying no body is stopped at the shell, and neither costs more than the
 *       other. A body is a body whether or not it happens to be a Forge entity: a shot that lives as a
 *       record has to reach the same reflection as a thrown block does.</li>
 * </ul>
 *
 * <p>The strike is driven with {@code /artest shield strike ...}, which calls the real service on the
 * logical server and reports the result — no client, no mixin, no live weapon needed.</p>
 */
public class ShieldStrikeAbsorptionTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;
    private static final int FE_PER_ITERATION = 4000;
    private static final double RADIUS = 4.0D;
    private static final Pattern STORED = Pattern.compile("\"shieldStored\":(-?\\d+)");

    @Test
    public void chargedShieldFullyAbsorbsACooperativeStrike() throws Exception {
        int gx = 1010, gz = 810;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);
        for (int i = 0; i < 15; i++) {
            chargeIteration(gx, gz);
        }
        assertTrue("emitter never powered:\n" + read(ex, gz), read(ex, gz).contains("\"powered\":true"));
        long storedBefore = readStored(read(ex, gz));

        // A RADIANT beam of 2000 declared energy, fired from outside the +Z shell straight inward. At the
        // default absorption rate 1.0, kind multiplier 1.0 (bias 0.5) and Tier 0 efficiency, cost == 2000
        // — well under the charged coil, so the shield stops it at the shell with no residual.
        int impactEnergy = 2000;
        String result = strike(ex, gz, impactEnergy, "RADIANT");
        assertTrue("a charged shield did not intercept a cheap cooperative strike:\n" + result,
                result.contains("\"intercepted\":true"));
        assertTrue("the strike was not fully absorbed (residual passed through a shield that could pay):\n"
                + result, result.contains("\"fullyAbsorbed\":true"));
        assertTrue("expected zero residual on a full absorb:\n" + result, result.contains("\"residual\":0"));

        long storedAfter = readStored(read(ex, gz));
        long drop = storedBefore - storedAfter;
        // Corroborate energy actually moved (anti false-green), and that a cheap strike spent only a
        // fraction — NOT the whole reserve (that would be the graceful-penetration case, not a full pay).
        // The exact cost depends on the tunable kind/bias multipliers, so only the shape is pinned.
        assertTrue("the coil paid nothing for a strike it reported fully absorbing (before=" + storedBefore
                + " after=" + storedAfter + "): no energy moved.", drop > 0);
        assertTrue("a cheap strike drained the whole reserve (drop=" + drop + " of " + storedBefore
                + "): that is a penetration, not a full pay.", drop < storedBefore);

        // No charge => no impediment (D134-2): drain the coil and the SAME strike now passes untouched.
        exec("artest shield charge " + DIM + " " + ex + " " + Y + " " + gz + " 0");
        String downResult = strike(ex, gz, impactEnergy, "RADIANT");
        assertTrue("a down shield still intercepted the strike — it must be a barrier only while charged:\n"
                + downResult, downResult.contains("\"intercepted\":false"));
    }

    @Test
    public void strikeGracefullyPenetratesAShieldItOutmatches() throws Exception {
        int gx = 1010, gz = 822;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);
        for (int i = 0; i < 15; i++) {
            chargeIteration(gx, gz);
        }
        assertTrue("emitter never powered:\n" + read(ex, gz), read(ex, gz).contains("\"powered\":true"));
        long storedBefore = readStored(read(ex, gz));

        // A strike whose cost is triple the stored charge: the shield spends all it has, the remainder
        // passes, and the coil drops toward zero (graceful penetration, "shields fall").
        int impactEnergy = (int) (storedBefore * 3L);
        String result = strike(ex, gz, impactEnergy, "RADIANT");
        assertTrue("an overmatching strike was not intercepted at all:\n" + result,
                result.contains("\"intercepted\":true"));
        assertTrue("an overmatching strike was reported fully absorbed — the shield cannot afford it:\n"
                + result, result.contains("\"fullyAbsorbed\":false"));
        long residual = readLong(result, "residual");
        long absorbed = readLong(result, "absorbed");
        assertTrue("no residual impact passed a shield that could not fully pay:\n" + result, residual > 0);
        // The shield spent essentially everything it had at strike time. We compare loosely against the
        // (slightly stale) storedBefore because the shared server ticks maintenance in the background
        // between the read and the strike; the contract is "spent most of the reserve", not an exact figure.
        assertTrue("the shield spent only a sliver of its reserve (absorbed=" + absorbed + " storedBefore="
                + storedBefore + "): an overmatching strike must drain what the coil holds.",
                absorbed > storedBefore / 2L && absorbed <= storedBefore);

        long storedAfter = readStored(read(ex, gz));
        assertTrue("the coil was not drained toward zero by the overmatching strike (after=" + storedAfter
                + " before=" + storedBefore + "):\n" + result, storedAfter < storedBefore / 4L);
    }

    @Test
    public void aDeclaredBodyIsReflectedWhereAnIdenticalBodilessStrikeIsStopped() throws Exception {
        int gx = 1010, gz = 834;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);
        for (int i = 0; i < 15; i++) {
            chargeIteration(gx, gz);
        }
        assertTrue("emitter never powered:\n" + read(ex, gz), read(ex, gz).contains("\"powered\":true"));

        // Both strikes are the same ray, the same declared energy and the same KINETIC kind, fired at a
        // shield that can afford either. The ONLY difference is that one declares the body it carries —
        // a shot travelling inward at 2 b/t — so the two outcomes can differ for exactly one reason.
        int impactEnergy = 2000;
        double inwardSpeed = 2.0D;
        String withBody = strike(ex, gz, impactEnergy, "KINETIC", 0.0D, 0.0D, -inwardSpeed);
        assertTrue("a declared body was not reported as declared — the probe never handed one to the "
                + "service, so nothing below tests reflection:\n" + withBody,
                withBody.contains("\"declaredBody\":true"));
        assertTrue("a shield that could pay did not fully absorb the strike:\n" + withBody,
                withBody.contains("\"fullyAbsorbed\":true"));
        assertTrue("a fully absorbed kinetic strike carrying a travelling body was not reflected — a "
                + "shot that lives as a record must bounce like a thrown body does:\n" + withBody,
                withBody.contains("\"reflected\":true"));

        // It came in along -Z, so it must leave along +Z: the shell reverses the inward component.
        double newVz = readDouble(withBody, "newVz");
        assertTrue("the reflected body still travels inward (newVz=" + newVz + ", it arrived at "
                + (-inwardSpeed) + "): the shell did not turn it around:\n" + withBody, newVz > 0.0D);
        // And it may never leave faster than it arrived — the shell cannot hand out energy it never
        // absorbed. This holds at any restitution setting; only the perfect-mirror default is an equality.
        double speed = Math.sqrt(square(readDouble(withBody, "newVx")) + square(readDouble(withBody, "newVy"))
                + square(newVz));
        assertTrue("the shell accelerated the body it reflected (out=" + speed + " in=" + inwardSpeed
                + "): a mirror returns energy, it does not create it:\n" + withBody,
                speed <= inwardSpeed + 1.0E-6D);

        String bodiless = strike(ex, gz, impactEnergy, "KINETIC");
        assertTrue("a bodiless declared strike was reported as carrying a body:\n" + bodiless,
                bodiless.contains("\"declaredBody\":false"));
        assertTrue("an abstract kinetic source with no travelling body was not fully absorbed:\n" + bodiless,
                bodiless.contains("\"fullyAbsorbed\":true"));
        assertTrue("a strike with no travelling body was reflected — there is nothing there to reflect:\n"
                + bodiless, bodiless.contains("\"reflected\":false"));

        // One impact, one pricing path: reflecting is not a surcharge. Same declared energy, same kind,
        // same shell => the same bill, whether or not a body came back out.
        long bodyCost = readLong(withBody, "absorbed");
        long bodilessCost = readLong(bodiless, "absorbed");
        assertTrue("reflecting a body was billed differently from stopping one (" + bodyCost + " vs "
                + bodilessCost + "): the reflection must scale speed, never the cost.",
                bodyCost == bodilessCost);
    }

    @Test
    public void aBodyThatOutmatchesTheShieldPenetratesInsteadOfBouncing() throws Exception {
        int gx = 1010, gz = 846;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);
        for (int i = 0; i < 15; i++) {
            chargeIteration(gx, gz);
        }
        assertTrue("emitter never powered:\n" + read(ex, gz), read(ex, gz).contains("\"powered\":true"));
        long storedBefore = readStored(read(ex, gz));

        // The other side of the condition the reflection rule straddles. Same declared body, but an
        // energy the shield cannot cover: the shield spends what it has, the remainder passes, and the
        // body keeps going the way it was going. A short pay must never bounce anything back.
        int impactEnergy = (int) (storedBefore * 3L);
        String result = strike(ex, gz, impactEnergy, "KINETIC", 0.0D, 0.0D, -2.0D);
        // Without this the whole test passes on a strike that never carried a body at all — "did not
        // reflect" is the trivial answer to "there was nothing there".
        assertTrue("the body this test declares never reached the service:\n" + result,
                result.contains("\"declaredBody\":true"));
        assertTrue("an overmatching strike was reported fully absorbed — the shield cannot afford it:\n"
                + result, result.contains("\"fullyAbsorbed\":false"));
        assertTrue("a shield that could not pay still reflected the body: graceful penetration means the "
                + "body carries on, not that it bounces for free:\n" + result,
                result.contains("\"reflected\":false"));
        assertTrue("no residual impact passed a shield that could not fully pay:\n" + result,
                readLong(result, "residual") > 0);
    }

    private String strike(int ex, int gz, int impactEnergy, String kind) throws Exception {
        return exec(strikeCommand(ex, gz, impactEnergy, kind));
    }

    /** The same strike, additionally DECLARING the travelling body it carries at that world velocity. */
    private String strike(int ex, int gz, int impactEnergy, String kind, double vx, double vy, double vz)
            throws Exception {
        return exec(strikeCommand(ex, gz, impactEnergy, kind) + " " + vx + " " + vy + " " + vz);
    }

    private String strikeCommand(int ex, int gz, int impactEnergy, String kind) {
        double cx = ex + 0.5D, cy = Y + 0.5D, cz = gz + 0.5D;
        double ox = cx, oy = cy, oz = cz + RADIUS + 3.0D; // outside the +Z shell
        return "artest shield strike " + DIM + " " + ox + " " + oy + " " + oz
                + " 0 0 -1 10 " + impactEnergy + " " + kind;
    }

    private static double square(double v) {
        return v * v;
    }

    private String read(int x, int z) throws Exception {
        return exec("artest shield read " + DIM + " " + x + " " + Y + " " + z);
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private void chargeIteration(int gx, int gz) throws Exception {
        exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
        exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
        exec("artest shield tick " + DIM);
    }

    private static long readStored(String json) {
        Matcher m = STORED.matcher(json);
        assertTrue("no shieldStored field in probe response: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9][0-9.eE+-]*)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
