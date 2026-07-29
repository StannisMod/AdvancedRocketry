package zmaster587.advancedRocketry.navigation;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

/**
 * One address a memory crystal holds: <b>which body</b> it is, <b>where</b> that body was seen,
 * <b>what</b> it is, <b>how much</b> of it has been resolved, and <b>when</b> that was observed.
 *
 * <p><b>An entry names a BODY, not a point in space.</b> Bodies orbit, so the coordinate an entry
 * carries is only where the body stood when it was written — the record's <i>observation</i>, useful
 * for showing the pilot something and as the last resort if the body can no longer be found. What
 * makes the entry mean the same destination tomorrow is {@link #dimId()}, the body's own dimension.
 * Without it, a crystal was a list of stale points: a pilot picked "Kepler", the ship flew to where
 * Kepler had been, and arrived in empty space with the drive's burst already spent.</p>
 *
 * <p>Knowledge is a stamp of a moment, not a permanent truth — a terraformed planet or a moved station
 * goes stale — so every entry carries the world tick it was observed at. That timestamp is what lets two
 * crystals (or a ship and its base) merge by <b>freshness</b>: same body, newer observation wins.</p>
 *
 * <p>The detail {@link InfoTier} is the ladder the nav GUI reads to decide which fields it may show for
 * this body; it never exceeds what the observation that wrote it actually resolved.</p>
 *
 * <p>Immutable. The NBT shape is a same-version wire/save contract — the item travels between client and
 * server, and between players.</p>
 */
public final class CrystalEntry {

    private static final String KEY_NAME = "name";
    private static final String KEY_KIND = "kind";
    private static final String KEY_DETAIL = "detail";
    private static final String KEY_OBSERVED = "observedTick";
    private static final String KEY_DIM = "dim";

    private final GalacticCoord coord;
    private final String name;
    private final SystemBodyKind kind;
    private final InfoTier detail;
    private final long observedTick;
    private final int dimId;

    /** A record of a bare COORDINATE — a hand-noted point with no body behind it. */
    public CrystalEntry(GalacticCoord coord, String name, SystemBodyKind kind,
                        InfoTier detail, long observedTick) {
        this(coord, name, kind, detail, observedTick, Constants.INVALID_PLANET);
    }

    public CrystalEntry(GalacticCoord coord, String name, SystemBodyKind kind,
                        InfoTier detail, long observedTick, int dimId) {
        if (coord == null) {
            throw new IllegalArgumentException("a crystal entry without a coordinate is not an address");
        }
        this.coord = coord;
        this.name = name == null ? "" : name;
        this.kind = kind == null ? SystemBodyKind.PLANET : kind;
        this.detail = detail == null ? InfoTier.TELESCOPE : detail;
        this.observedTick = observedTick;
        this.dimId = dimId;
    }

    /** Where the body stood when this was observed. NOT a durable destination — see {@link #dimId()}. */
    public GalacticCoord coord() {
        return coord;
    }

    /**
     * The dimension of the body this entry names, or {@link Constants#INVALID_PLANET} for a record of
     * a bare coordinate. This is the entry's IDENTITY: it is what a pick aims the ship at, and what
     * lets a moving destination still be found tomorrow.
     */
    public int dimId() {
        return dimId;
    }

    /** {@code true} when this entry names a body rather than a bare point in space. */
    public boolean namesBody() {
        return dimId != Constants.INVALID_PLANET;
    }

    /**
     * What two records must agree on to be records of the SAME thing: the body, where there is one,
     * and otherwise the coordinate. A body's coordinate changes as it orbits, so keying knowledge by
     * coordinate alone would let one planet accumulate an entry per observation.
     */
    public String identityKey() {
        return namesBody() ? bodyIdentityKey(dimId) : "cell:" + coord.cellKey();
    }

    /** The {@link #identityKey()} any entry for the body in dimension {@code dimId} carries. */
    public static String bodyIdentityKey(int dimId) {
        return "dim:" + dimId;
    }

    /** The label shown in the nav GUI; may be empty for a hand-typed coordinate. */
    public String name() {
        return name;
    }

    public SystemBodyKind kind() {
        return kind;
    }

    /** How much of this body has been resolved — the ceiling on what the nav GUI may reveal. */
    public InfoTier detail() {
        return detail;
    }

    /** World tick of the observation this entry records. */
    public long observedTick() {
        return observedTick;
    }

    /**
     * The fresher of the two records of ONE body: the newer observation wins outright, and a tie
     * keeps the one that resolved more detail. Callers must only merge entries with the same
     * {@link #identityKey()} — merging two different bodies is a bug, not a policy choice, so it
     * throws. Two observations of the same body at different points in its orbit DO merge; that is
     * the whole reason identity is not the coordinate.
     */
    public CrystalEntry mergeWith(CrystalEntry other) {
        if (other == null) {
            return this;
        }
        if (!identityKey().equals(other.identityKey())) {
            throw new IllegalArgumentException("refusing to merge two different addresses: "
                    + identityKey() + " vs " + other.identityKey());
        }
        if (other.observedTick > observedTick) {
            return other;
        }
        if (other.observedTick < observedTick) {
            return this;
        }
        return other.detail.atLeast(detail) ? other : this;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound nbt = new NBTTagCompound();
        coord.writeToNBT(nbt);
        nbt.setString(KEY_NAME, name);
        nbt.setString(KEY_KIND, kind.name());
        nbt.setString(KEY_DETAIL, detail.name());
        nbt.setLong(KEY_OBSERVED, observedTick);
        nbt.setInteger(KEY_DIM, dimId);
        return nbt;
    }

    /**
     * Read an entry written by {@link #writeToNBT()}. An unreadable kind or detail level falls back to
     * the coarsest sane value rather than dropping the address: a crystal written by a build that knew
     * one more body kind must still navigate here.
     */
    public static CrystalEntry readFromNBT(NBTTagCompound nbt) {
        if (nbt == null) {
            return null;
        }
        return new CrystalEntry(
                GalacticCoord.readFromNBT(nbt),
                nbt.getString(KEY_NAME),
                parseKind(nbt.getString(KEY_KIND)),
                parseDetail(nbt.getString(KEY_DETAIL)),
                nbt.getLong(KEY_OBSERVED),
                // A crystal written before entries named bodies carries no identity; it still
                // navigates, as the coordinate record it always was.
                nbt.hasKey(KEY_DIM) ? nbt.getInteger(KEY_DIM) : Constants.INVALID_PLANET);
    }

    private static SystemBodyKind parseKind(String raw) {
        for (SystemBodyKind k : SystemBodyKind.values()) {
            if (k.name().equals(raw)) {
                return k;
            }
        }
        return SystemBodyKind.PLANET;
    }

    private static InfoTier parseDetail(String raw) {
        for (InfoTier t : InfoTier.values()) {
            if (t.name().equals(raw)) {
                return t;
            }
        }
        return InfoTier.TELESCOPE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CrystalEntry)) {
            return false;
        }
        CrystalEntry other = (CrystalEntry) o;
        return observedTick == other.observedTick
                && dimId == other.dimId
                && coord.equals(other.coord)
                && name.equals(other.name)
                && kind == other.kind
                && detail == other.detail;
    }

    @Override
    public int hashCode() {
        int h = coord.hashCode();
        h = 31 * h + name.hashCode();
        h = 31 * h + kind.hashCode();
        h = 31 * h + detail.hashCode();
        h = 31 * h + dimId;
        return 31 * h + (int) (observedTick ^ (observedTick >>> 32));
    }

    @Override
    public String toString() {
        return "CrystalEntry[" + identityKey() + " @" + coord.cellKey() + " '" + name + "' " + kind
                + " detail=" + detail + " t=" + observedTick + "]";
    }
}
