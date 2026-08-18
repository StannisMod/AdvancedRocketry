package zmaster587.advancedRocketry.damage;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.damage.DamageOutcome;
import zmaster587.advancedRocketry.api.damage.ImpactRequest;
import zmaster587.advancedRocketry.api.damage.StopReason;
import zmaster587.advancedRocketry.util.SweptSegment;
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

    /**
     * Hard bound on how many blocks one walk may examine, derived from {@link #MAX_PATH_BLOCKS} and
     * never reached before it. A segment of length L crosses at most about 1.74 L voxels (the sum of
     * a unit vector's components), so three per block of reach is slack, not a second limit: the
     * geometric end of the path always comes first. It exists so a traversal cannot run away.
     */
    private static final int MAX_VOXELS_EXAMINED = MAX_PATH_BLOCKS * 3 + 3;

    private StructureDamageEngine() {
    }

    /**
     * Walk from {@code entry} along {@code direction}, spending {@code budget}. Every coordinate in
     * and out is in the caller's frame.
     *
     * <h3>The path is TRAVERSED, not sampled</h3>
     * <p>Every block the ray passes through is offered the budget, in order. The obvious alternative —
     * step one unit along the ray and read the block under each sample — is wrong for any ray that is
     * not parallel to an axis, because one unit of RAY is not one block of GRID: at thirty degrees it
     * walks past roughly a third of what it crosses, and the blocks it walks past keep their budget
     * and stand pristine inside the crater. Worse, each one is then counted as EMPTY, so six of them
     * in a row convince the walk it has come out the far side of a hull it is still inside.</p>
     */
    public static WalkResult penetrate(World world, Vec3d entry, Vec3d direction, int budget) {
        return penetrate(world, entry, direction, budget, ImpactRequest.UNBOUNDED_REACH,
                ImpactRequest.REFERENCE_AREA);
    }

    /**
     * The same walk, bounded by how far the body actually got this time and priced against its
     * cross-section.
     *
     * <h3>Reach</h3>
     * <p>A body that penetrates over time may only spend the path it travelled - the alternative,
     * resolving a whole bore in the tick it started, is what made a shot's whole life happen inside
     * one impact. {@link #MAX_PATH_BLOCKS} stays as the backstop for a caller with no notion of reach
     * (an explosion, a collision), so nothing walks away forever.</p>
     *
     * <h3>Area</h3>
     * <p>Material resists with a pressure, so the energy per unit of depth is that pressure times the
     * body's cross-section: the same energy behind a wider face bores less far, and behind a narrower
     * one bores further. At {@link ImpactRequest#REFERENCE_AREA} the price is exactly what it was
     * before any of this was priced, which is what keeps every shipped weapon where it was.</p>
     */
    public static WalkResult penetrate(World world, Vec3d entry, Vec3d direction, int budget,
                                       double reachBlocks, double crossSectionArea) {
        return penetrate(world, entry, direction, budget, reachBlocks, crossSectionArea, false);
    }

    /**
     * The same walk, told whether the body is CONTINUING a bore through the block it starts in. A body
     * that is already inside one has paid for it on an earlier tick; charging it again every tick it
     * fails to leave would make a slow round strictly deadlier than a fast one.
     */
    public static WalkResult penetrate(World world, Vec3d entry, Vec3d direction, int budget,
                                       double reachBlocks, double crossSectionArea,
                                       boolean resumesInside) {
        WalkResult result = new WalkResult();
        result.budgetLeft = budget;
        if (world == null || entry == null || direction == null
                || (direction.x == 0.0D && direction.y == 0.0D && direction.z == 0.0D)) {
            result.outcome = DamageOutcome.NOTHING_STRUCK;
            result.stopReason = StopReason.NO_CANDIDATES;
            return result;
        }

        Walk walk = new Walk(world, entry, direction, result, reachBlocks, crossSectionArea,
                resumesInside);
        SweptSegment.traverse(entry, walk.farEnd, MAX_VOXELS_EXAMINED, walk);
        return walk.finish();
    }

    /**
     * One walk's state, told about each block the ray enters. It is an object rather than a loop only
     * because the traversal calls back; every decision is the one the loop made.
     */
    private static final class Walk implements SweptSegment.Visitor {

        private final World world;
        private final Vec3d entry;
        private final WalkResult result;
        /** The far end of the reach, {@link #MAX_PATH_BLOCKS} blocks of RAY along the direction. */
        private final Vec3d farEnd;

        private final double areaFactor;
        /** True while the first voxel is still to come: it is already paid for, so it is not charged. */
        private boolean skipThisVoxel;
        /** How far the far end is, in blocks: what a parameter along the segment is measured against. */
        private final double reach;

        private boolean entered;
        private boolean decided;
        private int consecutiveEmpty;
        private boolean previousWasSolid;
        private Vec3d lastSolidExit;

        private Walk(World world, Vec3d entry, Vec3d direction, WalkResult result, double reachBlocks,
                     double crossSectionArea, boolean resumesInside) {
            this.skipThisVoxel = resumesInside;
            this.world = world;
            this.entry = entry;
            this.result = result;
            this.areaFactor = crossSectionArea / ImpactRequest.REFERENCE_AREA;
            double length = Math.sqrt(direction.x * direction.x + direction.y * direction.y
                    + direction.z * direction.z);
            Vec3d unit = length <= 1.0E-9D ? direction : scale(direction, 1.0D / length);
            this.reach = Math.min(reachBlocks, MAX_PATH_BLOCKS);
            this.farEnd = entry.add(scale(unit, this.reach));
        }

        @Override
        public boolean visit(BlockPos pos, double tEnter, net.minecraft.util.EnumFacing entryFace) {
            Vec3d here = entry.add(scale(farEnd.subtract(entry), tEnter));
            if (previousWasSolid) {
                // The ray left the previous solid block exactly where it entered this one.
                lastSolidExit = here;
                previousWasSolid = false;
            }

            if (!world.isBlockLoaded(pos)) {
                // Not "there is nothing here" — nobody looked. A caller that can retry should.
                return decide(entered ? DamageOutcome.ABSORBED : DamageOutcome.NOTHING_STRUCK,
                        StopReason.TARGET_UNLOADED, null);
            }

            IBlockState state = world.getBlockState(pos);
            if (!isStructure(world, pos, state)) {
                if (entered && ++consecutiveEmpty >= GAP_TOLERANCE) {
                    return decide(DamageOutcome.EXITED, StopReason.EXITED_FAR_SIDE, lastSolidExit);
                }
                return false;
            }

            consecutiveEmpty = 0;
            if (!entered) {
                entered = true;
                result.entryPoint = here;
            }
            result.penetrationDepth++;
            result.distanceWalked = tEnter * reach;
            previousWasSolid = true;

            if (skipThisVoxel) {
                // The block this bore is standing in, already bought on an earlier tick.
                skipThisVoxel = false;
                return false;
            }

            if (isIndestructible(world, pos, state)) {
                // Nothing gets through this. The budget dies here rather than tunnelling past it.
                result.budgetSpent += result.budgetLeft;
                result.budgetLeft = 0;
                return decide(DamageOutcome.ABSORBED, StopReason.BUDGET_EXHAUSTED, null);
            }

            spendInto(world, pos, state, result, areaFactor);
            if (result.budgetLeft <= 0) {
                return decide(DamageOutcome.ABSORBED, StopReason.BUDGET_EXHAUSTED, null);
            }
            return false;
        }

        private boolean decide(DamageOutcome outcome, StopReason reason, Vec3d exitPoint) {
            result.outcome = outcome;
            result.stopReason = reason;
            result.exitPoint = exitPoint;
            decided = true;
            return true;
        }

        /** The outcome for a walk that ran off the end of its reach without deciding anything. */
        private WalkResult finish() {
            if (decided) {
                return result;
            }
            if (!entered) {
                result.outcome = DamageOutcome.NOTHING_STRUCK;
                result.stopReason = StopReason.NO_CANDIDATES;
                return result;
            }
            // Budget still in hand at the path limit: hand it back rather than absorb it silently.
            result.outcome = DamageOutcome.EXITED;
            result.stopReason = StopReason.EXITED_FAR_SIDE;
            result.exitPoint = previousWasSolid ? farEnd : lastSolidExit;
            return result;
        }
    }

    /** Spend as much of the remaining budget into one block as its stages will take. */
    private static void spendInto(World world, BlockPos pos, IBlockState state, WalkResult result,
                                  double areaFactor) {
        int maxStage = DamageState.getMaxStage(world, pos);
        int stage = DamageState.getStage(world, pos);
        int stageCost = stageCost(world, pos, areaFactor);

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
        return stageCost(world, pos, 1.0D);
    }

    /**
     * What one stage costs a body of a given cross-section, as a multiple of the reference one. The
     * material resists with a pressure; a wider body pushes that pressure over more area and pays
     * proportionally more for the same depth, which is where sectional density comes from without
     * anybody writing it down as a rule.
     */
    public static int stageCost(World world, BlockPos pos, double areaFactor) {
        double toughness = WeightEngine.INSTANCE.getToughness(world, pos);
        int maxStage = Math.max(1, DamageState.getMaxStage(world, pos));
        double perStage = (STAGE_COST_BASE + toughness * STAGE_COST_TOUGHNESS_MULT) / maxStage;
        return Math.max(1, (int) Math.ceil(perStage * Math.max(0.0D, areaFactor)));
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
        /** How far along its direction the walk got before it stopped, in blocks. */
        public double distanceWalked;
        public Vec3d entryPoint;
        public Vec3d exitPoint;
    }
}
