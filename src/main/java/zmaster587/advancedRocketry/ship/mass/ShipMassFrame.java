package zmaster587.advancedRocketry.ship.mass;

import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * What a ship weighs, where that weight sits, and how it resists being turned.
 *
 * <p>Three numbers drive every derived flight characteristic, and they are all here: the total mass
 * decides acceleration for a given thrust, the centre of mass decides what torque a thruster makes
 * from where it is bolted, and the inertia tensor decides what angular acceleration that torque
 * actually produces. A design that keeps only a single "weight" scalar can express none of the three
 * questions players care about — why a loaded ship is sluggish, why an unevenly loaded one pulls to
 * one side, and why a long ship rolls more readily than it yaws.</p>
 *
 * <p>The split into structural, content and crew is not decoration: structure changes only when the
 * hull is built on or broken, while content and crew change constantly and without any block ever
 * changing. Keeping them apart is what lets the expensive half be recomputed rarely and the cheap
 * half often, and it is also what the pre-flight readout shows — "1000 t of ship, 500 t of cargo" is
 * a sentence a player can act on in a way that "1500 t" is not.</p>
 *
 * <p>The frame is <b>derived</b>: it is computed from the hull and its contents and is never saved.
 * A saved characteristic goes stale the moment somebody welds a plate on, and a stale ship
 * characteristic is worse than an absent one because it looks authoritative.</p>
 *
 * <p>Frames are immutable; build one with {@link ShipMassFrameBuilder}.</p>
 */
public final class ShipMassFrame {

    private final double structuralMass;
    private final double contentMass;
    private final double crewMass;
    private final Vector3dc centreOfMass;
    private final Matrix3dc inertia;

    ShipMassFrame(double structuralMass, double contentMass, double crewMass,
                  Vector3dc centreOfMass, Matrix3dc inertia) {
        this.structuralMass = structuralMass;
        this.contentMass = contentMass;
        this.crewMass = crewMass;
        this.centreOfMass = centreOfMass;
        this.inertia = inertia;
    }

    /** A ship with nothing in it: no mass, origin centre, zero inertia. */
    public static ShipMassFrame empty() {
        return new ShipMassFrame(0.0D, 0.0D, 0.0D, new Vector3d(), new Matrix3d().zero());
    }

    /** Kilograms of hull. */
    public double getStructuralMass() {
        return structuralMass;
    }

    /** Kilograms of fluids, items and machine contents. */
    public double getContentMass() {
        return contentMass;
    }

    /** Kilograms of people aboard, with what they carry. */
    public double getCrewMass() {
        return crewMass;
    }

    /** Kilograms, all three categories. Never negative. */
    public double getTotalMass() {
        return structuralMass + contentMass + crewMass;
    }

    /**
     * The centre of mass, in ship-frame metres. Torque arms are measured from here, so this moving is
     * what makes an asymmetrically loaded ship handle differently rather than merely slower.
     */
    public Vector3dc getCentreOfMass() {
        return centreOfMass;
    }

    /**
     * The inertia tensor about the centre of mass, in the ship's own frame, kg·m².
     *
     * <p>Symmetric, and positive definite whenever the ship has any mass at all — the solver inverts
     * it every physics step, so a singular tensor would surface as a NaN torque rather than as a
     * small error.</p>
     */
    public Matrix3dc getInertia() {
        return inertia;
    }

    /**
     * The same ship, with its centre of mass expressed about an origin {@code (dx, dy, dz)} further
     * back — i.e. every coordinate shifted by {@code +(dx, dy, dz)}.
     *
     * <p><b>Why a frame needs this at all.</b> A frame is only meaningful about a stated origin, and
     * the origin a hull is most cheaply MEASURED about is not the one the physics record is KEPT in.
     * The record's centre of mass is in the ship's own subspace address space, which for a real craft
     * is millions of blocks from zero; accumulating second moments about a point that far away spends
     * most of a double's precision on a constant that cancels at the end. Measuring about something
     * near the hull and translating the answer costs one addition and keeps every intermediate at the
     * scale of the ship.</p>
     *
     * <p>Only the centre moves. The inertia tensor is already expressed <em>about the centre of
     * mass</em>, and that is invariant under translation — which is the property that makes this safe
     * and is worth pinning rather than assuming.</p>
     */
    public ShipMassFrame translated(double dx, double dy, double dz) {
        return new ShipMassFrame(structuralMass, contentMass, crewMass,
                new Vector3d(centreOfMass).add(dx, dy, dz), inertia);
    }

    @Override
    public String toString() {
        return "ShipMassFrame{total=" + getTotalMass() + "kg (structural=" + structuralMass
                + ", content=" + contentMass + ", crew=" + crewMass + "), com=" + centreOfMass + "}";
    }
}
