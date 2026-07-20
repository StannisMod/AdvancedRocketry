package zmaster587.advancedRocketry.test.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import static org.junit.Assert.assertTrue;

/**
 * A player who SITS DOWN IN THE PILOT SEAT BEFORE HIS SHIP IS ASSEMBLED must still be flying that
 * ship the moment assembly finishes - without standing up and sitting down again.
 *
 * <p>This is how a human actually builds and boards a tier-2 craft: he walks onto the launchpad,
 * right-clicks the pilot seat while the craft is still a pile of loose blocks, and only then runs
 * the assembly. The seat he clicked is therefore the seat at its ORIGINAL world position. Assembly
 * moves the craft's blocks into the ship's own coordinate space, and the pilot's control binding has
 * to survive that move.</p>
 *
 * <p><b>Two methods, because this scenario changes TWO things at once.</b> Measured against the
 * working post-assembly-boarding flight test, the human's route differs in WHEN he boards (before vs
 * after assembly) AND in HOW he boards (a real client right-click on the seat block vs a probe that
 * spawns the seat dummy server-side and mounts it). A single red run over both variables could not
 * say which one drove it. So the scenario is run twice over a {boarding mechanism} x {boarding
 * moment} matrix:</p>
 *
 * <pre>
 *                     | pre-assembly            | post-assembly
 *   ------------------+-------------------------+---------------------------------
 *   probe mount       | {@link #aPilotBoardedByProbeBeforeAssemblyCanFlyTheShip()}
 *                     |                         | (the existing passing flight test)
 *   real right-click  | {@link #aPilotWhoRightClickedTheSeatBeforeAssemblyCanFlyTheShip()}
 *                     |                         | NOT REACHABLE
 * </pre>
 *
 * <p>The fourth cell - a real right-click on the seat of an ALREADY ASSEMBLED ship - cannot be
 * written with this harness, and that hole is named rather than hidden: the client's block
 * interaction takes WORLD coordinates, while an assembled ship's blocks live in the ship's own
 * subspace at coordinates the client never renders at. There is no world position to click.</p>
 *
 * <p><b>What the two methods jointly discriminate.</b> If BOTH fail, the driver is WHEN: the pilot's
 * control binding does not survive assembly, regardless of how it was established. If ONLY the
 * right-click method fails, the driver is HOW: the real block-activation path never confers ship
 * control in the first place, and the pre-assembly moment is innocent.</p>
 *
 * <p><b>The boarding must be confirmed, not assumed.</b> A seat's block-activation handler reports
 * success to the client unconditionally while doing its real work server-side only, so the client's
 * own return value cannot distinguish "the player sat down" from "the server silently dropped the
 * right-click" (reach limit, an unconfirmed teleport, a held item preempting the click). The test
 * therefore confirms the boarding independently, from what the CLIENT reports it is riding - and
 * checks WHAT it is riding and WHERE that thing is, so "he took the pilot seat" cannot be satisfied
 * by riding something else. A failure there is reported as a BROKEN ARRANGEMENT, not as a broken
 * contract.</p>
 *
 * <p><b>The climb is measured against a no-key control leg.</b> A freshly assembled physics object
 * that overlaps solid geometry can be resolved by displacing it UPWARD, and this craft is assembled
 * directly above a solid launchpad. Without a control, that settle would be credited to the pilot's
 * key and turn a still-broken control path green - the worst possible outcome, because a green here
 * would be read as "the bug is not real". So the ship is first settled to a stable altitude, then
 * its free drift is measured over a window of the SAME length as the measurement window with NO key
 * held, and only then is the key-held climb measured. Both numbers are reported side by side.</p>
 *
 * <p><b>History.</b> This file began as the missing repro for a craft that a hand-boarded pilot
 * could not fly: a pilot seated before assembly kept riding a mount bound to the seat's build-time
 * world position, which assembly vacates, so nothing in his control chain resolved and the piloting
 * client never sent a single input packet. It now pins the fixed contract: the assembler queues
 * every seated pilot's binding for re-expression onto the relocated seat, and both cells must stay
 * green.</p>
 *
 * <p>Manual server + client lifecycle rather than the shared base class, because the config has to be
 * written into the game directory BEFORE the server boots and the base class owns a throwaway root
 * it never exposes. Each method pays a full harness boot; that cost is accepted here because the
 * discrimination between the two variables is the entire point of the file.</p>
 *
 * <p>Gated on real Valkyrien Skies - run with {@code -PwithVS}.</p>
 */
public class VSPreAssemblyBoardingPilotControlE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern SEAT_XYZ = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 2800, BY = 64, BZ = 2800;

    /**
     * Where the fixture puts the pilot seat: the craft's centre column is (baseX+3, baseZ+3) and the
     * seat caps it four blocks above the craft's base row (which itself sits one above the pad).
     * Measured against the world before the click, never trusted blind.
     */
    private static final int SEAT_X = BX + 3, SEAT_Y = BY + 5, SEAT_Z = BZ + 3;

    /**
     * A clear spot on the launchpad, two blocks north of the craft's column and well inside the
     * pad's edge (a player teleported here with leftover falling momentum must not be able to
     * slide off before it is damped). The craft occupies only the z=baseZ+3 slice, so this column
     * is empty from the pad up.
     */
    private static final double STAND_X = BX + 3.5, STAND_Y = BY + 1, STAND_Z = BZ + 1.5;

    /**
     * The server refuses a block interaction beyond (reach + 3) blocks, so wherever the client
     * actually ends up standing has to be inside that radius of the seat's centre.
     */
    private static final double MAX_INTERACT_DIST_SQ = 64.0;

    /**
     * How far the ridden mount may sit from the seat block's centre and still count as "he is in
     * THAT seat". The seat's mount is spawned at the block centre offset by a fifth of a block
     * vertically, so two blocks of slack is generous while still excluding a neighbouring block.
     */
    private static final double MOUNT_AT_SEAT_DIST_SQ = 4.0;

    /** The ship must gain at least this much altitude while the key is held, or it is not flying. */
    private static final double MIN_CLIMB = 1.0;

    // ---- Measurement windows -------------------------------------------------------------------
    // TICKS_PER_SAMPLE/MEASURE_SAMPLES: the ship gets 200 ticks (10 s) to climb one block, sampled
    // every 5 ticks. The no-key control leg runs for exactly the same 200 ticks so the two numbers
    // are directly comparable; the key-held leg may exit EARLY once it has climbed, which only makes
    // the comparison stronger (less time to accumulate the same drift).
    // SETTLE_*: before either leg the ship must hold one altitude within SETTLE_EPS across 100 ticks
    // - half the measurement window, the same order of magnitude, and long enough that a post-
    // assembly upward resolve has visibly ended rather than merely paused. SETTLE_EPS is 5 cm: far
    // below MIN_CLIMB, far above the double-precision jitter of a physics object at rest. The settle
    // budget is 1200 ticks (60 s), generous because a settle that never converges is itself the
    // finding: the fixture cannot sit still and no climb measured on it would mean anything.
    // MAX_CONTROL_DRIFT is a quarter of MIN_CLIMB: any free drift at or above that makes the
    // key-held climb unattributable.
    private static final int TICKS_PER_SAMPLE = 5;
    private static final int MEASURE_SAMPLES = 40;
    private static final int SETTLE_STABLE_SAMPLES = 20;
    private static final int SETTLE_MAX_SAMPLES = 240;
    private static final double SETTLE_EPS = 0.05;
    private static final double MAX_CONTROL_DRIFT = MIN_CLIMB / 4.0;

    /** How the pilot gets into the seat. The variable the second test method isolates. */
    private enum Boarding {
        /** The probe path the passing post-assembly flight test uses, performed BEFORE assembly. */
        PROBE,
        /** The human's path: a real client right-click on the loose seat block. */
        RIGHT_CLICK
    }

    private Path root;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    /** Where the fixture reported its rocket builder - the block the assembly step is driven from. */
    private int builderX, builderY, builderZ;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue("Server harness disabled - set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled - set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        root = Files.createTempDirectory("forge-preassembly-boarding-");
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        // Opt the production space subsystem back in: without this the subsystem detects the harness
        // and stands down, and then this test would not be running the configuration a player runs.
        String cfg = "# seeded by VSPreAssemblyBoardingPilotControlE2ETest\n"
                + "performance {\n"
                + "    B:spaceRegisterUnderTestHarness=true\n"
                + "}\n";
        Files.write(arConfigDir.resolve("advancedRocketry.cfg"), cfg.getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startFailed) {
            serverHarness.close();
            serverHarness = null;
            throw startFailed;
        }
    }

    @After
    public void stopBoth() throws Exception {
        Exception first = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                first = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
            serverHarness = null;
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * The human's own route: right-click the seat of a craft that is still loose blocks, then
     * assemble, then fly. Changes BOTH the boarding moment and the boarding mechanism relative to
     * the passing post-assembly flight test - which is why the probe variant below exists.
     */
    @Test
    public void aPilotWhoRightClickedTheSeatBeforeAssemblyCanFlyTheShip() throws Exception {
        runPreAssemblyBoardingScenario(Boarding.RIGHT_CLICK);
    }

    /**
     * The SAME boarding mechanism the passing post-assembly flight test uses, moved to BEFORE
     * assembly. This isolates the boarding MOMENT: everything else - fixture, coordinates, seeded
     * config, control key, thresholds, polling shape - is the passing test's.
     */
    @Test
    public void aPilotBoardedByProbeBeforeAssemblyCanFlyTheShip() throws Exception {
        runPreAssemblyBoardingScenario(Boarding.PROBE);
    }

    /**
     * The shared scenario, so the two variants cannot drift apart: build loose, board (by
     * {@code how}), assemble, settle, measure a no-key control leg, then measure the key-held climb.
     */
    private void runPreAssemblyBoardingScenario(Boarding how) throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        // The subsystem must actually be up, or the run silently degrades into a different
        // configuration than the one a player is in and its result would mean nothing.
        String status = exec("artest space subsystem-status");
        assertTrue("ARRANGEMENT: the production space subsystem must be REGISTERED - the seeded "
                        + "config is what opts it in: " + status,
                status.contains("\"registered\":true"));

        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        // Build the craft as LOOSE BLOCKS only. Assembly is deliberately deferred until after the
        // player has taken his seat - that ordering is the entire experiment.
        buildLooseFixture(BX, BY, BZ, VARIANT);

        // Stand the client on the pad, next to the craft. The wait is not cosmetic: the server
        // ignores block interactions while a teleport it issued is still unconfirmed by the client,
        // and a right-click sent too early would be dropped without a trace.
        exec("tp @a " + STAND_X + " " + STAND_Y + " " + STAND_Z + " 0 0");
        bot().waitTicks(20);

        // MEASURE the seat before touching it - the fixture's geometry is verified, never assumed.
        JsonObject seatBlock = null;
        String seatName = "";
        boolean seatChunkLoaded = false;
        for (int attempt = 0; attempt < 20 && !seatName.toLowerCase(Locale.ROOT).contains("pilotseat"); attempt++) {
            bot().waitTicks(TICKS_PER_SAMPLE);
            seatBlock = bot().blockState(SEAT_X, SEAT_Y, SEAT_Z);
            // An unloaded chunk reports an EMPTY block name, which is otherwise indistinguishable
            // from "the wrong block is there" - separate the two before believing either.
            seatChunkLoaded = seatBlock.has("loaded") && seatBlock.get("loaded").getAsBoolean();
            seatName = seatChunkLoaded ? seatBlock.get("block").getAsString() : "";
        }
        assertTrue("ARRANGEMENT: the seat's chunk must be LOADED on the client before its block can "
                        + "be measured - an unloaded chunk reports an empty block name, not a wrong "
                        + "one. measured=" + seatBlock,
                seatChunkLoaded);
        assertTrue("ARRANGEMENT: the block the test is about to board must really be the pilot seat "
                        + "as the CLIENT sees it, at (" + SEAT_X + "," + SEAT_Y + "," + SEAT_Z
                        + "). measured=" + seatBlock,
                seatName.toLowerCase(Locale.ROOT).contains("pilotseat"));

        String boardingEvidence = (how == Boarding.RIGHT_CLICK)
                ? boardByRightClick()
                : boardByProbe();

        // The boarding's own return value cannot be trusted to mean "he sat down" - confirm it from
        // what the client reports it is riding, WHAT that thing is, and WHERE it is.
        JsonObject riding = awaitRiding(20);
        assertTrue("ARRANGEMENT FAILED (not a contract failure): the bot never took the seat, so the "
                        + "scenario under test never started. Nothing can be concluded about flying a "
                        + "ship boarded before assembly. boarding=" + how
                        + " evidence=" + boardingEvidence + " riding=" + riding,
                isRiding(riding));
        assertTrue("ARRANGEMENT FAILED (not a contract failure): the bot is riding SOMETHING, but not "
                        + "the pilot seat's mount, so it is not piloting anything. boarding=" + how
                        + " riding=" + riding,
                entityClassOf(riding).contains("EntityDummy"));
        double mountDistSq = distanceSqFromMountToSeatCentre(riding);
        assertTrue("ARRANGEMENT FAILED (not a contract failure): the mount the bot is riding is not "
                        + "at the seat it was supposed to board, at (" + SEAT_X + "," + SEAT_Y + ","
                        + SEAT_Z + "). boarding=" + how + " distSq=" + mountDistSq
                        + " limit=" + MOUNT_AT_SEAT_DIST_SQ + " riding=" + riding,
                mountDistSq < MOUNT_AT_SEAT_DIST_SQ);

        // Now assemble the craft, with the pilot already aboard.
        String assemble = assembleFixture();
        assertTrue("ARRANGEMENT: a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"ok\":true"));
        bot().waitTicks(20);

        // CONTRACT, first half: sitting still means sitting. Assembling the ship under a seated
        // player must not throw him out of his seat. A single "riding" sample is not enough here -
        // a server-side dismount whose packet has not yet reached the client still reads as seated -
        // so require two consecutive positive samples with a wait between them.
        JsonObject ridingAfter = awaitRidingTwiceInARow(20);
        assertTrue("a player who sat in the pilot seat before assembling his ship must STILL be "
                        + "seated once assembly finishes - he should never have to stand up and sit "
                        + "down again to fly what he just built. boarding=" + how
                        + " ridingBeforeAssembly=" + riding + " ridingAfterAssembly=" + ridingAfter,
                isRiding(ridingAfter));
        assertTrue("the mount a player is left riding after assembly must still be the pilot seat's, "
                        + "not some leftover entity. boarding=" + how + " riding=" + ridingAfter,
                entityClassOf(ridingAfter).contains("EntityDummy"));

        // ARRANGEMENT window, load-aware: the boarding is re-expressed onto the relocated seat by
        // an asynchronous rebind that can only run once the physics mod has finished relocating the
        // craft into its subspace — and under a parallel-suite load that relocation lags by many
        // seconds. The contract clock ("controls the ship immediately") starts at the physics
        // object going LIVE, so waiting for the rebind here measures the contract, not a softened
        // version of it; without the wait the measurement legs below just run out before the ship
        // exists. Early exit the moment the rebind lands; a cancelled/expired rebind is a red.
        int rebindBudget = (int) (240 * com.github.stannismod.forge.testing.TestTimeouts.factor());
        String rebindState = "";
        boolean rebound = false;
        for (int i = 0; i < rebindBudget && !rebound; i++) {
            bot().waitTicks(TICKS_PER_SAMPLE);
            rebindState = exec("artest vs seat-delivery");
            rebound = rebindState.contains("\"rebindRebound\":1");
            assertTrue("the pre-assembly boarding's rebind must never be CANCELLED or EXPIRED while "
                            + "the pilot demonstrably sits on his stale mount: " + rebindState,
                    rebindState.contains("\"rebindCancelled\":0")
                            && rebindState.contains("\"rebindExpired\":0"));
        }
        assertTrue("ARRANGEMENT: the assembly's crew rebind never completed within "
                        + (rebindBudget * TICKS_PER_SAMPLE) + " ticks (load-scaled) - the relocated "
                        + "ship/seat never became resolvable, so the control chain under test never "
                        + "came up. boarding=" + how + " delivery=" + rebindState,
                rebound);

        // Paste-site census, printed unconditionally (visible in green runs too): assembly pastes
        // the craft one block above its build position before relocating it into the ship's
        // subspace, and whether anything LINGERS in the world afterwards distinguishes a clean
        // relocation from a leftover world-frame copy (which would explain pilot input landing on
        // a world-coordinate seat in live play).
        System.out.println("[PASTE-SITE] boarding=" + how
                + " seatBuild=" + bot().blockState(SEAT_X, SEAT_Y, SEAT_Z)
                + " seatPaste=" + bot().blockState(SEAT_X, SEAT_Y + 1, SEAT_Z));

        // ---- CONTROL LEG ---------------------------------------------------------------------
        // Settle first: a freshly assembled physics object may be resolved upward out of the pad it
        // overlaps, and that motion is not the pilot's.
        double yRest = settleShipAltitude();
        assertTrue("ARRANGEMENT UNSOUND (not a contract failure): the ship never reached a stable "
                        + "resting altitude within " + (SETTLE_MAX_SAMPLES * TICKS_PER_SAMPLE)
                        + " ticks, so it will not sit still and NO climb measured on it could be "
                        + "attributed to pilot input. boarding=" + how,
                !Double.isNaN(yRest));

        double controlDrift = measureMaxDrift(yRest);
        assertTrue("ARRANGEMENT UNSOUND (not a contract failure): with NO key held the ship still "
                        + "moved " + controlDrift + " blocks over "
                        + (MEASURE_SAMPLES * TICKS_PER_SAMPLE) + " ticks (limit " + MAX_CONTROL_DRIFT
                        + "). A ship that drifts on its own cannot be used to measure whether the "
                        + "pilot's key lifted it - this says nothing about the contract, only that "
                        + "the arrangement is not a usable instrument. boarding=" + how
                        + " yRest=" + yRest,
                controlDrift < MAX_CONTROL_DRIFT);

        // ---- EXPERIMENTAL LEG ----------------------------------------------------------------
        double yBefore = shipPosY();
        assertTrue("ARRANGEMENT: the ship must still report an altitude at the start of the "
                        + "key-held window", !Double.isNaN(yBefore));

        // The real key, through the real client input path, exactly as a player holds it.
        double yAfter = yBefore;
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        try {
            for (int attempt = 0; attempt < MEASURE_SAMPLES && (yAfter - yBefore) < MIN_CLIMB; attempt++) {
                bot().waitTicks(TICKS_PER_SAMPLE);
                double y = shipPosY();
                if (!Double.isNaN(y)) {
                    yAfter = y;
                }
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }

        // Late paste-site census: by now the relocation demonstrably finished (the settle and the
        // measurement windows ran on the live ship), so anything still at the paste site is a
        // LINGERING world-frame copy, not relocation-in-progress.
        System.out.println("[PASTE-SITE post-flight] boarding=" + how
                + " seatPaste=" + bot().blockState(SEAT_X, SEAT_Y + 1, SEAT_Z));

        // Delivery-chain diagnostics, gathered AFTER the key-held window so they describe this very
        // attempt. Folded into the failure message: a red run must name the gate that ate the
        // input (client never sent / server dropped / delivered but no motion), not just report
        // "climb 0.0" and leave the chain to be guessed at.
        String delivery = deliveryDiagnostics();

        assertTrue("a player who took the pilot seat BEFORE assembling his ship must be able to FLY "
                        + "that ship right after assembly: holding the vertical-up key has to lift it, "
                        + "with no re-seating. boarding=" + how
                        + " | CONTROL (no key, same " + (MEASURE_SAMPLES * TICKS_PER_SAMPLE)
                        + "-tick window): drift=" + controlDrift
                        + " | EXPERIMENT (key held): yBefore=" + yBefore + " yAfter=" + yAfter
                        + " climb=" + (yAfter - yBefore) + " (need >= " + MIN_CLIMB + ")"
                        + " | DELIVERY: " + delivery
                        + " | riding=" + ridingAfter + " subsystem=" + status,
                (yAfter - yBefore) >= MIN_CLIMB);
    }

    /**
     * Reads both halves of the pilot-input delivery chain: the CLIENT's gate/send counters and last
     * seat resolution (reflectively, from the client JVM), and the SERVER's receive/deliver
     * counters and its own last resolution (via the read-only {@code seat-delivery} probe). Never
     * throws - a diagnostic that kills the run it is meant to explain would be worse than none -
     * and reports read failures inline instead.
     */
    private String deliveryDiagnostics() {
        String client;
        try {
            client = "gateClosedTicks=" + staticValue("zmaster587.advancedRocketry.client.KeyBindings", "shipGateClosedTicks")
                    + " gateOpenTicks=" + staticValue("zmaster587.advancedRocketry.client.KeyBindings", "shipGateOpenTicks")
                    + " sends=" + staticValue("zmaster587.advancedRocketry.client.KeyBindings", "shipInputSendCount")
                    + " resolves=" + staticValue("zmaster587.advancedRocketry.tile.TilePilotSeat", "riderResolveCount")
                    + " lastResolve[" + staticValue("zmaster587.advancedRocketry.tile.TilePilotSeat", "lastRiderResolve") + "]";
        } catch (Exception e) {
            client = "unreadable(" + e + ")";
        }
        String server;
        try {
            server = exec("artest vs seat-delivery");
        } catch (Exception e) {
            server = "unreadable(" + e + ")";
        }
        return "client{" + client + "} server{" + server + "}";
    }

    /** One client static field's value, via the harness's reflective read. */
    private String staticValue(String className, String fieldName) throws Exception {
        JsonObject read = bot().readStaticField(className, fieldName);
        return read.has("value") ? read.get("value").getAsString() : String.valueOf(read);
    }

    // ---- Boarding variants -------------------------------------------------------------------

    /**
     * The human's stimulus: a real right-click on the seat of an UNASSEMBLED craft, preceded by the
     * two arrangement checks that would otherwise let the server drop the click without a trace.
     */
    private String boardByRightClick() throws Exception {
        // Where the client ACTUALLY is - not where it was told to go. The teleport is what could
        // have failed, so measuring the target instead of the observation would check nothing.
        // And it DOES fail on the first try: a player teleported while still falling from the
        // staging position carries his momentum through the teleport, lands moving, and can slide
        // off the pad - so the teleport is re-issued until the client is OBSERVABLY standing in
        // reach, not merely told to be.
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;
        double distSq = Double.POSITIVE_INFINITY;
        for (int attempt = 0; attempt < 5 && distSq >= MAX_INTERACT_DIST_SQ; attempt++) {
            if (attempt > 0) {
                exec("tp @a " + STAND_X + " " + STAND_Y + " " + STAND_Z + " 0 0");
            }
            // The wait is load-bearing twice over: the server drops interactions while its own
            // teleport is unconfirmed, and the client needs time to damp any leftover motion.
            bot().waitTicks(20);
            JsonObject state = bot().reportState();
            for (int ready = 0; ready < 20 && !isWorldReady(state); ready++) {
                bot().waitTicks(TICKS_PER_SAMPLE);
                state = bot().reportState();
            }
            assertTrue("ARRANGEMENT: the client's world must be ready before its position can be "
                    + "read: " + state, isWorldReady(state));
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();
            distSq = distanceSqToSeatCentre(px, py, pz);
        }
        assertTrue("ARRANGEMENT: the client must OBSERVABLY be standing within the server's "
                        + "interaction reach of the seat, or the right-click is discarded before it "
                        + "reaches the seat block - and repeated teleports could not put it there. "
                        + "observed=(" + px + "," + py + "," + pz + ")"
                        + " distSq=" + distSq + " limit=" + MAX_INTERACT_DIST_SQ,
                distSq < MAX_INTERACT_DIST_SQ);

        // A held stack can consume the right-click before the block ever sees it - and a freshly
        // joined player does NOT start empty-handed (mods hand out items on first join), so the
        // hand is emptied explicitly and then VERIFIED from the client, not assumed.
        exec("clear @a");
        bot().selectHotbar(0);
        // The clear takes a few ticks to reach the client, so poll for the OBSERVED empty hand
        // rather than sampling once.
        JsonObject items = bot().reportPlayerItems();
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            if (isWorldReady(items)) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    break;
                }
            }
            bot().waitTicks(TICKS_PER_SAMPLE);
            items = bot().reportPlayerItems();
        }
        assertTrue("ARRANGEMENT: the client must be in a ready world before its held item can be "
                        + "read - a not-yet-ready client reports no hand at all: " + items,
                isWorldReady(items));
        if (heldId == null || !heldId.isEmpty()) {
            heldId = items.getAsJsonObject("held").get("id").getAsString();
        }
        assertTrue("ARRANGEMENT: the bot's main hand must be EMPTY (it was cleared server-side) so "
                        + "the right-click reaches the seat block rather than being consumed by a "
                        + "held item. held=" + heldId,
                heldId != null && heldId.isEmpty());

        return "click=" + bot().interactBlock(SEAT_X, SEAT_Y, SEAT_Z);
    }

    /**
     * The passing post-assembly test's boarding mechanism, performed BEFORE assembly. The seat probe
     * finds its seat by scanning the world's loaded tile entities, so a seat that is still a loose
     * world block is exactly as findable as one already relocated into a ship - which is what makes
     * this variant possible at all.
     */
    private String boardByProbe() throws Exception {
        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("ARRANGEMENT: the seat probe must FIND the loose pilot seat before assembly: "
                + mountInfo, mountInfo.contains("\"seatFound\":true"));

        // The probe takes the first pilot seat it finds anywhere in the world; pin that it found
        // OUR seat, at the position the block measurement just verified.
        Matcher sm = SEAT_XYZ.matcher(mountInfo);
        assertTrue("ARRANGEMENT: seat-mount must report the seat position it bound: " + mountInfo,
                sm.find());
        assertTrue("ARRANGEMENT: the seat the probe bound must be the fixture's seat at (" + SEAT_X
                        + "," + SEAT_Y + "," + SEAT_Z + "), not some other pilot seat in the world: "
                        + mountInfo,
                Integer.parseInt(sm.group(1)) == SEAT_X
                        && Integer.parseInt(sm.group(2)) == SEAT_Y
                        && Integer.parseInt(sm.group(3)) == SEAT_Z);

        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("ARRANGEMENT: seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("ARRANGEMENT: the bot must mount the seat's dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10);
        return "seatMount=" + mountInfo + " mount=" + mount;
    }

    // ---- Observation helpers -----------------------------------------------------------------

    private ClientBot bot() {
        return clientHarness.bot();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String shipInfo() throws Exception {
        return exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
    }

    /** The ship's world altitude, or {@code NaN} while it is not reporting one. */
    private double shipPosY() throws Exception {
        Matcher m = POS_Y.matcher(shipInfo());
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    /**
     * Waits for the ship to hold one altitude within {@link #SETTLE_EPS} across
     * {@link #SETTLE_STABLE_SAMPLES} consecutive samples, and returns that altitude - or
     * {@code NaN} if it never settles within the budget.
     */
    private double settleShipAltitude() throws Exception {
        double anchor = Double.NaN;
        int stable = 0;
        for (int sample = 0; sample < SETTLE_MAX_SAMPLES; sample++) {
            bot().waitTicks(TICKS_PER_SAMPLE);
            double y = shipPosY();
            if (Double.isNaN(y)) {
                anchor = Double.NaN;
                stable = 0;
                continue;
            }
            if (Double.isNaN(anchor) || Math.abs(y - anchor) > SETTLE_EPS) {
                anchor = y;
                stable = 0;
            } else {
                stable++;
                if (stable >= SETTLE_STABLE_SAMPLES) {
                    return y;
                }
            }
        }
        return Double.NaN;
    }

    /** The largest deviation from {@code from} over a full measurement window. */
    private double measureMaxDrift(double from) throws Exception {
        double worst = 0.0;
        for (int sample = 0; sample < MEASURE_SAMPLES; sample++) {
            bot().waitTicks(TICKS_PER_SAMPLE);
            double y = shipPosY();
            if (!Double.isNaN(y)) {
                worst = Math.max(worst, Math.abs(y - from));
            }
        }
        return worst;
    }

    /** Polls until the client reports it is riding something, then returns that last report. */
    private JsonObject awaitRiding(int attempts) throws Exception {
        JsonObject riding = bot().reportRidingEntity();
        for (int attempt = 0; attempt < attempts && !isRiding(riding); attempt++) {
            bot().waitTicks(TICKS_PER_SAMPLE);
            riding = bot().reportRidingEntity();
        }
        return riding;
    }

    /**
     * Polls until the client reports riding on TWO consecutive samples separated by a wait. A
     * dismount performed server-side does not reach the client instantly, so a single positive
     * sample taken right after assembly can report a seat the player has already lost.
     */
    private JsonObject awaitRidingTwiceInARow(int attempts) throws Exception {
        JsonObject last = bot().reportRidingEntity();
        boolean previousWasRiding = isRiding(last);
        for (int attempt = 0; attempt < attempts; attempt++) {
            bot().waitTicks(TICKS_PER_SAMPLE);
            JsonObject now = bot().reportRidingEntity();
            if (isRiding(now) && previousWasRiding) {
                return now;
            }
            previousWasRiding = isRiding(now);
            last = now;
        }
        return last;
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private static String entityClassOf(JsonObject riding) {
        return riding != null && riding.has("entityClass")
                ? riding.get("entityClass").getAsString() : "";
    }

    private static boolean isWorldReady(JsonObject report) {
        return report != null && report.has("worldReady") && report.get("worldReady").getAsBoolean();
    }

    /** How far the ridden mount sits from the seat block's centre, or {@code +inf} if unreported. */
    private static double distanceSqFromMountToSeatCentre(JsonObject riding) {
        if (riding == null || !riding.has("posX") || !riding.has("posY") || !riding.has("posZ")) {
            return Double.POSITIVE_INFINITY;
        }
        return distanceSqToSeatCentre(riding.get("posX").getAsDouble(),
                riding.get("posY").getAsDouble(), riding.get("posZ").getAsDouble());
    }

    private static double distanceSqToSeatCentre(double x, double y, double z) {
        double dx = x - (SEAT_X + 0.5);
        double dy = y - (SEAT_Y + 0.5);
        double dz = z - (SEAT_Z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- Fixture -----------------------------------------------------------------------------

    /**
     * Places the craft's blocks and STOPS. Assembly is a separate step here, unlike the
     * post-assembly-boarding tests which do both at once, because the player has to be able to sit
     * down in between.
     */
    private void buildLooseFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("ARRANGEMENT: chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("ARRANGEMENT: pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("ARRANGEMENT: fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("ARRANGEMENT: fixture missing builderPos: " + fixture, bp.find());
        builderX = Integer.parseInt(bp.group(1));
        builderY = Integer.parseInt(bp.group(2));
        builderZ = Integer.parseInt(bp.group(3));
    }

    /** Turns the loose blocks placed by {@link #buildLooseFixture} into a ship. */
    private String assembleFixture() throws Exception {
        return exec("artest rocket assemble 0 " + builderX + " " + builderY + " " + builderZ);
    }
}
