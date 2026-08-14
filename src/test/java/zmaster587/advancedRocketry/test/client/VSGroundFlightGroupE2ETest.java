package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;

import static org.junit.Assert.assertTrue;

/**
 * Ground-assembled tier-2 ships, flown: four scenarios that used to be four classes and four client
 * boots, now one boot.
 *
 * <p>Each scenario keeps the base coordinates its own green runs were taken on — 2200, 2400, 2600,
 * 2800 along the x==z diagonal, 200 blocks apart — because a migrating test that changes its ground
 * has changed its subject. The fifth scenario is not a migration: it is the control that proves the
 * ship oracle this class relies on can tell two ships apart.</p>
 *
 * <h2>Why every ship question here is keyed on an ID</h2>
 *
 * <p>{@code vs ship-info <dim> <x> <y> <z>} answers about the loaded ship NEAREST the point. On the
 * one-boot-per-test world these scenarios came from there was only ever one ship, so the position
 * was an identity by accident. Here there are four, and neither available bound works: unbounded,
 * the reply describes whichever neighbour happens to be closest; bounded by
 * {@link #SHIP_QUERY_RADIUS}, it describes nothing at all as soon as a scenario flies its ship
 * further than that — and {@link #seatedPilotFliesShipTravelsWithItAndCameraLocksToNose} holds the
 * lift key for sixty uninterrupted ticks on purpose. So each scenario captures its ship's id the
 * moment it loads and asks by id from then on.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSGroundFlightGroupE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-ground-flight";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern VEL_Y = Pattern.compile("\"velY\":(-?[0-9.E\\-]+)");
    private static final Pattern QW = Pattern.compile("\"qw\":(-?[0-9.E\\-]+)");
    private static final Pattern QX = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern QY = Pattern.compile("\"qy\":(-?[0-9.E\\-]+)");
    private static final Pattern QZ = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");

    private static final String SEAT_VARIANT = "with-pilot-seat";
    private static final String AFC_VARIANT = "with-advanced-flight-computer";

    // ── the ship oracle's own control ────────────────────────────────────────

    /**
     * The instrument this whole class rests on, exercised against the situation it exists for: TWO
     * loaded ships, and a question that must name one of them.
     *
     * <p>A detector nobody has watched fire manufactures confidence, so this asserts BOTH legs. The
     * id-keyed lookup must answer about the ship it names and about no other — including when that
     * ship is nowhere near where it was built. And the unbounded positional lookup, asked at the
     * FIRST ship's base after the second one has been parked closer to it, must be shown answering
     * with the WRONG ship: without that leg "the id form was right" would be indistinguishable from
     * "any form would have been right here".</p>
     */
    @Test
    public void aShipQuestionKeyedOnIdNamesItsOwnShipAndTheNearestFormDoesNot() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath", serverHasVs());

        final int ax = 5400, ay = 64, az = 5400;
        final int bx = 5500, by = 64, bz = 5500;

        // Two ships, built 141 blocks apart — this tier's own fixture spacing.
        exec("tp @a " + (ax + 600) + " 120 " + (az + 600) + " 0 0");
        bot().waitTicks(10);
        String assembleA = assembleFixture(ax, ay, az, AFC_VARIANT);
        scenario().requireArranged("ship A must assemble: " + assembleA,
                assembleA.contains("\"rocketCount\":0"));
        String assembleB = assembleFixture(bx, by, bz, AFC_VARIANT);
        scenario().requireArranged("ship B must assemble: " + assembleB,
                assembleB.contains("\"rocketCount\":0"));

        // permaload so BOTH stay loaded with one client that cannot stand in two places. This is an
        // affordance, and it is scoped OFF the leg under test: the question here is which ship a
        // lookup names, never whether a ship stays loaded.
        exec("artest vs permaload true");
        exec("artest vs load-ships 0");
        exec("tp @a " + (ax + 0.5) + " " + (ay + 6) + " " + (az + 0.5) + " 0 0");
        bot().waitTicks(20);

        String idA = captureShipIdAt(ax, ay, az);
        String idB = captureShipIdAt(bx, by, bz);
        scenario().record("idA", idA).record("idB", idB);
        assertTrue("ARRANGEMENT: the two fixtures must be two DIFFERENT ships, or this control has "
                + "nothing to discriminate (idA=" + idA + " idB=" + idB + ")", !idA.equals(idB));

        // Move A far from its build site — the situation a bounded query cannot survive and an
        // unbounded one answers wrongly. It is put NEXT TO B so "nearest to A's base" is B.
        String moved = exec("artest vs teleport-ship 0 " + ax + " " + ay + " " + az
                + " " + (bx + 8) + " " + (by + 40) + " " + (bz + 8));
        scenario().record("teleportA", moved);
        exec("artest vs unpark 0 " + (bx + 8) + " " + (by + 40) + " " + (bz + 8));
        bot().waitTicks(20);

        // LEG 1 — the id still names A, and the position it reports is A's NEW one.
        String byIdA = shipInfoById(idA);
        scenario().record("byIdA", byIdA);
        assertTrue("an id-keyed lookup must still answer about the ship it names after that ship "
                + "has moved: " + byIdA, byIdA.contains("\"managed\":true"));
        assertTrue("…and the id in the reply must be the one asked for: " + byIdA,
                idA.equals(readShipId(byIdA)));
        double aY = readDouble(byIdA, POS_Y);
        assertTrue("…and it must report where A IS now, not where it was built (posY=" + aY + ")",
                aY > ay + 20);

        // LEG 2 — the nearest form, asked at A's OWN base, now names B. This is the failure the id
        // form exists to remove, observed rather than asserted.
        String nearestAtAsBase = exec("artest vs ship-info 0 " + ax + " " + ay + " " + az);
        scenario().record("nearestAtAsBase", nearestAtAsBase);
        assertTrue("the unbounded nearest lookup must still answer something: " + nearestAtAsBase,
                nearestAtAsBase.contains("\"managed\":true"));
        assertTrue("CONTROL: asked at A's own base with A flown away, the unbounded nearest lookup "
                + "must answer with B — this is the wrong answer the id form exists to prevent, and "
                + "a run where it happens to be right would make the id form untested. reply="
                + nearestAtAsBase, idB.equals(readShipId(nearestAtAsBase)));

        // LEG 3 — and the bounded form, the previous mitigation, answers about NOTHING.
        String boundedAtAsBase = exec("artest vs ship-info 0 " + ax + " " + ay + " " + az
                + " " + SHIP_QUERY_RADIUS);
        scenario().record("boundedAtAsBase", boundedAtAsBase);
        assertTrue("CONTROL: bounded at " + SHIP_QUERY_RADIUS + " blocks the same question answers "
                + "managed:false — correct about the neighbour and useless about A, which is why a "
                + "radius is a mitigation and not an identity. reply=" + boundedAtAsBase,
                boundedAtAsBase.contains("\"managed\":false"));
    }

    // ── migrated: VSShipClientLoadE2ETest ────────────────────────────────────

    /**
     * Migrated verbatim from {@code VSShipClientLoadE2ETest}. Every {@code ship-info} in the body
     * was a positional nearest query about a ship this scenario had just built; each is now keyed
     * on the id captured at load. Nothing else changed.
     */
    @Test
    public void assembledShipLoadsWithClientPresentAndFliesAndRotatesUnderForce() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath", serverHasVs());

        final int BX = 2200, BY = 64, BZ = 2200;

        // Keep the client FAR AWAY during assembly + spawn. VS crashes with
        // "Tried loading a ShipData that was already loaded?" if a player is near the
        // ship as it spawns (spawn-load and proximity-load collide in one server tick).
        // Assemble with no observer, let the ship settle, THEN approach so a single
        // proximity load runs.
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        int allBefore = count("ship-count-all");
        String assemble = assembleFixture(BX, BY, BZ, AFC_VARIANT);
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // Wait for the ship to appear in the queryable registry (async spawn), then let
        // it fully settle with no observer. An INCREMENT, not an absolute count: on a shared world
        // "there is a ship in dim 0" is answered by every neighbour that ever assembled one.
        int all = allBefore;
        for (int i = 0; i < 40 && all <= allBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a VS ship in the queryable registry (before=" + allBefore
                + " after=" + all + ")", all > allBefore);
        bot().waitTicks(40); // settle before any observer approaches

        // Now walk the client ONTO the ship's projected location. A real client near the
        // ship pulls its chunks in and VS loads it — the thing testServer never does.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Poll for the ship to become LOADED (the state testServer could never reach) and take its
        // identity in the same step — every question below is about THIS ship.
        final String shipId = captureShipIdAt(BX, BY, BZ);
        double zBefore = readDouble(shipInfoById(shipId), POS_Z);
        assertTrue("a VS ship must LOAD with a client present", !Double.isNaN(zBefore));

        // Now that it is loaded + physics-enabled, command a straight-UP velocity realized
        // as FORCE (the working path — a raw setpoint does nothing). Up isolates the result
        // from ground friction: the only thing to overcome is gravity, and the controller's
        // deadbeat force (F = mass·accel, clamped to thrust authority) exceeds it. Re-command
        // each tick and POLL for the climb — VS's physics-thread activation after a load can
        // lag a few ticks, so drive until it rises (bounded) rather than a fixed window.
        double yBefore = readDouble(shipInfoById(shipId), POS_Y);
        double yAfter = yBefore;
        double velY = 0.0;
        for (int i = 0; i < 80 && yAfter - yBefore <= 1.5; i++) {
            String cmd = exec("artest vs force-vel-by-id 0 " + shipId + " 0 8 0");
            assertTrue("force-vel must reach THIS ship's own flight computer: " + cmd,
                    cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            String info = shipInfoById(shipId);
            yAfter = readDouble(info, POS_Y);
            velY = readDouble(info, VEL_Y);
        }

        // Force actually integrates into motion: the ship must climb. (Model A's setLinearVelocity
        // left this flat with velY≈1.8; a force controller lifts it.)
        assertTrue("commanded +Y velocity (via force) must lift the loaded ship "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + " velY=" + velY + ")",
                yAfter - yBefore > 1.0);

        // The same controller must also ROTATE the ship: command a yaw angular velocity,
        // realized as TORQUE (linear zeroed -> the ship hovers while it turns). The ship's
        // body->world attitude quaternion must move meaningfully off where it started. Poll
        // for the turn (bounded) for the same activation-lag robustness.
        double[] qBefore = readQuat(shipInfoById(shipId));
        double dot = 1.0;
        for (int i = 0; i < 80 && dot >= 0.97; i++) {
            String cmd = exec("artest vs force-rot-by-id 0 " + shipId + " 0 1.0 0");
            assertTrue("force-rot must reach THIS ship's own flight computer: " + cmd,
                    cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            double[] qNow = readQuat(shipInfoById(shipId));
            // |dot| of two unit quaternions is cos(halfAngle); < 0.98 => rotated by more than ~23°.
            dot = Math.abs(qBefore[0] * qNow[0] + qBefore[1] * qNow[1]
                    + qBefore[2] * qNow[2] + qBefore[3] * qNow[3]);
        }
        assertTrue("commanded yaw (via torque) must rotate the loaded ship "
                        + "(|quat dot|=" + dot + ", 1.0 = unmoved)",
                dot < 0.98);

        // ATTITUDE HOLD: command an absolute target orientation (90° yaw about world Y) and the
        // controller must drive the ship's attitude TO it and converge — the interface Free
        // Flight feeds (its per-tick target quaternion). Poll for convergence (bounded).
        final double[] target = {0.70710678, 0.0, 0.70710678, 0.0}; // {w,x,y,z}
        double convDot = 0.0;
        for (int i = 0; i < 120 && convDot < 0.98; i++) {
            String cmd = exec("artest vs point-by-id 0 " + shipId
                    + " " + target[0] + " " + target[1] + " " + target[2] + " " + target[3]);
            assertTrue("point must reach THIS ship's own flight computer: " + cmd,
                    cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            double[] q = readQuat(shipInfoById(shipId));
            convDot = Math.abs(q[0] * target[0] + q[1] * target[1] + q[2] * target[2] + q[3] * target[3]);
        }
        assertTrue("attitude-hold must converge the ship to the commanded orientation "
                        + "(|dot to target|=" + convDot + ", 1.0 = exact)",
                convDot > 0.98);

        // FULL FREE FLIGHT PATH: hand the flight computer a held pilot input. Its server tick
        // reads the ship's attitude, runs FreeFlightPhysics, and publishes to the controller —
        // no probe touches the ship command directly here. A throttle input must MOVE the ship
        // through that path (throttle -> body axis -> world velocity -> controller force). We assert
        // total displacement (not just altitude): the ship is left tilted by the earlier rotate
        // and attitude phases, so "body up" is not world up — moving at all is the contract here.
        // Hand the ship back to its own flight computer first. The three rungs above drove this
        // computer's PROBE channel, which deliberately outranks the pilot channel — so leaving it in
        // force would make the FF rung measure the attitude hold that is still commanded, not the
        // throttle. Asserted: a release nobody checks is indistinguishable from no release.
        String released = exec("artest vs force-clear-by-id 0 " + shipId);
        assertTrue("ARRANGEMENT: the probe command must be released before the FF path is measured: "
                + released, released.contains("\"cleared\":true"));

        double[] pBefore = readVec(shipInfoById(shipId));
        double[] at = pBefore;
        double disp = 0.0;
        for (int i = 0; i < 80 && disp <= 1.5; i++) {
            // Addressed by SHIP and re-issued from its freshest pose each iteration. The input used to
            // go to a server-wide static, which no pilot has; re-sending is also what a real pilot's
            // client does every tick, and it keeps the address on a ship that is by now moving.
            String cmd = exec("artest vs ff-input-by-id 0 " + shipId
                    + " 0 1 0 0 0 0"); // throttleVertical = full up
            assertTrue("the throttle must reach this ship's own flight computer: " + cmd,
                    cmd.contains("\"afcResolved\":true"));
            bot().waitTicks(1);
            double[] p = readVec(shipInfoById(shipId));
            at = p;
            double dx = p[0] - pBefore[0], dy = p[1] - pBefore[1], dz = p[2] - pBefore[2];
            disp = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        assertTrue("a Free Flight throttle input must move the ship through the AFC's FF path "
                        + "(displacement=" + disp + ")",
                disp > 1.0);
    }

    // ── migrated: VSShipNearbyObserverNoCrashE2ETest ─────────────────────────

    /**
     * Migrated from {@code VSShipNearbyObserverNoCrashE2ETest}. Its two gates were whole-dimension
     * counts, which on a shared world are answered by a neighbour's ship before this scenario builds
     * anything: "appeared in the registry" is now an increment, and "loaded" is this ship's own id
     * resolving at its own base.
     */
    @Test
    public void assemblingWithAnObserverAtThePadDoesNotCrashVs() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath", serverHasVs());

        final int BX = 2400, BY = 64, BZ = 2400;

        // Put the observer AT the build site and keep it there through assembly + spawn — the
        // double-load window the sister test avoids. If the guard is absent, VS faults here.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(10);

        int allBefore = count("ship-count-all");
        String assemble = assembleFixture(BX, BY, BZ, AFC_VARIANT);
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // The ship must appear in the queryable registry (async spawn did not fault) ...
        int all = allBefore;
        for (int i = 0; i < 40 && all <= allBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly with an observer present must still create a VS ship (before="
                        + allBefore + " after=" + all
                        + ") — a fault in the double-load window would prevent it", all > allBefore);

        // ... and it must LOAD (the observer never left, so the proximity load runs in the same
        // window as the spawn load). Reaching LOADED with the observer present through spawn is
        // the no-crash contract: the guard turned the illegal double-load into a no-op. Scoped to
        // THIS ship, at THIS base — a neighbour's loaded ship is not evidence about this window.
        String shipId = captureShipIdAt(BX, BY, BZ, 60);
        assertTrue("a VS ship assembled under a nearby observer must load without VS faulting "
                        + "(id=" + shipId + ", registry before=" + allBefore + " after=" + all + ")",
                shipInfoById(shipId).contains("\"managed\":true"));
    }

    // ── migrated: VSShipSeatDriveE2ETest ─────────────────────────────────────

    /**
     * Migrated from {@code VSShipSeatDriveE2ETest}: the server-side bisection of the seat &rarr; AFC
     * &rarr; force path. Body unchanged apart from the ship oracle.
     */
    @Test
    public void seatPathResolvesAfcAndFliesTheShip() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath", serverHasVs());

        final int BX = 2600, BY = 64, BZ = 2600;

        // Assemble far from any observer (double-load window), then approach to load.
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        int allBefore = count("ship-count-all");
        String assemble = assembleFixture(BX, BY, BZ, SEAT_VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));

        int all = allBefore;
        for (int i = 0; i < 40 && all <= allBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a VS ship (before=" + allBefore + " after=" + all + ")",
                all > allBefore);
        bot().waitTicks(40);

        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        final String shipId = captureShipIdAt(BX, BY, BZ);
        double yBefore = readDouble(shipInfoById(shipId), POS_Y);

        // Server-side seat drive: the seat must resolve its AFC, and a full-up throttle through
        // the seat->AFC per-tile path must lift the ship (isolates ground friction: up only).
        double yAfter = yBefore;
        String lastSeat = "";
        for (int i = 0; i < 80 && yAfter - yBefore <= 1.5; i++) {
            lastSeat = exec("artest vs seat-input 0 0 1 0 0 0 0"); // throttleVertical = full up
            assertTrue("seat-input must find the pilot seat: " + lastSeat,
                    lastSeat.contains("\"seatFound\":true"));
            assertTrue("the pilot seat must resolve its linked flight computer (offset intact "
                            + "after VS relocation): " + lastSeat,
                    lastSeat.contains("\"afcResolved\":true"));
            bot().waitTicks(1);
            yAfter = readDouble(shipInfoById(shipId), POS_Y);
        }
        assertTrue("a throttle driven through the pilot seat -> AFC -> force path must lift the ship "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + ", lastSeat=" + lastSeat + ")",
                yAfter - yBefore > 1.0);
    }

    // ── migrated: VSShipPilotKeysE2ETest ─────────────────────────────────────

    /**
     * Migrated from {@code VSShipPilotKeysE2ETest} — the full-path pilot e2e. This is the scenario
     * that makes the id keying compulsory rather than tidy: it holds the lift key for sixty
     * uninterrupted ticks to climb clear of the terrain, which puts the ship far outside any bound
     * a nearest-lookup could carry.
     *
     * <p>Its seat lookup is scoped too. {@code vs seat-mount} takes the FIRST pilot seat in the
     * world's loaded-tile list with no position filter, so on a shared world it mounts whichever
     * ship happens to be listed first; {@code vs find-seat} resolves the seat inside the ship at a
     * given world anchor, and {@code seat-mount-at} mounts that one.</p>
     */
    @Test
    public void seatedPilotFliesShipTravelsWithItAndCameraLocksToNose() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath", serverHasVs());

        final int BX = 2800, BY = 64, BZ = 2800;

        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        int allBefore = count("ship-count-all");
        String assemble = assembleFixture(BX, BY, BZ, SEAT_VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));

        int all = allBefore;
        for (int i = 0; i < 40 && all <= allBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a VS ship (before=" + allBefore + " after=" + all + ")",
                all > allBefore);
        bot().waitTicks(40);

        // Approach so the client loads the ship (and its seat/AFC tiles).
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        final String shipId = captureShipIdAt(BX, BY, BZ);
        String atRest = shipInfoById(shipId);
        double yBefore = readDouble(atRest, POS_Y);
        scenario().record("shipAtRest", atRest);

        // Sit the bot on THIS ship's pilot seat: resolve the seat inside the ship at this
        // scenario's own anchor, then mount that subspace block.
        String found = exec("artest vs find-seat 0 " + BX + " " + (BY + 5) + " " + BZ);
        Matcher sm = Pattern.compile("\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)")
                .matcher(found);
        assertTrue("find-seat must resolve THIS ship's subspace seat: " + found, sm.find());
        String mountInfo = exec("artest vs seat-mount-at 0 " + sm.group(1) + " " + sm.group(2)
                + " " + sm.group(3));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount-at must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat

        // Baseline the CLIENT pilot position BEFORE the climb: the mount the bot rides (its dummy)
        // and the player camera. A pilot glued to the ship rises with it; a detached one stays here.
        double riderYBefore = bot().reportRidingEntity().get("posY").getAsDouble();
        double camYBefore = bot().reportState().get("playerY").getAsDouble();

        // Drive REAL keys: hold vertical-up. The client samples it, sends it to the seat, and the
        // AFC lifts the ship. Up isolates from ground friction; poll for the climb (bounded).
        final double y0 = yBefore;
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (load-scaled ceiling + early exit): a fixed 100-iteration budget
            // under-lifts a frame-starved client under concurrent-fork load and reds a healthy climb.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDouble(shipInfoById(shipId), POS_Y),
                    y -> y - y0 > 1.5, 2, 100);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double yAfter = lift.value;
        scenario().record("lift", lift);

        assertTrue("holding the vertical-up key while seated must lift the ship through the FULL "
                        + "client path (key -> packet -> seat -> AFC -> force): yBefore=" + yBefore
                        + " yAfter=" + yAfter,
                yAfter - yBefore > 1.0);

        // --- The seated pilot must TRAVEL with the ship (client-observed). Read the CLIENT rider +
        // camera again: both must have climbed, and the rider's climb must track the server ship's.
        // Before the fix that glues the seat dummy to the moving ship, the dummy stays at spawn while
        // the ship departs, so these client deltas would be ~0 even though the server ship moved.
        bot().waitTicks(6); // let the client ship transform settle at the new altitude
        String afterSettle = shipInfoById(shipId);
        double serverYAfter = readDouble(afterSettle, POS_Y);
        double riderYAfter = bot().reportRidingEntity().get("posY").getAsDouble();
        double camYAfter = bot().reportState().get("playerY").getAsDouble();
        scenario().record("shipAfterSettle", afterSettle)
                .record("riderBeforeAfter", riderYBefore + " -> " + riderYAfter)
                .record("serverBeforeAfter", yBefore + " -> " + serverYAfter);
        assertTrue("the CLIENT-rendered rider must climb with the ship (it stayed behind): "
                        + "riderYBefore=" + riderYBefore + " riderYAfter=" + riderYAfter,
                riderYAfter - riderYBefore > 1.0);
        assertTrue("the pilot's CLIENT camera must climb with the ship: camYBefore=" + camYBefore
                        + " camYAfter=" + camYAfter,
                camYAfter - camYBefore > 1.0);
        assertTrue("the client rider climb must TRACK the server ship climb (client="
                        + (riderYAfter - riderYBefore) + " server=" + (serverYAfter - yBefore) + ")",
                Math.abs((riderYAfter - riderYBefore) - (serverYAfter - yBefore)) < 3.0);

        // --- The OTHER TWO translation axes, in world coordinates. The vertical key above proves
        // exactly ONE channel of the pilot path; nose and lateral are separate fields of the same
        // packet and separate components of the body-frame setpoint, so a channel that never leaves
        // the client — a binding whose conflict context is off, a field dropped on the wire — is
        // invisible to a vertical-only test. Q/E in particular share their default keys with vanilla
        // drop/inventory and reach the craft only because the pilot-seat conflict context suppresses
        // the vanilla action, a gate the always-active W/S do not carry.
        //
        // A freshly assembled VS ship carries the IDENTITY attitude and nothing above has commanded a
        // rotation, so its nose is world +Z and its right is world +X: which axis a key drives can be
        // read straight off the world position, without asking the ship where it is pointing. It is
        // checked PER AXIS on purpose — "the ship moved" would go green on a key that drove the wrong
        // axis entirely. (This runs BEFORE the mouse leg below, which commands roll and would take
        // body-right off world +X.)
        //
        // Climb clear of the terrain first and then CUT: Flight Assist is a cruise control, so
        // releasing the vertical key leaves the ship climbing, and a horizontal leg flown at pad
        // height could be stopped by a hillside rather than by the ship's own controls.
        bot().holdKey(Keyboard.KEY_R);
        bot().waitTicks(60);
        bot().releaseKey(Keyboard.KEY_R);
        cutAndSettle();

        final double xBeforeNose = readDouble(shipInfoById(shipId), POS_X);
        final double zBeforeNose = readDouble(shipInfoById(shipId), POS_Z);
        ClientPoll.Result<Double> nose;
        bot().holdKey(Keyboard.KEY_W);          // keyBindForward -> body forward
        try {
            nose = ClientPoll.until(bot()::waitTicks, () -> readDouble(shipInfoById(shipId), POS_Z),
                    z -> z - zBeforeNose > 2.0, 2, 60);
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
        }
        double xAfterNose = readDouble(shipInfoById(shipId), POS_X);
        cutAndSettle();
        assertTrue("holding the FORWARD key while seated must drive the ship along its NOSE — world "
                        + "+Z on an identity-attitude ship — through the full client path. "
                        + "zBefore=" + zBeforeNose + " poll=" + nose,
                nose.value - zBeforeNose > 1.0);
        assertTrue("…and it must be the NOSE axis it drives, not merely some motion: the world-Z "
                        + "travel must dominate the world-X travel. dz=" + (nose.value - zBeforeNose)
                        + " dx=" + (xAfterNose - xBeforeNose),
                Math.abs(nose.value - zBeforeNose) > Math.abs(xAfterNose - xBeforeNose));

        final double xBeforeStrafe = readDouble(shipInfoById(shipId), POS_X);
        final double zBeforeStrafe = readDouble(shipInfoById(shipId), POS_Z);
        ClientPoll.Result<Double> strafe;
        bot().holdKey(Keyboard.KEY_Q);          // strafeLeft -> +right -> world +X at identity
        try {
            strafe = ClientPoll.until(bot()::waitTicks, () -> readDouble(shipInfoById(shipId), POS_X),
                    x -> x - xBeforeStrafe > 2.0, 2, 60);
        } finally {
            bot().releaseKey(Keyboard.KEY_Q);
        }
        double zAfterStrafe = readDouble(shipInfoById(shipId), POS_Z);
        cutAndSettle();
        assertTrue("holding the STRAFE key while seated must drive the ship along its LATERAL axis — "
                        + "world +X on an identity-attitude ship. That key is Q, which vanilla binds "
                        + "to drop and which reaches the craft only because the pilot-seat conflict "
                        + "context suppresses the vanilla action; a red here is that suppression, the "
                        + "strafe field on the wire, or the axis it lands on. xBefore=" + xBeforeStrafe
                        + " poll=" + strafe,
                strafe.value - xBeforeStrafe > 1.0);
        assertTrue("…and it must be the LATERAL axis it drives: the world-X travel must dominate "
                        + "the world-Z travel. dx=" + (strafe.value - xBeforeStrafe)
                        + " dz=" + (zAfterStrafe - zBeforeStrafe),
                Math.abs(strafe.value - xBeforeStrafe) > Math.abs(zAfterStrafe - zBeforeStrafe));

        // --- The mouse must STEER the ship, never free-look the camera (the FF cockpit contract).
        // The ship is now hovering roughly upright. Inject a hard SIDEWAYS mouse look each tick
        // (horizontal mouse -> roll cursor; pure roll leaves the nose direction fixed). A camera that
        // free-looks would accumulate tens of degrees off; a nose-locked one is re-pinned to the
        // (unmoved) ship nose every client tick. Read BOTH the client camera and the server ship
        // attitude, converting the latter to a heading with the SAME quat->Euler the lock uses.
        double camYawBefore = bot().reportState().get("playerYaw").getAsDouble();
        for (int i = 0; i < 6; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat() + 30f, st.get("playerPitch").getAsFloat());
            bot().waitTicks(1);
        }
        bot().waitTicks(4);
        double camYawAfter = bot().reportState().get("playerYaw").getAsDouble();
        float shipNoseYaw = shipNoseYaw(shipInfoById(shipId));
        assertTrue("a hard sideways mouse look must NOT free-look the camera — the view stays locked "
                        + "(camYawBefore=" + camYawBefore + " camYawAfter=" + camYawAfter + ")",
                angDiff(camYawAfter, camYawBefore) < 15.0);
        assertTrue("the CLIENT camera yaw must be LOCKED to the ship nose, not where the mouse pointed "
                        + "(camYawAfter=" + camYawAfter + " shipNose=" + shipNoseYaw + ")",
                angDiff(camYawAfter, shipNoseYaw) < 12.0);

        exec("artest player dismount");
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    /**
     * Zero the cruise setpoint and let the ship come to rest. Flight Assist RETAINS a released
     * throttle, so without this each leg would measure the one before it still coasting.
     */
    private void cutAndSettle() throws Exception {
        bot().holdKey(Keyboard.KEY_X);          // throttle cut
        bot().waitTicks(40);
        bot().releaseKey(Keyboard.KEY_X);
        bot().waitTicks(10);
    }

    /** The ship nose heading (MC yaw, degrees) from the attitude quaternion in {@code vs ship-info},
     *  using the SAME quat&rarr;Euler conversion the production camera lock uses (no convention drift). */
    private float shipNoseYaw(String shipInfoJson) {
        return FreeFlightPhysics.eulerFromQuat(new FreeFlightPhysics.Quat(
                readDouble(shipInfoJson, QW), readDouble(shipInfoJson, QX),
                readDouble(shipInfoJson, QY), readDouble(shipInfoJson, QZ)))[0];
    }

    /** Wrapped angular distance on the circle, degrees in [0, 180]. */
    private static double angDiff(double a, double b) {
        return Math.abs(((a - b + 540) % 360) - 180);
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double[] readVec(String shipInfoJson) {
        return new double[]{readDouble(shipInfoJson, POS_X), readDouble(shipInfoJson, POS_Y),
                readDouble(shipInfoJson, POS_Z)};
    }

    private double[] readQuat(String shipInfoJson) {
        return new double[]{readDouble(shipInfoJson, QW), readDouble(shipInfoJson, QX),
                readDouble(shipInfoJson, QY), readDouble(shipInfoJson, QZ)};
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
