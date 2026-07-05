package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.world.weather.ARDimensionWorldInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #66 — pure-math unit pins for
 * {@link ARDimensionWorldInfo#computeSleepWakeTime(long, int)}: the sleep wake-up
 * must land on the planet's dawn (a multiple of {@code rotationalPeriod}),
 * strictly forward, by at most one planetary day.
 */
public class SleepWakeTimeTest {

    private static void assertDawnInvariants(long current, int rp) {
        long wake = ARDimensionWorldInfo.computeSleepWakeTime(current, rp);
        assertEquals("wake must land on a multiple of rotationalPeriod (dawn) for rp=" + rp
                + ", current=" + current, 0L, Math.floorMod(wake, (long) rp));
        assertTrue("wake must move strictly forward (current=" + current + ", wake=" + wake + ")",
                wake > current);
        assertTrue("wake must skip less than a full extra day (current=" + current
                + ", wake=" + wake + ", rp=" + rp + ")", wake - current <= rp);
    }

    @Test
    public void rp24000MatchesVanillaRounding() {
        // With a 24000 day, the helper must reproduce vanilla's i - i%24000.
        for (long t : new long[]{0L, 1L, 12345L, 23999L, 24000L, 50000L}) {
            long vanilla = (t + 24000L) - (t + 24000L) % 24000L;
            assertEquals("rp=24000 must equal vanilla rounding at t=" + t,
                    vanilla, ARDimensionWorldInfo.computeSleepWakeTime(t, 24000));
        }
    }

    @Test
    public void nonVanillaPeriodsLandOnDawn() {
        for (int rp : new int[]{13888, 46875, 128000, 1, 7777}) {
            for (long t : new long[]{0L, 1L, 5000L, 99999L, 1_000_000L}) {
                assertDawnInvariants(t, rp);
            }
        }
    }

    @Test
    public void alreadyAtDawnSkipsToNextDay() {
        // current exactly on a dawn boundary → advance a full day, not stay put.
        int rp = 13888;
        long wake = ARDimensionWorldInfo.computeSleepWakeTime(2L * rp, rp);
        assertEquals(3L * rp, wake);
    }

    @Test
    public void nonPositivePeriodFallsBackTo24000() {
        // Defensive: a bad rotationalPeriod must not divide-by-zero or loop.
        assertEquals(24000L, ARDimensionWorldInfo.computeSleepWakeTime(0L, 0));
        assertEquals(24000L, ARDimensionWorldInfo.computeSleepWakeTime(0L, -5));
    }
}
