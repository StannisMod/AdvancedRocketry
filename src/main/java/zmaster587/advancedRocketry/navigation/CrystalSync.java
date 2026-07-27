package zmaster587.advancedRocketry.navigation;

/**
 * Keeping two crystals — a base's and a ship's — carrying the same knowledge.
 *
 * <p>A sync is not a copy in one direction: after it, <b>both</b> sides hold the union of what either
 * knew, and for any body both had seen, the newer observation. That symmetry is the point. A survey
 * ship that comes home should not have to remember which way to press the button, and a base that
 * learned something while the ship was away should reach the ship the same way.</p>
 *
 * <p>Nothing is ever lost by syncing: like a copy, it only adds addresses and refreshes stale ones.</p>
 */
public final class CrystalSync {

    private CrystalSync() {
    }

    /**
     * Make {@code a} and {@code b} agree. Returns the total number of addresses that changed across
     * both crystals — zero when they were already in step, which is the common case and lets a caller
     * skip writing either item back.
     */
    public static int sync(CrystalMemory a, CrystalMemory b) {
        if (a == null || b == null || a == b) {
            return 0;
        }
        // Snapshot the source side before the first merge mutates it, so the second merge sees what
        // the other crystal ORIGINALLY held rather than what it just learned.
        CrystalMemory beforeA = CrystalMemory.of(a.list());
        int changed = a.copyFrom(b);
        changed += b.copyFrom(beforeA);
        return changed;
    }
}
