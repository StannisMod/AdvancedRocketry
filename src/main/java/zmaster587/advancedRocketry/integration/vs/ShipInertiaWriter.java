package zmaster587.advancedRocketry.integration.vs;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.World;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.physics_data.ShipInertiaData;
import zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration;
import zmaster587.advancedRocketry.ship.mass.ShipMassFrame;

/**
 * The one place that writes a mass frame into the physics engine's own record, and the only place in
 * the mass model that holds physics-engine types.
 *
 * <h2>Why a writer and not just a provider</h2>
 *
 * <p>The engine keeps mass, centre of mass and the inertia tensor in one record that it re-reads
 * every physics tick, and it maintains that record as an <b>accumulator</b>: the paste path feeds it
 * one block at a time, a per-block hook applies deltas for the rest of the craft's life, and a world
 * load restores whatever number was serialized. The hull is never rescanned. So authoritative writes
 * are picked up for free — the incremental deltas are merely one way to produce those three numbers,
 * not a channel to fight.</p>
 *
 * <p>Writing a fresh centre of mass mid-flight is safe: the physics loop shifts the body's origin by
 * the same vector rotated into world space, so the craft does not jump when the centre moves.</p>
 *
 * <h2>Delta by default, recompute as the authority, disagreement REPORTED</h2>
 *
 * <p>{@link #apply} is the authority — a whole frame, computed from the hull, written as three fields
 * together. {@link #reconcile} is the instrument that says whether the cheap incremental path has
 * drifted from it, and it <b>does not correct anything</b>. That is deliberate and it is the point:
 * a reconciliation that silently substitutes the right number turns a safety net into normal
 * operation and destroys the only signal that a trigger is missing. The repair for drift is the
 * missing trigger; the report is what makes it findable. In a test build the report is a failure.</p>
 *
 * <h2>The tensor may never be singular</h2>
 *
 * <p>The physics loop inverts the tensor every tick, so a degenerate one is a NaN torque rather than
 * a rounding error — a single cube or a perfectly straight mast is enough to produce one. The frame
 * handed here is expected to be regularised already (its builder smears each contributor over its
 * own extent); this class refuses a frame whose tensor cannot be inverted rather than passing it on,
 * because a crash inside the physics tick names neither the ship nor the writer.</p>
 */
public final class ShipInertiaWriter {

    private ShipInertiaWriter() {}

    /**
     * Obtained straight from log4j rather than through the mod class. Reaching for the mod's own
     * static logger initialises the mod, which initialises the block registry, which refuses to load
     * before the game has bootstrapped — so a boundary class that logged that way could not be
     * exercised outside a running server, and the rules it enforces are exactly the kind that must be.
     */
    private static final Logger LOG = LogManager.getLogger("advancedrocketry.mass");

    /** Relative disagreement in total mass above which drift is worth reporting. */
    private static final double MASS_TOLERANCE = 0.01;

    /** Absolute disagreement in the centre of mass, in blocks, above which drift is worth reporting. */
    private static final double CENTRE_TOLERANCE = 0.05;

    /**
     * Write {@code frame} into the physics record of the craft named by {@code shipId}.
     *
     * <p>All three fields go together. Writing mass without the centre, or the centre without the
     * tensor, leaves the engine integrating a body that never existed.</p>
     *
     * @return {@code true} when the record was written; {@code false} when this world holds no such
     *         craft, which is a complete answer and not an error — an unnamed or unloaded craft has
     *         no record to write, and the caller must not invent one
     */
    public static boolean apply(@Nullable World world, @Nullable UUID shipId,
                                @Nullable ShipMassFrame frame) {
        if (world == null || shipId == null || frame == null) {
            return false;
        }
        ShipInertiaData record = recordOf(world, shipId);
        return record != null && applyTo(record, frame, String.valueOf(shipId));
    }

    /**
     * The write itself, against the record rather than against a world. Public because the rules it
     * enforces — all three fields together, a singular tensor refused — have to be reachable without a
     * running server: the record is a plain data holder, so a test can hold one, and a rule nothing can
     * exercise is a rule nothing keeps.
     */
    public static boolean applyTo(ShipInertiaData record, ShipMassFrame frame, String shipName) {
        Matrix3d tensor = new Matrix3d(frame.getInertia());
        if (!isInvertible(tensor)) {
            // Refused HERE, where the ship's name is still in hand. The same frame accepted would
            // crash inside the physics tick as a NaN torque, naming neither the craft nor the writer.
            LOG.error("refusing a singular inertia tensor for ship " + shipName
                    + "; the physics tick inverts it every step. frame=" + frame);
            return false;
        }
        record.setGameTickMass(frame.getTotalMass());
        record.setGameTickCenterOfMass(new Vector3d(frame.getCentreOfMass()));
        record.setGameMoITensor(tensor);
        return true;
    }

    /**
     * Compare the physics record against {@code authority} and report any disagreement. Changes
     * nothing.
     *
     * @return a description of the drift, or {@code null} when the record agrees with the authority
     *         (or when there is no such craft to compare)
     */
    @Nullable
    public static String reconcile(@Nullable World world, @Nullable UUID shipId,
                                  @Nullable ShipMassFrame authority) {
        if (world == null || shipId == null || authority == null) {
            return null;
        }
        ShipInertiaData record = recordOf(world, shipId);
        if (record == null) {
            return null;
        }
        String drift = compare(record, authority, String.valueOf(shipId));
        if (drift != null) {
            report(drift);
        }
        return drift;
    }

    /**
     * The comparison, against the record rather than against a world, and WITHOUT reporting: returns
     * the description of the drift or {@code null} when there is none. Split out, and public, so the
     * tolerances and the wording can be pinned by a test that is not also asserting how loudly a build
     * complains about them.
     */
    @Nullable
    public static String compare(ShipInertiaData record, ShipMassFrame authority, String shipName) {
        double recorded = record.getGameTickMass();
        double expected = authority.getTotalMass();
        // Relative on mass, because the tolerance has to mean the same thing for a shuttle and for a
        // capital hull; absolute on the centre, because a tenth of a block is a tenth of a block.
        double massScale = Math.max(Math.abs(expected), 1.0);
        double massError = Math.abs(recorded - expected);
        double centreError = new Vector3d(authority.getCentreOfMass())
                .sub(new Vector3d(record.getGameTickCenterOfMass())).length();
        if (massError / massScale <= MASS_TOLERANCE && centreError <= CENTRE_TOLERANCE) {
            return null;
        }
        // SIGN is part of the report, not just magnitude: a record that is consistently light points
        // at removals that were never applied, a heavy one at additions counted twice, and those are
        // different missing triggers.
        return "ship " + shipName + " inertia drift: mass recorded " + recorded
                + " vs authority " + expected + " (" + (recorded > expected ? "+" : "")
                + (recorded - expected) + ", " + String.format("%.2f%%", 100.0 * massError / massScale)
                + "), centre off by " + String.format("%.4f", centreError) + " blocks";
    }

    /**
     * Drift is a DEFECT in the trigger set, so it is loud in a test build and merely visible in
     * production: a player's game must not die over a mass that is 2% stale, but a test run that
     * tolerates it silently is how the missing trigger survives to the next release.
     */
    private static void report(String message) {
        if (TestProbeCommandRegistration.isTestMode()) {
            throw new IllegalStateException(message);
        }
        LOG.warn(message);
    }

    @Nullable
    private static ShipInertiaData recordOf(World world, UUID shipId) {
        if (!VSIntegration.isAvailable()) {
            return null;
        }
        ShipData ship = VSBridge.shipDataByUuid(world, shipId);
        return ship == null ? null : ship.getInertiaData();
    }

    /**
     * Whether the tensor has an inverse. Checked by determinant rather than by attempting the inverse,
     * because an inversion of a singular matrix yields NaNs instead of failing.
     */
    private static boolean isInvertible(Matrix3d tensor) {
        double det = tensor.determinant();
        return !Double.isNaN(det) && !Double.isInfinite(det) && Math.abs(det) > 1.0e-9;
    }
}
