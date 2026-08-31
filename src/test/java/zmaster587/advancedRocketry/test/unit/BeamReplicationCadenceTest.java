package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import zmaster587.advancedRocketry.client.ClientBeamTracker;
import zmaster587.advancedRocketry.projectile.BeamReplication;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When a burning beam is worth a packet, and when silence is the right answer.
 *
 * <p>A beam is replicated as a STATE rather than as events, which puts it between two failures that
 * are both invisible in a dev world with one player in it: told too rarely and a client's drawing
 * either lags the gun or blinks out while it is still burning; told every tick and one gun costs
 * twenty packets a second on every nearby connection for a picture that is not changing. What is
 * pinned here is the shape of the arrangement — never while dark, always on a change, and often
 * enough that the client's own staleness timeout cannot fire under a beam that is still lit — and
 * none of the periods, which are tuning.</p>
 */
public class BeamReplicationCadenceTest {

    private static final BlockPos GUN = new BlockPos(100, 70, 100);
    /** A dark gun offers no line at all, which is the shape "not burning" has on the wire. */
    private static final java.util.List<Vec3d> NO_LINE = java.util.Collections.emptyList();

    /** The ordinary beam: two points. A bent one would have more, and the cadence does not care. */
    private static java.util.List<Vec3d> line(Vec3d from, Vec3d to) {
        return java.util.Arrays.asList(from, to);
    }

    private static final Vec3d MUZZLE = new Vec3d(100.5D, 74.0D, 100.5D);
    private static final Vec3d TARGET = new Vec3d(140.5D, 74.0D, 100.5D);

    /** Any phase will do for the cadence claims; the spread of phases is its own test below. */
    private static final int PHASE = 3;

    @Test
    public void aDarkGunSaysNothingAtAll() {
        BeamReplication.Channel channel = new BeamReplication.Channel();
        for (long tick = 0; tick < 200; tick++) {
            assertFalse("a gun that is not burning, and was not burning last time anybody was told,"
                    + " sent a packet on tick " + tick + " — every idle gun in the world would then"
                    + " be paying for a beam it does not have",
                    channel.offer(tick, PHASE, false, NO_LINE));
        }
    }

    @Test
    public void theFirstTickOfBurningIsAnnouncedAtOnce() {
        BeamReplication.Channel channel = new BeamReplication.Channel();
        assertTrue("the tick a beam lit was not announced: a client is told nothing else about a"
                + " beam, so one that is not announced is one nobody can see",
                channel.offer(0L, PHASE, true, line(MUZZLE, TARGET)));
    }

    @Test
    public void goingOutIsAnnouncedOnceAndThenTheGunIsQuietAgain() {
        BeamReplication.Channel channel = new BeamReplication.Channel();
        channel.offer(0L, PHASE, true, line(MUZZLE, TARGET));

        assertTrue("the beam went out and nobody was told: the client would hold the last segment it"
                + " was sent, drawing a beam from a gun that has stopped firing",
                channel.offer(1L, PHASE, false, NO_LINE));
        for (long tick = 2; tick < 60; tick++) {
            assertFalse("the gun kept announcing that it is not burning, on tick " + tick,
                    channel.offer(tick, PHASE, false, NO_LINE));
        }
    }

    /**
     * The two-sided arrangement, read from both sides rather than restated: the server's heartbeat
     * is what keeps a client's copy alive, so the longest silence during an unchanging burn has to
     * be shorter than the time after which the client drops the drawing.
     */
    @Test
    public void aSteadyBurnIsRepeatedOftenEnoughThatTheClientNeverDropsIt() {
        BeamReplication.Channel channel = new BeamReplication.Channel();
        int longestSilence = 0;
        int sinceSent = 0;
        int sent = 0;
        for (long tick = 0; tick < 400; tick++) {
            if (channel.offer(tick, PHASE, true, line(MUZZLE, TARGET))) {
                sent++;
                longestSilence = Math.max(longestSilence, sinceSent);
                sinceSent = 0;
            } else {
                sinceSent++;
            }
        }
        longestSilence = Math.max(longestSilence, sinceSent);

        assertTrue("a beam held on one spot for twenty seconds went unmentioned for " + longestSilence
                + " ticks, and the client drops one it has not heard about in "
                + ClientBeamTracker.stalenessTicks() + " ticks: a beam that is still burning would"
                + " blink out on every watching client",
                longestSilence < ClientBeamTracker.stalenessTicks());
        assertTrue("a beam that never changed was announced on " + sent + " of 400 ticks: a state"
                + " that is not changing is being sent as though it were", sent < 400 / 4);
    }

    @Test
    public void anAimThatIsMovingIsAnnouncedAsItMoves() {
        BeamReplication.Channel channel = new BeamReplication.Channel();
        channel.offer(0L, PHASE, true, line(MUZZLE, TARGET));

        // The gun tracks a target across its front: one tick later the far end is metres away from
        // where the client was told it was.
        Vec3d swung = new Vec3d(TARGET.x, TARGET.y, TARGET.z + 4.0D);
        assertTrue("the beam swung four blocks across and the client was not told: it would be drawn"
                + " burning into whatever it was pointed at half a second ago",
                channel.offer(1L, PHASE, true, line(MUZZLE, swung)));

        // And the muzzle itself moves when the gun is on a ship under way.
        Vec3d carried = new Vec3d(MUZZLE.x + 3.0D, MUZZLE.y, MUZZLE.z);
        assertTrue("the gun itself moved and the client was not told: a beam on a moving ship would"
                + " hang in the air behind it", channel.offer(2L, PHASE, true, line(carried, swung)));
    }

    /**
     * A broadside that lit on the same tick must not heartbeat on the same tick. Same period, same
     * average traffic, peak divided by the number of guns.
     */
    @Test
    public void gunsSittingSideBySideDoNotBeatInUnison() {
        Set<Integer> phases = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            phases.add(BeamReplication.phaseOf(new BlockPos(100 + i, 70, 100)));
        }
        assertTrue("twelve guns in a row share " + phases.size() + " heartbeat phase(s): a battery"
                + " that lit together would put its whole refresh traffic into one tick in every"
                + " period, which is the peak the period was chosen to avoid", phases.size() > 3);
    }

    /** The tracker is the client's whole memory of what is burning; it starts and ends empty. */
    @Test
    public void theClientDrawsNothingUntilItIsTold() {
        ClientBeamTracker.clear();
        assertEquals("the client's beam tracker did not start empty", 0, ClientBeamTracker.count());
        ClientBeamTracker.lit(GUN.toLong(), line(MUZZLE, TARGET));
        assertEquals("a beam the client was told about is not being drawn", 1,
                ClientBeamTracker.count());
        ClientBeamTracker.extinguished(GUN.toLong());
        assertEquals("a beam the client was told had gone out is still being drawn", 0,
                ClientBeamTracker.count());
    }

    /**
     * The backstop for every way a beam can end without anybody being able to say so — the gun blown
     * up, the chunk unloaded, the player out of range at the moment it stopped.
     */
    @Test
    public void aBeamNobodyMentionsAgainStopsBeingDrawn() {
        ClientBeamTracker.clear();
        ClientBeamTracker.lit(GUN.toLong(), line(MUZZLE, TARGET));
        for (int tick = 0; tick < ClientBeamTracker.stalenessTicks(); tick++) {
            ClientBeamTracker.tick();
        }
        assertEquals("a beam went undrawn while it was still being mentioned", 1,
                ClientBeamTracker.count());
        ClientBeamTracker.tick();
        assertEquals("a beam nobody has mentioned since it lit is still being drawn: a gun destroyed"
                + " mid-burn would leave a beam burning across the sky until the player relogged", 0,
                ClientBeamTracker.count());
        ClientBeamTracker.clear();
    }
}
