package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.Test;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Login restore for a pilot who was ABOARD when he left: seated in a cell, seated on the ground
 * before the climb, or standing on his own deck. Each leg logs him out, restarts the server under
 * him, and requires him back on his ship.
 *
 * <p>See {@link AbstractSpaceLoginRestoreClientTest} for why this contract can only be tested on a
 * real client, and for the fixture the three legs share.</p>
 *
 * <p><b>Why this is three classes rather than one.</b> The original
 * {@code SpaceLoginRestoreClientE2ETest} held all seven legs, and every one of them boots a server
 * and a client and then restarts the server — 1032.8 s measured 2026-08-07 at 8 forks, in ONE gradle
 * fork, which made this single class the wall-clock FLOOR of the whole non-VS client tier while
 * seven forks idled. Sharing a harness is not available here: the restart IS the subject. So the
 * legs were split across three classes instead, which is the same optimisation from the other end —
 * the tier's floor is its longest UNIT, and a unit nobody can shorten can still be divided.</p>
 */
public class SpaceLoginRestoreSeatedPilotE2ETest extends AbstractSpaceLoginRestoreClientTest {

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

        // Coming back ON the ship is only half of it: he also has to STAY where he was put. Every
        // position pin above is written at POSE_EPSILON, a tolerance sized to separate "at his ship"
        // from "at a spawn" - three orders of magnitude coarser than a drift a player feels under his
        // own feet, which is why this class was green while play reported exactly that.
        requireHeIsNotDraggedAlongHisDeck(dim);
    }

}
