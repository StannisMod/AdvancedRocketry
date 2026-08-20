package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.hyperdrive.CapacitorCharge;
import zmaster587.advancedRocketry.hyperdrive.DriveTuning;
import zmaster587.advancedRocketry.tile.hyperdrive.TileJumpCapacitor;

import net.minecraft.nbt.NBTTagCompound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What the jump bank promises now that it holds real energy.
 *
 * <p><b>The contract this file used to assert has been RETRACTED, and that is worth stating rather
 * than quietly rewriting.</b> It pinned "time away is time charging" and "an unloaded month charges
 * exactly like a loaded one" — properties of a capacitor whose level was a closed form of the world
 * clock and which therefore stored no energy at all. They were only true because the charge was FREE:
 * the biggest single cost in the hyperdrive family, the window burst at twenty times the drive's
 * power, was paid for in wall-clock time. An unloaded ship's reactors are not running either, so
 * charging through an absence was manufacturing energy a second time, more quietly.</p>
 *
 * <p>What is pinned instead is that the energy comes from the SHIP: a bank with nothing feeding it
 * never fills however long anybody waits, it accepts no faster than its throughput allows, and it
 * refuses to be used as a battery by the rest of the vessel. Plus the one thing that was never wrong —
 * turning a deficit and a rate into a number of ticks — now labelled as the best case it is.</p>
 */
public class CapacitorChargeTest {

    /**
     * A capacitor with no world, so its build is just the controller block: capacity
     * {@code CAPACITOR_BASE_CAPACITY}, throughput {@code CAPACITOR_BASE_ACCEPT_RATE}. Enough to pin
     * every property here, none of which is about the scan.
     */
    private static TileJumpCapacitor bareCapacitor() {
        return new TileJumpCapacitor();
    }

    /**
     * What the ship pushes in. The Forge Energy port itself cannot be exercised here — its
     * {@code Capability} handle is injected by Forge and is null outside a loaded game — so the tests
     * drive the RULE the port delegates to, which is where the rule belongs.
     */
    private static long push(TileJumpCapacitor capacitor, long amount) {
        return capacitor.acceptCharge(amount, false);
    }

    // ── the energy is the ship's ──────────────────────────────────────────────

    @Test
    public void aBankWithNothingFeedingItNeverFills() {
        // THE property the old model got wrong. This capacitor is asked about repeatedly and nothing
        // ever pushes into it; it must stay empty, because a buffer is not a generator.
        TileJumpCapacitor capacitor = bareCapacitor();

        assertEquals("a fresh bank is empty", 0L, capacitor.charge());
        for (int i = 0; i < 1_000; i++) {
            assertEquals("a bank nobody feeds must not gain charge by being asked about it",
                    0L, capacitor.charge());
        }
        assertEquals("and no elapsed anything fills it either", 0L, capacitor.charge());
    }

    @Test
    public void whatTheShipPushesInIsWhatTheBankHolds() {
        TileJumpCapacitor capacitor = bareCapacitor();

        long accepted = push(capacitor, 5L);
        assertEquals("the bank takes what it is given, up to its throughput", 5L, accepted);
        assertEquals(5L, capacitor.charge());
    }

    @Test
    public void aBankAcceptsNoFasterThanItsThroughputAllows() {
        // Heat sinks are what raise this. They do not make energy — a bank with every sink in the world
        // fills at nothing if nothing is feeding it, which is the previous test.
        TileJumpCapacitor capacitor = bareCapacitor();

        long accepted = push(capacitor, Long.MAX_VALUE);
        assertEquals("one tick may not swallow more than the accept rate",
                DriveTuning.CAPACITOR_BASE_ACCEPT_RATE, accepted);
        assertEquals(DriveTuning.CAPACITOR_BASE_ACCEPT_RATE, capacitor.charge());
    }

    @Test
    public void aBankNeverOverfills() {
        TileJumpCapacitor capacitor = bareCapacitor();
        long capacity = capacitor.capacity();

        long pushed = 0L;
        for (int i = 0; i < 100_000 && capacitor.charge() < capacity; i++) {
            pushed += push(capacitor, Long.MAX_VALUE);
        }
        assertEquals("a full bank is full", capacity, capacitor.charge());
        assertEquals("and it never took more than it can hold", capacity, pushed);
        assertEquals("a full bank accepts nothing further", 0L, push(capacitor, 1_000L));
    }

    @Test
    public void aSimulatedPushChangesNothing() {
        TileJumpCapacitor capacitor = bareCapacitor();

        long would = capacitor.acceptCharge(3L, true);
        assertEquals("a simulation must report what a real push would take", 3L, would);
        assertEquals("...and must not have taken it", 0L, capacitor.charge());
    }

    @Test
    public void theJumpBankIsNOTtheShipsBattery() {
        // Only the drive's own burst may take from it. If the rest of the vessel could pull, a jump
        // bank would become the ship's general storage and the burst would be paid for out of whatever
        // happened to be lying around at the moment — which is the free energy coming back sideways.
        // The port cannot be exercised without Forge's capability registry, so what is pinned here is
        // the machine's own rule: nothing but the drive's burst removes charge, and the burst goes
        // through discharge(). The port's refusal is one line of delegation over this.
        TileJumpCapacitor capacitor = bareCapacitor();
        push(capacitor, 10L);

        assertEquals("a partial take must remove nothing", 0L, capacitor.discharge(11L));
        assertEquals("the charge is untouched", 10L, capacitor.charge());
    }

    // ── the burst really leaves the buffer ────────────────────────────────────

    @Test
    public void aBurstTakesAllOfItOrNoneOfIt() {
        // Half a burst does not open half a window, so a bank that cannot cover one must not be
        // partially drained by the attempt.
        TileJumpCapacitor capacitor = bareCapacitor();
        capacitor.fill();
        long full = capacitor.charge();
        assertTrue("the fixture needs a bank with something in it", full > 0L);

        assertEquals("a burst larger than the bank takes nothing", 0L,
                capacitor.discharge(full + 1L));
        assertEquals("...and leaves it untouched", full, capacitor.charge());

        assertEquals("a burst it can cover takes exactly that", full - 1L,
                capacitor.discharge(full - 1L));
        assertEquals("and the energy is really gone", 1L, capacitor.charge());
    }

    @Test
    public void aStoredChargeIsREADbackOffTheSave() {
        // It is real stored energy now, so it has to persist — under the old model only c0 and a tick
        // stamp were written and the level was recomputed, which is exactly how an absence created it.
        //
        // ONLY THE READ HALF is pinned here, and the limit is worth naming rather than hiding: writing
        // goes through TileEntity's registry mapping, which does not exist outside a loaded game, so
        // this test builds the compound by hand. The write half is exercised by the real save path in
        // the server tier but is not ASSERTED anywhere yet, and saying so is better than a green that
        // reads as though it were.
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setLong("capCharge", 7L);

        TileJumpCapacitor restored = bareCapacitor();
        restored.readFromNBT(nbt);
        assertEquals("a reloaded bank holds what it held", 7L, restored.charge());
    }

    // ── the cooldown forecast, now a best case ───────────────────────────────

    @Test
    public void theForecastIsADeficitOverARate() {
        assertEquals("already there", 0L, CapacitorCharge.ticksToReach(500L, 1_000L, 10L, 500L));
        assertEquals("400 short at 10 a tick", 40L,
                CapacitorCharge.ticksToReach(100L, 1_000L, 10L, 500L));
        assertEquals("a partial tick still costs a whole one", 41L,
                CapacitorCharge.ticksToReach(99L, 1_000L, 10L, 500L));
    }

    @Test
    public void aBankTooSmallToEverHoldABurstSaysSoInsteadOfCountingForever() {
        assertEquals(-1L, CapacitorCharge.ticksToReach(0L, 1_000L, 10L, 5_000L));
    }

    @Test
    public void aBankWithNoInflowNeverGetsThere() {
        // The forecast's own statement of the property the first test pins on the tile: a rate of zero
        // is not "a very long time", it is never.
        assertEquals(-1L, CapacitorCharge.ticksToReach(0L, 10_000L, 0L, 5_000L));
    }

    @Test
    public void theForecastIsTheBANKSbestCaseAndTheTileSaysSo() {
        // Named for what it is. The rate is the bank's own accept ceiling, so a ship whose reactors
        // deliver less waits longer — and nothing here may present that number as a promise.
        TileJumpCapacitor capacitor = bareCapacitor();
        long needed = capacitor.capacity();
        long forecast = capacitor.ticksUntilAtFullInflow(needed);

        assertEquals("an empty bank at its full accept rate",
                (needed + DriveTuning.CAPACITOR_BASE_ACCEPT_RATE - 1L)
                        / DriveTuning.CAPACITOR_BASE_ACCEPT_RATE,
                forecast);
        assertEquals("a burst bigger than the bank is never reachable", -1L,
                capacitor.ticksUntilAtFullInflow(needed + 1L));
    }
}
