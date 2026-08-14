package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * A position or displacement in LIGHT YEARS — the vocabulary the galaxy layer is written in.
 *
 * <p>Why not blocks: an intergalactic position reaches 10¹² light years, which is 4·10²⁵ blocks and
 * does not fit a {@code long}. Below the galaxy layer, blocks and a sectorised {@link GalacticCoord}
 * are exactly right and stay so; above it, the honest type is a physical length in a {@code double},
 * whose relative precision is uniform at any magnitude. The conversion between the two lives in
 * {@link UniverseScale} and nowhere else.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class LightYearVector {

    public static final LightYearVector ZERO = new LightYearVector(0d, 0d, 0d);

    private final double x;
    private final double y;
    private final double z;

    private LightYearVector(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static LightYearVector of(double x, double y, double z) {
        return new LightYearVector(x, y, z);
    }

    /** The position a cell NAME stands at in the static frame, in light years. */
    public static LightYearVector ofCell(GalacticCoord cell) {
        return new LightYearVector(
                UniverseScale.lightYearsForCells(cell.sectorX()),
                UniverseScale.lightYearsForCells(cell.sectorY()),
                UniverseScale.lightYearsForCells(cell.sectorZ()));
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public LightYearVector plus(LightYearVector other) {
        return new LightYearVector(x + other.x, y + other.y, z + other.z);
    }

    public LightYearVector minus(LightYearVector other) {
        return new LightYearVector(x - other.x, y - other.y, z - other.z);
    }

    public LightYearVector scale(double factor) {
        return new LightYearVector(x * factor, y * factor, z * factor);
    }

    /** Length in light years. */
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Distance to {@code other} in light years. */
    public double distanceTo(LightYearVector other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ") ly";
    }
}
