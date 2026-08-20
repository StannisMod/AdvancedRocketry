package zmaster587.advancedRocketry.api.damage;

import net.minecraft.util.math.Vec3d;

/**
 * One declared impact against structure — everything the damage engine needs and nothing about the
 * thing that produced it. A weapon names no block, no stage and no toughness; the engine names no
 * weapon, no archetype and no trajectory. This object is the whole of what crosses between them.
 *
 * <h3>Frames</h3>
 * <p>{@link #getPoint()} and {@link #getDirection()} are <b>world</b> coordinates. A shot is computed
 * in the world frame, so that is what it hands over; converting into the frame the blocks actually
 * live in happens once, inside the engine, where it can be got right in one place rather than at every
 * call site.</p>
 *
 * <h3>Budget</h3>
 * <p>{@link #getBudget()} is denominated in the same unit as the shield's impact energy, so a shield
 * that is overwhelmed hands its residual straight through as a budget with no conversion factor in
 * between. There is exactly one conversion in the whole chain and it already lives at the muzzle.</p>
 *
 * <h3>Identity</h3>
 * <p>{@link #getImpactId()} identifies this impact for as long as it might be retried. The engine
 * refuses a repeat outright, because the paths that make retries real — an impact resolved later
 * because its region was unloaded, a shot re-examined across a load transition — are exactly the paths
 * where double damage would never show up in a diff. A caller that genuinely wants a second, distinct
 * impact gives it a new id.</p>
 */
public final class ImpactRequest {

    private final long impactId;
    private final Vec3d point;
    private final Vec3d direction;
    private final int budget;
    private final ImpactKind kind;
    private final SelectionMode selectionMode;
    private final double reachBlocks;
    private final double crossSectionArea;
    private final boolean resumesInside;
    private final DamageCause cause;

    /** The cross-section a budget is priced against unless the caller says otherwise. */
    public static final double REFERENCE_AREA = Math.PI * 0.25D * 0.25D;

    /** Reach for a caller that has no notion of one — an explosion, a collision, a hazard. */
    public static final double UNBOUNDED_REACH = Double.MAX_VALUE;

    public ImpactRequest(long impactId, Vec3d point, Vec3d direction, int budget, ImpactKind kind,
                         SelectionMode selectionMode) {
        this(impactId, point, direction, budget, kind, selectionMode, UNBOUNDED_REACH, REFERENCE_AREA);
    }

    public ImpactRequest(long impactId, Vec3d point, Vec3d direction, int budget, ImpactKind kind,
                         SelectionMode selectionMode, double reachBlocks, double crossSectionArea) {
        this(impactId, point, direction, budget, kind, selectionMode, reachBlocks, crossSectionArea,
                false);
    }

    public ImpactRequest(long impactId, Vec3d point, Vec3d direction, int budget, ImpactKind kind,
                         SelectionMode selectionMode, double reachBlocks, double crossSectionArea,
                         boolean resumesInside) {
        this.resumesInside = resumesInside;
        this.impactId = impactId;
        this.point = point;
        this.direction = normalize(direction);
        this.budget = Math.max(0, budget);
        this.kind = kind == null ? ImpactKind.KINETIC : kind;
        this.selectionMode = selectionMode == null ? SelectionMode.PENETRATING : selectionMode;
        this.reachBlocks = reachBlocks <= 0.0D ? 0.0D : reachBlocks;
        this.crossSectionArea = crossSectionArea <= 0.0D ? REFERENCE_AREA : crossSectionArea;
        this.cause = DamageCause.IMPACT;
    }

    /** Copy constructor for {@link #withCause}; the only field that differs is the cause. */
    private ImpactRequest(ImpactRequest from, DamageCause cause) {
        this.impactId = from.impactId;
        this.point = from.point;
        this.direction = from.direction;
        this.budget = from.budget;
        this.kind = from.kind;
        this.selectionMode = from.selectionMode;
        this.reachBlocks = from.reachBlocks;
        this.crossSectionArea = from.crossSectionArea;
        this.resumesInside = from.resumesInside;
        this.cause = cause == null ? DamageCause.IMPACT : cause;
    }

    /**
     * The same request, declared as a different KIND OF EVENT.
     *
     * <p>Every geometric field means what it meant — something arrived at a point and spent a budget
     * along a direction — so this changes nothing about how the damage resolves. What it changes is
     * what the units it reaches are TOLD happened to them, and a hull scraping a canyon wall is not a
     * hull being shot at, however identical the arithmetic.</p>
     */
    public ImpactRequest withCause(DamageCause newCause) {
        return newCause == null || newCause == this.cause ? this : new ImpactRequest(this, newCause);
    }

    /** A solid body striking at a point and boring along its direction of travel. */
    public static ImpactRequest penetrating(long impactId, Vec3d point, Vec3d direction, int budget,
                                            ImpactKind kind) {
        return new ImpactRequest(impactId, point, direction, budget, kind, SelectionMode.PENETRATING);
    }

    /**
     * The same, from a body that is only allowed to get so far this time and has a cross-section of
     * its own — a shot boring through a hull over several ticks, which may spend only as much of its
     * path as it actually travelled.
     */
    public static ImpactRequest penetrating(long impactId, Vec3d point, Vec3d direction, int budget,
                                            ImpactKind kind, double reachBlocks,
                                            double crossSectionArea) {
        return new ImpactRequest(impactId, point, direction, budget, kind, SelectionMode.PENETRATING,
                reachBlocks, crossSectionArea, false);
    }

    /**
     * The same, from a body that is CONTINUING a bore it began on an earlier tick: it is standing in
     * the block it starts in and has already been charged for it.
     *
     * <p>Without this a slow round pays for the block it is embedded in once per tick and grinds it to
     * dust without moving, which is not "penetration takes time" — it is a shot that gets stronger the
     * slower it goes.</p>
     */
    public static ImpactRequest resuming(long impactId, Vec3d point, Vec3d direction, int budget,
                                         ImpactKind kind, double reachBlocks,
                                         double crossSectionArea) {
        return new ImpactRequest(impactId, point, direction, budget, kind, SelectionMode.PENETRATING,
                reachBlocks, crossSectionArea, true);
    }

    /**
     * What kind of event this was, for the units it reaches. Defaults to {@link DamageCause#IMPACT},
     * which is what every geometric request is unless its caller says otherwise — the damage layer
     * resolves all of them identically and only the telling differs.
     */
    public DamageCause getCause() {
        return cause;
    }

    /** Identity for retry refusal; see the class note. */
    public long getImpactId() {
        return impactId;
    }

    /** Where the impact meets structure, in WORLD coordinates. */
    public Vec3d getPoint() {
        return point;
    }

    /** Unit direction of travel, in WORLD coordinates (zero vector if the caller gave a degenerate one). */
    public Vec3d getDirection() {
        return direction;
    }

    /** Damage budget, in shield-energy-equivalent units. */
    public int getBudget() {
        return budget;
    }

    public ImpactKind getKind() {
        return kind;
    }

    public SelectionMode getSelectionMode() {
        return selectionMode;
    }

    /**
     * How far along its direction this impact may reach, in blocks. A body that penetrates over time
     * grants only the distance it actually travelled this tick; a caller with no such notion leaves it
     * {@link #UNBOUNDED_REACH} and the engine's own path limit is what bounds the walk.
     */
    public double getReachBlocks() {
        return reachBlocks;
    }

    /**
     * The body's cross-section, in square blocks. Material resists with a PRESSURE, so the energy a
     * body spends per unit of depth is that pressure times this area: the same energy behind a wider
     * face bores less far. Defaults to {@link #REFERENCE_AREA}, at which the price is exactly what it
     * was before areas were priced at all.
     */
    public double getCrossSectionArea() {
        return crossSectionArea;
    }

    /** True when the body already paid for the block it starts in, on an earlier tick of the same bore. */
    public boolean resumesInside() {
        return resumesInside;
    }

    private static Vec3d normalize(Vec3d v) {
        if (v == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        double len = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        if (len <= 1.0E-8D) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        return new Vec3d(v.x / len, v.y / len, v.z / len);
    }
}
