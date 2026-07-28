package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * The relog-persistence contract of the ship-frame crew (any-attitude crew contract C14): a
 * player who logs out standing ABOARD a ship's deck logs back in ABOARD, at the same deck point,
 * at any ship attitude - never handed to world gravity while the capture re-seeds.
 *
 * <p>The subject is the HARD side of every axis this bug lives on: a real client player, captured
 * on the deck of an INVERTED ship (world gravity points away from the deck overhead, so any
 * un-captured tick starts a fall), across a REAL relog ({@code ClientBot.reconnect} - a full
 * server logout with player-data save and a fresh login, not a teleport).</p>
 *
 * <p>Gated on real VS - run with {@code -PwithVS}.</p>
 */
public class VSCrewRelogPersistenceE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";

    /** The account every client harness launches under; the server keys his data by it. */
    private static final String BOT = "ForgeTestClient";

    private static final Pattern SHIP_FRAME_X = Pattern.compile("\"bodyShipFrameX\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_FRAME_Y = Pattern.compile("\"bodyShipFrameY\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_FRAME_Z = Pattern.compile("\"bodyShipFrameZ\":(-?[0-9.E\\-]+)");

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
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(200);
        double upY = readDouble(shipInfo(bx, by, bz), Pattern.compile("\"qx\":(-?[0-9.E\\-]+)"));
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

        // Contract C14: still ABOARD (deck semantics, not a hull-stand catch), still AT the deck
        // spot he logged out on - never handed to world gravity for a visible fall.
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
            info = shipInfo(bx, by, bz);
            if (!info.contains("\"managed\":true")) {
                continue;
            }
            double[] candidate = {readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
            if (distance(candidate, new double[]{bx, by, bz}) < 24.0) {
                where = candidate;
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

    private String shipInfo(int bx, int by, int bz) throws Exception {
        return exec("artest vs ship-info 0 " + bx + " " + by + " " + bz);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
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
