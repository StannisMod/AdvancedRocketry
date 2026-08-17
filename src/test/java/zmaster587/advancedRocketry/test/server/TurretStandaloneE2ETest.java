package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A gun with nothing attached to it.
 *
 * <p>No cable, no console, no network — the configuration a player who has just built their first
 * turret is in, and the one a design that leans on a control network is most likely to leave broken.
 * Everything asserted here is about that gun alone: that its numbers come from what was built around
 * it, that it fires at what it was pointed at, and that it stops firing for reasons it states. If a
 * later wave makes any of this depend on a network being present, these go red — which is the point
 * of writing them before the network has a console at all.</p>
 */
public class TurretStandaloneE2ETest extends AbstractSharedServerTest {

    /** This class's own site, clear of the other server scenarios. */
    private static final int X = 9400, Y = 80, Z = 9400;

    /** How long a gun is given to get a round away before the test calls it broken. */
    private static final long FIRE_TIMEOUT_MS = 20_000L;

    /** Vanilla's surface projectile gravity — what a round fired in the overworld falls by per tick. */
    private static final double SURFACE_GRAVITY_PER_TICK_SQUARED = 0.03D;

    /**
     * Inside the region Valkyrien Skies allocates ship blocks in — its chunk allocator starts at
     * chunk X 320000, so anything past block X ~5.12 million is shipyard.
     */
    private static final int SHIPYARD_X = 5_120_400;

    @Test
    public void aGunWithNoNetworkFiresAtWhatItWasPointedAt() throws Exception {
        int bx = X;
        buildSite(bx);
        buildGun(bx);

        String built = awaitOperable(bx);
        assertTrue("what was built is not a gun: " + built, built.contains("\"operable\":true"));
        assertEquals("every part placed should have been counted: " + built, 8, extractInt(built, "parts"));
        assertTrue("a built gun must have a muzzle speed: " + built, readDouble(built, "muzzleSpeed") > 0.0D);

        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);
        // A point 40 blocks away, level with the mount: reachable, and nothing of the gun's own is
        // in the way.
        exec("artest turret target 0 " + bx + " " + Y + " " + Z + " " + (bx + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D));

        String fired = awaitShots(bx, 1);
        assertTrue("a gun with a target, a charge and no network never fired: " + fired,
                extractInt(fired, "shots") >= 1);
        assertTrue("a gun that fired must name the round it fired: " + fired,
                readLong(fired, "lastShot") > 0L);
    }

    /**
     * The round is a real one: it exists in the substrate, it is going the way the gun is pointing,
     * and it is worth what the build says it is worth.
     */
    @Test
    public void theRoundItFiresIsTheRoundItsBuildDescribes() throws Exception {
        int bx = X + 100;
        buildSite(bx);
        buildGun(bx);
        awaitOperable(bx);
        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);
        // Straight up: nothing to hit, so the round is still in the air to be read.
        exec("artest turret target 0 " + bx + " " + Y + " " + Z + " " + (bx + 0.5D) + " "
                + (Y + 200.5D) + " " + (Z + 0.5D));

        String fired = awaitShots(bx, 1);
        long shotId = readLong(fired, "lastShot");
        int declaredEnergy = extractInt(fired, "impactEnergy");
        double declaredSpeed = readDouble(fired, "muzzleSpeed");

        String shot = exec("artest shot read 0 " + shotId);
        assertTrue("the gun reported a shot the substrate does not have: " + shot,
                shot.contains("\"present\":true"));
        assertEquals("the round is not worth what the build says it is worth: " + shot,
                declaredEnergy, extractInt(shot, "energy"));
        // Not an equality: by the time a test can read it, the round has been in the air for a few
        // ticks and the world's gravity has been acting on it — which is the substrate doing its
        // job. What is pinned is that it LEFT at the build's muzzle speed and that nothing other
        // than the declared environment has touched it since.
        int age = extractInt(shot, "age");
        double speed = readDouble(shot, "speed");
        double gravityLoss = SURFACE_GRAVITY_PER_TICK_SQUARED * age;
        assertTrue("the round is faster than the build can fire (" + speed + " vs " + declaredSpeed
                + "): " + shot, speed <= declaredSpeed + 1.0E-6D);
        assertTrue("the round is slower than gravity alone can explain (" + speed + " after " + age
                + " ticks, muzzle " + declaredSpeed + "): something other than the declared"
                + " environment is acting on it: " + shot,
                speed >= declaredSpeed - gravityLoss - 1.0E-3D);
        assertTrue("a gun aimed straight up fired something that is not going up: " + shot,
                readDouble(shot, "vy") > 0.0D);
    }

    /** A controller with nothing built around it is not a gun and does not fire. */
    @Test
    public void anUnbuiltControllerIsNotAGunAndFiresNothing() throws Exception {
        int bx = X + 200;
        buildSite(bx);
        place("advancedrocketry:turret", bx, Y, Z);

        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);
        exec("artest turret target 0 " + bx + " " + Y + " " + Z + " " + (bx + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D));
        Thread.sleep(3_000L);

        String state = read(bx);
        assertFalse("a bare controller must not report itself operable: " + state,
                state.contains("\"operable\":true"));
        assertEquals("a bare controller fired something: " + state, 0, extractInt(state, "shots"));
    }

    /** A dead drive is the one failure that stops the shooting as well as the turning. */
    @Test
    public void aDeadDriveStopsTheGunFiring() throws Exception {
        int bx = X + 300;
        buildSite(bx);
        buildGun(bx);
        // Wait for the build to be counted BEFORE killing the drive: a gun that was never assembled
        // fires nothing either, and this test would then pass without ever exercising its subject.
        String armed = awaitOperable(bx);
        assertTrue("the gun was never assembled, so a silent gun proves nothing: " + armed,
                armed.contains("\"operable\":true"));
        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);
        exec("artest turret drive 0 " + bx + " " + Y + " " + Z + " DEAD");
        exec("artest turret target 0 " + bx + " " + Y + " " + Z + " " + (bx + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D));
        Thread.sleep(3_000L);

        String state = read(bx);
        assertEquals("a gun with a dead drive fired: " + state, 0, extractInt(state, "shots"));
        assertEquals("the drive state was not the one that was set: " + state, "DEAD",
                extractString(state, "drive"));
    }

    /**
     * A gun whose own hull is in front of the barrel holds fire instead of demolishing it.
     *
     * <p>Every other scenario in this class mounts the gun in open air, which is exactly the
     * arrangement that cannot exhibit the defect this pins: the muzzle sits a few blocks along the
     * aim and nothing asks what is there, so a turret recessed into a hull shells its own ship one
     * round at a time.</p>
     */
    @Test
    public void aGunWithItsOwnHullInFrontOfTheBarrelHoldsFire() throws Exception {
        int bx = X + 400;
        buildSite(bx);
        buildGun(bx);
        awaitOperable(bx);
        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);

        // A wall across the line of fire, just past where the muzzle sits.
        assertTrue("could not build the wall", exec("artest fill 0 " + (bx + 6) + " " + (Y - 1) + " "
                + (Z - 2) + " " + (bx + 7) + " " + (Y + 2) + " " + (Z + 2) + " minecraft:stone")
                .contains("\"ok\":true"));
        exec("artest turret target 0 " + bx + " " + Y + " " + Z + " " + (bx + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D));
        Thread.sleep(4_000L);

        String blocked = read(bx);
        assertEquals("a gun fired into the structure it is built into: " + blocked, 0,
                extractInt(blocked, "shots"));
        assertTrue("the gun was not even aiming, so the silence proves nothing: " + blocked,
                blocked.contains("\"onTarget\":true"));

        // The control: take the wall away and the same gun, same target, fires.
        assertTrue("could not clear the wall", exec("artest fill 0 " + (bx + 6) + " " + (Y - 1) + " "
                + (Z - 2) + " " + (bx + 7) + " " + (Y + 2) + " " + (Z + 2) + " minecraft:air")
                .contains("\"ok\":true"));
        String firing = awaitShots(bx, 1);
        assertTrue("with the obstruction gone the gun still refuses to fire, so the hold was not"
                + " about the wall: " + firing, extractInt(firing, "shots") >= 1);
    }

    /**
     * A gun standing in the shipyard that no ship claims does NOTHING — it does not even count its
     * own build.
     *
     * <p>Valkyrien Skies keeps ship blocks in a far-off region (block X past ~5.12 million), and a
     * ship's chunks load before its ship object exists. In that window every coordinate a machine
     * aboard holds is a shipyard address rather than a place in the world, so there is no partial
     * behaviour that is correct — only waiting. The control that keeps this from passing for the
     * wrong reason is {@link #aGunWithNoNetworkFiresAtWhatItWasPointedAt}: the same eight blocks,
     * placed the same way at ordinary coordinates, assemble and fire.</p>
     */
    @Test
    public void aGunAboardAnUnnamedShipDoesNothingAtAll() throws Exception {
        int bx = SHIPYARD_X;
        buildSite(bx);
        buildGun(bx);

        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);
        exec("artest turret target 0 " + bx + " " + Y + " " + Z + " " + (bx + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D));
        Thread.sleep(4_000L);

        String state = read(bx);
        assertFalse("a gun aboard an unnamed ship counted its build: it is ticking when it should be"
                + " waiting: " + state, state.contains("\"operable\":true"));
        assertEquals("a gun aboard an unnamed ship fired: " + state, 0, extractInt(state, "shots"));
        assertEquals("a gun aboard an unnamed ship turned: " + state, 0.0D, readDouble(state, "yaw"),
                1.0E-9D);

        // NOT a global count: this class shares one server and its other scenarios have rounds of
        // their own in the air. The precise claim is that nothing is flying out THERE — a round
        // fired from the shipyard address would be, by tens of thousands of blocks.
        String inFlight = exec("artest shot list 0");
        double furthest = furthestShotX(inFlight);
        assertTrue("a round is in the air in the shipyard (x=" + furthest + "), so the gun acted on"
                + " an address no player can reach: " + inFlight, furthest < 1_000_000.0D);
    }

    /**
     * The manual seam: a gun under a hand chooses no target, obeys the bearing it is given, and fires
     * only when told — on exactly the same conditions the automatic path checks.
     *
     * <p>This is the half of the manned gun that has to exist for the seat and the first-person view
     * to be an addition rather than a rewrite. It is pinned now, while there is nothing driving it,
     * because a seam nobody exercises is a seam that quietly stops working.</p>
     */
    @Test
    public void aGunUnderManualControlIgnoresItsTargetAndFiresOnlyWhenTold() throws Exception {
        int bx = X + 500;
        buildSite(bx);
        buildGun(bx);
        awaitOperable(bx);
        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);

        // A target it WOULD engage on its own, so "did not fire" is about the mode.
        exec("artest turret target 0 " + bx + " " + Y + " " + Z + " " + (bx + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D));
        exec("artest turret manual 0 " + bx + " " + Y + " " + Z + " true");
        Thread.sleep(4_000L);

        String held = read(bx);
        assertEquals("a gun in manual control fired on an assigned target by itself: " + held, 0,
                extractInt(held, "shots"));
        assertTrue("the gun did not enter manual control: " + held, held.contains("\"manual\":true"));

        // It obeys a hand-given bearing...
        exec("artest turret bearing 0 " + bx + " " + Y + " " + Z + " -90 0");
        Thread.sleep(3_000L);
        String aimed = read(bx);
        assertEquals("the mount ignored the bearing it was handed: " + aimed, -90.0D,
                readDouble(aimed, "yaw"), 2.0D);

        // ...and fires when the trigger is pulled, once per pull.
        String shot = exec("artest turret fire 0 " + bx + " " + Y + " " + Z);
        assertTrue("the trigger did nothing: " + shot + " state: " + read(bx),
                shot.contains("\"fired\":true"));
        assertEquals("one pull fired more than one round: " + read(bx), 1,
                extractInt(read(bx), "shots"));

        // Returning it to automatic re-engages the target it was given.
        exec("artest turret manual 0 " + bx + " " + Y + " " + Z + " false");
        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);
        String resumed = awaitShots(bx, 2);
        assertTrue("the gun never went back to firing on its own: " + resumed,
                extractInt(resumed, "shots") >= 2);
    }

    // ---- scenario construction

    /**
     * The reference gun: a controller with four barrel sections, two feeds and two cooling jackets
     * around it. Every one of them touches the run, which is all the assembly asks of a build.
     */
    private void buildGun(int bx) throws Exception {
        place("advancedrocketry:turret", bx, Y, Z);
        for (int i = 1; i <= 4; i++) {
            place("advancedrocketry:gunBarrel", bx, Y + i, Z);
        }
        place("advancedrocketry:gunAmmoFeed", bx + 1, Y, Z);
        place("advancedrocketry:gunAmmoFeed", bx - 1, Y, Z);
        place("advancedrocketry:gunCooling", bx, Y, Z + 1);
        place("advancedrocketry:gunCooling", bx, Y, Z - 1);
    }

    /** Air around the site, and a chunk that stays loaded so the gun's own tile actually ticks. */
    private void buildSite(int bx) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + ((bx - 16) >> 4) + " "
                + ((Z - 16) >> 4) + " " + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill 0 " + (bx - 4) + " " + (Y - 2) + " "
                + (Z - 4) + " " + (bx + 60) + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air")
                .contains("\"ok\":true"));
        assertTrue("could not hold the chunk", exec("artest chunk forceload 0 " + (bx >> 4) + " "
                + (Z >> 4)).contains("\"ok\":true"));
    }

    /**
     * Wait for the gun to have fired at least {@code wanted} rounds, and answer its state.
     *
     * <p>Polled rather than counted in ticks: the harness server really runs, so how many ticks pass
     * while a command round-trips is not something a test gets to decide. What is asserted is that
     * it fired at all, within a bound generous enough that a slow host is not a failure.</p>
     */
    private String awaitShots(int bx, int wanted) throws Exception {
        long deadline = System.currentTimeMillis() + FIRE_TIMEOUT_MS;
        String state = read(bx);
        while (System.currentTimeMillis() < deadline && extractInt(state, "shots") < wanted) {
            Thread.sleep(250L);
            state = read(bx);
        }
        return state;
    }

    /**
     * Wait until the gun has counted what was built around it. The assembly is re-walked on its own
     * cadence rather than on every block change, so a read taken the instant the last part lands is
     * reading a gun that has not looked at itself yet.
     */
    private String awaitOperable(int bx) throws Exception {
        long deadline = System.currentTimeMillis() + FIRE_TIMEOUT_MS;
        String state = read(bx);
        while (System.currentTimeMillis() < deadline && !state.contains("\"operable\":true")) {
            Thread.sleep(250L);
            state = read(bx);
        }
        return state;
    }

    private String read(int bx) throws Exception {
        return exec("artest turret read 0 " + bx + " " + Y + " " + Z);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** The largest x any shot in flight reports, or 0 when nothing is up. */
    private static double furthestShotX(String json) {
        Matcher m = Pattern.compile("\"x\":(-?[\\d.eE+]+)").matcher(json);
        double furthest = 0.0D;
        while (m.find()) {
            furthest = Math.max(furthest, Math.abs(Double.parseDouble(m.group(1))));
        }
        return furthest;
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[\\d.eE+]+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
