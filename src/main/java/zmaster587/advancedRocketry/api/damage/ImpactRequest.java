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

    public ImpactRequest(long impactId, Vec3d point, Vec3d direction, int budget, ImpactKind kind,
                         SelectionMode selectionMode) {
        this.impactId = impactId;
        this.point = point;
        this.direction = normalize(direction);
        this.budget = Math.max(0, budget);
        this.kind = kind == null ? ImpactKind.KINETIC : kind;
        this.selectionMode = selectionMode == null ? SelectionMode.PENETRATING : selectionMode;
    }

    /** A solid body striking at a point and boring along its direction of travel. */
    public static ImpactRequest penetrating(long impactId, Vec3d point, Vec3d direction, int budget,
                                            ImpactKind kind) {
        return new ImpactRequest(impactId, point, direction, budget, kind, SelectionMode.PENETRATING);
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
