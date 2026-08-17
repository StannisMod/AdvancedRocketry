package zmaster587.advancedRocketry.ship.mass;

/**
 * One thing that weighs something, at a point in the ship's own frame.
 *
 * <p>Everything a ship's mass is made of enters the model through this type: a hull block, the fluid
 * in a tank, the items in a chest, a crewman in a seat. Keeping them in one currency is what makes
 * the centre of mass and the inertia tensor mean anything — a model where the hull is counted in one
 * place and the cargo in another can produce a centre of mass that lies outside the ship.</p>
 *
 * <p><b>Units are kilograms and metres.</b> One block is one metre, so a block of ordinary building
 * material weighs a few hundred kilograms, exactly as its density suggests. Nothing here knows about
 * gravity: mass is a property of the ship, weight is a property of where the ship happens to be, and
 * conflating the two makes a craft change character when it changes planet.</p>
 *
 * <p>The {@link #extent} is what the mass occupies, not where it sits. It exists because a body with
 * no size has no rotational inertia of its own, and a ship assembled purely from sizeless points can
 * come out with a singular inertia tensor — a straight line of blocks being the obvious case. The
 * physics solver inverts that tensor every step, so a singular one is not an inaccuracy, it is a NaN
 * torque. Giving each contributor its true box inertia removes the failure at the source.</p>
 */
public final class MassContributor {

    /** Which half of the ship a contributor belongs to; the split is what the readout reports. */
    public enum Kind {
        /** Blocks. Changes only when the hull is built on or broken. */
        STRUCTURAL,
        /** Fluids, items, anything held inside a machine. Changes without a block ever changing. */
        CONTENT,
        /** People aboard, with what they carry. */
        CREW
    }

    /** A block fills its cell, so a contributor standing in for one is a metre on a side. */
    public static final double BLOCK_EXTENT = 1.0D;

    private final double x;
    private final double y;
    private final double z;
    private final double mass;
    private final double extent;
    private final Kind kind;

    private MassContributor(double x, double y, double z, double mass, double extent, Kind kind) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.mass = mass;
        this.extent = extent;
        this.kind = kind;
    }

    /**
     * A contributor the size of one block, centred on {@code (x, y, z)} in ship-frame metres.
     *
     * @param mass kilograms; negative values are clamped away, since a negative contribution would
     *             let one part of a ship cancel another and produce a centre of mass nowhere near it
     */
    public static MassContributor ofBlock(double x, double y, double z, double mass, Kind kind) {
        return new MassContributor(x, y, z, Math.max(0.0D, mass), BLOCK_EXTENT, kind);
    }

    /**
     * A contributor of a stated size. Used where the thing is not a block — a crewman, or a load
     * concentrated in part of a hold.
     */
    public static MassContributor of(double x, double y, double z, double mass, double extent,
                                     Kind kind) {
        return new MassContributor(x, y, z, Math.max(0.0D, mass),
                Math.max(1.0e-3D, extent), kind);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    /** Kilograms. Never negative. */
    public double getMass() {
        return mass;
    }

    /** The side length of the box this mass is treated as filling, in metres. Never zero. */
    public double getExtent() {
        return extent;
    }

    public Kind getKind() {
        return kind;
    }

    @Override
    public String toString() {
        return "MassContributor{" + kind + " " + mass + "kg at (" + x + "," + y + "," + z
                + ") extent " + extent + "}";
    }
}
