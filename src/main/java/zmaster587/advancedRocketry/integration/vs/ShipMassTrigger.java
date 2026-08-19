package zmaster587.advancedRocketry.integration.vs;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.physics_data.ShipInertiaData;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import zmaster587.advancedRocketry.api.event.ShipLifecycleEvent;
import zmaster587.advancedRocketry.ship.mass.ShipMassFrame;

/**
 * Recomputes a ship's mass from its hull at the moments where the incremental path cannot be trusted
 * to have kept up, and records it whenever the two disagree.
 *
 * <h2>Why these moments and no others</h2>
 *
 * <p>The incremental path — a delta applied as each block changes — is the working answer and stays
 * so. A full pass over the hull is justified only where something happened that the deltas were never
 * shown:</p>
 *
 * <ul>
 *   <li><b>Assembled.</b> The craft was fed to the accumulator block by block moments ago. The
 *       recompute here is not a repair; it is the independent second opinion that makes a
 *       disagreement detectable at all, and this is the one case where the two really ought to
 *       agree.</li>
 *   <li><b>Pasted.</b> A craft cut out of one world and re-registered around blocks somewhere else.
 *       Same shape as an assembly and the same reason.</li>
 *   <li><b>Loaded.</b> The record came off disk carrying a number of unknown provenance — written by
 *       some earlier version, possibly by a build with a different table. This is the one transition
 *       where a disagreement is not a defect but the entire reason to recompute, so it is applied
 *       WITHOUT being reported.</li>
 * </ul>
 *
 * <p>Deliberately NOT on a timer and NOT on a positional probe. Before the engine names a ship its
 * blocks are already loaded and ticking at shipyard addresses no player can reach; a frame computed
 * in that window is a frame about a craft that does not exist yet. Arming on the naming announcement
 * is what makes "named" a precondition by construction rather than by a check somebody can forget.</p>
 *
 * <h2>Drift is recorded, never corrected quietly, and never thrown</h2>
 *
 * <p>A disagreement on an assembly or a paste means a trigger is missing somewhere, and the repair is
 * that trigger. Substituting the right number silently would turn the safety net into normal
 * operation and destroy the only signal that anything is wrong — so the authoritative frame is
 * written AND the disagreement is kept, with its sign, for a test to assert on.</p>
 *
 * <p>It is kept rather than thrown because this runs inside the world tick, from an event whose
 * contract forbids a handler to throw: an exception here leaves the tick with nothing between it and
 * the server loop, and a dead server reports "the process exited", not "the mass drifted by 4%". A
 * failure nobody can read is not a louder failure.</p>
 */
public final class ShipMassTrigger {

    private ShipMassTrigger() {}

    private static final Logger LOG = LogManager.getLogger("advancedrocketry.mass");

    /** Disagreements seen since the last reset. Bounded: a broken build must not fill the heap. */
    private static final List<String> DRIFT = new ArrayList<>();

    private static final int MAX_DRIFT = 64;

    /** Recomputes that ran, whatever they found — so "no drift" is separable from "never ran". */
    private static int recomputes;

    /** Recomputes that produced no frame: the ship owned no blocks, or every block priced at zero. */
    private static int skipped;

    private static int driftDropped;

    /** The Forge subscriber. Registered for the whole run; the work is gated by the event itself. */
    public static final class Hooks {
        @SubscribeEvent
        public void onShipNamed(ShipLifecycleEvent.ShipNamed event) {
            try {
                recompute(event);
            } catch (Throwable failure) {
                // The event contract forbids a handler to throw, and this one runs inside the ship
                // manager's own tick. A mass model that cannot compute is a degraded ship; a mass
                // model that kills the world tick is a degraded server.
                LOG.error("ship mass recompute failed for " + event.shipUuid, failure);
            }
        }
    }

    private static synchronized void recompute(ShipLifecycleEvent.ShipNamed event) {
        ShipMassFrame authority = ShipHullMass.frameOf(event.world, event.shipUuid);
        recomputes++;
        if (authority == null) {
            skipped++;
            return;
        }
        ShipData ship = VSBridge.shipDataByUuid(event.world, event.shipUuid);
        if (ship == null) {
            skipped++;
            return;
        }
        ShipInertiaData record = ship.getInertiaData();
        if (event.cause != ShipLifecycleEvent.Cause.LOADED) {
            // Compared BEFORE the write, or there is nothing left to compare against.
            String drift = ShipInertiaWriter.compare(record, authority, String.valueOf(event.shipUuid));
            if (drift != null) {
                noteDrift(event.cause + ": " + drift);
            }
        }
        ShipInertiaWriter.applyTo(record, authority, String.valueOf(event.shipUuid));
    }

    private static void noteDrift(String description) {
        LOG.warn(description);
        if (DRIFT.size() >= MAX_DRIFT) {
            driftDropped++;
            return;
        }
        DRIFT.add(description);
    }

    /** Forget everything, so a test leg starts against a clean recorder. */
    public static synchronized void reset() {
        DRIFT.clear();
        recomputes = 0;
        skipped = 0;
        driftDropped = 0;
    }

    /**
     * What the recomputes found, as a JSON object body (no braces).
     *
     * <p>Every field in every state: a build where nothing drifted and a build where nothing ran are
     * different answers, and only the counters tell them apart.</p>
     */
    public static synchronized String summary() {
        StringBuilder out = new StringBuilder();
        out.append("\"recomputes\":").append(recomputes);
        out.append(",\"skipped\":").append(skipped);
        out.append(",\"driftCount\":").append(DRIFT.size() + driftDropped);
        out.append(",\"driftDropped\":").append(driftDropped);
        out.append(",\"drift\":[");
        for (int i = 0; i < DRIFT.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(DRIFT.get(i).replace('"', '\'')).append('"');
        }
        out.append(']');
        return out.toString();
    }
}
