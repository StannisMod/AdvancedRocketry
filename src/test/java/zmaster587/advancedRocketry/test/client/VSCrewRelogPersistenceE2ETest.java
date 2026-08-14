package zmaster587.advancedRocketry.test.client;


import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The relog-persistence contract of the ship-frame crew: a player who logs out standing ABOARD a
 * ship's deck logs back in ABOARD, at the same deck point, at any ship attitude - never handed to
 * world gravity while the capture re-seeds.
 *
 * <p>The subject is the HARD side of every axis this bug lives on: a real client player, captured
 * on the deck of an INVERTED ship (world gravity points away from the deck overhead, so any
 * un-captured tick starts a fall), across a REAL relog ({@code ClientBot.reconnect} - a full
 * server logout with player-data save and a fresh login, not a teleport).</p>
 *
 * <p>Gated on real VS - run with {@code -PwithVS}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSCrewRelogPersistenceE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-crew-relog";
    }


    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";

    /**
     * THIS scenario's ship, by identity — captured by {@code buildShip} at the one moment its base
     * provably holds no other, and the address every later question and command uses. A radius bound
     * is a mitigation, not an identity: these scenarios roll, hover and drop the ship on purpose, and
     * a shared client always has a neighbour in candidacy.
     */
    private String scenarioShipId;

    /** The account every client harness launches under; the server keys his data by it. */
    private static final String BOT = "ForgeTestClient";

    private static final Pattern SHIP_FRAME_X = Pattern.compile("\"bodyShipFrameX\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_FRAME_Y = Pattern.compile("\"bodyShipFrameY\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_FRAME_Z = Pattern.compile("\"bodyShipFrameZ\":(-?[0-9.E\\-]+)");

    /**
     * The ship's OWN rotation must never count as someone else moving the crew member.
     *
     * <p>This test fails if production breaks the contract that <b>a body standing on a deck stays
     * captured, and stays put in the ship frame, while the ship rotates under it</b> - at any rotation
     * rate, with no input at all.</p>
     *
     * <p><b>Why this leg exists, and why it is on a planet.</b> The maintainer reported being dragged
     * along his deck after a login in space, on a ship that was inverted. His own session log named the
     * mechanism: the external-move guard released the deck capture 25 times in ~50 s with
     * {@code externalMove(sub)} deltas up to 0.43 blocks/tick against its 0.2-block slack, while the
     * ship's attitude was settling at a 53.6 deg tilt - and between the release and the re-capture the
     * body belongs to vanilla and the physics mod, so it slides. The guard's own javadoc names this case
     * in advance: the slack absorbs "about one tick of ship motion at the body's radius from the
     * rotation centre ... a far-from-centre pilot on a violently spinning ship is the one case where it
     * could still approach the slack".</p>
     *
     * <p>So the DRIVER is ship rotation at radius - not the space cell, not the relog, not the login
     * restore, all of which merely accompanied it in the report. Reproducing the driver puts the subject
     * where rolling a ship is already proven to work, and it removes three variables from the
     * arrangement. A space-cell version would additionally need a way to rotate a ship inside a cell,
     * which the harness currently does not have.</p>
     *
     * <p>The idle window before the roll is the control: the guard must be quiet while nothing rotates,
     * or a count taken during the roll is not attributable to the roll.</p>
     */
    @Test
    public void aCrewMemberStandingOnADeckIsNotReleasedWhileTheShipRolls() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        requireHeIsHeldThroughARoll(6680, 6680, "ordinary world coordinates");
    }

    // A "far from origin" variant of this leg was tried and is REFUTED, so it is not here: the
    // 5120001.5 / 51200.9 coordinates in the reporter's log are the ship's SUBSPACE point, not a world
    // position. Measured - a ship built at world 6720 reports its crew at B=5120000.000, 51200.000 in
    // exactly the same range, because that is simply where Valkyrien Skies parks a subspace. There is
    // no coordinate-magnitude variable to vary.
    private void requireHeIsHeldThroughARoll(int bx, int bz, String where) throws Exception {
        final int by = 64;

        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("ARRANGEMENT: he must be captured on the deck before anything rotates: "
                        + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        // CONTROL: a still ship must produce no releases and no travel. Without it, a nonzero count
        // during the roll could belong to the arrangement (the walk onto the deck, the settle) rather
        // than to the rotation.
        long dropsAtRest = clientLong("externalMoveDrops");
        long restMark = lastClientTick();
        bot().waitTicks(30);
        String restHistory = clientTickHistory();
        long dropsDuringRest = clientLong("externalMoveDrops") - dropsAtRest;
        double restTravel = bodyPointTravel(restHistory, restMark);

        // THE DRIVER: roll the ship under him, and measure WHILE it turns - the release happens during
        // the attitude change, not after it.
        double h = Math.toRadians(170.0) / 2.0;
        assertTrue("ARRANGEMENT: the attitude hold must accept the roll command",
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        long rollMark = lastClientTick();
        long dropsBeforeRoll = clientLong("externalMoveDrops");
        double upY = 1.0;
        for (int attempt = 0; attempt < 25 && upY > -0.9; attempt++) {
            bot().waitTicks(10);
            double qx = readDouble(shipInfo(), Pattern.compile("\"qx\":(-?[0-9.E\\-]+)"));
            upY = 1.0 - 2.0 * qx * qx;
        }
        String rollHistory = clientTickHistory();
        long dropsDuringRoll = clientLong("externalMoveDrops") - dropsBeforeRoll;
        double rollTravel = bodyPointTravel(rollHistory, rollMark);
        int resolvedDuringRoll = resolvedSince(rollHistory, rollMark);

        String observed = "\n  at rest:      drops=" + dropsDuringRest + " bodyTravel=" + restTravel
                + " resolved=" + resolvedSince(restHistory, restMark)
                + "\n  during roll:  drops=" + dropsDuringRoll + " bodyTravel=" + rollTravel
                + " resolved=" + resolvedDuringRoll + " upY=" + upY
                + "\n  lastDropReason=" + clientString(SHIP_FRAME_TRAVEL, "lastDropReason")
                + "\n" + mover();
        System.out.println("[roll-hold]" + observed);

        assertTrue("ARRANGEMENT: the ship must actually have rotated, or nothing was driven (upY="
                + upY + ")" + observed, upY < -0.9);
        assertTrue("ARRANGEMENT: the client must have resolved the body through the roll, or a clean "
                + "result describes the instrument" + observed, resolvedDuringRoll >= 20);
        assertEquals("CONTROL: the guard must be quiet while the ship is still - otherwise the count "
                + "during the roll is not attributable to the rotation" + observed,
                0L, dropsDuringRest);

        assertEquals("the ship's own rotation must not count as someone else moving the crew member: "
                        + "the external-move guard released the capture " + dropsDuringRoll + " time(s) "
                        + "while the ship rolled under a body with no input. Between a release and the "
                        + "re-capture the body belongs to vanilla and the physics mod, which is what a "
                        + "player feels as being dragged along his own deck." + observed,
                0L, dropsDuringRoll);
        assertTrue("and he must not travel along the deck while it rotates under him (moved "
                        + rollTravel + " blocks in the ship frame, bar " + ROLL_DRIFT_TOLERANCE + ")"
                        + observed,
                rollTravel < ROLL_DRIFT_TOLERANCE);
    }

    /** How far a carried body may travel in the ship frame while the ship rotates, in blocks. */
    private static final double ROLL_DRIFT_TOLERANCE = 0.35D;

    /**
     * A crew member WALKING his own deck must never be released by the external-move guard.
     *
     * <p>This test fails if production breaks the contract that <b>the guard releases a deck capture
     * only on movement the ship-frame resolver did not itself produce</b>. A walk that the resolver
     * swept and committed is movement it produced; treating it as a foreign teleport hands the body
     * back to vanilla and to the physics mod for the ticks between the release and the re-capture,
     * which is what a player feels as being dragged along his own deck.</p>
     *
     * <p><b>Why the stimulus is a walk, and why the ship is upright and still.</b> Taken from the
     * distribution in the reporter's own session log rather than from one quoted line: of the 174
     * {@code externalMove(sub)} releases in it, <b>every one is {@code remote=true}</b> (the CLIENT's
     * copy of the local player - the server's copy is a follower and rebases instead of dropping),
     * <b>148 are at {@code tiltDeg} of 1.1 deg or less</b>, and <b>150 report {@code shipObstacles}
     * of 1 or more</b>, i.e. an upright ship and a body with deck support under it. {@code carrySeen}
     * is 0.0 at nearly all of them, so the ship was not moving either. Each burst is bracketed by
     * {@code [FF-TRACE/WALK] forward=0.98} lines: the body was WALKING.</p>
     *
     * <p>The releases' own numbers name the shape: the released {@code dSub} equals, in magnitude and
     * with the opposite sign, the SERVER's {@code MoverType.PLAYER} step on the same tick (e.g. server
     * {@code d=(-0.0516, -9.9e-5, 0.20830)} against client {@code dSub=(0.0516, -2e-10, -0.20830)}),
     * with {@code frameMoved} at zero. The body's live point sits exactly one walk step BEHIND the
     * point this class committed - the "walking thrash whose entityMoved exactly negated this commit's
     * motion" the resolver's own drag-suppression comment names.</p>
     *
     * <p>The idle window before the walk is the control - a body on a moving platform needs its own
     * no-change leg in the SAME run, or a green walk leg cannot be told from an instrument that never
     * fires - and it doubles as the sensitivity witness. The walk is taken in short bursts with a 180-degree turn between them, because the
     * fixture's deck is 5x5: one long hold would walk him off it and the leg would measure the edge
     * rather than the guard.</p>
     */
    @Test
    public void aCrewMemberWalkingHisOwnDeckIsNeverReleasedByTheExternalMoveGuard() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        requireHeIsHeldThroughAWalk(6720, 6720, "ordinary world coordinates");
    }

    private void requireHeIsHeldThroughAWalk(int bx, int bz, String where) throws Exception {
        final int by = 64;

        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("ARRANGEMENT: he must be captured on the deck before he walks (" + where + "): "
                        + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        // CONTROL, same body, same deck, same window length, stimulus absent.
        long dropsBeforeIdle = clientLong("externalMoveDrops");
        long idleMark = lastClientTick();
        bot().waitTicks(40);
        String idleHistory = clientTickHistory();
        long dropsIdle = clientLong("externalMoveDrops") - dropsBeforeIdle;
        int resolvedIdle = resolvedSince(idleHistory, idleMark);

        // THE STIMULUS: a real key on the real client input surface, in bursts that keep him on a
        // 5x5 deck. The turn between bursts is a real look, so each burst walks the way he faces.
        long walkMark = lastClientTick();
        long dropsBeforeWalk = clientLong("externalMoveDrops");
        double walked = walkInBursts();
        String walkHistory = clientTickHistory();
        long dropsWalk = clientLong("externalMoveDrops") - dropsBeforeWalk;
        int resolvedWalk = resolvedSince(walkHistory, walkMark);
        int inputTicks = inputTicksSince(walkHistory, walkMark);
        int offDeckTicks = offDeckTicksSince(walkHistory, walkMark);
        String capAfter = exec("artest vs deck-capture");

        String observed = "\n  " + where
                + "\n  idle (control): drops=" + dropsIdle + " resolved=" + resolvedIdle
                + "\n  walking:        drops=" + dropsWalk + " resolved=" + resolvedWalk
                + " inputTicks=" + inputTicks + " offDeckTicks=" + offDeckTicks
                + " walked=" + walked
                + "\n  lastDropReason=" + clientString(SHIP_FRAME_TRAVEL, "lastDropReason")
                + "\n  capture after=" + capAfter
                + "\n" + mover()
                + "\n  CLIENT per-tick record:\n" + walkHistory;
        System.out.println("[walk-hold]" + observed);

        // ARRANGEMENT first, so a clean result can never be the instrument's silence.
        assertTrue("ARRANGEMENT: the client must have resolved the body through both windows, or "
                + "neither count means anything" + observed, resolvedIdle >= 20 && resolvedWalk >= 20);
        assertTrue("ARRANGEMENT: the resolver must have SEEN the walk input, or the key never "
                + "reached the client's movement path" + observed, inputTicks >= 10);
        assertTrue("ARRANGEMENT: he must actually have covered ground on the deck" + observed,
                walked > 1.0);
        assertTrue("ARRANGEMENT: he must have stayed ON the deck for the whole walk - a body that "
                + "walked off the edge is measuring the edge, not the guard" + observed,
                offDeckTicks == 0);
        assertTrue("ARRANGEMENT: he must still be captured ABOARD at the end" + observed,
                capAfter.contains("\"alreadyTracked\":true") && !capAfter.contains("\"hullStand\":true"));
        assertEquals("CONTROL: the guard must be quiet while he stands still - otherwise the count "
                + "during the walk is not attributable to the walk" + observed, 0L, dropsIdle);

        assertEquals("a crew member's own walk must not count as someone else moving him: the "
                + "external-move guard released the deck capture " + dropsWalk + " time(s) across "
                + resolvedWalk + " resolved ticks of walking on a still, upright deck. Between a "
                + "release and the re-capture the body belongs to vanilla and to the physics mod, "
                + "which is what a player feels as being dragged along his own deck." + observed,
                0L, dropsWalk);
    }

    /** Walk bursts, and ticks per burst: short enough that the body stays on a 5x5 deck, and enough
     *  of them that a per-tick misfire cannot hide in a single burst. */
    private static final int WALK_BURSTS = 4;
    /** Six, measured: ten carried him 2.3 blocks per burst and off a deck whose half-width is 2.5,
     *  and the leg then measured the deck edge instead of the guard. */
    private static final int WALK_BURST_TICKS = 6;

    /** Walk him back and forth across the deck with a real key; returns the ground he covered. */
    private double walkInBursts() throws Exception {
        double walked = 0.0;
        for (int burst = 0; burst < WALK_BURSTS; burst++) {
            bot().setLook(burst % 2 == 0 ? 0f : 180f, 0f);
            bot().waitTicks(4);
            double[] from = clientPos();
            bot().holdKey(Keyboard.KEY_W);
            bot().waitTicks(WALK_BURST_TICKS);
            bot().releaseKey(Keyboard.KEY_W);
            walked += distance(from, clientPos());
        }
        return walked;
    }

    /**
     * A server tick BURST must not cost a crew member his deck capture.
     *
     * <p>This test fails if production breaks the contract that <b>the external-move guard's
     * per-tick allowance means the same thing across a tick that really took three seconds as across
     * one that took fifty milliseconds</b>. The guard compares a raw subspace delta against a flat
     * 0.2 blocks and calls anything larger a foreign teleport; across a skipped-tick burst the two
     * sides of the same body legitimately arrive that far apart, and the release hands a body the
     * resolver was holding back to vanilla and to the physics mod.</p>
     *
     * <p><b>Why this stimulus, out of everything the report mentioned.</b> Correlation over the whole
     * reporter's log, not one line: <b>150 of the 174</b> {@code externalMove(sub)} releases fall
     * within 20 seconds of a {@code "Can't keep up! ... skipping N tick(s)"} warning, 30 of them
     * within 5 - and the eleven stalls in those logs skip 53 to 64 ticks each. The tilt, the space
     * cell, the inverted deck and the relog are all things the reporter happened to be doing; the
     * stall is the thing that keeps arriving just before the releases. So the stall is the driver and
     * the rest are conditions, and this leg reproduces the driver on the plainest possible subject:
     * an upright, stationary ship on a planet, exactly the arrangement whose walking leg is green.</p>
     *
     * <p>The walk is part of the stimulus, not decoration, and the leg carries BOTH halves of it as
     * separate windows: a freeze with the body standing still, and a freeze with the key held ACROSS
     * it. Only the second one lets the two sides diverge - the client keeps ticking and keeps walking
     * him while the server's copy stands frozen, so the resumed loop has to absorb the whole
     * accumulated step at once. A freeze on a body that was not moving has nothing to catch up on,
     * which is why it is the control rather than the stimulus.</p>
     */
    @Test
    public void aCrewMemberIsNotReleasedWhenTheServerSkipsATickBurst() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6760, by = 64, bz = 6760;

        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("ARRANGEMENT: he must be captured on the deck before the server stalls: "
                        + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        // CONTROL: the same body, the same deck, the same walk - without the stall. The walking leg
        // measures this too, but it has to be in THIS run: a control from another boot has a
        // different ship pose and a different settle history.
        long dropsBeforeControl = clientLong("externalMoveDrops");
        long controlMark = lastClientTick();
        bot().waitTicks(20);
        double controlWalked = walkInBursts();
        String controlHistory = clientTickHistory();
        long dropsControl = clientLong("externalMoveDrops") - dropsBeforeControl;
        int resolvedControl = resolvedSince(controlHistory, controlMark);

        // CONTROL B: the stall with the body STANDING STILL. Measured because it separates the two
        // halves of the driver - a frozen tick loop on its own, versus a frozen tick loop while the
        // client keeps resolving movement the server has not applied yet.
        long idleStallMark = lastClientTick();
        long dropsBeforeIdleStall = clientLong("externalMoveDrops");
        String idleStall = exec("artest server stall " + STALL_MS);
        bot().waitTicks(20);
        long dropsIdleStall = clientLong("externalMoveDrops") - dropsBeforeIdleStall;
        int resolvedIdleStall = resolvedSince(clientTickHistory(), idleStallMark);

        // THE DRIVER: the key is HELD ACROSS the freeze. The client keeps ticking and keeps walking
        // him while the server's copy of him stands frozen; when the loop resumes, one server tick
        // has to absorb everything the client did meanwhile. That accumulated step is what the
        // reporter's log shows the guard measuring against its flat per-tick 0.2 blocks.
        //
        // He is walked to the far edge first, so the whole freeze happens with a deck's width of
        // runway ahead of him - the freeze is not shortened to fit the fixture, the runway is
        // arranged to fit the freeze.
        bot().setLook(180f, 0f);
        bot().waitTicks(4);
        bot().holdKey(Keyboard.KEY_W);
        bot().waitTicks(WALK_BURST_TICKS);
        bot().releaseKey(Keyboard.KEY_W);
        bot().setLook(0f, 0f);
        bot().waitTicks(4);
        long stallMark = lastClientTick();
        long dropsBeforeStall = clientLong("externalMoveDrops");
        double[] beforeStalledWalk = clientPos();
        bot().holdKey(Keyboard.KEY_W);
        String stall = exec("artest server stall " + WALK_STALL_MS);
        // Released the instant the loop resumes, and the window closed with it. The releases this leg
        // is about arrive as the two sides re-converge, within a tick or two of the resume; every
        // further tick with the key held only spends runway. Under parallel load the round trips
        // stretch, and a 1000 ms freeze with six trailing ticks walked him 6.0 blocks off a deck
        // 5 across - the leg then measured the deck edge and said so (offDeckTicks=3).
        bot().releaseKey(Keyboard.KEY_W);
        double stalledWalked = distance(beforeStalledWalk, clientPos());
        // The window stays open a while longer with the key DOWN: the re-convergence takes a few
        // ticks, and a window that closes on the release tick is too short to be witnessed (14
        // resolved ticks, against the 20 every other window in this class is held to). Nothing walks
        // here, so the runway is not spent.
        bot().waitTicks(15);
        String stallHistory = clientTickHistory();
        long dropsAfterStall = clientLong("externalMoveDrops") - dropsBeforeStall;
        int resolvedAfterStall = resolvedSince(stallHistory, stallMark);
        int offDeckAfterStall = offDeckTicksSince(stallHistory, stallMark);
        String capAfter = exec("artest vs deck-capture");

        String observed = "\n  control A, walk, no stall:   drops=" + dropsControl + " resolved="
                + resolvedControl + " walked=" + controlWalked
                + "\n  control B, stall while idle: drops=" + dropsIdleStall + " resolved="
                + resolvedIdleStall + "  " + idleStall.substring(Math.max(0, idleStall.indexOf('{')))
                + "\n  walk ACROSS the stall:       drops=" + dropsAfterStall + " resolved="
                + resolvedAfterStall + " walked=" + stalledWalked
                + " offDeckTicks=" + offDeckAfterStall
                + "\n  stall probe: " + stall
                + "\n  lastDropReason=" + clientString(SHIP_FRAME_TRAVEL, "lastDropReason")
                + "\n  capture after=" + capAfter
                + "\n" + mover();
        System.out.println("[tick-burst]" + observed);

        assertTrue("ARRANGEMENT: both freezes must really have SKIPPED ticks, or nothing was driven - "
                        + "a stall that advanced the world clock normally is not a stall" + observed,
                stall.contains("\"ok\":true") && stalledTicks(stall) <= WALK_STALL_MS / 200
                        && idleStall.contains("\"ok\":true")
                        && stalledTicks(idleStall) <= STALL_MS / 200);
        assertTrue("ARRANGEMENT: the client must have resolved the body through every window"
                        + observed,
                resolvedControl >= 20 && resolvedIdleStall >= 20 && resolvedAfterStall >= 20);
        assertTrue("ARRANGEMENT: he must have covered ground both times he walked" + observed,
                controlWalked > 1.0 && stalledWalked > 1.0);
        assertTrue("ARRANGEMENT: he must have stayed ON the deck across the stall - a body that "
                + "walked off the edge is measuring the edge, not the guard" + observed,
                offDeckAfterStall == 0);
        assertEquals("CONTROL A: the guard must be quiet for the same walk without a stall, or the "
                + "count across the stall is not attributable to it" + observed, 0L, dropsControl);

        assertEquals("a skipped-tick burst must not cost a crew member his deck capture: the "
                + "external-move guard released him " + dropsAfterStall + " time(s) when the server "
                + "froze for " + WALK_STALL_MS + " ms with his walk key held - against "
                + dropsControl + " for the same walk with no freeze, and " + dropsIdleStall
                + " for the same freeze with him standing still. Its allowance is per TICK and flat, "
                + "so everything the client resolved while the loop was frozen arrives in one server "
                + "tick and is measured against the budget of a tick that took 50 ms." + observed,
                0L, dropsAfterStall);
    }

    /** How long the server's tick loop is frozen: the reporter's own stalls ran 2.87-3.22 s and
     *  skipped 53-64 ticks. */
    private static final int STALL_MS = 3000;

    /** The freeze he WALKS across is shorter, and the reason is the fixture's runway rather than the
     *  mechanism: at ~0.117 blocks per client tick a full three-second freeze carries him seven
     *  blocks, and this deck is five across. Ten skipped ticks already put ten times the guard's
     *  per-tick assumption into one server tick, and leave slop for the round trips to stretch
     *  under parallel load. */
    private static final int WALK_STALL_MS = 500;

    /** Ticks the world clock advanced across the stall probe's window - the witness that it really
     *  froze the loop rather than sleeping a command thread beside it. */
    private static long stalledTicks(String stallJson) {
        Matcher m = Pattern.compile("\"ticksAdvanced\":(-?\\d+)").matcher(stallJson);
        return m.find() ? Long.parseLong(m.group(1)) : Long.MAX_VALUE;
    }

    @Test
    public void aPlayerWhoRelogsOnAnInvertedDeckStaysAboardIt() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6520, by = 64, bz = 6520;

        // Capture the client player on the OPEN top deck while the ship is upright, then roll the
        // ship to inverted UNDER him - the capture carries his deck spot through the roll, leaving
        // him standing on the deck of an inverted ship (hanging under the hull in world terms).
        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("the player must be captured on the deck before the roll: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        double h = Math.toRadians(170.0) / 2.0;
        assertTrue("attitude hold must accept the inversion",
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(200);
        double upY = readDouble(shipInfo(), Pattern.compile("\"qx\":(-?[0-9.E\\-]+)"));
        // upY from the quat: for a roll about X, upY = 1 - 2*qx^2 (qy=qz=0). Read qx directly.
        upY = 1.0 - 2.0 * upY * upY;
        assertTrue("the ship must be (near-)inverted for the relog to be able to drop the player "
                + "(upY=" + upY + ")", upY < -0.9);
        String capBefore = exec("artest vs deck-capture");
        assertTrue("the player must still be captured on the inverted deck before the relog: "
                + capBefore, capBefore.contains("\"alreadyTracked\":true"));
        double preY = bot().reportState().get("playerY").getAsDouble();

        // The REAL relog: full server logout (player data saved) + fresh login.
        bot().reconnect();
        bot().waitForWorld();
        // Give the rejoined client time to stream chunks, load the ship and re-engage the
        // capture; poll rather than sleep a fixed window so a working build passes fast.
        boolean aboard = false;
        String capNow = "";
        for (int i = 0; i < 40 && !aboard; i++) {
            bot().waitTicks(5);
            capNow = exec("artest vs deck-capture");
            // ABOARD specifically: a hull-stand catch (falling under the inverted hull until the
            // hull geometry stops the body somewhere) is exactly the captured-but-world-camera
            // desync of the original report - it must NOT satisfy this contract.
            aboard = capNow.contains("\"alreadyTracked\":true")
                    && !capNow.contains("\"hullStand\":true");
        }
        double postY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[relog] preY=" + preY + " postY=" + postY + " aboard=" + aboard
                + " dY=" + (postY - preY));
        System.out.println("[relog] cap=" + capNow);

        // Relog persistence: still ABOARD (deck semantics, not a hull-stand catch), still AT the
        // deck spot he logged out on - never handed to world gravity for a visible fall.
        assertTrue("after a relog on an inverted deck the player must be captured ABOARD again "
                + "(deck semantics, not hull-stand), not handed to world gravity: " + capNow,
                aboard);
        assertTrue("after a relog the player must still be AT his deck spot, not fallen off "
                + "(preY=" + preY + " postY=" + postY + ")", Math.abs(postY - preY) < 1.5);
    }

    /**
     * A crew member who logs out WHILE WALKING must come back STANDING STILL on the spot the durable
     * record names — not sliding on in the direction he was going.
     *
     * <p><b>What this pins that the inverted-deck leg cannot.</b> That leg logs out a body at rest,
     * so the only thing it can catch is a lost position. A walking body carries something else
     * across the logout: its MOTION. The deck resolver writes the body's velocity onto the server
     * entity every tick it commits, vanilla saves that into the player file, and the fresh entity
     * comes back holding it. If the restore lets the client's own first-contact capture take the
     * body — instead of applying the deck point the record names — that velocity is inherited as
     * ship-relative motion and the crew member skates across his own deck after logging in, with no
     * input. Reported from a real session on 2026-07-27.</p>
     *
     * <p>The ship is PARKED for this leg on purpose: a stationary deck makes "the spot he left" a
     * fixed world position, so a drift of a couple of blocks cannot be confused with the deck having
     * carried him somewhere. The walk itself is witnessed (he must actually cover ground before the
     * logout), because a leg where the body never moved would pass without exercising anything.</p>
     */
    @Test
    public void aCrewMemberWhoLogsOutWalkingComesBackStandingStillOnHisDeckSpot() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6620, by = 64, bz = 6620;

        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("the player must be captured on the deck before he walks: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        // CONTROL, before anything is done to him: does a settled crew member creep along this deck
        // ANYWAY? The ship holds station rather than standing still, and a station-keeping deck is
        // documented to feed its crew a constant no-input drift. Without this baseline, any creep
        // measured after the relog would be blamed on the restore by default.
        double[] idle0 = deckPoint();
        bot().waitTicks(30);
        double idleCreep = alongDeck(idle0, deckPoint());
        System.out.println("[walk-relog] CONTROL idle creep along the deck over 30 ticks = "
                + idleCreep);

        double[] beforeWalk = clientPos();
        bot().holdKey(Keyboard.KEY_W);
        bot().waitTicks(12);
        double[] walking = clientPos();
        bot().releaseKey(Keyboard.KEY_W);
        // Give the release a couple of ticks to actually reach the server as "no input" before the
        // logout. The subject of this leg is an inherited VELOCITY, not an inherited INPUT, and the
        // two are separable: the velocity survives about ten ticks of deck drag, so two ticks keep
        // nearly all of it while making it impossible for a key still held (the harness reuses one
        // client JVM across the reconnect) to masquerade as a restore defect afterwards.
        bot().waitTicks(2);
        // ARRANGEMENT WITNESS: he has to be genuinely under way, or the motion this leg is about
        // never exists and a green below would mean nothing.
        assertTrue("the crew member must actually cover ground on the deck before logging out "
                + "(moved " + distance(beforeWalk, walking) + " blocks)",
                distance(beforeWalk, walking) > 0.75);

        // Log out WHILE the body still carries that walk. The release above only stops the input;
        // the velocity is still on the entity for several ticks of drag, and it is what gets saved.
        //
        // The reference is the SERVER's position, read as the last thing before the reconnect —
        // NOT an earlier client sample. The body is still decelerating, so a sample taken before it
        // stopped names a spot he had not reached yet; measuring against one made the restore look
        // 1.3 blocks wrong when it was landing him exactly where he logged out. Even this reference
        // lags by the command's own round trip, which is why the comparison against it is a bound on
        // gross misplacement, and the TIGHT pins are the two drift windows below — drift being what
        // was actually reported from play.
        double[] logoutOffset = deckPoint();

        bot().reconnect();
        bot().waitForWorld();

        boolean aboard = false;
        String capNow = "";
        for (int i = 0; i < 40 && !aboard; i++) {
            bot().waitTicks(5);
            capNow = exec("artest vs deck-capture");
            aboard = capNow.contains("\"alreadyTracked\":true")
                    && !capNow.contains("\"hullStand\":true");
        }
        assertTrue("after the relog he must be captured ABOARD the deck again, or 'he did not "
                + "drift' would just mean he is standing on something else: " + capNow, aboard);

        // Everything the tight pins below measure is taken from the CLIENT's own per-tick record,
        // which starts here: the client owns this body's movement, and the server-side probe reads
        // the server's copy of it.
        long fromTick = lastClientTick();

        // Measured in the SHIP FRAME, one snapshot per sample (see deckPoint's note on the three
        // instruments that were wrong before it). The whole observation is TRACED rather than
        // sampled at two points: what a residual velocity looks like - a decaying slide - and what a
        // late re-capture looks like - a step - are indistinguishable from two readings, and the
        // difference decides which writer to go after.
        double[][] trace = new double[11][];
        String[] who = new String[11];
        trace[0] = deckPoint();
        who[0] = mover();
        for (int i = 1; i < trace.length; i++) {
            bot().waitTicks(5);
            trace[i] = deckPoint();
            who[i] = mover();
        }
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < trace.length; i++) {
            path.append(String.format(java.util.Locale.ROOT, "%nt=%-3d %s  step=%.4f  %s",
                    i * 5, fmt(trace[i]), i == 0 ? 0.0 : distance(trace[i - 1], trace[i]), who[i]));
        }
        System.out.println("[walk-relog] logoutDeckPoint=" + fmt(logoutOffset) + " seedOutcome="
                + clientString(SHIP_FRAME_TRAVEL, "lastSeedOutcome") + " trace:" + path);
        System.out.println("[walk-relog] CLIENT per-tick history (B = the client body's own "
                + "ship-frame point):\n" + clientTickHistory());

        double[] justAfter = trace[0];
        double[] oneSecondLater = trace[4];
        double[] later = trace[10];
        double slid = distance(justAfter, oneSecondLater);

        // Split by axis, because the two are different claims. ALONG the deck is the contract this
        // leg exists for - a body that inherited the walk it logged out on travels there, and that
        // is what was reported from play. ACROSS it (the deck normal, ship-frame Y) a restored body
        // legitimately settles the last fraction onto the surface it was placed just above; measured
        // at ~0.02 blocks/tick, decaying. Pinning the two together would either let a skate hide
        // inside a settle tolerance or fail the leg for a body doing exactly the right thing.
        assertTrue("a crew member restored onto his deck must not SLIDE along it: he is given a "
                + "recorded position, not re-acquired from the velocity vanilla handed his fresh "
                + "entity (moved " + alongDeck(justAfter, oneSecondLater) + " blocks along the deck "
                + "in 20 ticks with no input; seed outcome="
                + clientString(SHIP_FRAME_TRAVEL, "lastSeedOutcome") + ")" + path,
                alongDeck(justAfter, oneSecondLater) < 0.35);
        assertTrue("and he must not sink through it either (moved "
                + Math.abs(justAfter[1] - oneSecondLater[1]) + " blocks along the deck normal)" + path,
                Math.abs(justAfter[1] - oneSecondLater[1]) < 0.75);

        // And he must STAY put - not merely have stopped by then.
        double afterCreep = alongDeck(oneSecondLater, later);
        assertTrue("a restored crew member must not creep along the deck once he has landed on it "
                + "(after the relog he moved " + afterCreep
                + " blocks along the deck in 30 ticks; the same body before the relog moved "
                + idleCreep + " over the same window)" + path, afterCreep < 0.35);

        // The two CLIENT-side pins, and the reason they exist alongside the sampled trace above.
        //
        // (1) ONE deck point. The client is where this body's movement is decided, and what it
        // decides each tick is a ship-frame point. Held still, that point must not travel: a body
        // given a recorded position keeps it. The reading is the client's own committed point, so
        // it carries none of the ship-transform skew that contaminates any position re-derived from
        // world coordinates while the ship is moving - and the ship IS moving here, for the first
        // second or so after a rejoin, which is exactly when this used to break.
        //
        // (2) ONE capture mode. Standing on a deck is ABOARD (deck gravity, deck camera, deck walk
        // basis); the world-frame HULL mode is for a body on the ship's outer skin. A body that
        // alternates between them is in neither contract, and the alternation is what produced (1):
        // the hull mode re-bases the held deck point onto the body's current world position, so
        // every flip banked the skew and the crew member ratcheted along his own deck.
        String history = clientTickHistory();
        assertTrue("the client's per-tick record must exist, or these pins measure nothing "
                + "(is the client JVM in test mode?)", history.contains("|B="));
        double heldTravel = heldPointTravel(history, fromTick);
        int hullTicks = hullStandTicks(history, fromTick);
        int covered = resolvedSince(history, fromTick);
        System.out.println("[walk-relog] CLIENT held-point travel along the deck = " + heldTravel
                + " over " + covered + " resolved ticks, hullStand ticks = " + hullTicks);
        // WITNESS: "it did not travel" and "nothing was recorded" are the same number otherwise.
        assertTrue("the client must have resolved the body through the observation window, or the "
                + "two pins below cannot fail (" + covered + " ticks recorded)\n" + history,
                covered > 20);
        assertTrue("the deck point the client holds him at must not travel along the deck with no "
                + "input (moved " + heldTravel + " blocks)\n" + history, heldTravel < 0.2);
        assertTrue("a crew member standing on a deck must stay in ABOARD capture semantics - "
                + "flipping to the world-frame hull mode re-bases his deck point onto wherever the "
                + "world thinks he is (" + hullTicks + " hull-stand ticks)\n" + history,
                hullTicks == 0);

        // Gross-misplacement bound: he has to come back on the deck spot he left, not somewhere
        // else on the ship. Loose on purpose - the reference is read one command before the logout
        // and the body is still decelerating, so a few tenths of a block are the instrument's.
        assertTrue("he must come back where he logged out (deck point " + fmt(logoutOffset)
                + "), not " + distance(logoutOffset, later) + " blocks away at " + fmt(later),
                distance(logoutOffset, later) < 1.5);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /**
     * The BODY's live position in the ship frame — the crew member himself, mapped through his
     * anchor ship's transform at the moment of asking.
     *
     * <p>Three instruments were tried before this one and each was wrong in its own way, which is
     * worth keeping written down: a WORLD position counts the ship carrying the body as the body
     * moving; a world position differenced against a separately-sampled ship pose counts the ship's
     * own station-keeping step (~1.5 blocks between two samples taken ticks apart) as the body
     * sliding; and the CAPTURE's committed point freezes while anything else holds the body, so a
     * pinned body reads as perfectly still. This one is a single snapshot, in the right frame,
     * derived from the body's own coordinates every time it is asked.</p>
     */
    private double[] deckPoint() throws Exception {
        String cap = exec("artest vs deck-capture");
        assertTrue("the deck capture must be live to report a ship-frame point: " + cap,
                cap.contains("\"alreadyTracked\":true"));
        return new double[]{readDouble(cap, SHIP_FRAME_X), readDouble(cap, SHIP_FRAME_Y),
                readDouble(cap, SHIP_FRAME_Z)};
    }

    /**
     * WHO is moving the body, as counters rather than inference: how many ticks the ship-frame
     * resolver has committed, how many it has DECLINED (leaving the body to vanilla and to the
     * physics mod's own mover), and how many external world moves it has had to absorb. A drift
     * while `declined` climbs is a resolver that stepped back; a drift while `worldMove` climbs is
     * something else pulling the body.
     */
    private String mover() throws Exception {
        String st = exec("artest vs shipframe-stats");
        return "SRV[resolved=" + readLong(st, "resolvedTicks")
                + " declined=" + readLong(st, "declinedTicks")
                + " worldMoves=" + readLong(st, "worldMoveApplies")
                + " in=" + readString2(st, "lastInStrafe") + "/" + readString2(st, "lastInForward")
                + " mShip=(" + readString2(st, "lastMotionShipX") + ","
                + readString2(st, "lastMotionShipY") + "," + readString2(st, "lastMotionShipZ") + ")"
                + " carry=(" + readString2(st, "lastCarryX") + "," + readString2(st, "lastCarryY")
                + "," + readString2(st, "lastCarryZ") + ")"
                + " guardCarry=" + readString2(st, "lastGuardCarry") + "]"
                // The client side is deliberately THIN here — every field costs a round trip, and
                // the round trips stretch the very timeline this trace is sampling. The client's
                // own per-tick record is read once, at the end (clientTickHistory).
                + " CLI[resolved=" + clientString(SHIP_FRAME_TRAVEL, "resolvedTicks")
                + " declined=" + clientString(SHIP_FRAME_TRAVEL, "declinedTicks")
                + " worldMoves=" + clientString(SHIP_FRAME_TRAVEL, "worldMoveApplies")
                + " extDrops=" + clientString(SHIP_FRAME_TRAVEL, "externalMoveDrops")
                + " lastDrop=" + clientString(SHIP_FRAME_TRAVEL, "lastDropReason") + "]"
                + " srvLastMove=" + readString(st, "lastWorldMove");
    }

    /**
     * The CLIENT's own per-tick record of the resolution, read as one field. The sampled trace above
     * cannot answer this leg's question on its own: it costs a round trip per field, so its
     * "5 ticks" are really 5 ticks plus the reads, and anything that settles between two samples is
     * invisible. This is the client body's ship-frame point every tick it was resolved.
     */
    private String clientTickHistory() throws Exception {
        return clientString(SHIP_FRAME_TRAVEL, "tickHistory");
    }

    /** One line of that record: the resolved-tick number, which capture path produced it, and the
     *  ship-frame point the client COMMITTED for the body that tick. */
    private static final Pattern HISTORY_LINE = Pattern.compile(
            "(\\d+)([afh])\\|B=[^|]*\\|H=(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)\\|");

    /**
     * The same line with the LIVE body point captured instead of the committed one. A separate pattern
     * rather than extra groups on {@link #HISTORY_LINE}, so the existing helpers' group numbers stay
     * where they are. The committed point reads perfectly still for a body something else is holding;
     * this is the one that answers "did the body move along the deck".
     */
    private static final Pattern BODY_LINE = Pattern.compile(
            "(\\d+)([afh])\\|B=(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)\\|");

    /** How far the BODY travelled along the deck in the window: first tick after {@code fromTick} to
     *  the farthest one, so a body that wanders out and back cannot pass. */
    private double bodyPointTravel(String history, long fromTick) {
        Matcher m = BODY_LINE.matcher(history);
        double[] first = null;
        double worst = 0.0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) <= fromTick) {
                continue;
            }
            double[] point = {Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5))};
            if (first == null) {
                first = point;
            } else {
                worst = Math.max(worst, alongDeck(first, point));
            }
        }
        return worst;
    }

    /**
     * The same line read for what came INTO the tick rather than where the body ended up: the walk
     * inputs the resolver saw, and whether it had the deck under the feet.
     *
     * <p>Both are arrangement witnesses for the walking leg, and both were needed the hard way: a
     * clean drop count means nothing if the key never reached the resolver, and a body that has
     * walked off a 5x5 deck is measuring the deck edge rather than the guard.</p>
     */
    private static final Pattern INPUT_LINE = Pattern.compile(
            "(\\d+)([afh])\\|B=[^|]*\\|H=[^|]*\\|m=[^|]*\\|c=[^|]*\\|in=(-?[0-9.]+)/(-?[0-9.]+)\\|d=(\\d)");

    /** Ticks after {@code fromTick} in which the resolver saw a nonzero walk input. */
    private int inputTicksSince(String history, long fromTick) {
        Matcher m = INPUT_LINE.matcher(history);
        int n = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick
                    && (Double.parseDouble(m.group(3)) != 0.0 || Double.parseDouble(m.group(4)) != 0.0)) {
                n++;
            }
        }
        return n;
    }

    /** Ticks after {@code fromTick} the body spent without the deck under it. */
    private int offDeckTicksSince(String history, long fromTick) {
        Matcher m = INPUT_LINE.matcher(history);
        int n = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick && "0".equals(m.group(5))) {
                n++;
            }
        }
        return n;
    }

    /** A client-side counter as a number, or {@code -1} when it cannot be read. */
    private long clientLong(String field) throws Exception {
        String value = clientString(SHIP_FRAME_TRAVEL, field);
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    /** The most recent resolved-tick number in the client's record — the mark an observation starts
     *  from, so the pins never read ticks from before the window (the record survives the relog:
     *  the harness reuses one client JVM). */
    private long lastClientTick() throws Exception {
        Matcher m = HISTORY_LINE.matcher(clientTickHistory());
        long last = -1;
        while (m.find()) {
            last = Long.parseLong(m.group(1));
        }
        return last;
    }

    /** How far the committed deck point travelled ALONG the deck across the window — first commit
     *  after {@code fromTick} to the farthest one, not merely the last, so a body that wanders out
     *  and back cannot pass. */
    private double heldPointTravel(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        double[] first = null;
        double worst = 0.0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) <= fromTick) {
                continue;
            }
            double[] held = {Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5))};
            if (first == null) {
                first = held;
            } else {
                worst = Math.max(worst, alongDeck(first, held));
            }
        }
        return worst;
    }

    private int hullStandTicks(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        int hull = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick && "h".equals(m.group(2))) {
                hull++;
            }
        }
        return hull;
    }

    /** How many ticks the window actually covers — a witness that the pins above had something to
     *  look at, since "no travel" and "no ticks recorded" read the same. */
    private int resolvedSince(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        int n = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick) {
                n++;
            }
        }
        return n;
    }

    /** A numeric JSON field as text (the probe writes doubles unquoted). */
    private static String readString2(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.EN\\-]+)").matcher(json);
        return m.find() ? m.group(1) : "?";
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private static String readString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "?";
    }

    /** The client's own rendered position. */
    private double[] clientPos() throws Exception {
        com.google.gson.JsonObject state = bot().reportState();
        return new double[]{state.get("playerX").getAsDouble(),
                state.get("playerY").getAsDouble(), state.get("playerZ").getAsDouble()};
    }

    private static String fmt(double[] p) {
        return "[" + p[0] + "," + p[1] + "," + p[2] + "]";
    }

    /** Class holding the client-side seed diagnostics this test quotes in its failure text. */
    private static final String SHIP_FRAME_TRAVEL =
            "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel";

    /** A client-side static field, as a string — diagnostics only, never an assertion subject. */
    private String clientString(String className, String field) throws Exception {
        try {
            return bot().readStaticField(className, field).get("value").getAsString();
        } catch (Exception unavailable) {
            return "<unreadable: " + unavailable.getMessage() + ">";
        }
    }

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(int bx, int by, int bz) throws Exception {
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);

        int shipsBefore = count("ship-count-all");
        String assemble = assembleFixture(bx, by, bz);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));

        int all = shipsBefore;
        for (int i = 0; i < 40 && all <= shipsBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a NEW VS ship (was " + shipsBefore + ", now " + all + ")",
                all > shipsBefore);
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        String info = "";
        double[] where = null;
        for (int i = 0; i < 40 && where == null; i++) {
            bot().waitTicks(5);
            // The scenario's ONE positional lookup, at the only moment it is defensible: the ship
            // was just assembled here and has not moved. It yields an IDENTITY, and everything
            // afterwards is keyed on that.
            info = exec("artest vs ship-info 0 " + bx + " " + by + " " + bz
                    + " " + SHIP_QUERY_RADIUS);
            if (!info.contains("\"managed\":true")) {
                continue;
            }
            double[] candidate = {readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
            String foundId = readShipId(info);
            if (distance(candidate, new double[]{bx, by, bz}) < 24.0 && foundId != null) {
                where = candidate;
                scenarioShipId = foundId;
            }
        }
        assertTrue("the ship built at this base must LOAD with the client present; nearest was: " + info,
                where != null);
        return where;
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    /** This scenario's ship, asked by identity — no distance term to be wrong about. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return shipInfoById(scenarioShipId);
    }


    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }


    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    /** Distance ALONG the deck - the ship-frame horizontal plane, with the deck normal dropped. */
    private static double alongDeck(double[] a, double[] b) {
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
