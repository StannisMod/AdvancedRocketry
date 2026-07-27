package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.hyperdrive.CapacitorCharge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What the capacitor promises: a charge that is <b>computed</b>, never accumulated.
 *
 * <p>The contracts under test are the ones the rest of the game leans on. Time away is time
 * charging, whether or not anything was loaded to notice — that is what lets a ship park in an empty
 * cell for a month and come back ready. A bank never overfills, never goes negative, and never gains
 * anything from a clock that ran backwards. And the cooldown between jumps is not a timer at all: it
 * is however long the same arithmetic takes to reach the next burst, so a bank too small to ever
 * hold one says so instead of counting forever.</p>
 */
public class CapacitorChargeTest {

    @Test
    public void timeAwayIsTimeCharging() {
        long atStart = CapacitorCharge.at(0L, 100L, 5L, 1_000_000L, 100L);
        long after200Ticks = CapacitorCharge.at(0L, 100L, 5L, 1_000_000L, 300L);

        assertEquals("nothing has elapsed yet", 0L, atStart);
        assertTrue("200 ticks of absence must have charged the bank: " + after200Ticks,
                after200Ticks > atStart);
    }

    @Test
    public void anUnloadedMonthChargesExactlyLikeALoadedOne() {
        // The whole point of computing rather than ticking: two capacitors, same build, same elapsed
        // time, one of them in a cell nobody visited. They must agree.
        long month = 20L * 60L * 60L * 24L * 30L;
        long ticked = CapacitorCharge.at(0L, 0L, 1L, Long.MAX_VALUE, month);
        long parked = CapacitorCharge.at(0L, 0L, 1L, Long.MAX_VALUE, month);

        assertEquals(ticked, parked);
        assertEquals("and the closed form is exactly rate x elapsed", month, ticked);
    }

    @Test
    public void chargeNeverExceedsCapacity() {
        long charge = CapacitorCharge.at(0L, 0L, 1_000L, 5_000L, 1_000_000L);

        assertEquals("a full bank is full, however long it waits", 5_000L, charge);
    }

    @Test
    public void aClockThatRanBackwardsGainsNothing() {
        // A restored world can hand back a smaller tick count than a tile remembers. That must read
        // as "no time has passed", never as a negative charge or a wrapped one.
        long charge = CapacitorCharge.at(4_000L, 9_000L, 10L, 10_000L, 500L);

        assertEquals(4_000L, charge);
    }

    @Test
    public void anAbsenceLongEnoughToOverflowStillJustFills() {
        long charge = CapacitorCharge.at(0L, 0L, Long.MAX_VALUE / 2L, 10_000L, Long.MAX_VALUE / 2L);

        assertEquals("a colossal elapsed time must saturate at capacity, not wrap negative",
                10_000L, charge);
    }

    @Test
    public void theCooldownIsHowLongTheNextBurstTakesToArrive() {
        long ticks = CapacitorCharge.ticksUntil(0L, 0L, 10L, 10_000L, 0L, 1_000L);

        assertEquals("1000 needed at 10 per tick", 100L, ticks);
    }

    @Test
    public void aBankThatAlreadyHoldsTheBurstHasNoCooldown() {
        assertEquals(0L, CapacitorCharge.ticksUntil(5_000L, 0L, 10L, 10_000L, 0L, 1_000L));
    }

    @Test
    public void aBankTooSmallForTheBurstSaysSoInsteadOfCountingForever() {
        assertEquals("a build that can never open the window must be reported, not waited on",
                -1L, CapacitorCharge.ticksUntil(0L, 0L, 10L, 500L, 0L, 1_000L));
    }

    @Test
    public void aBankThatNeverRechargesSaysSoToo() {
        assertEquals(-1L, CapacitorCharge.ticksUntil(0L, 0L, 0L, 10_000L, 0L, 1_000L));
    }
}
