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
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.nio.charset.StandardCharsets;
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
public class SpaceLoginRestoreClientE2ETest {

    /** The account every client harness launches under; the server keys his player data by it. */
    private static final String BOT = "ForgeTestClient";

    /** Where an orphaned login lands, and the one dimension a restored pilot must NOT be in. */
    private static final int OVERWORLD_DIM = 0;

    /** The launch dimension the ship takes off from - always registered, always terrain-generated. */
    private static final int LAUNCH_DIM = 0;

    /** Where the piloted ship is built: a loaded overworld region well clear of other fixtures. */
    private static final int SRC_X = 6800;
    private static final int SRC_Y = 80;
    private static final int SRC_Z = 6800;

    /** A world height comfortably above the default orbit ceiling, so the ceiling check fires. */
    private static final int ABOVE_CEILING_Y = 1200;

    /**
     * The six flight channels - forward, vertical, strafe, yaw, pitch, roll - as the flight-input
     * probe takes them. They are NOT prefixed by a dimension: the input is one server-wide channel,
     * and reading the leading zero as a dimension id is the trap this constant exists to close.
     * {@link #HELD_CLIMB} is a pilot holding the ship up; {@link #HANDS_OFF} is him letting go.
     */
    private static final String HELD_CLIMB = "0 1 0 0 0 0";
    private static final String HANDS_OFF = "0 0 0 0 0 0";

    /**
     * How far off his ship the client may be and still count as "back at his ship". Covers the
     * seat's offset from the ship's own origin plus a few ticks of settling, and is far too small to
     * be satisfied by any other dimension's spawn.
     */
    private static final double POSE_EPSILON = 24.0D;

    /** Sentinel for "the client has no world yet", so nobody reads a "dim" key that is absent. */
    private static final int NO_CLIENT_WORLD = Integer.MIN_VALUE;

    /**
     * A demonstrable held-key climb: well above settle jitter, cheap to reach. Same bar as the
     * planet-side relog-control pin ({@link VSPilotSeatRelogControlE2ETest}) - the contract is
     * "held input MOVES the ship within a bounded window", not any particular rate.
     */
    private static final double MIN_CLIMB = 1.0;

    private static final Pattern SHIP_ID = Pattern.compile("\"shipId\":\"([^\"]+)\"");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern FORGE_DIMS = Pattern.compile("\"forgeDimensions\":\\[([^\\]]*)]");

    /** The ship production minted for the arranged pilot, and the cell production settled it in. */
    private String arrangedShipId;
    private String arrangedCellKey;

    private Path root;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

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
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        // Opt the production space subsystem back in under the harness. Written as a whole config
        // file rather than patched in, because on the first boot none exists yet; the mod fills in
        // every other key with its default and preserves this one. Without it the subsystem stands
        // down, nothing persists the ledger, and the whole test would be vacuous.
        String cfg = "# seeded by SpaceLoginRestoreClientE2ETest\n"
                + "performance {\n"
                + "    B:spaceRegisterUnderTestHarness=true\n"
                + "}\n";
        Files.write(arConfigDir.resolve("advancedRocketry.cfg"), cfg.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void stopHarnesses() throws Exception {
        closeBoth();
    }

    /**
     * The pilot logs out seated on his ship and the server is restarted under him. He must come back
     * in his ship's cell and back in his seat.
     */
    @Test
    public void aPilotWhoLoggedOutSeatedOnHisShipComesBackAboardItAfterAServerRestart() throws Exception {
        requireHeComesBackAboardHisShip(seatThePilotAboardHisShip());
    }

    /**
     * The same contract, reached the way a player actually reaches it: the pilot takes his seat ON THE
     * GROUND and is still in it when his ship crosses into the cell. He never sits down inside a space
     * cell at all.
     *
     * <p><b>Why this is a separate leg and not a variant of the arrangement above.</b> The durable
     * aboard record is what the restore runs on, and it was for a long time written only by the mount
     * TRANSITION - so it existed for a pilot who sat down in a cell and did not exist for a pilot who
     * sat down on a planet and flew up. Both pilots are equally aboard, and the leg above cannot tell
     * them apart because its arrangement seats him after the arrival. This one is the route that was
     * broken in real play: fly to orbit, log out, come back standing at the build site you left hours
     * ago, with no message and your ship still in orbit without you.</p>
     *
     * <p>The arrangement is the witness for the mechanism as well as the setup for the restart: it
     * requires the record to be ABSENT while he sits on the planet and PRESENT once he has arrived,
     * so a green here cannot come from a record that was already there before the flight.</p>
     */
    @Test
    public void aPilotWhoBoardedOnThePlanetAndFlewUpComesBackAboardAfterAServerRestart()
            throws Exception {
        requireHeComesBackAboardHisShip(seatThePilotBeforeHeLeavesTheGround());
    }

    /**
     * The shared subject of both positive legs: whatever route put the pilot in his seat in a cell,
     * stopping the server under him must bring him back in that seat, in his ship's cell, with a
     * control chain that still flies. Takes the slot dimension the arrangement banked him in.
     */
    private void requireHeComesBackAboardHisShip(int slotDim) throws Exception {
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
     * A crew member who STANDS UP on his own ship in orbit is still aboard it, and must come back
     * aboard - on his feet, on his own deck, in his ship's cell - not seated, and not at an ordinary
     * spawn.
     *
     * <p>Two contracts meet here and must not be confused. That he comes back NOT SEATED is one: a
     * player who left his post must not be dragged back into it by the next login. That he comes
     * back IN HIS SHIP'S CELL is the other, and it is the one this leg used to pin INVERTED -
     * standing up dropped the durable record entirely, the restore then had no evidence he had ever
     * been aboard, and he woke at his overworld build site with his ship still in orbit without him.
     * This leg is what says that is fixed.</p>
     *
     * <p><b>What replaced this leg's second job.</b> While it asserted "overworld" it also served as
     * the falsifiability witness for the positive legs - the proof that these oracles can answer
     * "not aboard" at all. It cannot do that any more, so the witness now comes from a player who
     * was never aboard in the first place:
     * {@link #aPlayerWhoWasNeverAboardIsNotRestoredOntoTheShip}.</p>
     *
     * <p>Both of the ways this leg could be green for the wrong reason are closed before it looks at
     * the client: the production subsystem must be up on the second boot, and the ship must still be
     * in the ledger.</p>
     */
    @Test
    public void aPilotWhoStoodUpBeforeLoggingOutComesBackAboardOnHisFeet() throws Exception {
        int slotDim = seatThePilotAboardHisShip();

        // Stand up through the production path. The record must SURVIVE it and change SHAPE: he is
        // no longer in a seat, he is on the deck - which is a way of BEING aboard, not of leaving.
        // Polled, because the record is refreshed on a one-second cadence: a single sample taken on
        // the dismount tick reads the shape he had a moment ago and says nothing.
        String dismount = exec("artest player dismount");
        assertTrue("the pilot must leave his seat: " + dismount, dismount.contains("\"ok\":true"));
        String tag = "";
        for (int attempt = 0; attempt < 40 && !tag.contains("\"posture\":\"STANDING\""); attempt++) {
            bot().waitTicks(5);
            tag = exec("artest space aboard-tag " + BOT);
        }
        assertTrue("standing up on his own deck must keep him aboard, as a STANDING record - a "
                + "record dropped here is exactly what used to send him to an ordinary spawn: " + tag,
                tag.contains("\"tagged\":true") && tag.contains("\"posture\":\"STANDING\""));
        assertTrue("and it must still name the ship he is standing on: " + tag
                + " (entered ship " + arrangedShipId + ")", tag.contains(arrangedShipId));
        // He must really be resolved on the DECK, in the ship's own frame, before the restart: that
        // is what produces the record asserted above, and a hull-stand catch is not it.
        String capBefore = exec("artest vs deck-capture");
        assertTrue("ARRANGEMENT: he must be captured ABOARD the deck after standing up, or the record "
                + "above describes something other than a crew member on his feet: " + capBefore,
                capBefore.contains("\"alreadyTracked\":true")
                        && !capBefore.contains("\"hullStand\":true"));

        String serverBeforeLogout = exec("artest player position-of " + BOT);
        assertEquals("the SERVER must still have him in his ship's slot dimension when it writes him "
                + "to disk: " + serverBeforeLogout, slotDim, readInt(serverBeforeLogout, "playerDim"));

        closeBoth();
        keepBootLog("boot1-standing");

        serverHarness = RealDedicatedServerHarness.startWith(root, false);

        // Both discriminators, BEFORE the client connects. Without them a client reading says
        // nothing about the record: a second boot whose subsystem stood down, or whose ledger did
        // not survive the shutdown save, would leave him at an ordinary spawn for its own reasons.
        String statusAfter = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2, or nothing below is "
                + "exercising it: " + statusAfter, statusAfter.contains("\"registered\":true"));
        String ledger = exec("artest space ledger-get " + arrangedShipId);
        assertTrue("his ship must still be ledgered - there has to be a ship to restore him ONTO: "
                + ledger, ledger.contains("\"found\":true"));

        exec("artest vs permaload true");
        startClient();
        bot().waitForWorld();

        // Poll for the end state on the same budget the positive legs use: the deck hold waits for
        // the ship to finish re-assembling before it can place him, and gives up silently after it.
        int dim = NO_CLIENT_WORLD;
        boolean placed = false;
        for (int attempt = 0; attempt < 45 && !placed; attempt++) {
            bot().waitTicks(10);
            dim = clientDim();
            placed = dim != NO_CLIENT_WORLD && dim != OVERWORLD_DIM;
        }
        JsonObject riding = bot().reportRidingEntity();
        JsonObject state = bot().reportState();
        String observed = "clientDim=" + dim + " riding=" + riding + " state=" + state;

        assertTrue("the client must have a world at all before anything can be read from it: "
                + observed, dim != NO_CLIENT_WORLD);
        assertFalse("a pilot who stood up must NOT be re-seated on the ship he left: " + observed,
                riding.get("riding").getAsBoolean());
        assertNotEquals("he stood up ON HIS OWN SHIP in orbit, which is a way of BEING aboard - so he "
                + "must not come back at an ordinary spawn. Note dim 0 is an AMBIGUOUS failure: "
                + "vanilla also forces it when the target world did not load, so attribute a red here "
                + "from the server's login-restore log line. " + observed, OVERWORLD_DIM, dim);

        // And he must be back ON his ship rather than merely in its cell: the deck hold puts the body
        // on the stored deck point, so his client-rendered position has to be at the ship.
        double[] shipPose = awaitShipPose(dim);
        assertNotNull("his ship must be live in the dimension he came back to: " + observed, shipPose);
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
        observed = "clientDim=" + dim + " state=" + state + " shipPose=[" + shipPose[0] + ","
                + shipPose[1] + "," + shipPose[2] + "]";
        assertEquals("he must come back at his ship on X: " + observed,
                shipPose[0], clientX, POSE_EPSILON);
        assertEquals("he must come back at his ship on Y: " + observed,
                shipPose[1], clientY, POSE_EPSILON);
        assertEquals("he must come back at his ship on Z: " + observed,
                shipPose[2], clientZ, POSE_EPSILON);

        // And that position must realize a coordinate inside his ship's own ledgered cell - the check
        // "not the overworld" cannot make, since an ordinary block height in the right slot world
        // would still pass it.
        GalacticCoord cell = GalacticCoord.fromCellKey(arrangedCellKey);
        assertNotNull("the ledger reported an unreadable cell key: " + arrangedCellKey, cell);
        GalacticCoord realized = CellWorldMapper.coordOfPose(cell, clientX, clientY, clientZ);
        assertTrue("the client's position must realize a coordinate in his ship's own cell "
                + arrangedCellKey + ", but it maps to " + realized.cellKey() + ": " + observed,
                realized.sameCell(cell));
    }

    /**
     * THE FALSIFIABILITY WITNESS for every positive leg in this class, and the reason a green
     * "he is aboard" means anything at all: the same world, the same entry, the same ship in the
     * same cell, the same two boots and the same oracles - but a player who never boarded.
     *
     * <p>He must come back where vanilla would put him, off the ship and carrying no record. Without
     * a leg that comes back negative through these very oracles, "he is aboard his ship" could
     * equally be produced by an oracle that answers "aboard" unconditionally, or by a restore that
     * drags every logging-in player to the nearest ship. This job used to belong to the standing
     * pilot's leg; it stopped being able to do it the moment a standing crew member was correctly
     * restored aboard.</p>
     */
    @Test
    public void aPlayerWhoWasNeverAboardIsNotRestoredOntoTheShip() throws Exception {
        flyOneShipIntoItsCell();

        // The client never went near the ship. The record must be absent BEFORE the restart too -
        // that is what makes the reading after it attributable to the restore rather than to a
        // record that was never written in the first place.
        String tagBefore = exec("artest space aboard-tag " + BOT);
        assertTrue("a player who never boarded must carry no aboard record: " + tagBefore,
                tagBefore.contains("\"tagged\":false"));

        closeBoth();
        keepBootLog("boot1-never-aboard");

        serverHarness = RealDedicatedServerHarness.startWith(root, false);

        // The same two discriminators the other legs establish, so this leg differs from them in
        // exactly one thing: whether the player was ever aboard.
        String statusAfter = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2: " + statusAfter,
                statusAfter.contains("\"registered\":true"));
        String ledger = exec("artest space ledger-get " + arrangedShipId);
        assertTrue("the ship must still be ledgered - a restore with nothing to restore ONTO would "
                + "leave him in the overworld for the wrong reason: " + ledger,
                ledger.contains("\"found\":true"));

        exec("artest vs permaload true");
        startClient();
        bot().waitForWorld();
        bot().waitTicks(450);

        int dim = clientDim();
        JsonObject riding = bot().reportRidingEntity();
        String tag = exec("artest space aboard-tag " + BOT);
        String observed = "clientDim=" + dim + " riding=" + riding + " tag=" + tag;

        assertTrue("the client must have a world at all before anything can be read from it: "
                + observed, dim != NO_CLIENT_WORLD);
        assertEquals("a player who was never aboard must come back where vanilla puts him, not in "
                + "the ship's cell: " + observed, OVERWORLD_DIM, dim);
        assertFalse("and he must not be seated on a ship he never boarded: " + observed,
                riding.get("riding").getAsBoolean());
        assertTrue("and the record oracle must still answer NO for him - if it cannot, every "
                + "\"tagged\":true in this class is worthless: " + observed,
                tag.contains("\"tagged\":false"));
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
    private int seatThePilotAboardHisShip() throws Exception {
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
    private int flyOneShipIntoItsCell() throws Exception {
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
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
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
    private int seatThePilotBeforeHeLeavesTheGround() throws Exception {
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
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
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
    private void keepBootLog(String label) {
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
    private void assumeProductionSubsystemAvailable() throws Exception {
        String vs = exec("artest vs available");
        Assume.assumeTrue("Valkyrien Skies absent - the production space subsystem declines to "
                + "register without it; run with -PwithVS: " + vs, vs.contains("\"available\":true"));
    }

    // --- lifecycle ---------------------------------------------------------------------------------

    /** Start the client against the live server, never leaking the server JVM if the client fails. */
    private void startClient() throws Exception {
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
    private void closeBoth() throws Exception {
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

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private ClientBot bot() {
        return clientHarness.bot();
    }

    /**
     * Hold {@code key} until the client-rendered rider altitude climbs {@link #MIN_CLIMB} over
     * {@code from} (bounded, early-exit, load-scaled); returns the last observed altitude. Same
     * stimulus/observation pair as the planet-side relog-control pin: the REAL key in, the
     * client's own rendered player altitude out.
     */
    private double climbWith(int key, double from) throws Exception {
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
    private String awaitTagged() throws Exception {
        String tag = "";
        for (int attempt = 0; attempt < 40 && !tag.contains("\"tagged\":true"); attempt++) {
            bot().waitTicks(5);
            tag = exec("artest space aboard-tag " + BOT);
        }
        return tag;
    }

    /** The client's own rendered player altitude, or NaN while it has no world/player. */
    private double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    /**
     * The client's OWN view of which dimension it is in, or {@link #NO_CLIENT_WORLD} while it has no
     * world yet. The weather report is the only client-side dimension oracle there is, and while
     * {@code mc.world} is null it answers with the readiness flag and nothing else - so the flag has
     * to be read before "dim" exists to be read at all.
     */
    private int clientDim() throws Exception {
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
    private String[] awaitSettledShipSlot() throws Exception {
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
    private double[] awaitShipPose(int dim) throws Exception {
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
    private int waitForLoadedShip(int dim) throws Exception {
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
    private void clearArea(int baseX, int baseZ) throws Exception {
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
    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket " + LAUNCH_DIM
                + " " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher builder = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, builder.find());
        return builder.group(1) + " " + builder.group(2) + " " + builder.group(3);
    }

    private static String readShipId(String json) {
        Matcher m = SHIP_ID.matcher(json);
        assertTrue("expected a minted ship id in: " + json, m.find());
        return m.group(1);
    }

    private static int readInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("expected int \"" + key + "\" in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static int readIntOr(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.E\\-]+)").matcher(json);
        assertTrue("expected number \"" + key + "\" in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static String readString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** The pool's slot dimension ids as reported by {@code space subsystem-status}. */
    private static java.util.List<Integer> slotDimsOf(String json) {
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

    private static boolean readBool(String json, String key) {
        return Pattern.compile("\"" + key + "\":true").matcher(json).find();
    }
}
