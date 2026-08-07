package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.lwjgl.input.Keyboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Login restore for a tier-2 ship's crew, observed on the REAL CLIENT across a REAL server restart:
 * a player who logs out seated on his ship in a space cell has to come back aboard that ship, in his
 * ship's own slot dimension - not in the overworld, and not merely standing beside it.
 *
 * <p><b>Why this must be a client test.</b> The only real subject of this contract is a live player
 * with a client attached. The first restore phase rewrites where a logging-in player is placed, and
 * the second re-seats him a few ticks later; the unit tier fakes the whole world seam and the server
 * tier has no logging-in player at all, so both of those are blind here BY CONSTRUCTION. They are
 * already green. This test is therefore the primary verification, not a formality on top of one.</p>
 *
 * <p><b>Shape.</b> Two server boots over one world root, two client JVMs, manual harness lifecycle.
 * The harness reserves a fresh port on every boot, so a client cannot be reconnected across a
 * restart; a second client JVM is started instead. That is sound because the client's identity is
 * deterministic - every client launches under the same username and therefore under the same
 * offline-mode UUID, and the server keys player data by UUID. Boot 1 arranges and witnesses, then
 * the server is simply stopped with NO explicit save first: the shutdown save is part of what is
 * under test, and saving twice would hide an implementation that only marks its snapshot dirty
 * during that last pass.</p>
 *
 * <p><b>The ship gets into space the way a ship really gets into space.</b> The arrangement does not
 * conjure a settled ship: it builds a piloted tier-2 ship on the ground with the real assembler,
 * feeds it a held-throttle input so its flight computer sees a pilot flying, and lifts it past the
 * launch dimension's orbit ceiling. The flight computer's own server tick then runs the entry
 * on-ramp, which picks the destination cell, crosses the ship into it and settles it in the ship
 * ledger. Every identifier this test works with - the ship id, the cell, the slot dimension - is
 * therefore CHOSEN BY PRODUCTION and read back, never invented here. That matters beyond tidiness:
 * the restore reads the very ledger that entry wrote, so an arrangement that wrote its own ledger
 * would be testing a fixture instead of the subsystem.</p>
 *
 * <p><b>What the instrument actually delivers.</b> Acceptance is client-observed: the client's own
 * rendered dimension, its own riding entity, its own position. The limit has to be stated honestly -
 * "he never appeared in the overworld" is SAMPLED, not proven. There is no client-side
 * dimension-change transcript, so this test observes where the client IS when it looks, never every
 * frame it passed through on the way. A restore that flickered through an overworld frame and then
 * corrected itself would still read green here; what is proven is the end state.</p>
 *
 * <p><b>Exactly ONE ship in the cell.</b> The entry materializes a fresh cell for a single ship, and
 * that is load-bearing rather than incidental: the re-seating matches a seat by proximity to the
 * ship's pose and by the seat's flight-computer link offset, with no ship-id filter. Two ships of
 * the same fixture geometry parked near each other share that offset, so a second nearby ship could
 * satisfy the riding assertion for the wrong ship. One ship per cell removes the ambiguity by
 * construction instead of by hoping. The same assumption lets the ship be located by "the ship
 * nearest any point in the cell" without a search.</p>
 *
 * <p><b>Scope.</b> Every branch of the restore decision is already pinned exhaustively at the unit
 * tier and is deliberately not re-derived here. What this adds is that the REAL wiring runs that
 * decision: a real ledger written by the real entry path, persisted to and restored from disk, the
 * real login hook, a real slot dimension registered again on the second boot, and a real client that
 * has to end up inside it - with a WORKING control chain: a seated return whose held key no longer
 * flies the ship is the play-reported shape of a broken relog, so both sides of the restart hold the
 * client's real vertical-up key and require the client-rendered altitude to climb (the pre-restart
 * leg makes a post-restart red attributable to the restore rather than to a chain that never worked
 * in the cell).</p>
 *
 * <p>Position is never written out as a literal. The pilot is expected back at his ship, so the
 * ship's own live pose is the actual he is compared against; and that pose is separately required to
 * realize a coordinate inside his ship's LEDGERED cell, checked through
 * {@link CellWorldMapper#coordOfPose(GalacticCoord, double, double, double)} - the documented
 * inverse of the cell-to-world pose mapping. That is what catches "right dimension, ordinary block
 * height": a position outside the pose band renormalises into a neighbouring sector and stops
 * matching the cell. Hardcoding the band offset instead would turn a legitimate retune of a value
 * documented as tunable into a test failure.</p>
 *
 * <p>Skips (never fails) when the server harness is off, when the client harness is off, or when
 * Valkyrien Skies is absent - the production subsystem declines to register without it, so the
 * wiring under test would not exist at all. Run with {@code -PwithVS}.</p>
 */
public abstract class AbstractSpaceLoginRestoreClientTest {

    /** The account every client harness launches under; the server keys his player data by it. */
    protected static final String BOT = "ForgeTestClient";

    /**
     * The ship fixture. A crew member has to be able to STAND and WALK on this ship: the seat-only
     * variant has no floor, and a body walked off it is dropped by the capture with
     * {@code noHullContact} - which arrives as a silent record and reads exactly like "he did not
     * move". This variant adds the 5x5 deck under the seat and is the one the planet-side walking
     * relog test flies for the same reason.
     */
    protected static final String VARIANT = "with-pilot-deck";

    /** Where an orphaned login lands, and the one dimension a restored pilot must NOT be in. */
    protected static final int OVERWORLD_DIM = 0;

    /**
     * A stable fragment of what a pilot is told when the server has no record of his ship. Matching a
     * fragment rather than the whole line is deliberate: this is the player-visible sentence, so the
     * test has to read what he reads — but the punctuation and the colour code around it are not the
     * contract.
     */
    protected static final String SHIP_LOST_NEEDLE = "ship could not be found";

    /** The launch dimension the ship takes off from - always registered, always terrain-generated. */
    protected static final int LAUNCH_DIM = 0;

    /** Where the piloted ship is built: a loaded overworld region well clear of other fixtures. */
    protected static final int SRC_X = 6800;
    protected static final int SRC_Y = 80;
    protected static final int SRC_Z = 6800;

    /** A world height comfortably above the default orbit ceiling, so the ceiling check fires. */
    protected static final int ABOVE_CEILING_Y = 1200;

    /**
     * The six flight channels - forward, vertical, strafe, yaw, pitch, roll - as the flight-input
     * probe takes them. They are NOT prefixed by a dimension: the input is one server-wide channel,
     * and reading the leading zero as a dimension id is the trap this constant exists to close.
     * {@link #HELD_CLIMB} is a pilot holding the ship up; {@link #HANDS_OFF} is him letting go.
     */
    protected static final String HELD_CLIMB = "0 1 0 0 0 0";
    protected static final String HANDS_OFF = "0 0 0 0 0 0";

    /**
     * How far off his ship the client may be and still count as "back at his ship". Covers the
     * seat's offset from the ship's own origin plus a few ticks of settling, and is far too small to
     * be satisfied by any other dimension's spawn.
     */
    protected static final double POSE_EPSILON = 24.0D;

    /** Sentinel for "the client has no world yet", so nobody reads a "dim" key that is absent. */
    protected static final int NO_CLIENT_WORLD = Integer.MIN_VALUE;

    /**
     * A demonstrable held-key climb: well above settle jitter, cheap to reach. Same bar as the
     * planet-side relog-control pin ({@link VSPilotSeatRelogControlE2ETest}) - the contract is
     * "held input MOVES the ship within a bounded window", not any particular rate.
     */
    protected static final double MIN_CLIMB = 1.0;

    /**
     * The no-input observation window, in ticks. Two seconds: long enough that a drift of the
     * reported size (about a block per thirty ticks) is unmistakable, short enough not to invite the
     * station-hold's own settling into the measurement.
     */
    protected static final int OBSERVE_TICKS = 40;

    /**
     * The floor below which the window is not evidence. "The body did not move" and "the resolver
     * never ran" are the same reading, and only one of them is a pass.
     */
    protected static final int MIN_RESOLVED = 20;

    /**
     * How far a no-input body may travel along its deck across the window, in blocks. Same bar as the
     * planet-side relog pin, so a red here is comparable with that leg rather than a new standard;
     * the reported drift is several times it.
     */
    protected static final double DRIFT_TOLERANCE = 0.35D;

    /**
     * How far the ship itself must travel across the window for the window to mean anything, in
     * blocks. A body held to a motionless deck cannot drift however broken the hold is - the first cut
     * of this pin measured exactly that and came back all zeros.
     */
    protected static final double MIN_DECK_MOVE = 1.0D;

    /**
     * And the ceiling of that band. The reported symptom is a ship that is ALMOST STATIONARY -
     * settling - which is also the regime ledger #108's mechanism lives in (~0.15 blocks/tick).
     * Holding the throttle through the window instead let the ship reach its cruise cap, two blocks
     * per tick, and there the client's record simply went silent: a different regime with its own
     * suspected defect, tracked separately. Expressed as a RATE, in blocks per tick: nothing damps a
     * pulse in a space cell, so the totals accumulate window over window while the rate is what
     * actually distinguishes a settling ship from one at its cap.
     */
    protected static final double MAX_DECK_RATE = 0.75D;

    /**
     * The throttle pulse, in ticks: enough to set the ship moving, short enough that what the window
     * observes is the station hold SETTLING it rather than the ship accelerating to its cap.
     */
    protected static final int THROTTLE_PULSE_TICKS = 8;

    /**
     * The per-tick step band a creep lives in, in blocks: above the floating-point noise of a held
     * point, below the one-off jump of a placement or a teleport. Summing only the steps inside it
     * separates "he was put somewhere" from "he is being walked along the deck".
     */
    protected static final double CREEP_STEP_MIN = 0.002D;
    protected static final double CREEP_STEP_MAX = 0.30D;

    /**
     * How far the walk itself must carry him along the deck, in blocks, for the post-release window to
     * be about an inherited velocity rather than about a body that never walked.
     */
    protected static final double MIN_WALK_TRAVEL = 0.5D;

    /** The class whose client-side statics carry the per-tick ship-frame record. */
    protected static final String SHIP_FRAME_TRAVEL =
            "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel";

    /**
     * One line of that record: {@code <tick><path>|B=<live body point>|H=<committed point>|m=<incoming
     * ship-relative motion>|c=<carry>|in=<strafe>/<forward>|d=<on deck>}. Every field is in the SHIP's
     * frame, which is what makes a drift measurable at all.
     */
    protected static final Pattern HISTORY_LINE = Pattern.compile(
            "(\\d+)([afh])\\|B=(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)"
                    + "\\|H=(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)"
                    + "\\|m=(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)"
                    + "\\|c=(-?[0-9.E\\-]+)\\|in=(-?[0-9.]+)/(-?[0-9.]+)\\|d=(\\d)"
                    + "\\|s=(\\d)(\\d)/(-?\\d+)");

    protected static final Pattern SHIP_ID = Pattern.compile("\"shipId\":\"([^\"]+)\"");
    protected static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    protected static final Pattern FORGE_DIMS = Pattern.compile("\"forgeDimensions\":\\[([^\\]]*)]");

    /** The ship production minted for the arranged pilot, and the cell production settled it in. */
    protected String arrangedShipId;
    protected String arrangedCellKey;

    protected Path root;
    protected RealDedicatedServerHarness serverHarness;
    protected RealClientHarness clientHarness;

    @Before
    public void seedWorldDirectory() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled - set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled - set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        root = Files.createTempDirectory("forge-client-space-login-restore-");
    }

    @After
    public void stopHarnesses() throws Exception {
        closeBoth();
    }

    /** The newest client chat line containing {@code needle} (case-insensitive), or null. */
    protected String chatLineContaining(String needle) throws Exception {
        com.google.gson.JsonArray lines = bot().reportChat(20).getAsJsonArray("lines");
        if (lines == null) {
            return null;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).getAsString();
            if (line.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT))) {
                return line;
            }
        }
        return null;
    }

    /**
     * The shared subject of both positive legs: whatever route put the pilot in his seat in a cell,
     * stopping the server under him must bring him back in that seat, in his ship's cell, with a
     * control chain that still flies. Takes the slot dimension the arrangement banked him in.
     */
    protected void requireHeComesBackAboardHisShip(int slotDim) throws Exception {
        // CONTROL LEG (pre-restart): the seated pilot's REAL key must fly the ship in its cell
        // BEFORE the restart - without this, a dead key after the reboot could be a chain that
        // never worked in the cell at all, and the post-restart assertion could not indict the
        // restore. The stimulus is the client's own vertical-up key, not the flight-input probe
        // the arrangement used: what is being proven here is the key->packet->flight-computer
        // chain the restored pilot will need again on the other side of the restart.
        double preY0 = clientPlayerY();
        double preY1 = climbWith(Keyboard.KEY_R, preY0);
        assertTrue("ARRANGEMENT (control leg): the seated pilot must be able to fly his ship in "
                + "its cell BEFORE the restart. clientY " + preY0 + " -> " + preY1
                + " (need +" + MIN_CLIMB + ")", (preY1 - preY0) >= MIN_CLIMB);
        // Let the station-hold settle the hovering ship before he logs out: the restore below
        // compares his login position against the ship's LIVE pose, and a ship still drifting
        // upward when the server stops turns that comparison into a moving target.
        bot().waitTicks(30);

        // The restore can only be exercised if he is STILL aboard in the slot dimension at the moment
        // the server writes him to disk. Assert that here rather than at the end: a pilot who has
        // already been moved out by this point makes the whole reboot leg vacuous, and the resulting
        // "he came back in the overworld" would be a statement about the arrangement, not about the
        // restore. Failing here says "he never logged out aboard"; failing after the reboot says
        // "he logged out aboard and did not come back".
        // Read this SERVER-side, not from the client. What gets written to disk is the server's
        // player entity, and the two can disagree: the client keeps rendering the cell it was sent
        // to while the server has already put the entity somewhere else. A client-side check here
        // passes in exactly the case this assertion exists to catch.
        String serverBeforeLogout = exec("artest player position-of " + BOT);
        JsonObject ridingBeforeLogout = bot().reportRidingEntity();
        assertEquals("the SERVER must still have him in his ship's slot dimension when it writes him "
                        + "to disk - the login restore keys off the saved dimension, so if he is "
                        + "banked in the overworld here the reboot leg proves nothing: "
                        + serverBeforeLogout + " clientRiding=" + ridingBeforeLogout,
                slotDim, readInt(serverBeforeLogout, "playerDim"));
        // The dimension FIELD is what gets persisted, and it is maintained separately from the world
        // the entity ticks in. If it has drifted back to the overworld while he stands in the cell,
        // he is written to disk as an overworld player and the restore can never fire for him.
        assertEquals("the pilot's persisted dimension field must match the cell he is standing in: "
                        + serverBeforeLogout, slotDim, readInt(serverBeforeLogout, "playerDimField"));

        // The pool's composition on this side of the restart, kept so the boot-2 assertions can say
        // whether the slot the pilot was banked in still means the same thing afterwards.
        String statusBefore = exec("artest space subsystem-status");

        // Deliberately NO explicit save before the stop: what survives has to survive the shutdown
        // save alone, which is the only save a real operator's stop ever runs.
        closeBoth();
        keepBootLog("boot1");

        // --- boot 2: a brand new server JVM and a brand new client JVM, same world root ----------
        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        String statusAfter = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2, or nothing below is "
                + "exercising it: " + statusAfter, statusAfter.contains("\"registered\":true"));

        // The pool re-mints its slot dimension ids on every boot, so the id the pilot was banked under
        // (slotDim) routinely means nothing on this side of the restart - the two sets can be entirely
        // disjoint. That is NOT asserted either way here: it is the subsystem's business, and pinning
        // it would freeze an implementation detail. What matters is that the restore survives it, which
        // is what the assertions below measure. The two pool snapshots ride along in their failure text
        // so that a red is attributable to the id churn rather than merely correlated with it.
        String pools = "\n  pool on boot 1: " + slotDimsOf(statusBefore)
                + "\n  pool on boot 2: " + slotDimsOf(statusAfter)
                + "\n  pilot was banked in slot dim " + slotDim;

        // The ledger is what carries the ship across the restart, so read it back BEFORE the client
        // connects: a restore that finds no ledgered ship resolves "ship unknown" and drops the
        // pilot at an ordinary spawn, and that failure must be attributable to the ledger rather
        // than to the login hook.
        String ledger = exec("artest space ledger-get " + arrangedShipId);
        assertTrue("the settled ship must survive the shutdown save and come back in the ledger - "
                + "without it there is nothing for the restore to restore him onto: " + ledger,
                ledger.contains("\"found\":true"));
        assertEquals("and it must come back SETTLED in the same cell it entered: " + ledger,
                arrangedCellKey, readString(ledger, "cell"));
        assertEquals("a ship that came back in some other ledger state would send the restore down "
                + "a different branch entirely: " + ledger, "SETTLED", readString(ledger, "state"));

        // Issued BEFORE the client connects: the restore fires on his connection, and a headless
        // server has nobody standing near the ship to hold it loaded for the re-seating.
        exec("artest vs permaload true");

        startClient();
        bot().waitForWorld();

        // The re-seating retries on a budget of a couple of hundred ticks and then gives up
        // SILENTLY, leaving the player standing aboard, so this has to poll well past that budget
        // rather than sample once.
        JsonObject riding = null;
        int dim = NO_CLIENT_WORLD;
        boolean aboard = false;
        for (int attempt = 0; attempt < 45 && !aboard; attempt++) {
            bot().waitTicks(10);
            dim = clientDim();
            riding = bot().reportRidingEntity();
            aboard = dim != NO_CLIENT_WORLD && dim != OVERWORLD_DIM
                    && riding.get("riding").getAsBoolean();
        }

        JsonObject state = bot().reportState();
        String observed = "clientDim=" + dim + " riding=" + riding + " state=" + state + pools;

        assertTrue("the client must have a world at all before anything can be read from it: "
                + observed, dim != NO_CLIENT_WORLD);
        assertNotEquals("he logged out aboard his ship, so he must NOT come back in the overworld. "
                + "Note dim 0 is an AMBIGUOUS failure: it is produced both by the subsystem's own "
                + "orphan fallback and by vanilla silently forcing dim 0 when the target world did "
                + "not load, so attribute a red here from the server's login-restore log line rather "
                + "than from this number alone. " + observed, OVERWORLD_DIM, dim);
        assertTrue("the pilot must come back SEATED on his ship rather than merely in its cell - "
                + "being put back in the chair is what a player experiences as the restore working: "
                + observed, riding.get("riding").getAsBoolean());
        assertTrue("and the thing he is riding must be a ship seat's mount: " + riding,
                riding.get("entityClass").getAsString().endsWith("EntityDummy"));

        // Which dimension he came back to only means something relative to his ship. Slot ids are a
        // POOL and are re-minted every boot, so the id itself is not stable across a restart and
        // must not be asserted; what must hold is that the slot he is seated in is bound to HIS
        // ship's cell. Sitting down re-stamps the aboard record from the world he is actually in,
        // which is exactly that statement, and it also proves the record was rebuilt rather than
        // merely surviving.
        String tag = exec("artest space aboard-tag " + BOT);
        assertTrue("being re-seated must leave him aboard again: " + tag, tag.contains("\"tagged\":true"));
        assertTrue("he must be back aboard the SAME ship, not some other one: " + tag
                + " (entered ship " + arrangedShipId + ")", tag.contains(arrangedShipId));
        assertTrue("and the slot dimension he woke up in must be the one bound to his ship's cell "
                + arrangedCellKey + " - a different cell would mean the restore materialized the "
                + "wrong address: " + tag, tag.contains("\"cell\":\"" + arrangedCellKey + "\""));

        // Where the ship actually is, right now, in the dimension the client reports. Read from the
        // server rather than remembered from boot 1, so the comparison is against the ship's live
        // pose and not against a snapshot that a legitimate drift would invalidate.
        double[] shipPose = awaitShipPose(dim);
        assertNotNull("his ship must be live in the dimension he came back to - if it is not, "
                + "'he is riding something' says nothing about the ship: " + observed, shipPose);

        assertTrue("the client must report a player position: " + state,
                state.get("worldReady").getAsBoolean());

        // Being seated is reported before the client has finished resolving WHERE the seat is: right
        // after the join its X and Z snap to the ship while Y is still converging, so a sample taken
        // the instant "riding" turns true catches a position that belongs to neither end. Give it a
        // bounded number of ticks to settle and keep the LAST reading either way - if it never
        // converges that is a real failure and the assertions below must still report it.
        double clientX = state.get("playerX").getAsDouble();
        double clientY = state.get("playerY").getAsDouble();
        double clientZ = state.get("playerZ").getAsDouble();
        for (int attempt = 0; attempt < 40 && Math.abs(clientY - shipPose[1]) > POSE_EPSILON;
                attempt++) {
            bot().waitTicks(10);
            state = bot().reportState();
            if (!state.get("worldReady").getAsBoolean()) {
                continue;
            }
            clientX = state.get("playerX").getAsDouble();
            clientY = state.get("playerY").getAsDouble();
            clientZ = state.get("playerZ").getAsDouble();
            double[] livePose = awaitShipPose(dim);
            if (livePose != null) {
                shipPose = livePose;
            }
        }
        observed = "clientDim=" + dim + " riding=" + bot().reportRidingEntity() + " state=" + state
                + " shipPose=[" + shipPose[0] + "," + shipPose[1] + "," + shipPose[2] + "]" + pools;
        assertEquals("he must come back at his ship on X: " + observed,
                shipPose[0], clientX, POSE_EPSILON);
        assertEquals("he must come back at his ship on Y: " + observed,
                shipPose[1], clientY, POSE_EPSILON);
        assertEquals("he must come back at his ship on Z: " + observed,
                shipPose[2], clientZ, POSE_EPSILON);

        // And that position has to realize a coordinate inside his ship's ledgered cell. This is the
        // check "he is in a slot dimension" cannot make: a slot world is an ordinary world, so a
        // pilot dumped at ordinary block height inside the right slot would still read as "not the
        // overworld" - but mapped back through the pose band he lands in a neighbouring sector.
        GalacticCoord cell = GalacticCoord.fromCellKey(arrangedCellKey);
        assertNotNull("the ledger reported an unreadable cell key: " + arrangedCellKey, cell);
        GalacticCoord realized = CellWorldMapper.coordOfPose(cell, clientX, clientY, clientZ);
        assertTrue("the client's position must realize a coordinate in his ship's own cell "
                + arrangedCellKey + ", but it maps to " + realized.cellKey() + ": " + observed,
                realized.sameCell(cell));

        // CONTROL LEG (load-bearing): the restored chain still FLIES the ship. Being put back in
        // the chair is only half the relog promise - a restored seat with a dead key is exactly
        // the play-reported shape of a broken control chain, and it would read green on every
        // assertion above. Same real-key stimulus, same client-observed altitude as the pre-restart
        // leg, so a red here is attributable to the restart and nothing else.
        double postY0 = clientPlayerY();
        double postY1 = climbWith(Keyboard.KEY_R, postY0);
        assertTrue("after the restart, held input must MOVE THE SHIP - a restored seat with a "
                + "dead key is a broken control chain. clientY " + postY0 + " -> " + postY1
                + " (need +" + MIN_CLIMB + ") delivery=" + exec("artest vs seat-delivery"),
                (postY1 - postY0) >= MIN_CLIMB);
    }

    /**
     * A crew member restored onto his deck must not be DRAGGED along it: with no input at all, his
     * own position in the ship's frame has to stay put.
     *
     * <p><b>Frame, and why it is the whole measurement.</b> The subject is the body's point in the
     * SHIP's frame, so the ship's own motion is already divided out and a carried body reads as
     * still. A world-frame reading cannot make this statement at all - it counts the ship carrying
     * the crew member as the crew member moving.</p>
     *
     * <p><b>Why the LIVE body point and not the held one.</b> The per-tick record carries both: the
     * body's own coordinates ({@code B}) and the point the resolver has COMMITTED for it ({@code H}).
     * A body that something else is pulling has a perfectly still committed point, so the existing
     * planet-side pins - which read the committed point - pass while the player slides. {@code B} is
     * the only field in this repo that answers "did the body move along the deck".</p>
     *
     * <p><b>The window must be witnessed.</b> "No travel" and "no ticks recorded" read identically,
     * so the number of resolved ticks inside the window is asserted before the travel is. Without
     * that, a client whose resolver never ran would produce the cleanest possible pass.</p>
     */
    protected void requireHeIsNotDraggedAlongHisDeck(int dim) throws Exception {
        // DIAGNOSTIC FIRST, and it covers the login itself. The client JVM is new on this boot, so its
        // record starts empty and everything in it belongs to this session: the whole restore, the
        // placement jump included. A drag reported "right after entering the game" is a TRANSIENT, and
        // a window opened once the placement has settled is exactly the shape that cannot see one.
        String sinceConnect = clientTickHistory();
        String transient_ = "resolved=" + resolvedSince(sinceConnect, -1L)
                + " worstStep=" + worstStep(sinceConnect, -1L)
                + " creepBandTotal=" + creepBandTotal(sinceConnect, -1L)
                + " " + writerSummary(sinceConnect, -1L);

        // THE DRIVER, not the condition. The previous cut of this pin measured a body on a deck that
        // was standing perfectly still - every field came back exactly 0.0, carry included - and a
        // held body on a motionless deck cannot drift no matter what is wrong with the hold. Ledger
        // #108's own retraction says it outright: the relog was never the variable, the SHIP'S MOTION
        // was. So the deck is put in motion for the window, and the ship's own displacement is
        // WITNESSED afterwards: without that witness a still ship reads as a clean pass again.
        pulseThrottle();
        double[] deckBefore = awaitShipPose(dim);
        assertNotNull("the ship must be live before the window opens", deckBefore);
        long fromTick = lastClientTick();
        long dropsBeforeIdle = clientLong("externalMoveDrops");
        bot().waitTicks(OBSERVE_TICKS);
        long dropsInIdle = clientLong("externalMoveDrops") - dropsBeforeIdle;
        double[] deckAfter = awaitShipPose(dim);
        assertNotNull("the ship must still be live after the window", deckAfter);
        double deckMoved = distance(deckBefore, deckAfter);
        assertTrue("ARRANGEMENT: the deck must MOVE during the observation window, or a body that "
                        + "stayed put proves nothing about the hold - a motionless deck cannot drag "
                        + "anyone (the ship moved " + deckMoved + " blocks, need > " + MIN_DECK_MOVE
                        + "). Transient window since connect: " + transient_,
                deckMoved > MIN_DECK_MOVE);
        assertTrue("ARRANGEMENT: this window must observe a SETTLING deck, not a ship at its cruise "
                        + "cap - the reported symptom is a ship that is almost stationary, and at cap "
                        + "speed the hold has a separate suspected defect of its own (the ship moved "
                        + deckMoved + " blocks in " + OBSERVE_TICKS + " ticks = "
                        + (deckMoved / OBSERVE_TICKS) + " per tick, ceiling " + MAX_DECK_RATE + "). "
                        + dropReasons(),
                deckMoved / OBSERVE_TICKS < MAX_DECK_RATE);

        String history = clientTickHistory();
        assertTrue("the client's per-tick ship-frame record must exist, or this pin measures nothing "
                + "at all (history=" + history + ")", history != null && history.length() > 0);
        int resolved = resolvedSince(history, fromTick);
        assertTrue("the client must have resolved the body through the observation window, or a clean "
                        + "result here is a statement about the instrument rather than the body "
                        + "(resolved " + resolved + " ticks after tick " + fromTick + ", need >= "
                        + MIN_RESOLVED + "). A silent record has three readings and only one of them "
                        + "is 'no drift': " + dropReasons() + "\n" + history,
                resolved >= MIN_RESOLVED);

        double bodyTravel = bodyPointTravel(history, fromTick);
        double heldTravel = heldPointTravel(history, fromTick);
        String writers = writerSummary(history, fromTick);

        // DIAGNOSTIC, never a pin: the maintainer's own cure - sitting down and standing up again
        // re-installs the capture and the drift stops. It names WHERE the bad state comes from (an
        // install, not the steady-state hold), which is what a fix has to act on; but "the cure
        // works" is not a promise production makes, so it rides in the failure text only.
        // THE OTHER HALF OF THE REPORT, and the half an idle window cannot reach: the drift rides ON
        // TOP OF WALKING. The stimulus is a real key; the observation is the body's own ship-frame
        // point after the key is RELEASED - a body that keeps going once the input stops is carrying
        // something the walk did not give it. Measured under the RESTORED capture first.
        double[] restoredWalk = walkThenIdle(dim);
        // TWICE under the SAME capture. The first pair came back four times weaker under the restored
        // capture than under the re-installed one, and those two legs differed in ORDER as well as in
        // provenance. A second walk through the unchanged capture separates them: still weak means the
        // restored capture is the variable, back to normal means the first walk after a login is.
        double[] restoredWalkAgain = walkThenIdle(dim);

        String cure = measureAfterReCapture(dim);

        // The same stimulus again, through a capture just re-installed by hand: the maintainer's own
        // cure turned into an in-run CONTROL - same body, same deck, same key, only the capture's
        // provenance differs. Diagnostic, not a pin.
        double[] freshWalk = walkThenIdle(dim);
        double[] freshWalkAgain = walkThenIdle(dim);

        System.out.println("[space-drag] deckMoved=" + deckMoved + " bodyTravel=" + bodyTravel
                + " heldTravel=" + heldTravel + " resolved=" + resolved
                + "\n[space-drag] " + writers
                + "\n[space-drag] since connect (transient): " + transient_
                + "\n[space-drag] walk 1 under the RESTORED capture: " + describeWalk(restoredWalk)
                + "\n[space-drag] walk 2 under the RESTORED capture: " + describeWalk(restoredWalkAgain)
                + "\n[space-drag] after a re-capture: " + cure
                + "\n[space-drag] walk 1 under a FRESH capture:     " + describeWalk(freshWalk)
                + "\n[space-drag] walk 2 under a FRESH capture:     " + describeWalk(freshWalkAgain));

        // The walking half, asserted with its own witnesses: he must really have walked, the deck must
        // really have moved, and the record must really cover the window - otherwise a clean result is
        // about the arrangement. The subject is what remains AFTER the key is released.
        // Every number is measured and printed BEFORE any witness is allowed to fire: a witness that
        // aborts mid-experiment throws away the comparison that makes the result legible, which is
        // exactly what happened on the first plain-relog run.
        String walkTable = "\n  restored 1: " + describeWalk(restoredWalk)
                + "\n  restored 2: " + describeWalk(restoredWalkAgain)
                + "\n  fresh 1:    " + describeWalk(freshWalk)
                + "\n  fresh 2:    " + describeWalk(freshWalkAgain);
        assertTrue("ARRANGEMENT: the walk must actually move him along the deck, or the pin below "
                        + "measures a body that never walked. A walk that is weak ONLY under the "
                        + "restored capture is itself the finding rather than an arrangement fault - "
                        + "read the four together:" + walkTable,
                restoredWalk[0] > MIN_WALK_TRAVEL);
        double walkWindowTicks = 6 + 2 + OBSERVE_TICKS;
        assertTrue("ARRANGEMENT: the deck must move during the walk window, in the settling band ("
                        + (restoredWalk[3] / walkWindowTicks) + " blocks per tick, band "
                        + (MIN_DECK_MOVE / walkWindowTicks) + ".." + MAX_DECK_RATE + "): "
                        + describeWalk(restoredWalk) + " " + dropReasons(),
                restoredWalk[3] > MIN_DECK_MOVE
                        && restoredWalk[3] / walkWindowTicks < MAX_DECK_RATE);
        assertTrue("ARRANGEMENT: the record must cover the window AFTER the key was released, or the "
                        + "clean result is a frozen record rather than a still body - the tell is a "
                        + "travel figure identical to the previous leg's to the last digit: "
                        + describeWalk(restoredWalk) + " " + dropReasons(),
                restoredWalk[2] >= MIN_RESOLVED);
        assertTrue("a crew member restored onto his deck must not keep travelling once he stops "
                        + "walking: his ship-frame point moved " + restoredWalk[1] + " blocks over "
                        + (int) restoredWalk[2] + " ticks AFTER the key was released (bar "
                        + DRIFT_TOLERANCE + "), of which " + restoredWalk[4] + " arrived as per-tick "
                        + "creep. The same stimulus through a freshly re-installed capture: "
                        + describeWalk(freshWalk) + " (diagnostic control, not a promise)"
                        + "\n  walk under the restored capture: " + describeWalk(restoredWalk),
                restoredWalk[1] < DRIFT_TOLERANCE);

        assertTrue("a crew member restored onto his deck must not be dragged along it: with no input "
                        + "his own ship-frame point travelled " + bodyTravel + " blocks over "
                        + resolved + " resolved ticks (bar " + DRIFT_TOLERANCE + ") while his deck "
                        + "moved " + deckMoved + " blocks, and the point the resolver commits for him "
                        + "moved " + heldTravel + " - a still committed point under a travelling body "
                        + "is the signature of another writer moving him."
                        + "\n  writers: " + writers
                        + "\n  since connect (the login transient): " + transient_
                        + "\n  after a re-capture (diagnostic, not a promise): " + cure
                        + "\n" + history,
                bodyTravel < DRIFT_TOLERANCE);

        // THE MECHANISM ITSELF, asserted rather than inferred from the drift it produces.
        //
        // This fails if production breaks the contract that the external-move guard releases a deck
        // capture only on movement the ship-frame resolver did NOT itself produce. Nothing in either
        // window teleports the body: it stands still, or it walks a walk this class swept and
        // committed. Every release therefore hands a body the resolver was holding back to vanilla
        // and to the physics mod for the ticks until the re-capture, which is the drag as the player
        // experiences it - and it is why the maintainer's cure (a re-seat) works only until the next
        // release.
        //
        // Kept separate from the drift pins above because the two can disagree in BOTH directions: a
        // release whose vanilla ticks happen to net out leaves the drift clean, and a drift with no
        // release at all would name a different writer entirely. The count is the discriminator.
        assertEquals("the external-move guard must not release a crew member the resolver is itself "
                        + "moving: it released him " + dropsInIdle + " time(s) across " + resolved
                        + " resolved ticks with NO input at all, and " + (int) restoredWalk[10]
                        + " time(s) during a " + (int) restoredWalk[6] + "-tick walk it swept and "
                        + "committed. " + dropReasons() + walkTable,
                0L, dropsInIdle + (long) restoredWalk[10]);
    }

    /**
     * Walk him along the deck with a real key, release it, and watch what he does afterwards. Returns
     * {@code [walkTravel, idleTravelAfterRelease, resolvedTicksInTheIdleWindow, deckMoved,
     * creepBandTotal]}, all in the SHIP's frame except {@code deckMoved}.
     *
     * <p>The two ticks between the release and the idle window are deliberate and were learned on the
     * planet side: the subject is an inherited VELOCITY, not an inherited INPUT, and a window opened on
     * the release tick cannot tell a key that is still held from a body that is still moving.</p>
     */
    protected double[] walkThenIdle(int dim) throws Exception {
        pulseThrottle();
        double[] deckBefore = awaitShipPose(dim);
        long walkFrom = lastClientTick();
        long dropsBeforeWalk = clientLong("externalMoveDrops");
        bot().holdKey(Keyboard.KEY_W);
        // Six ticks, not twelve: the fixture's deck is small, and a walk long enough to carry him off
        // its edge ends the capture - which reads as a silent record rather than as a clean body.
        bot().waitTicks(6);
        bot().releaseKey(Keyboard.KEY_W);
        String walkHistory = clientTickHistory();
        long dropsInWalk = clientLong("externalMoveDrops") - dropsBeforeWalk;
        bot().waitTicks(2);
        long idleFrom = lastClientTick();
        long dropsBeforeIdle = clientLong("externalMoveDrops");
        bot().waitTicks(OBSERVE_TICKS);
        String idleHistory = clientTickHistory();
        long dropsInIdle = clientLong("externalMoveDrops") - dropsBeforeIdle;
        double[] deckAfter = awaitShipPose(dim);
        // THE INSTRUMENT-OR-SUBJECT SPLIT, and it has to be measured, not argued. A restored body that
        // does not move under the key has two readings: the hold cancels the motion, or the key never
        // arrived. They are told apart by whether the resolver SAW the input at all - and a reconnect
        // is exactly the moment a harness could lose the client's input focus, with the mount/dismount
        // of the "cure" quietly restoring it. Without this pair of numbers the finding would rest on
        // an assumption about the harness.
        return new double[]{
                bodyPointTravel(walkHistory, walkFrom),
                bodyPointTravel(idleHistory, idleFrom),
                resolvedSince(idleHistory, idleFrom),
                deckBefore == null || deckAfter == null ? -1.0 : distance(deckBefore, deckAfter),
                creepBandTotal(idleHistory, idleFrom),
                inputTicksIn(walkHistory, walkFrom),
                resolvedSince(walkHistory, walkFrom),
                // DECK CONTACT during the walk. The walk factor is chosen by it: a body the resolver
                // does not consider to be standing on the deck is moved with AIR control and damped
                // with air friction instead of the block's, which is weak input and slow decay - the
                // two halves of "he barely walks" and "he slides". This is the field that says which.
                offDeckTicksIn(walkHistory, walkFrom),
                sweepPinnedTicksIn(walkHistory, walkFrom),
                maxObstaclesIn(walkHistory, walkFrom),
                // WHO OWNED THE BODY. Every tick between an external-move release and the re-capture
                // belongs to vanilla and to the physics mod rather than to the ship-frame resolver,
                // and the reporter's own session log is 174 such releases - all of them on the CLIENT,
                // 148 of them on a deck tilted 1.1 deg or less, 150 of them with deck support under
                // the body. A release count is therefore not a diagnostic detail here: it is the
                // mechanism the report describes, measured directly.
                dropsInWalk,
                dropsInIdle};
    }

    /**
     * Ticks whose sweep collided HORIZONTALLY: the motion the input produced was zeroed against
     * geometry rather than swallowed by another writer. This is the discriminator between the two
     * remaining candidates for a pinned body - a zero here alongside a zero travel indicts whatever
     * re-applies the committed point, not the collision box.
     */
    protected int sweepPinnedTicksIn(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        int n = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick
                    && ("1".equals(m.group(16)) || "1".equals(m.group(17)))) {
                n++;
            }
        }
        return n;
    }

    /** The most obstacles the sweep saw in the window - a body standing inside geometry sees more. */
    protected int maxObstaclesIn(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        int worst = -1;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick) {
                worst = Math.max(worst, Integer.parseInt(m.group(18)));
            }
        }
        return worst;
    }

    /** How many ticks of the window the resolver did NOT consider the body to be on the deck. */
    protected int offDeckTicksIn(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        int n = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick && "0".equals(m.group(15))) {
                n++;
            }
        }
        return n;
    }

    /** How many ticks of the window carried a nonzero walk input, as the resolver saw it. */
    protected int inputTicksIn(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        int n = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick
                    && (Double.parseDouble(m.group(13)) != 0.0
                            || Double.parseDouble(m.group(14)) != 0.0)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Set the ship moving with a bounded pulse and let go. Holding the throttle across a window
     * instead accumulates speed run after run until the ship is at its cruise cap, which is a
     * different regime with a different suspected defect - and the accumulation is invisible in any
     * single window's numbers.
     */
    protected void pulseThrottle() throws Exception {
        exec("artest vs ff-input " + HELD_CLIMB);
        bot().waitTicks(THROTTLE_PULSE_TICKS);
        exec("artest vs ff-input " + HANDS_OFF);
    }

    /**
     * Why the record might be silent, as the three readings an absent line actually has: the resolver
     * never ran (resolved/declined counters), it ran and dropped the body (external-move drops and the
     * last drop's reason), or it is still holding him and simply had nothing to say. Without this a
     * silent record reads as "he did not move", which is the one reading it must never be able to fake.
     */
    protected String dropReasons() throws Exception {
        return "CLIENT[resolvedTicks=" + clientString(SHIP_FRAME_TRAVEL, "resolvedTicks")
                + " declinedTicks=" + clientString(SHIP_FRAME_TRAVEL, "declinedTicks")
                + " externalMoveDrops=" + clientString(SHIP_FRAME_TRAVEL, "externalMoveDrops")
                + " lastDropReason=" + clientString(SHIP_FRAME_TRAVEL, "lastDropReason")
                + " worldMoveApplies=" + clientString(SHIP_FRAME_TRAVEL, "worldMoveApplies") + "]";
    }

    protected static String describeWalk(double[] w) {
        return "walkTravel=" + w[0] + " idleAfterRelease=" + w[1] + " resolvedIdle=" + (int) w[2]
                + " deckMoved=" + w[3] + " creepBandTotal=" + w[4]
                + " inputTicksSeenByResolver=" + (int) w[5] + "/" + (int) w[6]
                + " offDeckTicksInWalk=" + (int) w[7]
                + " sweepPinnedTicks=" + (int) w[8] + " maxObstacles=" + (int) w[9]
                + " guardReleases=" + (int) w[10] + "walk/" + (int) w[11] + "idle";
    }

    /** A client-side counter as a number, or {@code -1} when it cannot be read. */
    protected long clientLong(String field) throws Exception {
        try {
            return Long.parseLong(clientString(SHIP_FRAME_TRAVEL, field).trim());
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }

    /**
     * Re-install the capture the way the maintainer did - sit in the ship's seat, stand up again -
     * and measure the same drift over the same window. Diagnostic only: any failure to find a seat
     * degrades to text, because this must never be the reason the subject's pin goes red.
     */
    protected String measureAfterReCapture(int dim) {
        try {
            String seat = exec("artest vs seat-mount " + dim);
            if (!readBool(seat, "seatFound")) {
                return "<no seat to re-capture through: " + seat + ">";
            }
            String mount = exec("artest player mount-entity " + readInt(seat, "dummyId"));
            if (!readBool(mount, "mounted")) {
                return "<could not re-seat: " + mount + ">";
            }
            bot().waitTicks(20);
            String dismount = exec("artest player dismount");
            if (!dismount.contains("\"ok\":true")) {
                return "<could not stand up again: " + dismount + ">";
            }
            bot().waitTicks(40);
            // Under MOTION, like the subject window - a re-capture measured on a motionless deck
            // would come back all zeros and could not be compared with anything.
            pulseThrottle();
            double[] deckBefore = awaitShipPose(dim);
            long fromTick = lastClientTick();
            bot().waitTicks(OBSERVE_TICKS);
            double[] deckAfter = awaitShipPose(dim);
            String history = clientTickHistory();
            String moved = deckBefore == null || deckAfter == null
                    ? "deckMoved=<ship not live>" : "deckMoved=" + distance(deckBefore, deckAfter);
            return moved
                    + " bodyTravel=" + bodyPointTravel(history, fromTick)
                    + " heldTravel=" + heldPointTravel(history, fromTick)
                    + " resolved=" + resolvedSince(history, fromTick)
                    + " " + writerSummary(history, fromTick);
        } catch (Exception unavailable) {
            return "<re-capture diagnostic unavailable: " + unavailable + ">";
        }
    }

    // --- arrangement -------------------------------------------------------------------------------

    /**
     * Boot 1, shared by both legs: bring the production subsystem up over the seeded world root, fly
     * ONE piloted ship into space through the real entry on-ramp, walk the real client into the cell
     * production put it in and sit him on its seat. Records the ship id and the cell key production
     * chose, which is everything either leg needs afterwards - the slot dimension deliberately is
     * not, because slot ids are re-minted every boot and must be re-observed rather than remembered.
     *
     * <p>The production subsystem is deliberately left to own the whole stack. There is a probe verb
     * that installs its own entry stack for the server-tier tests, and it must NOT be used here: it
     * replaces the shared manager, ledger and controller wholesale, so the ledger this test's entry
     * would write is a throwaway that no save hook persists - the restart would then find nothing and
     * the test would be red for an arrangement reason. It also mints extra slot dimensions outside
     * the production pool, which is the two-pool conflict the subsystem's harness standdown exists to
     * prevent in the first place. That choice is why the entry is watched through the subsystem's own
     * status and ledger rather than through the entry probe's status verb: the latter reports on that
     * probe-local stack and answers nothing at all when it was never installed.</p>
     *
     * <p>Every step is witnessed as it happens. An arrangement that half-succeeded quietly is the
     * failure mode that makes a two-boot test unattributable: after the restart there is no way left
     * to tell "the restore lost him" from "he was never aboard in the first place".</p>
     */
    protected int seatThePilotAboardHisShip() throws Exception {
        int slotDim = flyOneShipIntoItsCell();

        // The cell holds exactly this one ship, so "the ship nearest anywhere" is unambiguous.
        double[] shipPose = awaitShipPose(slotDim);
        assertNotNull("the settled ship is not live in its own slot dimension " + slotDim, shipPose);

        // Locate the pilot seat inside the re-assembled ship: the seat's SUBSPACE position (what the
        // seat's mount is bound to) and the seat's WORLD position (where the client has to stand).
        // Polled, not sampled once. The seat is searched from the ship's live pose, and a ship that
        // has just been re-assembled in its cell can still be settling when the ledger already calls
        // it SETTLED - so both the anchor and the shipyard's queryability lag by a few ticks. A single
        // shot here fails intermittently, and it fails in the ARRANGEMENT, which is the most expensive
        // kind of red: it looks like the subject broke.
        String seat = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            seat = exec("artest vs find-seat " + slotDim
                    + " " + (int) Math.round(shipPose[0])
                    + " " + (int) Math.round(shipPose[1])
                    + " " + (int) Math.round(shipPose[2]));
            if (readBool(seat, "seatFound")) {
                break;
            }
            bot().waitTicks(10);
            double[] livePose = awaitShipPose(slotDim);
            if (livePose != null) {
                shipPose = livePose;
            }
        }
        assertTrue("the pilot seat must survive the crossing and be locatable in the settled ship - "
                + "without a seat there is nothing to be restored into: " + seat,
                readBool(seat, "seatFound"));
        int seatX = readInt(seat, "seatX");
        int seatY = readInt(seat, "seatY");
        int seatZ = readInt(seat, "seatZ");

        String enter = exec("artest space enter " + BOT + " " + slotDim
                + " " + readDouble(seat, "shipWorldX")
                + " " + readDouble(seat, "shipWorldY")
                + " " + readDouble(seat, "shipWorldZ"));
        assertTrue("the client must be transferred into the ship's cell: " + enter,
                readBool(enter, "ok"));
        bot().waitTicks(20);
        assertEquals("the client must have followed into the ship's slot dimension - otherwise "
                + "everything below is arranging on the wrong side of a dimension boundary",
                slotDim, clientDim());

        String mountAt = exec("artest vs seat-mount-at " + slotDim
                + " " + seatX + " " + seatY + " " + seatZ);
        assertTrue("the pilot seat's mount must exist: " + mountAt, readBool(mountAt, "ok"));
        String mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
        assertTrue("the client must take the pilot seat: " + mount, readBool(mount, "mounted"));
        bot().waitTicks(10);

        assertTrue("the CLIENT must confirm it is seated BEFORE the restart, or 'seated afterwards' "
                + "is not an observation about the restore at all: " + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());

        // The SERVER's own view, taken here as well as just before the logout: these two samples
        // bracket the window in which the entity can drift back out of the cell, so a failure says
        // WHICH side of the mount lost him instead of merely that he was lost.
        String serverAfterMount = exec("artest player position-of " + BOT);
        assertEquals("the SERVER must agree the pilot is in the slot dimension right after he sits "
                        + "down - if it does not, the client and the server disagree from the very "
                        + "start and nothing downstream is measuring the restore: " + serverAfterMount,
                slotDim, readInt(serverAfterMount, "playerDim"));

        String tag = awaitTagged();
        assertTrue("sitting down must leave a durable aboard record - it is the only thing that "
                + "carries the pilot's ship across the restart: " + tag,
                tag.contains("\"tagged\":true"));
        assertTrue("and that record must name the ship the entry minted, not some other one: " + tag
                + " (entered ship " + arrangedShipId + ")", tag.contains(arrangedShipId));
        return slotDim;
    }

    /**
     * The half of the arrangement that has nothing to do with the pilot: bring the production
     * subsystem up over the seeded world root and fly ONE piloted ship into space through the real
     * entry on-ramp, leaving the client wherever it started. Records the ship id and the cell key
     * production chose and returns the slot dimension the ship settled in.
     *
     * <p>Shared so that the leg where nobody ever boards runs the SAME world, the same entry and the
     * same ledger as the legs where somebody does - which is what makes it a witness for their
     * oracles rather than a different experiment.</p>
     */
    protected int flyOneShipIntoItsCell() throws Exception {
        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        assumeProductionSubsystemAvailable();

        String status = exec("artest space subsystem-status");
        assertTrue("the production space subsystem must be live on boot 1 (that is what the seeded "
                + "config opt-in is for) - without it this test would silently assert nothing: "
                + status, status.contains("\"registered\":true"));
        // CONTROL (witness sensitivity): no ship is ledgered before the climb, so a ledgered ship
        // afterwards is an observation about the entry and not about a pre-existing record.
        assertEquals("no ship may be ledgered before the flight: " + status,
                0, readInt(status, "ledger"));

        // Headless: nothing holds a freshly assembled or freshly crossed ship loaded between calls.
        exec("artest vs permaload true");

        startClient();
        bot().waitForWorld();

        // The entry sends the ship to the launch body's own address. Resolve it first: a launch dim
        // that resolves to no cell would send the ship to the configured home anchor instead, which
        // is a different arrangement than the one this test believes it is running.
        String launch = exec("artest space launch-cell " + LAUNCH_DIM);
        assertTrue("the launch dimension must resolve to a galactic address: " + launch,
                launch.contains("\"ok\":true") && !launch.contains("\"cellKey\":null"));

        // Build a PILOTED tier-2 ship on the ground and assemble it with the real assembler - which
        // is what mints the durable ship id the aboard record and the ledger are both keyed by.
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, VARIANT);
        String assembled = exec("artest rocket assemble " + LAUNCH_DIM + " " + coords);
        assertTrue("a build carrying a flight computer must become a ship, not a rocket: " + assembled,
                assembled.contains("\"rocketCount\":0"));
        assertTrue("the ship never assembled in the launch dimension",
                waitForLoadedShip(LAUNCH_DIM) >= 1);

        String srcInfo = exec("artest vs ship-info " + LAUNCH_DIM
                + " " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("the assembled build is not a physics ship: " + srcInfo,
                srcInfo.contains("\"managed\":true"));
        int sx = (int) Math.round(readDouble(srcInfo, "posX"));
        int sy = (int) Math.round(readDouble(srcInfo, "posY"));
        int sz = (int) Math.round(readDouble(srcInfo, "posZ"));

        // A held throttle on the ship's flight input is what makes its computer see a pilot flying;
        // the climb past the dimension's orbit ceiling is what makes it call for an entry.
        exec("artest vs ff-input " + HELD_CLIMB);
        String climb = exec("artest vs teleport-ship " + LAUNCH_DIM + " " + sx + " " + sy + " " + sz
                + " " + sx + " " + ABOVE_CEILING_Y + " " + sz);
        assertTrue("the climb past the orbit ceiling failed: " + climb, climb.contains("\"ok\":true"));
        exec("artest vs unpark " + LAUNCH_DIM + " " + sx + " " + ABOVE_CEILING_Y + " " + sz);

        // The flight computer's own tick now runs the entry: it crosses the ship into the launch
        // body's cell and, on completion, settles it in the ledger. Nothing here drives it.
        String ledgerStatus = "";
        boolean settled = false;
        for (int attempt = 0; attempt < 160 && !settled; attempt++) {
            bot().waitTicks(5);
            ledgerStatus = exec("artest space subsystem-status");
            settled = readIntOr(ledgerStatus, "ledger", 0) >= 1;
        }
        assertTrue("the ship never entered space through the flight computer's own tick; last "
                + "subsystem status=" + ledgerStatus, settled);

        // Let go of the throttle now that the ship is where it was flying to. The flight input is a
        // single server-wide channel and the flight computer keeps applying it in the cell as well -
        // only the ENTRY trigger is restricted to planet-side - so a throttle left held would fly the
        // parked ship for the rest of the arrangement and turn every position read below into a
        // moving target. A pilot who is about to sit still and log out is not holding it down.
        exec("artest vs ff-input " + HANDS_OFF);

        // Find the slot the entry bound the cell to. Slot ids are minted per boot, so they are read
        // rather than known: the one slot dimension whose settled ship's flight computer resolves is
        // the ship's own. An entry that ended up ABANDONED settles the ledger too but leaves the
        // ship at its paste site rather than at its cell pose, and then nothing resolves here - which
        // is the right way for that outcome to surface.
        String[] slot = awaitSettledShipSlot();
        assertNotNull("the ledger holds a ship, but no slot dimension owns up to it - the entry "
                + "settled without leaving a workable ship at its cell pose", slot);
        int slotDim = Integer.parseInt(slot[0]);
        arrangedShipId = slot[1];

        String ledgerEntry = exec("artest space ledger-get " + arrangedShipId);
        assertTrue("the entered ship must be in the production ledger: " + ledgerEntry,
                ledgerEntry.contains("\"found\":true"));
        assertEquals("and it must be settled, not mid-jump: " + ledgerEntry,
                "SETTLED", readString(ledgerEntry, "state"));
        arrangedCellKey = readString(ledgerEntry, "cell");
        assertNotNull("the ledger reported no cell for the entered ship: " + ledgerEntry,
                arrangedCellKey);

        return slotDim;
    }

    /**
     * Boot 1 for the planet-boarded leg: same ship, same entry, but the pilot is IN THE SEAT before
     * the ship ever leaves the ground, and the crossing has to bring him along. Returns the slot
     * dimension he ends up banked in, and records the ship id and cell key production chose.
     *
     * <p>The two record readings around the flight are the point of the arrangement, not decoration.
     * On the ground the record must be ABSENT - being aboard means being aboard a ship in a cell, and
     * a planet-side seat is not that. After the arrival it must be PRESENT. Together they say the
     * record was produced BY the flight, which is the claim a green restart leg would otherwise be
     * unable to distinguish from "it was there all along".</p>
     */
    protected int seatThePilotBeforeHeLeavesTheGround() throws Exception {
        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        assumeProductionSubsystemAvailable();

        String status = exec("artest space subsystem-status");
        assertTrue("the production space subsystem must be live on boot 1: " + status,
                status.contains("\"registered\":true"));
        assertEquals("no ship may be ledgered before the flight: " + status,
                0, readInt(status, "ledger"));

        exec("artest vs permaload true");
        startClient();
        bot().waitForWorld();

        String launch = exec("artest space launch-cell " + LAUNCH_DIM);
        assertTrue("the launch dimension must resolve to a galactic address: " + launch,
                launch.contains("\"ok\":true") && !launch.contains("\"cellKey\":null"));

        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, VARIANT);
        String assembled = exec("artest rocket assemble " + LAUNCH_DIM + " " + coords);
        assertTrue("a build carrying a flight computer must become a ship, not a rocket: " + assembled,
                assembled.contains("\"rocketCount\":0"));
        assertTrue("the ship never assembled in the launch dimension",
                waitForLoadedShip(LAUNCH_DIM) >= 1);

        String srcInfo = exec("artest vs ship-info " + LAUNCH_DIM
                + " " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("the assembled build is not a physics ship: " + srcInfo,
                srcInfo.contains("\"managed\":true"));
        int sx = (int) Math.round(readDouble(srcInfo, "posX"));
        int sy = (int) Math.round(readDouble(srcInfo, "posY"));
        int sz = (int) Math.round(readDouble(srcInfo, "posZ"));

        // Board on the ground. The client has to be standing at the ship for its seat to be a loaded
        // tile at all, which is what the mount probe searches.
        exec("tp @a " + (SRC_X + 0.5) + " " + (SRC_Y + 6) + " " + (SRC_Z + 0.5) + " 0 0");
        bot().waitTicks(20);
        String seatMount = exec("artest vs seat-mount " + LAUNCH_DIM);
        assertTrue("the ground-side pilot seat must offer a mount: " + seatMount,
                readBool(seatMount, "seatFound"));
        String mount = exec("artest player mount-entity " + readInt(seatMount, "dummyId"));
        assertTrue("the client must take the pilot seat while still on the ground: " + mount,
                readBool(mount, "mounted"));
        bot().waitTicks(10);
        assertTrue("the CLIENT must confirm it is seated on the ground: " + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());

        // CONTROL (the record's meaning): a seat on a planet is not "aboard". If this already reads
        // tagged, the post-arrival reading below proves nothing about the flight.
        String groundTag = exec("artest space aboard-tag " + BOT);
        assertTrue("a pilot sitting on a planet must NOT yet carry an aboard record - the record "
                        + "means 'aboard a ship in a cell', and reading it as set here would make the "
                        + "post-arrival reading vacuous: " + groundTag,
                groundTag.contains("\"tagged\":false"));

        // Fly, with him in the chair the whole way.
        exec("artest vs ff-input " + HELD_CLIMB);
        String climb = exec("artest vs teleport-ship " + LAUNCH_DIM + " " + sx + " " + sy + " " + sz
                + " " + sx + " " + ABOVE_CEILING_Y + " " + sz);
        assertTrue("the climb past the orbit ceiling failed: " + climb, climb.contains("\"ok\":true"));
        exec("artest vs unpark " + LAUNCH_DIM + " " + sx + " " + ABOVE_CEILING_Y + " " + sz);
        bot().waitTicks(20);
        assertTrue("ARRANGEMENT: the pilot must still be in his seat as the ship reaches the ceiling "
                        + "- if the lift alone unseats him this leg never tests the crossing: "
                        + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());

        String ledgerStatus = "";
        boolean settled = false;
        for (int attempt = 0; attempt < 160 && !settled; attempt++) {
            bot().waitTicks(5);
            ledgerStatus = exec("artest space subsystem-status");
            settled = readIntOr(ledgerStatus, "ledger", 0) >= 1;
        }
        assertTrue("the ship never entered space through the flight computer's own tick; last "
                + "subsystem status=" + ledgerStatus, settled);
        exec("artest vs ff-input " + HANDS_OFF);

        String[] slot = awaitSettledShipSlot();
        assertNotNull("the ledger holds a ship, but no slot dimension owns up to it", slot);
        int slotDim = Integer.parseInt(slot[0]);
        arrangedShipId = slot[1];

        String ledgerEntry = exec("artest space ledger-get " + arrangedShipId);
        assertTrue("the entered ship must be in the production ledger: " + ledgerEntry,
                ledgerEntry.contains("\"found\":true"));
        assertEquals("and it must be settled, not mid-jump: " + ledgerEntry,
                "SETTLED", readString(ledgerEntry, "state"));
        arrangedCellKey = readString(ledgerEntry, "cell");
        assertNotNull("the ledger reported no cell for the entered ship: " + ledgerEntry,
                arrangedCellKey);

        // He rode his own ship across the seam: no probe transferred him, so a wrong dimension here
        // is the crossing failing to carry its crew, not an arrangement that walked him somewhere.
        int dim = NO_CLIENT_WORLD;
        for (int attempt = 0; attempt < 40 && dim != slotDim; attempt++) {
            bot().waitTicks(10);
            dim = clientDim();
        }
        assertEquals("the pilot must arrive in his ship's slot dimension by riding it there",
                slotDim, dim);
        assertTrue("and he must still be seated after the crossing: " + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());

        String serverAfterArrival = exec("artest player position-of " + BOT);
        assertEquals("the SERVER must agree he is in the slot dimension after the crossing: "
                + serverAfterArrival, slotDim, readInt(serverAfterArrival, "playerDim"));

        // THE SUBJECT: he never sat down in a cell, so if the record is written only by the mount
        // transition there is nothing here - and the restart leg that follows would then put him back
        // at his overworld build site, which is precisely the played-through report.
        String tag = "";
        for (int attempt = 0; attempt < 40 && !tag.contains("\"tagged\":true"); attempt++) {
            tag = exec("artest space aboard-tag " + BOT);
            if (!tag.contains("\"tagged\":true")) {
                bot().waitTicks(5);
            }
        }
        assertTrue("a pilot who boarded on the ground and rode his ship into a cell must carry the "
                        + "durable aboard record - it is the only evidence the restore has that he "
                        + "was ever aboard: " + tag, tag.contains("\"tagged\":true"));
        assertTrue("and that record must name the ship the entry minted: " + tag
                + " (entered ship " + arrangedShipId + ")", tag.contains(arrangedShipId));
        return slotDim;
    }

    /**
     * Copy the server's log aside under {@code label}. Each boot reopens {@code logs/latest.log} from
     * scratch, so without this the first boot's record - the only place that says what happened to the
     * pilot before he was written to disk - is destroyed by the second boot.
     */
    protected void keepBootLog(String label) {
        try {
            java.nio.file.Path live = root.resolve("logs").resolve("latest.log");
            if (java.nio.file.Files.exists(live)) {
                java.nio.file.Files.copy(live, root.resolve("logs").resolve(label + ".log"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            // The player file too: the SECOND boot's own logout rewrites it, so by the time anyone
            // inspects the world directory afterwards, what the FIRST boot persisted - the actual
            // input to the restore - is already gone.
            java.nio.file.Path live_pd = root.resolve("world").resolve("playerdata");
            java.nio.file.Path kept = root.resolve("world").resolve("playerdata-" + label);
            if (java.nio.file.Files.isDirectory(live_pd)) {
                java.nio.file.Files.createDirectories(kept);
                try (java.util.stream.Stream<java.nio.file.Path> files =
                             java.nio.file.Files.list(live_pd)) {
                    for (java.nio.file.Path f : files.collect(java.util.stream.Collectors.toList())) {
                        java.nio.file.Files.copy(f, kept.resolve(f.getFileName().toString()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception ignored) {
            // Diagnostics only - never fail a test because a log could not be copied.
        }
    }

    /**
     * The production subsystem only registers when Valkyrien Skies is present - without tier-2 ships
     * there is nothing for it to host, so it deliberately declines. The wiring under test would not
     * exist, hence a skip rather than a failure.
     */
    protected void assumeProductionSubsystemAvailable() throws Exception {
        String vs = exec("artest vs available");
        Assume.assumeTrue("Valkyrien Skies absent - the production space subsystem declines to "
                + "register without it; run with -PwithVS: " + vs, vs.contains("\"available\":true"));
    }

    // --- lifecycle ---------------------------------------------------------------------------------

    /** Start the client against the live server, never leaking the server JVM if the client fails. */
    protected void startClient() throws Exception {
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startupFailure) {
            try {
                serverHarness.close();
            } catch (Exception cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            serverHarness = null;
            throw startupFailure;
        }
    }

    /** Client first, then server: reversing the order leaks or hangs. Safe to call when nothing runs. */
    protected void closeBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception clientFailure) {
                deferred = clientFailure;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception serverFailure) {
                if (deferred == null) {
                    deferred = serverFailure;
                } else {
                    deferred.addSuppressed(serverFailure);
                }
            }
            serverHarness = null;
        }
        if (deferred != null) {
            throw deferred;
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    protected String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    protected ClientBot bot() {
        return clientHarness.bot();
    }

    /**
     * Hold {@code key} until the client-rendered rider altitude climbs {@link #MIN_CLIMB} over
     * {@code from} (bounded, early-exit, load-scaled); returns the last observed altitude. Same
     * stimulus/observation pair as the planet-side relog-control pin: the REAL key in, the
     * client's own rendered player altitude out.
     */
    protected double climbWith(int key, double from) throws Exception {
        int budget = (int) (40 * TestTimeouts.factor());
        double last = from;
        bot().holdKey(key);
        try {
            for (int i = 0; i < budget && (last - from) < MIN_CLIMB; i++) {
                bot().waitTicks(5);
                last = clientPlayerY();
            }
        } finally {
            bot().releaseKey(key);
        }
        return last;
    }

    /**
     * The player's aboard record once it exists, or the last reading if it never does. The record is
     * refreshed on a one-second cadence rather than on the mount itself, so every arrangement that
     * asserts "he is now aboard" has to give the writer its second - a single sample taken on the
     * mount tick is a statement about the cadence, not about the record.
     */
    protected String awaitTagged() throws Exception {
        String tag = "";
        for (int attempt = 0; attempt < 40 && !tag.contains("\"tagged\":true"); attempt++) {
            bot().waitTicks(5);
            tag = exec("artest space aboard-tag " + BOT);
        }
        return tag;
    }

    /** The client's own rendered player altitude, or NaN while it has no world/player. */
    protected double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    /**
     * The client's OWN view of which dimension it is in, or {@link #NO_CLIENT_WORLD} while it has no
     * world yet. The weather report is the only client-side dimension oracle there is, and while
     * {@code mc.world} is null it answers with the readiness flag and nothing else - so the flag has
     * to be read before "dim" exists to be read at all.
     */
    protected int clientDim() throws Exception {
        JsonObject weather = bot().reportWeather();
        if (!weather.get("worldReady").getAsBoolean()) {
            return NO_CLIENT_WORLD;
        }
        return weather.get("dim").getAsInt();
    }

    /**
     * The slot dimension holding the settled ship and that ship's id, as
     * {@code [dimensionId, shipId]} - or {@code null} if no slot ever owns up to one. Slot dimension
     * ids are minted fresh on every boot, so they can only be discovered: every registered dimension
     * is asked whether the production ledger has a settled ship there whose flight computer resolves
     * at the cell pose. The re-assembly is asynchronous, so this retries, force-loading the ships of
     * any dimension that answered at all.
     */
    protected String[] awaitSettledShipSlot() throws Exception {
        String dims = exec("artest dim list");
        Matcher list = FORGE_DIMS.matcher(dims);
        assertTrue("could not read the registered dimensions: " + dims, list.find());
        String[] ids = list.group(1).split(",");
        for (int attempt = 0; attempt < 30; attempt++) {
            for (String id : ids) {
                String trimmed = id.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String found = exec("artest space find-afc " + trimmed);
                if (found.contains("\"found\":true")) {
                    return new String[]{trimmed, readShipId(found)};
                }
                if (found.contains("\"found\":false")) {
                    // That dimension is loaded and the ledger is readable there; if the ship is
                    // simply not up yet, queueing its ships is what makes it resolvable.
                    exec("artest vs load-ships " + trimmed);
                }
            }
            bot().waitTicks(5);
        }
        return null;
    }

    /**
     * The live world position of the one ship in {@code dim}, or {@code null} if none is up within
     * the wait. The cell holds exactly one ship, so the nearest ship to any point is that ship.
     */
    protected double[] awaitShipPose(int dim) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            String info = exec("artest vs ship-info " + dim + " 0 0 0");
            if (info.contains("\"managed\":true")) {
                return new double[]{
                        readDouble(info, "posX"), readDouble(info, "posY"), readDouble(info, "posZ")};
            }
            exec("artest vs load-ships " + dim);
            bot().waitTicks(5);
        }
        return null;
    }

    /** Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces the load). */
    protected int waitForLoadedShip(int dim) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (readIntOr(exec("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = readIntOr(exec("artest vs ship-count " + dim), "count", -1);
                if (loaded >= 1) {
                    return loaded;
                }
            }
            bot().waitTicks(5);
        }
        return 0;
    }

    /** Clear the build site so the fixture is not welded to whatever terrain generated there. */
    protected void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + LAUNCH_DIM
                + " " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill " + LAUNCH_DIM
                + " " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    /** Place a fixture build and return its build-controller position, as the assembler wants it. */
    protected String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket " + LAUNCH_DIM
                + " " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher builder = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, builder.find());
        return builder.group(1) + " " + builder.group(2) + " " + builder.group(3);
    }

    protected static String readShipId(String json) {
        Matcher m = SHIP_ID.matcher(json);
        assertTrue("expected a minted ship id in: " + json, m.find());
        return m.group(1);
    }

    protected static int readInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("expected int \"" + key + "\" in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    protected static int readIntOr(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    /**
     * The JSON object out of a probe answer. The harness returns whatever the server printed, and a
     * test-mode server interleaves its own trace lines with the reply - so a numeric read against the
     * raw blob can match a digit from a log line and answer confidently with the wrong value.
     */
    protected static String jsonOf(String answer) {
        // The reply does not arrive on a line of its own: the server prints it THROUGH its logger, so
        // the object is embedded in "[14:40:23] [Server thread/INFO] [advancedrocketry]: {...}". A
        // line-level startsWith("{") therefore never matches and quietly returns the whole blob -
        // which is how a numeric read picks a digit out of a trace line and answers confidently.
        int open = answer.indexOf('{');
        int close = answer.lastIndexOf('}');
        return open >= 0 && close > open ? answer.substring(open, close + 1) : answer;
    }

    protected static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.E\\-]+)").matcher(json);
        assertTrue("expected number \"" + key + "\" in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    protected static String readString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** The pool's slot dimension ids as reported by {@code space subsystem-status}. */
    protected static java.util.List<Integer> slotDimsOf(String json) {
        Matcher m = Pattern.compile("\"slotDims\":\\[([^\\]]*)\\]").matcher(json);
        assertTrue("expected \"slotDims\" in: " + json, m.find());
        java.util.List<Integer> dims = new java.util.ArrayList<Integer>();
        String body = m.group(1).trim();
        if (!body.isEmpty()) {
            for (String part : body.split(",")) {
                dims.add(Integer.valueOf(part.trim()));
            }
        }
        return dims;
    }

    protected static boolean readBool(String json, String key) {
        return Pattern.compile("\"" + key + "\":true").matcher(json).find();
    }

    // --- the per-tick ship-frame record (client side) -----------------------------------------------

    /** A client-side static field as text, or a marked placeholder - never an assertion subject. */
    protected String clientString(String className, String field) throws Exception {
        try {
            return bot().readStaticField(className, field).get("value").getAsString();
        } catch (Exception unavailable) {
            return "<unreadable: " + unavailable.getMessage() + ">";
        }
    }

    /**
     * The client's whole per-tick record, read as ONE field. Sampling the individual statics instead
     * costs a round trip each, which stretches the very timeline being measured and hides everything
     * between the samples.
     */
    protected String clientTickHistory() throws Exception {
        return clientString(SHIP_FRAME_TRAVEL, "tickHistory");
    }

    /** The newest resolved-tick number on record - the mark a window starts from. The record survives
     *  the reconnect, so without this mark the pins would read ticks from before the restart. */
    protected long lastClientTick() throws Exception {
        Matcher m = HISTORY_LINE.matcher(clientTickHistory());
        long last = -1L;
        while (m.find()) {
            last = Long.parseLong(m.group(1));
        }
        return last;
    }

    /**
     * How far the BODY's own ship-frame point travelled along the deck inside the window: from the
     * first tick after {@code fromTick} to the FARTHEST one, not the last, so a body that wanders out
     * and comes back cannot pass.
     */
    protected double bodyPointTravel(String history, long fromTick) {
        return travel(history, fromTick, 3);
    }

    /** The same measure for the point the resolver COMMITS - still for a body someone else pulls. */
    protected double heldPointTravel(String history, long fromTick) {
        return travel(history, fromTick, 6);
    }

    protected double travel(String history, long fromTick, int firstGroup) {
        Matcher m = HISTORY_LINE.matcher(history);
        double[] first = null;
        double worst = 0.0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) <= fromTick) {
                continue;
            }
            double[] point = {Double.parseDouble(m.group(firstGroup)),
                    Double.parseDouble(m.group(firstGroup + 1)),
                    Double.parseDouble(m.group(firstGroup + 2))};
            if (first == null) {
                first = point;
            } else {
                worst = Math.max(worst, alongDeck(first, point));
            }
        }
        return worst;
    }

    /** Ticks the window actually covers - the witness that the pins had something to look at. */
    protected int resolvedSince(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        int n = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) > fromTick) {
                n++;
            }
        }
        return n;
    }

    /**
     * WHO moved the body, as numbers rather than inference. A nonzero incoming ship-relative motion
     * names a VELOCITY writer; a carry that does not match what the deck is doing names the held
     * carry; ticks on the hull path name the capture-mode flip that ledger #108 was; input ticks say
     * the body was not actually idle and the whole window is void.
     */
    protected String writerSummary(String history, long fromTick) {
        Matcher m = HISTORY_LINE.matcher(history);
        double worstMotion = 0.0;
        double worstCarry = 0.0;
        int hull = 0;
        int inputTicks = 0;
        int offDeck = 0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) <= fromTick) {
                continue;
            }
            double mx = Double.parseDouble(m.group(9));
            double mz = Double.parseDouble(m.group(11));
            worstMotion = Math.max(worstMotion, Math.sqrt(mx * mx + mz * mz));
            worstCarry = Math.max(worstCarry, Math.abs(Double.parseDouble(m.group(12))));
            if ("h".equals(m.group(2))) {
                hull++;
            }
            if (Double.parseDouble(m.group(13)) != 0.0 || Double.parseDouble(m.group(14)) != 0.0) {
                inputTicks++;
            }
            if ("0".equals(m.group(15))) {
                offDeck++;
            }
        }
        return "maxShipRelativeMotion=" + worstMotion + " maxCarry=" + worstCarry
                + " hullPathTicks=" + hull + " ticksWithInput=" + inputTicks
                + " ticksOffDeck=" + offDeck;
    }

    /**
     * The largest single-tick step the body's ship-frame point took. A placement jump shows up here
     * and nowhere else, which is what lets the creep total below stay honest about a steady drag.
     */
    protected double worstStep(String history, long fromTick) {
        return stepStat(history, fromTick, false);
    }

    /**
     * The sum of the per-tick steps that fall inside the creep band - the size of a drag, with the
     * one-off jumps of a placement excluded rather than averaged away.
     */
    protected double creepBandTotal(String history, long fromTick) {
        return stepStat(history, fromTick, true);
    }

    protected double stepStat(String history, long fromTick, boolean bandSum) {
        Matcher m = HISTORY_LINE.matcher(history);
        double[] previous = null;
        double worst = 0.0;
        double total = 0.0;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) <= fromTick) {
                continue;
            }
            double[] point = {Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5))};
            if (previous != null) {
                double step = alongDeck(previous, point);
                worst = Math.max(worst, step);
                if (step >= CREEP_STEP_MIN && step <= CREEP_STEP_MAX) {
                    total += step;
                }
            }
            previous = point;
        }
        return bandSum ? total : worst;
    }

    /** Distance in the deck plane: the ship frame's own horizontal, gravity excluded. */
    protected static double alongDeck(double[] a, double[] b) {
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    protected static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
