package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.PilotInputCadence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link PilotInputCadence} — when a pilot's command goes on the wire.
 *
 * <p>What these pin is the property the mechanism exists for: <b>a held command is re-asserted within
 * a bounded time</b>, so the cost of the server forgetting it is that bound and not the rest of the
 * flight. The interval and the phase are read from the class rather than restated, so re-tuning
 * changes the behaviour these tests describe without making them lie.</p>
 */
public class PilotInputCadenceTest {

    private static FreeFlightInput held() {
        return new FreeFlightInput(0f, 1f, 0f, 0f, 0f, 0f, 0f, false);
    }

    @Test
    public void aChangedInputGoesOutImmediately() {
        assertTrue("the first input ever must be sent",
                PilotInputCadence.shouldSend(held(), null, 1L, 0));
        assertTrue("a different input must be sent on the tick it changes",
                PilotInputCadence.shouldSend(held(), FreeFlightInput.zero(), 7L, 0));
    }

    /**
     * The defect this class was written for: a key held down, unchanged, while the server's copy of
     * it is gone. Over any window as long as the repeat interval the command must be re-asserted at
     * least once — asserted as a property of the window, not as "tick 20 specifically".
     */
    @Test
    public void aHeldInputIsReassertedWithinTheRepeatInterval() {
        FreeFlightInput input = held();
        int phase = PilotInputCadence.phaseOfSeat(11, 64, -7);

        int sends = 0;
        for (long tick = 1; tick <= PilotInputCadence.REPEAT_TICKS; tick++) {
            if (PilotInputCadence.shouldSend(input, input, tick, phase)) {
                sends++;
            }
        }
        assertEquals("exactly one re-assert per interval — more is a burst, none is the bug",
                1, sends);
    }

    @Test
    public void anIdleInputIsNeverRepeated() {
        FreeFlightInput idle = FreeFlightInput.zero();
        for (long tick = 0; tick <= 4L * PilotInputCadence.REPEAT_TICKS; tick++) {
            assertFalse("releasing everything must not become a heartbeat: losing \"no input\" costs "
                            + "nothing, because no input is what the server falls back to",
                    PilotInputCadence.shouldSend(idle, idle, tick, 0));
        }
    }

    @Test
    public void nullIsNeverSent() {
        assertFalse(PilotInputCadence.shouldSend(null, null, 0L, 0));
    }

    /**
     * Two seats must not repeat on the same tick. Pinned because the failure is invisible in single
     * play and only appears as a periodic spike on a busy server — the shape a shared {@code % N}
     * clock always has.
     */
    @Test
    public void twoSeatsRepeatOnDifferentTicks() {
        FreeFlightInput input = held();
        int phaseA = PilotInputCadence.phaseOfSeat(100, 70, 100);
        int phaseB = PilotInputCadence.phaseOfSeat(101, 70, 100);
        assertNotEquals("a seat one block over must land on a different phase", phaseA, phaseB);

        long tickA = -1, tickB = -1;
        for (long tick = 1; tick <= PilotInputCadence.REPEAT_TICKS; tick++) {
            if (tickA < 0 && PilotInputCadence.shouldSend(input, input, tick, phaseA)) {
                tickA = tick;
            }
            if (tickB < 0 && PilotInputCadence.shouldSend(input, input, tick, phaseB)) {
                tickB = tick;
            }
        }
        assertTrue("both seats must re-assert inside one interval", tickA > 0 && tickB > 0);
        assertNotEquals("two pilots must not stack their keep-alives onto one tick", tickA, tickB);
    }

    @Test
    public void thePhaseStaysInsideTheInterval() {
        for (int x = -40; x <= 40; x++) {
            int phase = PilotInputCadence.phaseOfSeat(x, -x, 3 * x);
            assertTrue("a phase outside the interval would silently disable the repeat: " + phase,
                    phase >= 0 && phase < PilotInputCadence.REPEAT_TICKS);
        }
    }
}
