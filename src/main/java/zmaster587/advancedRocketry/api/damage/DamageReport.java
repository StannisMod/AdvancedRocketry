package zmaster587.advancedRocketry.api.damage;

import net.minecraft.util.math.Vec3d;

/**
 * What structure did with a declared impact. The report states <em>facts</em> and never a decision:
 * whether the shot that produced this impact now terminates, keeps flying or ricochets is the
 * weapon's own business, and it is decidable from these fields.
 *
 * <p>There is deliberately <b>no per-block list</b>. A turret firing continuously produces a stream of
 * small impacts, and a list would be an allocation per shot on the hot path; which particular blocks
 * changed is a surfacing concern and rides the damage map, not the return value.</p>
 */
public final class DamageReport {

    private final DamageOutcome outcome;
    private final StopReason stopReason;
    private final int budgetSpent;
    private final int budgetLeft;
    private final int blocksStaged;
    private final int blocksDestroyed;
    private final Vec3d entryPoint;
    private final Vec3d exitPoint;
    private final int penetrationDepth;
    private final double distanceWalked;

    public DamageReport(DamageOutcome outcome, StopReason stopReason, int budgetSpent, int budgetLeft,
                        int blocksStaged, int blocksDestroyed, Vec3d entryPoint, Vec3d exitPoint,
                        int penetrationDepth) {
        this(outcome, stopReason, budgetSpent, budgetLeft, blocksStaged, blocksDestroyed, entryPoint,
                exitPoint, penetrationDepth, 0.0D);
    }

    public DamageReport(DamageOutcome outcome, StopReason stopReason, int budgetSpent, int budgetLeft,
                        int blocksStaged, int blocksDestroyed, Vec3d entryPoint, Vec3d exitPoint,
                        int penetrationDepth, double distanceWalked) {
        this.outcome = outcome;
        this.stopReason = stopReason;
        this.budgetSpent = budgetSpent;
        this.budgetLeft = budgetLeft;
        this.blocksStaged = blocksStaged;
        this.blocksDestroyed = blocksDestroyed;
        this.entryPoint = entryPoint;
        this.exitPoint = exitPoint;
        this.penetrationDepth = penetrationDepth;
        this.distanceWalked = Math.max(0.0D, distanceWalked);
    }

    /** Nothing damageable was met: no spend, no change. */
    public static DamageReport nothingStruck(int budget, StopReason reason) {
        return new DamageReport(DamageOutcome.NOTHING_STRUCK, reason, 0, budget, 0, 0, null, null, 0);
    }

    /** This identity was already applied; the caller is seeing its own earlier impact. */
    public static DamageReport duplicate(int budget) {
        return nothingStruck(budget, StopReason.DUPLICATE_IMPACT);
    }

    public DamageOutcome getOutcome() {
        return outcome;
    }

    public StopReason getStopReason() {
        return stopReason;
    }

    /** Budget consumed by structure. */
    public int getBudgetSpent() {
        return budgetSpent;
    }

    /** Budget still unspent — what a shot leaving the far side carries onward. */
    public int getBudgetLeft() {
        return budgetLeft;
    }

    /** How many blocks were advanced by at least one damage stage without being destroyed. */
    public int getBlocksStaged() {
        return blocksStaged;
    }

    public int getBlocksDestroyed() {
        return blocksDestroyed;
    }

    /** Where the impact entered structure, world frame; null when nothing was struck. */
    public Vec3d getEntryPoint() {
        return entryPoint;
    }

    /** Where it left, world frame; null unless the outcome is {@link DamageOutcome#EXITED}. */
    public Vec3d getExitPoint() {
        return exitPoint;
    }

    /** Blocks traversed along the path — what tells two weapons of equal energy apart. */
    /**
     * How far into the target this impact actually got, in blocks along its own direction — distinct
     * from {@link #getPenetrationDepth()}, which counts blocks met. A body that penetrates over time
     * advances by this, so a bore that stalls against armour advances by very little and one that
     * sails through advances by its whole reach.
     */
    public double getDistanceWalked() {
        return distanceWalked;
    }

    public int getPenetrationDepth() {
        return penetrationDepth;
    }
}
