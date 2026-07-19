package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.PendingSeedDecision;

import static org.junit.Assert.assertEquals;

/**
 * Contract pinning for the pending dismount seed's state machine
 * ({@link ShipFrameTravel#pendingSeedDecision}): the client half of the seat-dismount deck
 * capture. Pure function; no Minecraft state, so it runs under testUnit.
 *
 * <p>The player-visible contract behind these pins: standing up from a pilot seat puts the crew
 * member ON the deck at the seat, at any ship attitude - the seed WAITS OUT the transient
 * post-dismount exclusion instead of losing a race to it, replaces the interim capture vanilla's
 * blind dismount spot produced, applies at most once, and never snaps back a pilot whose
 * exclusion is real and persistent (dismounting straight into creative flight and leaving).</p>
 */
public class DismountPendingSeedTest {

    private static PendingSeedDecision decide(boolean excluded, int ticksLeft,
                                              boolean captureExists, boolean captureIsThisSeed,
                                              boolean capturePredatesSlot) {
        return ShipFrameTravel.pendingSeedDecision(
                excluded, ticksLeft, captureExists, captureIsThisSeed, capturePredatesSlot);
    }

    @Test
    public void theSeedWaitsOutTheTransientExclusionInsteadOfLosingToIt() {
        // The riding flag lingers a few ticks after the dismount packet: the seed must wait,
        // not die - this is the race that used to hand the body to vanilla's dismount spot.
        assertEquals(PendingSeedDecision.WAIT, decide(true, 40, false, false, false));
        assertEquals(PendingSeedDecision.WAIT, decide(true, 1, false, false, false));
    }

    @Test
    public void theSeedAppliesTheMomentTheBodyIsCapturable() {
        assertEquals(PendingSeedDecision.APPLY, decide(false, 39, false, false, false));
    }

    @Test
    public void aCaptureInstalledDuringTheWindowIsSupersededByTheSeatPoint() {
        // First contact at vanilla's world-frame dismount spot within the window is a
        // mis-boarding: the seat's deck point is the contractual stand for a dismount.
        assertEquals(PendingSeedDecision.APPLY, decide(false, 39, true, false, false));
    }

    @Test
    public void aCaptureThatPredatesTheSeedIsRespected() {
        // A body legitimately captured BEFORE the seed's window opened is not yanked to the seat.
        assertEquals(PendingSeedDecision.KEEP_PREEXISTING, decide(false, 39, true, false, true));
        assertEquals(PendingSeedDecision.KEEP_PREEXISTING, decide(true, 39, true, false, true));
    }

    @Test
    public void aTakenSeedNoOpsEveryLaterResend() {
        // Idempotency: the server re-sends the whole window; once the seed took, a re-send must
        // never teleport the body again - at any remaining TTL, excluded or not.
        assertEquals(PendingSeedDecision.ALREADY_SEEDED, decide(false, 39, true, true, false));
        assertEquals(PendingSeedDecision.ALREADY_SEEDED, decide(true, 0, true, true, true));
    }

    @Test
    public void aPersistentExclusionExpiresTheSeedWithoutEverSnapping() {
        // The old teleport war is impossible by construction: a pilot who dismounted straight
        // into creative flight and left is never snapped back - the slot just dissolves.
        assertEquals(PendingSeedDecision.EXPIRE, decide(true, 0, false, false, false));
    }

    @Test
    public void exclusionIsStillWaitedOutWhenAWindowCaptureAlreadyHoldsTheBody() {
        // A window first-contact capture holding the body (keeping it from falling) does not
        // cancel the seed: once the exclusion clears the seat point still wins.
        assertEquals(PendingSeedDecision.WAIT, decide(true, 20, true, false, false));
    }
}
