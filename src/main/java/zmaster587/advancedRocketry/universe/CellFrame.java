package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * The coordinate system a cell's contents live in — its ORIGIN as a function of world time. Every
 * cell that has a primary body rides that body; only a cell with none stands still.
 *
 * <p>A cell's origin is the position of its PRIMARY: the one real body the cell belongs to. That
 * position is the system anchor (which does not move) displaced by the primary's own orbital law, so
 * a frame is exactly two things: a static base and an ephemeris. A cell with no primary is VOID and
 * its frame is {@link #staticAt static} at {@code sector * CELL} — a degenerate frame, not an
 * exemption.</p>
 *
 * <p>A planet and its moons share ONE frame instance: they are one destination, and the moons move
 * inside it.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class CellFrame {

    private final AbsolutePos base;
    private final BodyEphemeris law;

    private CellFrame(AbsolutePos base, BodyEphemeris law) {
        this.base = base == null ? AbsolutePos.ORIGIN : base;
        this.law = law == null ? BodyEphemeris.STATIC : law;
    }

    /** The frame of a cell that does not move: origin {@code sector * CELL}, at every tick. */
    public static CellFrame staticAt(GalacticCoord name) {
        return new CellFrame(AbsolutePos.ofCellName(name), BodyEphemeris.STATIC);
    }

    /** A frame whose origin is {@code base} displaced by {@code law} — the primary's own motion. */
    public static CellFrame of(AbsolutePos base, BodyEphemeris law) {
        return new CellFrame(base, law);
    }

    /** Where this frame's origin is, absolutely, at world tick {@code tick}. */
    public AbsolutePos originAt(long tick) {
        return base.plus(law.offsetAt(tick));
    }

    /** The static base this frame is displaced from — the system anchor, in absolute blocks. */
    public AbsolutePos base() {
        return base;
    }

    /** The primary's displacement law. {@link BodyEphemeris#STATIC} for a void cell. */
    public BodyEphemeris law() {
        return law;
    }

    /** {@code true} iff this frame's origin is the same at every tick (a void cell, or a star). */
    public boolean isStatic() {
        return law.isStatic();
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound sub = new NBTTagCompound();
        sub.setLong("bx", base.x());
        sub.setLong("by", base.y());
        sub.setLong("bz", base.z());
        law.writeToNBT(sub); // nested sub-tag "ephemeris"
        nbt.setTag("frame", sub);
    }

    /**
     * Read a frame written by {@link #writeToNBT}. When the sub-tag is absent the caller's
     * {@code name} supplies a static frame — that is the honest default for a body whose frame was
     * never recorded, and it is what a void cell means.
     */
    public static CellFrame readFromNBT(NBTTagCompound nbt, GalacticCoord name) {
        if (nbt == null || !nbt.hasKey("frame")) {
            return staticAt(name);
        }
        NBTTagCompound sub = nbt.getCompoundTag("frame");
        return new CellFrame(AbsolutePos.of(sub.getLong("bx"), sub.getLong("by"), sub.getLong("bz")),
                BodyEphemeris.readFromNBT(sub));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CellFrame)) {
            return false;
        }
        CellFrame other = (CellFrame) o;
        return base.equals(other.base) && law.equals(other.law);
    }

    @Override
    public int hashCode() {
        return 31 * base.hashCode() + law.hashCode();
    }

    @Override
    public String toString() {
        return "CellFrame[base=" + base + ", law=" + law + "]";
    }
}
