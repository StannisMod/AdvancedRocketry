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

    /** Sentinel for {@link #orbitalDistance()}: this body has no orbit of its own (a star, a POI). */
    public static final int ORBIT_UNKNOWN = 0;

    /**
     * Sentinel for {@link #radiusEarths()}: this body has no radius of its own — a belt, a POI,
     * anything that is not a sphere. NOT "we forgot to set one": a consumer draws such a body at its
     * minimum size rather than guessing, because guessing is how a moon and a gas giant came to be
     * drawn identically.
     */
    public static final double RADIUS_UNKNOWN = 0d;

    private final GalacticCoord name;
    private final CellFrame frame;
    private final BodyEphemeris offsetLaw;
    private final SystemBodyKind kind;
    private final int dimId;
    private final int starId;
    private final int orbitalDistance;
    /** This body's own radius in EARTH radii, or {@link #RADIUS_UNKNOWN}. See {@link #radiusEarths()}. */
    private final double radiusEarths;

    /**
     * A body at rest in a STATIC frame — the reading for a POI, a fixture, or anything derived
     * without a system to ride. {@code address}'s sector triple becomes the name and its local offset
     * the (constant) in-cell offset.
     */
    /**
     * A body that DOES NOT MOVE — pinned to a static frame at its own cell, forever.
     *
     * <p>Named rather than offered as a plain constructor on purpose. This used to be
     * {@code new SystemBody(address, kind, dimId, starId, orbit)}, and it read like the ordinary way
     * to make a body while silently choosing immobility: the procedural generator built every planet
     * through it, so a whole galaxy of worlds stood still relative to their stars while the same
     * systems authored in XML orbited. A body that does not move is a real and legitimate thing — a
     * star at its own system's anchor, a belt centred on that star — but it is a CHOICE, and the
     * choice now has to be spelled.</p>
     *
     * <p>For a body that moves, pass its {@link CellFrame} and {@link BodyEphemeris} explicitly.</p>
     */
    public static SystemBody fixedAt(GalacticCoord address, SystemBodyKind kind, int dimId, int starId) {
        return fixedAt(address, kind, dimId, starId, ORBIT_UNKNOWN);
    }

    /** The same, carrying the body's orbital radius — see {@link #orbitalDistance()}. */
    public static SystemBody fixedAt(GalacticCoord address, SystemBodyKind kind, int dimId, int starId,
                                     int orbitalDistance) {
        return new SystemBody(requireAddress(address).cellCentre(), CellFrame.staticAt(address),
                BodyEphemeris.fixed(address.localX(), address.localY(), address.localZ()),
                kind, dimId, starId, orbitalDistance);
    }

    public SystemBody(GalacticCoord name, CellFrame frame, BodyEphemeris offsetLaw,
                      SystemBodyKind kind, int dimId, int starId) {
        this(name, frame, offsetLaw, kind, dimId, starId, ORBIT_UNKNOWN);
    }

    public SystemBody(GalacticCoord name, CellFrame frame, BodyEphemeris offsetLaw,
                      SystemBodyKind kind, int dimId, int starId, int orbitalDistance) {
        this(name, frame, offsetLaw, kind, dimId, starId, orbitalDistance, RADIUS_UNKNOWN);
    }

    public SystemBody(GalacticCoord name, CellFrame frame, BodyEphemeris offsetLaw,
                      SystemBodyKind kind, int dimId, int starId, int orbitalDistance,
                      double radiusEarths) {
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
        this.orbitalDistance = orbitalDistance;
        this.radiusEarths = Double.isNaN(radiusEarths) || radiusEarths < 0d
                ? RADIUS_UNKNOWN : radiusEarths;
    }

    /**
     * How big this body actually is, in EARTH radii, or {@link #RADIUS_UNKNOWN}.
     *
     * <p>A body's size is a property of the body, and it travels with it because nothing downstream
     * can recover it: a procedural world has no dimension to look it up in until somebody lands on
     * it, and the render feed reaches a client that cannot see the universe registry at all. Until
     * this existed the sky sized every body by DISTANCE alone, so a moon and a gas giant beside each
     * other drew identically.</p>
     */
    public double radiusEarths() {
        return radiusEarths;
    }

    /** The same body, carrying {@code radiusEarths}. The generators' way of stating a body's size. */
    public SystemBody withRadius(double newRadiusEarths) {
        return new SystemBody(name, frame, offsetLaw, kind, dimId, starId, orbitalDistance,
                newRadiusEarths);
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
    /** This body's motion law about its primary — see {@link BodyEphemeris#distUnits()}. */
    public BodyEphemeris offsetLaw() {
        return offsetLaw;
    }

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

    /**
     * How far this body orbits its primary, in Advanced Rocketry distance units (100 = 1 AU), or
     * {@link #ORBIT_UNKNOWN} for a body with no orbit of its own.
     *
     * <p>It travels WITH the body rather than being recomputed from the body's cell, because a cell is
     * coarse — a whole neighbourhood — while the orbit is what every physical property of the world is
     * derived from. Recovering it from the address would make a planet's temperature a function of the
     * placement arithmetic, so a tuning change to the layout would silently re-climate every world in
     * the galaxy.</p>
     */
    public int orbitalDistance() {
        return orbitalDistance;
    }

    /**
     * This body with a realized dimension attached. Used exactly once per body, when a descent turns it
     * from a scanned dot into a world; everything else about it — its name, its frame, its orbit — is
     * carried over untouched, because realization materializes what was already derived and changes
     * nothing about where the body is.
     */
    public SystemBody withDimId(int newDimId) {
        return newDimId == dimId ? this
                : new SystemBody(name, frame, offsetLaw, kind, newDimId, starId, orbitalDistance);
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
                : new SystemBody(name, newFrame, offsetLaw, kind, dimId, starId, orbitalDistance,
                        radiusEarths);
    }

    public void writeToNBT(NBTTagCompound nbt) {
        name.writeToNBT(nbt); // nested sub-tag "galacticCoord"
        frame.writeToNBT(nbt); // nested sub-tag "frame"
        offsetLaw.writeToNBT(nbt); // nested sub-tag "ephemeris"
        nbt.setString("kind", kind.name());
        nbt.setInteger("dimId", dimId);
        nbt.setInteger("starId", starId);
        if (orbitalDistance != ORBIT_UNKNOWN) {
            nbt.setInteger("orbitalDist", orbitalDistance);
        }
        if (radiusEarths != RADIUS_UNKNOWN) {
            nbt.setDouble("radiusEarths", radiusEarths);
        }
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
                nbt.getInteger("starId"),
                nbt.getInteger("orbitalDist"),
                nbt.getDouble("radiusEarths"));
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
                && orbitalDistance == other.orbitalDistance
                && Double.compare(radiusEarths, other.radiusEarths) == 0
                && name.equals(other.name) && offsetLaw.equals(other.offsetLaw)
                && frame.equals(other.frame);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + kind.hashCode();
        result = 31 * result + dimId;
        result = 31 * result + starId;
        result = 31 * result + orbitalDistance;
        result = 31 * result + Double.hashCode(radiusEarths);
        result = 31 * result + offsetLaw.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "SystemBody[" + kind + " dim=" + dimId + " star=" + starId + " @ " + name.cellKey()
                + (offsetLaw.isStatic() ? "" : " +orbit") + "]";
    }

    /**
     * An in-cell offset held inside the cell, <b>reporting the first time it has to</b>.
     *
     * <p>The clamp itself is right: a body outside its own neighbourhood would be a body in a
     * different cell, so saturating is the only safe answer. What was wrong is that it was SILENT.
     * An orbit that overflows does not fail — every point of it beyond the face collapses onto the
     * face, so a giant's outer moons stack at one spot and stop moving, which is a defect that gets
     * looked for in the renderer, in the ephemeris and in the frame before anyone suspects a clamp.
     * One line per axis per JVM run, naming the overflow, turns a week into a grep.</p>
     */
    private static long clampInCell(long v) {
        if (v > MAX_IN_CELL) {
            reportOverflow(v, MAX_IN_CELL);
            return MAX_IN_CELL;
        }
        if (v < -GalacticCoord.HALF_CELL) {
            reportOverflow(v, -GalacticCoord.HALF_CELL);
            return -GalacticCoord.HALF_CELL;
        }
        return v;
    }

    /** Said ONCE per distinct overflow magnitude: a flooded log is a log nobody reads either. */
    private static void reportOverflow(long raw, long clamped) {
        if (REPORTED_OVERFLOWS.add(raw / GalacticCoord.CELL)) {
            LOGGER.error("a body's in-cell offset {} is outside its own cell (half-cell {}) and was "
                            + "flattened onto the face at {}. Every further point of that orbit lands "
                            + "on the same spot, so the body will appear to stop moving: its orbit is "
                            + "wider than the cell that names it.",
                    raw, GalacticCoord.HALF_CELL, clamped);
        }
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("advancedrocketry/universe");
    private static final java.util.Set<Long> REPORTED_OVERFLOWS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
}
