package zmaster587.advancedRocketry.hyperdrive;

/**
 * The capacitor's charge, computed rather than accumulated.
 *
 * <p>Nothing here ever ticks. The charge is a closed form of the world clock — {@code charge(t) =
 * min(capacity, c0 + rate·(t − since))} — so a capacitor aboard a ship parked in an unloaded cell,
 * or one that spent a month in hyperspace, is exactly as charged as one that sat in a loaded chunk
 * the whole time. Only {@code c0} and {@code since} persist, and they only change when something
 * really happens to the capacitor: a burst, or a rebuild.</p>
 *
 * <p>The cooldown a pilot feels falls out of the same form and needs no timer of its own: after a
 * burst the capacitor is empty, so the reload is however long {@code charge(t)} takes to climb back
 * to the next burst's cost.</p>
 */
public final class CapacitorCharge {

    private CapacitorCharge() {
    }

    /**
     * The charge at {@code now}. Clamped at both ends: never below zero, never above capacity, and
     * never advanced by a clock that has run backwards (which a restored world can do).
     */
    public static long at(long baseCharge, long since, long chargeRate, long capacity, long now) {
        long cap = Math.max(0L, capacity);
        long base = Math.min(cap, Math.max(0L, baseCharge));
        long elapsed = now - since;
        if (elapsed <= 0L || chargeRate <= 0L) {
            return base;
        }
        long gained;
        long rate = Math.max(0L, chargeRate);
        if (rate != 0L && elapsed > (Long.MAX_VALUE - base) / rate) {
            gained = Long.MAX_VALUE - base; // a months-long absence overflows a naive multiply
        } else {
            gained = rate * elapsed;
        }
        return Math.min(cap, base + gained);
    }

    /**
     * How many ticks from {@code now} until the charge reaches {@code needed}, or {@code -1} when it
     * never will because the capacitor is too small to hold that much. Zero means "already".
     */
    public static long ticksUntil(long baseCharge, long since, long chargeRate, long capacity,
                                  long now, long needed) {
        long cap = Math.max(0L, capacity);
        if (needed <= 0L) {
            return 0L;
        }
        long current = at(baseCharge, since, chargeRate, cap, now);
        if (current >= needed) {
            return 0L;
        }
        if (needed > cap || chargeRate <= 0L) {
            return -1L; // no amount of waiting gets there
        }
        return (needed - current + chargeRate - 1L) / chargeRate;
    }
}
