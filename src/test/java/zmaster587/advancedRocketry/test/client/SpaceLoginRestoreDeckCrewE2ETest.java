package zmaster587.advancedRocketry.test.client;

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
 * Relogging while standing on a ship's deck — with no server restart — must not drag the crew member
 * along it, upright or inverted.
 *
 * <p>The measurement is a no-change control in the same run: the same body, on the same deck, over
 * the same window, with the relog absent. A deck is never perfectly still, so "he moved" is not an
 * observation about the relog until you know what he does when nothing is done to him.</p>
 *
 * <p>See {@link AbstractSpaceLoginRestoreClientTest} for the shared fixture, and for why the class
 * was split into three.</p>
 */
public class SpaceLoginRestoreDeckCrewE2ETest extends AbstractSpaceLoginRestoreClientTest {

    /**
     * THE REPORTED CASE, and it is deliberately NOT the restart case: a crew member standing on his
     * deck logs out and back IN while the server keeps running.
     *
     * <p><b>Why this is a separate leg.</b> The restart leg above measures the same body, the same
     * deck and the same posture and finds the hold exact - so whatever the report is about, a restart
     * does not carry it. A restart wipes every live object: the ship is re-assembled from disk, the
     * slot dimension re-minted, the capture rebuilt from nothing. A plain relog wipes none of that.
     * If two writers are fighting over where a restored body belongs, the restart is the arrangement
     * that destroys the fight before it can be observed, and this is the one that keeps it.</p>
     *
     * <p>The slot dimension is asserted UNCHANGED here, unlike across a restart: without a reboot the
     * pool does not re-mint its ids, so a different slot would mean something moved his ship, not
     * that the ids churned.</p>
     */
    @Test
    public void aCrewMemberWhoRelogsWithoutARestartIsNotDraggedAlongHisDeck() throws Exception {
        int slotDim = seatThePilotAboardHisShip();

        // The posture the report is about: on his feet, on his own deck.
        String dismount = exec("artest player dismount");
        assertTrue("the pilot must leave his seat: " + dismount, dismount.contains("\"ok\":true"));
        String tag = "";
        for (int attempt = 0; attempt < 40 && !tag.contains("\"posture\":\"STANDING\""); attempt++) {
            bot().waitTicks(5);
            tag = exec("artest space aboard-tag " + BOT);
        }
        assertTrue("ARRANGEMENT: standing up must keep him aboard as a STANDING record: " + tag,
                tag.contains("\"tagged\":true") && tag.contains("\"posture\":\"STANDING\""));
        String capBefore = exec("artest vs deck-capture");
        assertTrue("ARRANGEMENT: he must be captured ABOARD the deck before the relog, or the leg is "
                        + "not about a restored deck capture at all: " + capBefore,
                capBefore.contains("\"alreadyTracked\":true")
                        && !capBefore.contains("\"hullStand\":true"));

        // A REAL logout that leaves the world running. The client has no world to wait ticks in while
        // it is away, so the offline window is polled from the server side.
        bot().disconnect();
        String offline = "";
        boolean gone = false;
        for (int attempt = 0; attempt < 40 && !gone; attempt++) {
            Thread.sleep(250);
            offline = exec("artest player position-of " + BOT);
            gone = offline.contains("\"error\":\"no such player\"")
                    || offline.contains("\"error\":\"no players connected\"");
        }
        assertTrue("ARRANGEMENT: the server must see him GONE after the disconnect, or nothing below "
                + "is a relog: " + offline, gone);

        // Nobody is left near the ship to hold its chunks while he is away.
        exec("artest vs permaload true");

        bot().connect();
        bot().waitForWorld();
        int dim = NO_CLIENT_WORLD;
        for (int attempt = 0; attempt < 45 && (dim == NO_CLIENT_WORLD || dim == OVERWORLD_DIM);
                attempt++) {
            bot().waitTicks(10);
            dim = clientDim();
        }
        assertEquals("he relogged while standing on his ship in its cell, and no reboot re-minted the "
                        + "pool, so he must come back in the very same slot dimension: clientDim="
                        + dim + " riding=" + bot().reportRidingEntity(),
                slotDim, dim);

        requireHeIsNotDraggedAlongHisDeck(dim);
    }

    /**
     * The same relog, on an INVERTED deck - the attitude the report actually comes from.
     *
     * <p><b>Why the attitude is not decoration.</b> This path is governed by the any-attitude crew
     * contract: gravity is projected along the DECK normal rather than world -Y, the floor search looks
     * below the body's feet in the SHIP frame, the aboard/hull-stand classification depends on contact
     * orientation, and the deck-plane axes change sign. An upright fixture cannot exhibit an
     * attitude-dependent defect at all - which is why fourteen upright runs of the leg above could not,
     * and why "it did not reproduce" was a statement about the arrangement, not about the code.</p>
     *
     * <p>The ship is rolled while he is ALREADY captured on the deck, so the capture carries his deck
     * spot through the roll and leaves him standing on the deck of an inverted ship - hanging under the
     * hull in world terms - the same way the planet-side inverted leg arranges it. The inversion is
     * established BEFORE the logout, on the assumption that the ship was already inverted when he left;
     * "inverted while he was away" is a different arrangement and would need its own leg.</p>
     */
    @Test
    public void aCrewMemberWhoRelogsOnAnInvertedDeckIsNotDraggedAlongIt() throws Exception {
        int slotDim = seatThePilotAboardHisShip();

        String dismount = exec("artest player dismount");
        assertTrue("the pilot must leave his seat: " + dismount, dismount.contains("\"ok\":true"));
        String tag = "";
        for (int attempt = 0; attempt < 40 && !tag.contains("\"posture\":\"STANDING\""); attempt++) {
            bot().waitTicks(5);
            tag = exec("artest space aboard-tag " + BOT);
        }
        assertTrue("ARRANGEMENT: standing up must keep him aboard as a STANDING record: " + tag,
                tag.contains("\"tagged\":true") && tag.contains("\"posture\":\"STANDING\""));
        assertTrue("ARRANGEMENT: he must be captured on the deck while the ship is still upright: "
                        + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        // Roll the ship to (near-)inverted UNDER him, through the ROLL CHANNEL of the flight input.
        // The attitude-target verb was tried first and does not survive here: this arrangement leaves a
        // held all-zero flight input behind after the climb, and the flight computer re-commands "hold
        // the current attitude" from it every tick, so a commanded target is cancelled before it turns
        // anything (measured: three runs, upY stayed exactly 1.0 while the verb answered
        // commanded=true). The planet-side leg never publishes a flight input at all, which is why the
        // same verb works there. Rolling through the input is also the way a pilot actually rolls.
        double[] pose = awaitShipPose(slotDim);
        assertNotNull("the ship must be live to be rolled", pose);
        exec("artest vs ff-input 0 0 0 0 0 1");
        double upY = 1.0;
        for (int attempt = 0; attempt < 40 && upY > -0.9; attempt++) {
            bot().waitTicks(10);
            // upY from the quat: for a roll about X (qy=qz=0), upY = 1 - 2*qx^2.
            double qx = readDouble(jsonOf(exec("artest vs ship-info " + slotDim + " 0 0 0")), "qx");
            upY = 1.0 - 2.0 * qx * qx;
        }
        exec("artest vs ff-input " + HANDS_OFF);
        bot().waitTicks(20);
        String info = jsonOf(exec("artest vs ship-info " + slotDim + " 0 0 0"));
        assertTrue("ARRANGEMENT: the ship must be (near-)inverted before the relog, or this leg is "
                + "silently the upright one again (upY=" + upY + "): " + info, upY < -0.9);
        String capInverted = exec("artest vs deck-capture");
        assertTrue("ARRANGEMENT: he must still be captured on the INVERTED deck: " + capInverted,
                capInverted.contains("\"alreadyTracked\":true"));

        bot().disconnect();
        String offline = "";
        boolean gone = false;
        for (int attempt = 0; attempt < 40 && !gone; attempt++) {
            Thread.sleep(250);
            offline = exec("artest player position-of " + BOT);
            gone = offline.contains("\"error\":\"no such player\"")
                    || offline.contains("\"error\":\"no players connected\"");
        }
        assertTrue("ARRANGEMENT: the server must see him GONE after the disconnect: " + offline, gone);

        exec("artest vs permaload true");
        bot().connect();
        bot().waitForWorld();
        int dim = NO_CLIENT_WORLD;
        for (int attempt = 0; attempt < 45 && (dim == NO_CLIENT_WORLD || dim == OVERWORLD_DIM);
                attempt++) {
            bot().waitTicks(10);
            dim = clientDim();
        }
        assertEquals("he relogged standing on his INVERTED ship in its cell: clientDim=" + dim
                        + " riding=" + bot().reportRidingEntity(),
                slotDim, dim);

        requireHeIsNotDraggedAlongHisDeck(dim);
    }

}
