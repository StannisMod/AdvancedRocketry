package zmaster587.advancedRocketry.space;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The authoritative server-side record of every tier-2 ship known to the space subsystem:
 * {@code shipId -> (galactic coordinate, cell, lifecycle state, slot dim)}.
 *
 * <p><b>Key discipline:</b> the key is the ship's DURABLE id — the UUID minted at tier-2 assembly
 * and persisted in the Advanced Flight Computer's tile NBT (crossings carry tile NBT verbatim, so
 * it survives every jump). The physics mod's own ship UUID is re-minted on every re-assembly and
 * must never key an entry here.</p>
 *
 * <p><b>Refcount ownership:</b> a {@link State#SETTLED} entry owns ONE occupant refcount on its
 * cell (the {@link SpaceManager#materialize} the settling performed). Whoever moves the ship out
 * of the cell (a transit departure, a descent) releases that refcount and updates the entry —
 * the ledger records ownership, it does not call the manager itself.</p>
 *
 * <p>In-memory only (rebuilt as ships re-enter); NBT persistence is a follow-up concern. This is
 * the fix for transit-arrival amnesia: an arrived ship's coordinate now lives here instead of
 * being dropped with the finished transit. Server main thread only.</p>
 */
public final class ShipLedger {

    /** A ledgered ship's lifecycle state. */
    public enum State {
        /** Occupying a cell (owns one occupant refcount on it). */
        SETTLED,
        /** Parked in the shared hyperspace world while its coordinate advances logically. */
        IN_TRANSIT
    }

    /** One ship's ledger record. Immutable value — updates replace the entry. */
    public static final class Entry {
        public final GalacticCoord coord;
        public final State state;
        /** The slot dim the ship's cell is bound to; meaningful only while {@link State#SETTLED}. */
        public final int slotDim;

        Entry(GalacticCoord coord, State state, int slotDim) {
            this.coord = coord;
            this.state = state;
            this.slotDim = slotDim;
        }

        public String cellKey() {
            return coord.cellKey();
        }
    }

    private final Map<UUID, Entry> ships = new HashMap<>();

    /**
     * Record {@code shipId} as settled at {@code coord} in slot {@code slotDim}. The caller has
     * just materialized the cell (or handed over an existing refcount); the entry now owns it.
     */
    public void settle(UUID shipId, GalacticCoord coord, int slotDim) {
        ships.put(shipId, new Entry(coord, State.SETTLED, slotDim));
    }

    /**
     * Mark {@code shipId} in transit toward {@code target} (its origin refcount has been released
     * by the departure). The recorded coordinate is the TARGET — the logical integration detail
     * stays with the transit machinery; the ledger answers "where is/will be this ship".
     */
    public void beginTransit(UUID shipId, GalacticCoord target) {
        ships.put(shipId, new Entry(target, State.IN_TRANSIT, Integer.MIN_VALUE));
    }

    /**
     * Refresh a SETTLED ship's coordinate from its live pose (the flight computer's per-tick
     * self-report). A no-op for a ship that is not settled — a parked ship's coordinate is owned
     * by the transit integrator, not by a stale pose.
     */
    public void updatePosition(UUID shipId, GalacticCoord coord) {
        Entry e = ships.get(shipId);
        if (e == null || e.state != State.SETTLED) {
            return;
        }
        ships.put(shipId, new Entry(coord, State.SETTLED, e.slotDim));
    }

    /** The ledger record for {@code shipId}, or {@code null} if unknown. */
    public Entry get(UUID shipId) {
        return ships.get(shipId);
    }

    /** Remove {@code shipId} (left the subsystem entirely, e.g. descended onto a planet). */
    public void remove(UUID shipId) {
        ships.remove(shipId);
    }

    /** Number of ledgered ships. */
    public int size() {
        return ships.size();
    }

    /**
     * Whether any settled ship occupies the cell {@code cellKey}. This is what makes a cell
     * "claimed": the protection follows the thing the player actually owns, so it needs no separate
     * flag of its own that could drift out of step with the ledger or fail to survive a restart.
     */
    public boolean holdsShipIn(String cellKey) {
        if (cellKey == null) {
            return false;
        }
        for (Entry e : ships.values()) {
            if (e.state == State.SETTLED && cellKey.equals(e.cellKey())) {
                return true;
            }
        }
        return false;
    }

    /** A read-only copy of the ledger (probe/diagnostic surface). */
    public Map<UUID, Entry> snapshot() {
        return new HashMap<>(ships);
    }
}
