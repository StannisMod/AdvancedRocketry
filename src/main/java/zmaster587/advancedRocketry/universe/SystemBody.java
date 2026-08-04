package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.BlockDelta;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * One addressable object inside a star system (universe-model.md &sect;4): a star, planet, moon, or a POI
 * (asteroid belt, station slot). It is pure DATA — its walkable realization is Layer 2 (the ship branch),
 * never here.
 *
 * <p><b>A body has a NAME and a PLACE, and they are not the same number.</b> The name is the
 * {@link #name() sector triple} of its cell: an identifier, fixed for the life of the save, which is
 * what a coordinate a player wrote down keeps denoting. The place is a function of world time and
 * comes in two readings:</p>
 * <ul>
 *   <li>{@link #inCellOffsetAt(long)} — where the body stands inside its own cell's frame. Zero for
 *       the cell's PRIMARY by construction (the primary is what the frame is centred on); LIVE for a
 *       moon, which shares its parent's cell name and orbits inside it.</li>
 *   <li>{@link #absoluteAt(long)} — the frame origin at that tick plus the offset. Only ever an
 *       intermediate for a distance or a direction; nothing is stored as one, because a stored
 *       coordinate may not mean something different from the tick it was written at.</li>
 * </ul>
 *
 * <p>{@link #addressAt(long)} packages the first reading as a {@link GalacticCoord} — name plus
 * in-cell offset, which is the canonical stored form and the right input to anything that works
 * INSIDE one cell (a placement ring, the descent trigger, an entry coordinate). It is deliberately
 * not an absolute: two of those can only be compared at the same tick and through both frames.</p>
 *
 * <p>A {@link SystemBodyKind#PLANET planet}/{@link SystemBodyKind#MOON moon} carries the {@code dimId} of its
 * {@code DimensionProperties} — the dimension a descent drops into; other kinds carry
 * {@link Constants#INVALID_PLANET}. {@code starId} is the owning system (negative for a procedural system).</p>
 */
public final class SystemBody {

    /** No content may sit outside its own cell — a cell is a whole neighbourhood — so an offset is bounded. */
    private static final long MAX_IN_CELL = GalacticCoord.HALF_CELL - 1L;

    private final GalacticCoord name;
    private final CellFrame frame;
    private final BodyEphemeris offsetLaw;
    private final SystemBodyKind kind;
    private final int dimId;
    private final int starId;

    /**
     * A body at rest in a STATIC frame — the reading for a POI, a fixture, or anything derived
     * without a system to ride. {@code address}'s sector triple becomes the name and its local offset
     * the (constant) in-cell offset.
     */
    public SystemBody(GalacticCoord address, SystemBodyKind kind, int dimId, int starId) {
        this(requireAddress(address).cellCentre(), CellFrame.staticAt(address),
                BodyEphemeris.fixed(address.localX(), address.localY(), address.localZ()),
                kind, dimId, starId);
    }

    public SystemBody(GalacticCoord name, CellFrame frame, BodyEphemeris offsetLaw,
                      SystemBodyKind kind, int dimId, int starId) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        this.name = name.cellCentre();
        this.frame = frame == null ? CellFrame.staticAt(name) : frame;
        this.offsetLaw = offsetLaw == null ? BodyEphemeris.STATIC : offsetLaw;
        this.kind = kind;
        this.dimId = dimId;
        this.starId = starId;
    }

    private static GalacticCoord requireAddress(GalacticCoord address) {
        if (address == null) {
            throw new NullPointerException("address");
        }
        return address;
    }

    /**
     * This body's DURABLE cell name — the sector triple, at cell centre. An identifier: neither a
     * passing tick nor any amount of flight changes it, and membership of a cell is decided by
     * comparing these.
     */
    public GalacticCoord name() {
        return name;
    }

    /** The frame this body's cell rides. A planet and its moons share one: they are one destination. */
    public CellFrame frame() {
        return frame;
    }

    /**
     * Where this body stands inside its own cell's frame at {@code tick}. Zero for the cell's
     * primary; live for a moon. Held inside the cell — a body outside its own neighbourhood would be
     * a body in a different cell.
     */
    public BlockDelta inCellOffsetAt(long tick) {
        BlockDelta raw = offsetLaw.offsetAt(tick);
        if (raw.isZero()) {
            return raw;
        }
        return BlockDelta.of(clampInCell(raw.dx()), clampInCell(raw.dy()), clampInCell(raw.dz()));
    }

    /**
     * The full in-frame address at {@code tick}: this body's durable name plus where it stands inside
     * that cell. This is the canonical stored/aimed form — what persists is always a name plus an
     * offset, never a raw absolute — and the right value for every consumer that works within one cell.
     */
    public GalacticCoord addressAt(long tick) {
        BlockDelta offset = inCellOffsetAt(tick);
        return offset.isZero() ? name : name.plusLocalSaturating(offset.dx(), offset.dy(), offset.dz());
    }

    /**
     * Where this body IS, absolutely, at {@code tick} — its cell's frame origin displaced by its
     * in-cell offset. Compare two of these only at the same tick: a distance exists only at a tick.
     */
    public AbsolutePos absoluteAt(long tick) {
        return frame.originAt(tick).plus(inCellOffsetAt(tick));
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

    /**
     * {@code true} iff this body is the kind that can be a cell's PRIMARY — the body a frame is
     * centred on. Moons ride their parent's frame and POIs ride whatever frame their cell has, so
     * neither may define one.
     */
    public boolean definesFrame() {
        return kind == SystemBodyKind.STAR || kind == SystemBodyKind.PLANET
                || kind == SystemBodyKind.GAS_GIANT || kind == SystemBodyKind.ASTEROID_BELT;
    }

    /**
     * This body re-bound to {@code newFrame}. A POI is persisted with its name and its offset only;
     * which frame that cell rides is a property of the CELL, resolved when the POI is served, so a
     * station in a planet's cell travels with the planet instead of being left behind in empty space.
     */
    public SystemBody withFrame(CellFrame newFrame) {
        return newFrame == null || newFrame.equals(frame)
                ? this
                : new SystemBody(name, newFrame, offsetLaw, kind, dimId, starId);
    }

    public void writeToNBT(NBTTagCompound nbt) {
        name.writeToNBT(nbt); // nested sub-tag "galacticCoord"
        frame.writeToNBT(nbt); // nested sub-tag "frame"
        offsetLaw.writeToNBT(nbt); // nested sub-tag "ephemeris"
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
        GalacticCoord name = GalacticCoord.readFromNBT(nbt);
        return new SystemBody(name, CellFrame.readFromNBT(nbt, name), BodyEphemeris.readFromNBT(nbt),
                kind,
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
                && name.equals(other.name) && offsetLaw.equals(other.offsetLaw)
                && frame.equals(other.frame);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + kind.hashCode();
        result = 31 * result + dimId;
        result = 31 * result + starId;
        result = 31 * result + offsetLaw.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "SystemBody[" + kind + " dim=" + dimId + " star=" + starId + " @ " + name.cellKey()
                + (offsetLaw.isStatic() ? "" : " +orbit") + "]";
    }

    private static long clampInCell(long v) {
        if (v > MAX_IN_CELL) {
            return MAX_IN_CELL;
        }
        return v < -GalacticCoord.HALF_CELL ? -GalacticCoord.HALF_CELL : v;
    }
}
