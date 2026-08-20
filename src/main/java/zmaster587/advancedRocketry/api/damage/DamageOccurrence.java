package zmaster587.advancedRocketry.api.damage;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * What just happened to one unit — a cause, a severity and a place, at a moment.
 *
 * <h3>Why this exists beside the stage, which is already there</h3>
 * <p>A stage answers <em>how broken am I</em>: it is durable, it survives a save and a reassembly, and
 * it is PULLED by whoever wants it. It cannot answer <em>what just happened to me</em>, because a
 * shell and a collapsing hyperspace window leave exactly the same stage behind. A cause has no
 * persistent form to pull, so it is pushed once, at the moment it is true, and a unit that was not
 * listening has missed it — which is correct: it is news, not state.</p>
 *
 * <h3>The unit decides; this value decides nothing</h3>
 * <p>Everything here is a FACT about the event. There is no derate in it, no probability, no verdict,
 * because the consequence of being damaged is the unit's own to compute — a damaged engine throttles
 * itself back because it decides to stay safe, not because a table above it lowered a number. This
 * value is what the unit needs in order to decide, and nothing more.</p>
 */
public final class DamageOccurrence {

    private final DamageCause cause;
    private final ImpactKind kind;
    private final World world;
    private final BlockPos pos;
    private final Vec3d where;
    private final int stageBefore;
    private final int stageAfter;
    private final int maxStage;
    private final int budgetSpent;
    private final String shipId;

    public DamageOccurrence(DamageCause cause, ImpactKind kind, World world, BlockPos pos, Vec3d where,
                            int stageBefore, int stageAfter, int maxStage, int budgetSpent,
                            String shipId) {
        this.cause = cause;
        this.kind = kind;
        this.world = world;
        this.pos = pos;
        this.where = where;
        this.stageBefore = stageBefore;
        this.stageAfter = stageAfter;
        this.maxStage = maxStage;
        this.budgetSpent = budgetSpent;
        this.shipId = shipId;
    }

    /** What happened. Never null, and never to be switched over without a default — the list is open. */
    public DamageCause getCause() {
        return cause;
    }

    /**
     * What kind of thing struck, when something did; {@code null} for a cause that is not an arrival
     * along a line. Absent rather than defaulted on purpose: a hyperspace exit that reported itself as
     * {@code KINETIC} would be a lie every reader of this field would believe.
     */
    public ImpactKind getKind() {
        return kind;
    }

    /**
     * The world this happened in. Carried because a unit computing its own consequence — a tank
     * letting go, a reactor scramming — needs a handle on the game to do it with, and the alternative
     * was a static that would answer about whoever asked last.
     */
    public World getWorld() {
        return world;
    }

    /**
     * The unit's own position, in the frame its blocks live in — subspace aboard a ship, the world's
     * own otherwise. This is the position a tile lookup takes; {@link #getWhere()} is the one a
     * particle or a sound takes.
     */
    public BlockPos getPos() {
        return pos;
    }

    /**
     * Where it happened in WORLD coordinates, or {@code null} for a cause that has no place — a
     * hull-wide occurrence is not "at" anywhere and says so by carrying nothing rather than by
     * carrying the hull's centre, which would be a point nothing actually happened at.
     */
    public Vec3d getWhere() {
        return where;
    }

    /** The stage this unit was at before, and after. Equal means nothing advanced. */
    public int getStageBefore() {
        return stageBefore;
    }

    public int getStageAfter() {
        return stageAfter;
    }

    /** The stage at which this unit is gone. {@code getStageAfter() >= getMaxStage()} is destruction. */
    public int getMaxStage() {
        return maxStage;
    }

    /** How much energy went into THIS unit — the severity, in the engine's own units. */
    public int getBudgetSpent() {
        return budgetSpent;
    }

    /** The hull this happened to, or {@code null} when the unit is standing on the ground. */
    public String getShipId() {
        return shipId;
    }

    /**
     * Was this the blow that ended the unit? The one occurrence a unit most needs, and the one a
     * naive implementation loses: by the time an ordinary reader looks, the block is already air.
     */
    public boolean isDestroyed() {
        return stageAfter >= maxStage;
    }

    @Override
    public String toString() {
        return "DamageOccurrence[" + cause + (kind == null ? "" : "/" + kind)
                + " at " + pos + " stage " + stageBefore + "->" + stageAfter + "/" + maxStage
                + " spent " + budgetSpent + (shipId == null ? "" : " ship " + shipId) + "]";
    }
}
