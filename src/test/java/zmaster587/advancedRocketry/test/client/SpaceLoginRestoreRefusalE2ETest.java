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
 * The two legs where the restore must NOT put anybody on a ship: the pilot whose ship the server no
 * longer knows (he is told so, in his own chat, and lands somewhere survivable), and the player who
 * was never aboard at all (nothing happens to him).
 *
 * <p>These are the control legs of the restore. Without them, "everybody ends up on a ship" passes
 * the positive legs just as well as a working restore does.</p>
 *
 * <p>See {@link AbstractSpaceLoginRestoreClientTest} for the shared fixture, and for why the class
 * was split into three.</p>
 */
public class SpaceLoginRestoreRefusalE2ETest extends AbstractSpaceLoginRestoreClientTest {

    /**
     * The other end of the restore: when the server genuinely has no record of a returning pilot's
     * ship, he is TOLD so — not silently stood up at his spawn point wondering where his ship went.
     *
     * <p>This is a client test for the only reason that matters: the subject is a line of text a
     * player reads. The server can be asked whether it decided he was orphaned; it cannot be asked
     * whether he was informed. The bot's own chat log can.</p>
     *
     * <p><b>Why the ship is removed rather than the ledger damaged.</b> "The ledger has no such ship"
     * is one verdict reached from several directions — a ship dismantled while its owner was away, a
     * descent that took it out of the subsystem, a durable record that failed to come back. The
     * player's side of it is identical in every case, and the removal is the one direction that is
     * both production behaviour and arrangeable, so the arrangement asserts the ledger KNEW the ship
     * first: a "he was told his ship is missing" that came from a ship the server never had would be
     * a statement about the fixture.</p>
     *
     * <p>A relog is enough and is deliberate — the decision is made when a player's save file is read,
     * which a rejoin does as faithfully as a reboot, and it keeps the ship, the cell and the ledger in
     * one server's lifetime where the arrangement can still speak about them.</p>
     */
    @Test
    public void aPilotWhoseShipTheServerNoLongerKnowsIsToldSoWhenHeComesBack() throws Exception {
        seatThePilotAboardHisShip();

        // The notice must not already be on screen, or "it is there afterwards" says nothing.
        assertNull("nothing may have told him about a missing ship before one went missing: "
                + bot().reportChat(20), chatLineContaining(SHIP_LOST_NEEDLE));

        String forgot = exec("artest space ledger-forget " + arrangedShipId);
        assertTrue("arrangement: the ledger must have KNOWN this ship before being told to forget it - "
                + "otherwise the login below is about a ship that never existed: " + forgot,
                readBool(forgot, "wasKnown"));
        assertFalse("arrangement: and it must not know it afterwards: " + forgot,
                readBool(forgot, "found"));

        bot().reconnect();
        bot().waitForWorld();
        bot().waitTicks(40);

        String told = chatLineContaining(SHIP_LOST_NEEDLE);
        assertNotNull("a pilot whose ship the server cannot find must be TOLD, in chat, rather than "
                + "appearing at his spawn point with no explanation: " + bot().reportChat(20)
                + " serverPos=" + exec("artest player position-of " + BOT), told);

        // And he really is the orphan the message describes: out of the cell, off his ship.
        //
        // Placement is read from the SERVER, not from the client's rendered dimension. That is not a
        // convenience: measured here, the two DISAGREE on this path — the server has him in the
        // overworld at his spawn point while the client is still rendering the slot world it was in
        // (ledger #170). That split is a pre-existing property of the orphan path, it is filed, and it
        // is not what this test is about; what this test must not do is read the disagreeing instrument
        // and call the placement broken. `playerDimField` is quoted alongside `playerDim` because they
        // are maintained separately and a placement that moved one but not the other is a real failure
        // this assertion should catch.
        String serverPos = exec("artest player position-of " + BOT);
        JsonObject riding = bot().reportRidingEntity();
        assertEquals("the server must actually have placed him out of the cell, or the message is "
                        + "describing something that did not happen: " + serverPos
                        + " clientRiding=" + riding + " clientRenderedDim=" + clientDim(),
                OVERWORLD_DIM, readInt(serverPos, "playerDim"));
        assertEquals("and his persisted dimension field must agree, or the next login starts from a "
                        + "world he is not in: " + serverPos,
                OVERWORLD_DIM, readInt(serverPos, "playerDimField"));
        assertFalse("and the client agrees he is riding nothing: " + riding + " server=" + serverPos,
                riding.get("riding").getAsBoolean());
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

}
