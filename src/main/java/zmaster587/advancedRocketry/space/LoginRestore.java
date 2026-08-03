package zmaster587.advancedRocketry.space;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The <b>login restore decision</b> for a player who logged out aboard a tier-2 ship: given his
 * durable aboard-tag and the ship ledger, decide which dimension and position he must be placed in
 * before the server hands him a world.
 *
 * <p>This is deliberately a PURE decision surface. It answers "where does he go", it does not move
 * anybody: the caller performs the placement. Everything that would need a live server — reading the
 * ledger, materializing a cell, unpacking a parked transit, querying a ship's live pose, reading a
 * bed spawn — sits behind the {@link Ops} seam, which is addressed by <i>dimension id</i> and
 * <i>UUID</i> only and never by a {@code World} type. That is what makes the whole decision table
 * unit-testable with no Minecraft server at all (the {@link ShipCrossingService.Ops} precedent).</p>
 *
 * <p><b>Why the ledger outranks the tag.</b> The tag is written when the player sits down and
 * travels in his player file; it is a snapshot of where the ship was <i>when he left</i>. The ship
 * itself may have kept flying under another crew member the whole time he was offline, so
 * {@code tag.coord} can be arbitrarily stale. Whenever the ledger knows the ship, the ledger's
 * coordinate is the one that is used and the tag's coordinate is treated as a diagnostic only. The
 * tag's real payload is the ship <i>identity</i> and the seat offset.</p>
 *
 * <p><b>Totality.</b> {@link #resolve} never returns {@code null} and never throws — not for a
 * missing tag, not for a hostile {@link Ops}, not for an exhausted slot pool. It runs inside the
 * login path, where an escaping exception does not degrade a feature, it prevents the player from
 * joining at all. Every failure therefore collapses into the orphan fallback (personal spawn, else
 * the overworld spawn) with a {@link Reason} that says why, so the outcome is loggable and
 * observable rather than silent.</p>
 *
 * <p>Server main thread only.</p>
 */
public final class LoginRestore {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /** The dimension the overworld-spawn orphan fallback lands in. */
    public static final int OVERWORLD_DIM = 0;

    /**
     * Provisional in-slot position used when a ship's live pose cannot be read yet — a re-assembly
     * is asynchronous, so a player can be restored on the very tick the ship is still being built.
     * The player is still marked {@code aboard}, so the second restore phase keeps retrying the
     * seating and corrects the position once the ship answers. {@code tunable}.
     */
    private static final double[] UNRESOLVED_SHIP_POS = {0.5D, 65.0D, 0.5D};

    /**
     * Last-ditch position for the case where even the overworld spawn cannot be read. Only reachable
     * through a broken {@link Ops}; exists so the decision stays total. {@code tunable}.
     */
    private static final double[] LAST_DITCH_SPAWN = {0.5D, 64.0D, 0.5D};

    /**
     * The world-touching operations the decision needs, addressed by dim id / UUID so a test can
     * supply them without a server. Implementations are expected to be lenient: they report failure
     * by returning {@code null} / {@code -1} rather than by throwing.
     */
    public interface Ops {

        /** Ledger record for this ship, or {@code null} if unknown. */
        ShipLedger.Entry ledgerEntry(UUID shipId);

        /**
         * Materialize the cell holding {@code coord} and return its bound slot dim; {@code -1} if it
         * cannot be materialized (slot pool exhausted, subsystem down). Implementations MUST NOT let
         * {@link SpaceManager.PoolExhaustedException} escape — it is unchecked, so javac will not
         * force the catch, and an escape here lands in the middle of the login path.
         */
        int materialize(GalacticCoord coord);

        /**
         * Unpack a ship that is mid-jump into the shared hyperspace world and return that dim;
         * {@code -1} when there is no restored transit for it or the unpack failed.
         */
        int unpackTransit(UUID shipId);

        /** The ship's live world position {@code [x,y,z]} in {@code slotDim}, or {@code null} while
         *  it is not assembled yet. */
        double[] shipWorldPos(int slotDim, UUID shipId);

        /** The player's personal spawn as {@code [dim,x,y,z]}, or {@code null} when unset/invalid. */
        double[] personalSpawn(UUID playerId);

        /** The overworld's randomized spawn as {@code [x,y,z]}. */
        double[] overworldSpawn();
    }

    /**
     * Told to a returning player whose ship the server has no record of. He was aboard something when
     * he left and is standing at his spawn point now; without this the only trace is a server log line
     * he cannot read, and the first thing he knows about it is that his ship is not where he left it.
     */
    public static final String MSG_SHIP_UNKNOWN = "msg.loginrestore.shipunknown";

    /** Why a placement is what it is — carried into the log line and the diagnostic surface. */
    public enum Reason {
        /** Restored into his ship's own cell; the ship is settled there. */
        ABOARD_SETTLED,
        /** Restored into the shared hyperspace world; the ship is mid-jump. */
        ABOARD_IN_TRANSIT,
        /** No aboard-tag: he was never aboard, this is an ordinary login. */
        NO_TAG,
        /** Tag present, but the ledger has no such ship — it descended or was dismantled offline. */
        SHIP_UNKNOWN,
        /** The ledger knows the ship, but its cell could not be made live. */
        CELL_UNAVAILABLE
    }

    /** Where the logging-in player must be placed. Immutable value. */
    public static final class Placement {

        public final int dimension;
        public final double x;
        public final double y;
        public final double z;

        /** {@code true} =&gt; he belongs on a ship and the second restore phase must still seat him. */
        public final boolean aboard;

        /** The ship he belongs to; non-null iff {@link #aboard}. */
        public final UUID shipId;

        /** Why this placement was chosen. */
        public final Reason reason;

        private Placement(int dimension, double x, double y, double z, boolean aboard, UUID shipId,
                          Reason reason) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.aboard = aboard;
            this.shipId = shipId;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "Placement[" + reason + " dim=" + dimension + " pos=(" + x + "," + y + "," + z
                    + ")" + (aboard ? " aboard ship " + shipId : "") + "]";
        }
    }

    private LoginRestore() { }

    /**
     * Resolve the placement for a logging-in player. Never {@code null}, never throws.
     *
     * @param tag      his durable aboard record, or {@code null} if he carries none
     * @param ops      the world seam; a {@code null} or misbehaving {@code ops} degrades to orphan
     * @param playerId the player, used only to look up his personal spawn
     */
    public static Placement resolve(ShipAboardTag.Aboard tag, Ops ops, UUID playerId) {
        // 1. No tag at all: he never sat down on a ship. Ordinary login.
        if (tag == null) {
            return orphan(Reason.NO_TAG, ops, playerId);
        }

        // 2. The ship is gone from the ledger — it descended onto a planet or was dismantled while
        //    he was offline. His tag outlived its subject; there is nothing to restore him onto.
        ShipLedger.Entry entry = ledgerEntry(ops, tag.shipId);
        if (entry == null) {
            return orphan(Reason.SHIP_UNKNOWN, ops, playerId);
        }

        // 3. Mid-jump: the ship is parked in the shared hyperspace world, NOT in a cell. Its target
        //    cell must not be materialized here — that would claim a pool slot for a ship that is
        //    not in it yet.
        if (entry.state == ShipLedger.State.IN_TRANSIT) {
            int transitDim = unpackTransit(ops, tag.shipId);
            if (transitDim < 0) {
                return orphan(Reason.CELL_UNAVAILABLE, ops, playerId);
            }
            // Hyperspace is a plain parking world, NOT a coordinate-mapped cell — the ship sits
            // wherever the transit parked it, so the cell pose mapping does not apply here.
            return aboard(transitDim, tag.shipId, Reason.ABOARD_IN_TRANSIT, ops, UNRESOLVED_SHIP_POS);
        }

        // 4. Settled: make its cell live and put him in the bound slot world. The LEDGER's
        //    coordinate is used, never the tag's — see the class doc on staleness.
        if (entry.state == ShipLedger.State.SETTLED) {
            int slotDim = materialize(ops, entry.coord);
            if (slotDim < 0) {
                return orphan(Reason.CELL_UNAVAILABLE, ops, playerId);
            }
            // A settled cell maps its coordinates into a high world band, so the cell's own pose is
            // the only honest "somewhere near the ship" guess while the ship is still assembling.
            return aboard(slotDim, tag.shipId, Reason.ABOARD_SETTLED, ops,
                    CellWorldMapper.poseWorldOf(entry.coord));
        }

        // A ledger state this version does not know how to restore into. Keeps the decision total
        // if the lifecycle ever grows a third state without this table being updated with it.
        LOGGER.warn("[SPACE] login restore: unhandled ledger state {} for ship {}",
                entry.state, tag.shipId);
        return orphan(Reason.CELL_UNAVAILABLE, ops, playerId);
    }

    /**
     * Aboard placement at the ship's live pose, or at {@code provisional} while the ship is still
     * silent (an asynchronous re-assembly can be mid-flight on the very login tick).
     *
     * <p>The provisional position matters more than it looks: a settled cell maps its coordinates
     * into a high world band, so a generic near-bedrock fallback would drop the player roughly a
     * cell-radius BELOW his own ship instead of beside it. The caller therefore supplies the
     * fallback that belongs to its branch.</p>
     */
    private static Placement aboard(int dimension, UUID shipId, Reason reason, Ops ops,
                                    double[] provisional) {
        double[] pos = shipWorldPos(ops, dimension, shipId);
        if (pos == null || pos.length < 3) {
            pos = provisional == null || provisional.length < 3 ? UNRESOLVED_SHIP_POS : provisional;
        }
        return new Placement(dimension, pos[0], pos[1], pos[2], true, shipId, reason);
    }

    /** Not-aboard placement: his own bed if he has a usable one, else the overworld spawn. */
    private static Placement orphan(Reason reason, Ops ops, UUID playerId) {
        double[] bed = personalSpawn(ops, playerId);
        if (bed != null && bed.length >= 4) {
            return new Placement((int) bed[0], bed[1], bed[2], bed[3], false, null, reason);
        }
        double[] spawn = overworldSpawn(ops);
        if (spawn == null || spawn.length < 3) {
            spawn = LAST_DITCH_SPAWN;
        }
        return new Placement(OVERWORLD_DIM, spawn[0], spawn[1], spawn[2], false, null, reason);
    }

    // --- Ops accessors -------------------------------------------------------------------------
    // Every call is wrapped: the seam's contract already forbids throwing, but a login handler is
    // the wrong place to discover that an implementation broke it. A failed call is downgraded to
    // "unknown", which routes into the orphan fallback with a logged cause.

    private static ShipLedger.Entry ledgerEntry(Ops ops, UUID shipId) {
        if (ops == null || shipId == null) {
            return null;
        }
        try {
            return ops.ledgerEntry(shipId);
        } catch (RuntimeException bad) {
            LOGGER.error("[SPACE] login restore: ledger lookup failed for ship " + shipId, bad);
            return null;
        }
    }

    private static int materialize(Ops ops, GalacticCoord coord) {
        if (ops == null || coord == null) {
            return -1;
        }
        try {
            return ops.materialize(coord);
        } catch (RuntimeException bad) {
            LOGGER.error("[SPACE] login restore: could not materialize cell " + coord.cellKey(), bad);
            return -1;
        }
    }

    private static int unpackTransit(Ops ops, UUID shipId) {
        if (ops == null || shipId == null) {
            return -1;
        }
        try {
            return ops.unpackTransit(shipId);
        } catch (RuntimeException bad) {
            LOGGER.error("[SPACE] login restore: could not unpack transit for ship " + shipId, bad);
            return -1;
        }
    }

    private static double[] shipWorldPos(Ops ops, int slotDim, UUID shipId) {
        if (ops == null || shipId == null) {
            return null;
        }
        try {
            return ops.shipWorldPos(slotDim, shipId);
        } catch (RuntimeException bad) {
            LOGGER.error("[SPACE] login restore: could not read pose of ship " + shipId, bad);
            return null;
        }
    }

    private static double[] personalSpawn(Ops ops, UUID playerId) {
        if (ops == null || playerId == null) {
            return null;
        }
        try {
            return ops.personalSpawn(playerId);
        } catch (RuntimeException bad) {
            LOGGER.error("[SPACE] login restore: could not read personal spawn of " + playerId, bad);
            return null;
        }
    }

    private static double[] overworldSpawn(Ops ops) {
        if (ops == null) {
            return null;
        }
        try {
            return ops.overworldSpawn();
        } catch (RuntimeException bad) {
            LOGGER.error("[SPACE] login restore: could not read the overworld spawn", bad);
            return null;
        }
    }
}
