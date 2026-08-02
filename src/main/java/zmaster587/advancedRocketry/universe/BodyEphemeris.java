package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.space.BlockDelta;

/**
 * A body's ORBITAL LAW, frozen as elements rather than as a position.
 *
 * <p>This is what makes a cell able to ride its body (C15 ADDR-6): given a tick, it answers where the
 * body stands relative to whatever it orbits, in blocks. It is the same law
 * {@code DimensionProperties.orbitThetaAt} + {@code positionFor} apply, lifted out of the dimension
 * object so that a body which no longer has one &mdash; a PINNED procedural system, a POI &mdash;
 * still moves.</p>
 *
 * <p><b>A pin freezes the ELEMENTS, never the positions.</b> Snapshotting a system's positions would
 * stop that system dead the first time a player built a station in it, because pin-on-touch fires on
 * the first {@code addPoi}. Elements cost the same four numbers and keep the system alive.</p>
 *
 * <p>Immutable value type; NBT round-trips as a flat sub-compound.</p>
 */
public final class BodyEphemeris {

    /** The law of something that does not move: zero displacement at every tick. */
    public static final BodyEphemeris STATIC = new BodyEphemeris(0L, 0L, 0L, 0d, 0d, 0d, false, 0d, 0L);

    // A FIXED law (period <= 0) carries its displacement directly; an ORBIT law derives it.
    private final long fixedX;
    private final long fixedY;
    private final long fixedZ;

    private final double distUnits;   // orbital distance, in the caller's unit
    private final double baseTheta;   // authored base angle, RADIANS
    private final double phiDegrees;  // inclination, DEGREES (the loader stores it verbatim)
    private final boolean retrograde;
    private final double periodTicks; // <= 0 or non-finite ⇒ the body does not advance in time
    private final long unitBlocks;    // blocks per unit of distUnits; 0 ⇒ this is a FIXED law

    private BodyEphemeris(long fixedX, long fixedY, long fixedZ, double distUnits, double baseTheta,
                          double phiDegrees, boolean retrograde, double periodTicks, long unitBlocks) {
        this.fixedX = fixedX;
        this.fixedY = fixedY;
        this.fixedZ = fixedZ;
        this.distUnits = distUnits;
        this.baseTheta = baseTheta;
        this.phiDegrees = phiDegrees;
        this.retrograde = retrograde;
        this.periodTicks = periodTicks;
        this.unitBlocks = unitBlocks;
    }

    /** A constant displacement — a station slot at a fixed point inside its cell, a belt marker. */
    public static BodyEphemeris fixed(long dx, long dy, long dz) {
        if (dx == 0L && dy == 0L && dz == 0L) {
            return STATIC;
        }
        return new BodyEphemeris(dx, dy, dz, 0d, 0d, 0d, false, 0d, 0L);
    }

    /**
     * An orbit about whatever this body is bound to: {@code (d·cos θ, d·sin φ, d·sin θ)} in units of
     * {@code unitBlocks}, with {@code θ = (2π·(t mod P)/P + baseTheta) · (retrograde ? −1 : +1)}.
     *
     * <p>The retrograde sign multiplies the SUM, not the time term alone — that is the shipped law and
     * a body's NAME is derived through it, so changing the grouping would move every retrograde body's
     * name.</p>
     */
    public static BodyEphemeris orbit(double distUnits, double baseTheta, double phiDegrees,
                                      boolean retrograde, double periodTicks, long unitBlocks) {
        return new BodyEphemeris(0L, 0L, 0L, distUnits, baseTheta, phiDegrees, retrograde,
                periodTicks, unitBlocks);
    }

    /** {@code true} iff this law is time-invariant — the degenerate frame of C15 ADDR-6/ADDR-7. */
    public boolean isStatic() {
        return unitBlocks == 0L || !(periodTicks > 0d) || Double.isInfinite(periodTicks)
                || distUnits == 0d;
    }

    /** The displacement, in blocks, at world tick {@code tick}. */
    public BlockDelta offsetAt(long tick) {
        if (unitBlocks == 0L) {
            return BlockDelta.of(fixedX, fixedY, fixedZ);
        }
        double theta = thetaAt(tick);
        double phi = Math.toRadians(phiDegrees);
        return BlockDelta.of(
                Math.round(distUnits * Math.cos(theta) * unitBlocks),
                Math.round(distUnits * Math.sin(phi) * unitBlocks),
                Math.round(distUnits * Math.sin(theta) * unitBlocks));
    }

    /** The orbital angle (radians) at {@code tick}. */
    public double thetaAt(long tick) {
        double timeTheta = 0d;
        if (periodTicks > 0d && !Double.isInfinite(periodTicks)) {
            timeTheta = ((tick % periodTicks) / periodTicks) * (2d * Math.PI);
        }
        return (timeTheta + baseTheta) * (retrograde ? -1d : 1d);
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound sub = new NBTTagCompound();
        sub.setLong("fx", fixedX);
        sub.setLong("fy", fixedY);
        sub.setLong("fz", fixedZ);
        sub.setDouble("d", distUnits);
        sub.setDouble("th", baseTheta);
        sub.setDouble("phi", phiDegrees);
        sub.setBoolean("retro", retrograde);
        sub.setDouble("period", periodTicks);
        sub.setLong("unit", unitBlocks);
        nbt.setTag("ephemeris", sub);
    }

    /** Read a law written by {@link #writeToNBT}, or {@link #STATIC} when the sub-tag is absent. */
    public static BodyEphemeris readFromNBT(NBTTagCompound nbt) {
        if (nbt == null || !nbt.hasKey("ephemeris")) {
            return STATIC;
        }
        NBTTagCompound sub = nbt.getCompoundTag("ephemeris");
        return new BodyEphemeris(sub.getLong("fx"), sub.getLong("fy"), sub.getLong("fz"),
                sub.getDouble("d"), sub.getDouble("th"), sub.getDouble("phi"),
                sub.getBoolean("retro"), sub.getDouble("period"), sub.getLong("unit"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BodyEphemeris)) {
            return false;
        }
        BodyEphemeris other = (BodyEphemeris) o;
        return fixedX == other.fixedX && fixedY == other.fixedY && fixedZ == other.fixedZ
                && Double.compare(distUnits, other.distUnits) == 0
                && Double.compare(baseTheta, other.baseTheta) == 0
                && Double.compare(phiDegrees, other.phiDegrees) == 0
                && retrograde == other.retrograde
                && Double.compare(periodTicks, other.periodTicks) == 0
                && unitBlocks == other.unitBlocks;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(fixedX);
        result = 31 * result + Long.hashCode(fixedY);
        result = 31 * result + Long.hashCode(fixedZ);
        result = 31 * result + Double.hashCode(distUnits);
        result = 31 * result + Double.hashCode(baseTheta);
        result = 31 * result + Double.hashCode(phiDegrees);
        result = 31 * result + (retrograde ? 1 : 0);
        result = 31 * result + Double.hashCode(periodTicks);
        result = 31 * result + Long.hashCode(unitBlocks);
        return result;
    }

    @Override
    public String toString() {
        return unitBlocks == 0L
                ? "BodyEphemeris[fixed " + fixedX + "," + fixedY + "," + fixedZ + "]"
                : "BodyEphemeris[d=" + distUnits + " base=" + baseTheta + " phi=" + phiDegrees
                        + " retro=" + retrograde + " period=" + periodTicks + " unit=" + unitBlocks + "]";
    }
}
