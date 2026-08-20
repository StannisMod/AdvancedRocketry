package zmaster587.advancedRocketry.hyperdrive;

/**
 * How long a jump bank takes to reach a level — the cooldown a pilot is quoted, and nothing else.
 *
 * <p><b>This class used to BE the charge, and that was the defect.</b> It held a closed form of the
 * world clock, {@code charge(t) = min(capacity, c0 + rate·(t − since))}, so a capacitor stored no
 * energy: its level was arithmetic over elapsed ticks and the rate was conjured by welding heat sinks
 * on. The hyperdrive's largest single cost — the window burst, twenty times the drive's power — was
 * therefore free, paid for in wall-clock time rather than in generation. The bank is now a real Forge
 * Energy receiver fed by the ship (see {@code TileJumpCapacitor}), and what is left here is the one
 * thing that was never wrong: turning a deficit and a rate into a number of ticks.</p>
 *
 * <p>What that number IS has changed with it. It used to be a prediction, because the rate was a
 * property of the capacitor and could not be missed. It is now a <b>best case</b>: the rate is the
 * bank's own accept limit, and whether the ship's power plant actually delivers it is the plant's
 * business. A forecast that says "at full inflow" is honest; the same number presented as a promise
 * would be the free energy coming back as a lie about time.</p>
 */
public final class CapacitorCharge {

    private CapacitorCharge() {
    }

    /**
     * Ticks from now until a bank holding {@code current} of {@code capacity} reaches {@code needed},
     * fed at {@code ratePerTick}. Zero means "already"; {@code -1} means never, because the bank
     * cannot hold that much however long anybody waits.
     */
    public static long ticksToReach(long current, long capacity, long ratePerTick, long needed) {
        long cap = Math.max(0L, capacity);
        if (needed <= 0L) {
            return 0L;
        }
        long have = Math.min(cap, Math.max(0L, current));
        if (have >= needed) {
            return 0L;
        }
        if (needed > cap || ratePerTick <= 0L) {
            return -1L; // no amount of waiting gets there
        }
        long deficit = needed - have;
        return (deficit + ratePerTick - 1L) / ratePerTick;
    }
}
