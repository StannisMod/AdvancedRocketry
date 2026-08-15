package zmaster587.advancedRocketry.damage;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.damage.DamageOutcome;
import zmaster587.advancedRocketry.api.damage.StopReason;
import zmaster587.advancedRocketry.util.WeightEngine;

/**
 * The budget-and-spend loop: a damage budget is walked into blocks until it runs out.
 *
 * <p>Advancing one block by one stage costs {@code base + toughness x mult}, and the terminal stage is
 * destruction. That is the whole model — a bigger budget does not "do more damage" to one block, it
 * reaches further and takes more of them, which is what makes a heavy slug and a light round behave
 * differently at the same energy instead of being one number with two names.</p>
 *
 * <h3>Frame</h3>
 * <p>This class walks <b>in the frame it is handed</b> and knows nothing about which one that is. On a
 * ship the caller hands it subspace coordinates, because that is where ship blocks live; off a ship
 * the two frames are the same thing. Nothing here converts, so nothing here can convert wrongly; the
 * one conversion lives at the seam above.</p>
 */
public final class StructureDamageEngine {

    /**
     * Cost of advancing any block by one stage before toughness is counted, and the multiplier on
     * toughness. Both are balance numbers in shield-energy-equivalent units: with the defaults a
     * pane of glass gives way for a fraction of what a plated hull costs, and neither is pinned by a
     * test. They live here rather than in the config file until there is balance work to spend on
     * them.
     */
    private static final double STAGE_COST_BASE = 250.0D;
    private static final double STAGE_COST_TOUGHNESS_MULT = 250.0D;

    /**
     * How far a single impact is willing to bore. Not a physical limit — a limit on how much world one
     * impact may walk before the engine hands the remaining budget back to the caller and stops. A
     * shot with budget left at this point is reported as having exited, carrying that budget: better
     * that it re-enters as a fresh impact than that the engine silently swallows it.
     */
    private static final int MAX_PATH_BLOCKS = 64;

    /**
     * How many empty blocks in a row mean "out the far side" rather than "an internal cavity". A hull
     * with a corridor behind it is one target, not two; a shot that crosses a room and hits the
     * opposite wall should still be one impact.
     */
    private static final int GAP_TOLERANCE = 6;

    private StructureDamageEngine() {
    }

    /**
     * Walk from {@code entry} along {@code direction}, spending {@code budget}. Every coordinate in
     * and out is in the caller's frame.
     */
    public static WalkResult penetrate(World world, Vec3d entry, Vec3d direction, int budget) {
        WalkResult result = new WalkResult();
        result.budgetLeft = budget;
        if (world == null || entry == null || direction == null
                || (direction.x == 0.0D && direction.y == 0.0D && direction.z == 0.0D)) {
            result.outcome = DamageOutcome.NOTHING_STRUCK;
            result.stopReason = StopReason.NO_CANDIDATES;
            return result;
        }

        boolean enteredStructure = false;
        int consecutiveEmpty = 0;
        Vec3d lastSolidExit = null;
        BlockPos previous = null;

        for (int step = 0; step < MAX_PATH_BLOCKS; step++) {
            Vec3d samplePoint = entry.add(scale(direction, step + 0.5D));
            BlockPos pos = new BlockPos(Math.floor(samplePoint.x), Math.floor(samplePoint.y),
                    Math.floor(samplePoint.z));
            if (pos.equals(previous)) {
                continue;
            }
            previous = pos;

            if (!world.isBlockLoaded(pos)) {
                // Not "there is nothing here" — nobody looked. A caller that can retry should.
                result.outcome = enteredStructure ? DamageOutcome.ABSORBED : DamageOutcome.NOTHING_STRUCK;
                result.stopReason = StopReason.TARGET_UNLOADED;
                return result;
            }

            IBlockState state = world.getBlockState(pos);
            if (!isDamageable(world, pos, state)) {
                if (enteredStructure && ++consecutiveEmpty >= GAP_TOLERANCE) {
                    result.outcome = DamageOutcome.EXITED;
                    result.stopReason = StopReason.EXITED_FAR_SIDE;
                    result.exitPoint = lastSolidExit;
                    return result;
                }
                continue;
            }

            consecutiveEmpty = 0;
            if (!enteredStructure) {
                enteredStructure = true;
                result.entryPoint = samplePoint;
            }
            result.penetrationDepth++;
            lastSolidExit = entry.add(scale(direction, step + 1.0D));

            if (isIndestructible(world, pos, state)) {
                // Nothing gets through this. The budget dies here rather than tunnelling past it.
                result.budgetSpent += result.budgetLeft;
                result.budgetLeft = 0;
                result.outcome = DamageOutcome.ABSORBED;
                result.stopReason = StopReason.BUDGET_EXHAUSTED;
                return result;
            }

            spendInto(world, pos, state, result);
            if (result.budgetLeft <= 0) {
                result.outcome = DamageOutcome.ABSORBED;
                result.stopReason = StopReason.BUDGET_EXHAUSTED;
                return result;
            }
        }

        if (!enteredStructure) {
            result.outcome = DamageOutcome.NOTHING_STRUCK;
            result.stopReason = StopReason.NO_CANDIDATES;
            return result;
        }
        // Budget still in hand at the path limit: hand it back rather than absorb it silently.
        result.outcome = DamageOutcome.EXITED;
        result.stopReason = StopReason.EXITED_FAR_SIDE;
        result.exitPoint = lastSolidExit;
        return result;
    }

    /** Spend as much of the remaining budget into one block as its stages will take. */
    private static void spendInto(World world, BlockPos pos, IBlockState state, WalkResult result) {
        int maxStage = DamageState.getMaxStage(world, pos);
        int stage = DamageState.getStage(world, pos);
        int stageCost = stageCost(world, pos);

        boolean advanced = false;
        while (stage < maxStage && result.budgetLeft >= stageCost) {
            result.budgetLeft -= stageCost;
            result.budgetSpent += stageCost;
            stage++;
            advanced = true;
        }
        if (!advanced) {
            return;
        }

        if (stage >= maxStage) {
            BlockDamageSavedData.get(world).recordDestroyed(pos, state.getBlock(),
                    state.getBlock().getMetaFromState(state));
            DamageState.setStage(world, pos, stage);
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            result.blocksDestroyed++;
        } else {
            DamageState.setStage(world, pos, stage);
            result.blocksStaged++;
        }
    }

    /**
     * What one stage of damage costs here. A block already part-way gone is cheaper to finish: damage
     * that has been taken is damage the next hit does not have to do again.
     */
    public static int stageCost(World world, BlockPos pos) {
        double toughness = WeightEngine.INSTANCE.getToughness(world, pos);
        int maxStage = Math.max(1, DamageState.getMaxStage(world, pos));
        double perStage = (STAGE_COST_BASE + toughness * STAGE_COST_TOUGHNESS_MULT) / maxStage;
        return Math.max(1, (int) Math.ceil(perStage));
    }

    /**
     * Whether there is structure at {@code pos} — the one definition of "something is here", shared
     * with whatever decides <em>where</em> an impact happens. A travelling body that stopped at a
     * different set of blocks from the ones this engine is willing to spend budget on would either
     * halt in mid-air or bore through a wall it had already passed.
     */
    public static boolean isStructure(World world, BlockPos pos, IBlockState state) {
        return !state.getBlock().isAir(state, world, pos) && !state.getMaterial().isLiquid();
    }

    private static boolean isDamageable(World world, BlockPos pos, IBlockState state) {
        return isStructure(world, pos, state);
    }

    private static boolean isIndestructible(World world, BlockPos pos, IBlockState state) {
        return state.getBlockHardness(world, pos) < 0.0F;
    }

    private static Vec3d scale(Vec3d v, double s) {
        return new Vec3d(v.x * s, v.y * s, v.z * s);
    }

    /** What one walk did, in the frame it walked. The seam above maps the points back to world. */
    public static final class WalkResult {
        public DamageOutcome outcome = DamageOutcome.NOTHING_STRUCK;
        public StopReason stopReason = StopReason.NO_CANDIDATES;
        public int budgetSpent;
        public int budgetLeft;
        public int blocksStaged;
        public int blocksDestroyed;
        public int penetrationDepth;
        public Vec3d entryPoint;
        public Vec3d exitPoint;
    }
}
