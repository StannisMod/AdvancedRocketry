package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * One addressable object inside a star system (universe-model.md &sect;4): a star, planet, moon, or a POI
 * (asteroid belt, station slot). It is pure DATA — its walkable realization is Layer 2 (the ship branch),
 * never here.
 *
 * <p>Its full address is a single {@link GalacticCoord}: the <b>sector triple</b> is the system's cell and
 * the <b>local offset</b> is the in-system position (the star sits at the cell centre; other bodies within
 * &plusmn;2M blocks of it). One address type spans inter- AND intra-system placement.</p>
 *
 * <p>A {@link SystemBodyKind#PLANET planet}/{@link SystemBodyKind#MOON moon} carries the {@code dimId} of its
 * {@code DimensionProperties} — the dimension a descent drops into; other kinds carry
 * {@link Constants#INVALID_PLANET}. {@code starId} is the owning system (negative for a procedural system).</p>
 */
public final class SystemBody {

    private final GalacticCoord address;
    private final SystemBodyKind kind;
    private final int dimId;
    private final int starId;

    public SystemBody(GalacticCoord address, SystemBodyKind kind, int dimId, int starId) {
        if (address == null) {
            throw new NullPointerException("address");
        }
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        this.address = address;
        this.kind = kind;
        this.dimId = dimId;
        this.starId = starId;
    }

    /** The full galactic address: sector = system cell, local offset = in-system position. */
    public GalacticCoord address() {
        return address;
    }

    public SystemBodyKind kind() {
        return kind;
    }

    /** The dimension a descent drops into, or {@link Constants#INVALID_PLANET} for a non-dimension body. */
    public int dimId() {
        return dimId;
    }

    public int starId() {
        return starId;
    }

    /** {@code true} iff this body can be descended into as a walkable dimension. */
    public boolean isDescendTarget() {
        return kind.canDescend() && dimId != Constants.INVALID_PLANET;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        address.writeToNBT(nbt); // nested sub-tag "galacticCoord"
        nbt.setString("kind", kind.name());
        nbt.setInteger("dimId", dimId);
        nbt.setInteger("starId", starId);
    }

    public static SystemBody readFromNBT(NBTTagCompound nbt) {
        SystemBodyKind kind;
        try {
            kind = SystemBodyKind.valueOf(nbt.getString("kind"));
        } catch (IllegalArgumentException e) {
            kind = SystemBodyKind.STATION_SLOT; // unknown/renamed kind: keep it as an inert POI, don't crash
        }
        return new SystemBody(GalacticCoord.readFromNBT(nbt), kind,
                nbt.hasKey("dimId") ? nbt.getInteger("dimId") : Constants.INVALID_PLANET,
                nbt.getInteger("starId"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SystemBody)) {
            return false;
        }
        SystemBody other = (SystemBody) o;
        return dimId == other.dimId && starId == other.starId && kind == other.kind
                && address.equals(other.address);
    }

    @Override
    public int hashCode() {
        int result = address.hashCode();
        result = 31 * result + kind.hashCode();
        result = 31 * result + dimId;
        result = 31 * result + starId;
        return result;
    }

    @Override
    public String toString() {
        return "SystemBody[" + kind + " dim=" + dimId + " star=" + starId + " @ " + address + "]";
    }
}
