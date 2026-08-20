package zmaster587.advancedRocketry.damage;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.capability.CapabilityDamageAware;
import zmaster587.advancedRocketry.api.damage.DamageOutcome;
import zmaster587.advancedRocketry.api.damage.IDamageAware;
import zmaster587.advancedRocketry.api.damage.ImpactKind;
import zmaster587.advancedRocketry.api.damage.ImpactRequest;
import zmaster587.advancedRocketry.api.damage.StopReason;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.util.SweptVolume;

import java.util.ArrayList;
import java.util.List;
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

    /** One whole voxel at the origin — the box a block is asked to report its collision shape within. */
    private static final AxisAlignedBB FULL_VOXEL = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

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
        return penetrate(world, entry, direction, budget, reachBlocks, crossSectionArea, resumesInside,
                ImpactKind.KINETIC);
    }

    /**
     * The same walk, told what KIND of arrival it is spending.
     *
     * <p>The kind picks the material column the price is read from — pushed through, or boiled away —
     * and, for the thermal channel alone, decides whether the arrival is intense enough to remove any
     * material at all. Everything else about the walk is identical: a caller that does not care hands
     * {@code KINETIC} and gets exactly the walk this always was.</p>
     */
    public static WalkResult penetrate(World world, Vec3d entry, Vec3d direction, int budget,
                                       double reachBlocks, double crossSectionArea,
                                       boolean resumesInside, ImpactKind kind) {
        WalkResult result = new WalkResult();
        result.budgetLeft = budget;
        if (world == null || entry == null || direction == null
                || (direction.x == 0.0D && direction.y == 0.0D && direction.z == 0.0D)) {
            result.outcome = DamageOutcome.NOTHING_STRUCK;
            result.stopReason = StopReason.NO_CANDIDATES;
            return result;
        }

        if (tooFaintToDrill(kind, budget, crossSectionArea)) {
            // It warms the plate and is conducted away. The energy is gone either way — a beam too
            // faint to drill is absorbed, not reflected and not carried onward — but no material is
            // removed, which is the whole content of the threshold.
            result.budgetSpent = budget;
            result.budgetLeft = 0;
            result.outcome = DamageOutcome.ABSORBED;
            result.stopReason = StopReason.BUDGET_EXHAUSTED;
            return result;
        }

        Walk walk = new Walk(world, entry, direction, result, reachBlocks, crossSectionArea,
                resumesInside, kind);
        // The bound scales with the body, because the sweep does: holding a wide round to a ray's
        // voxel budget would not make it cheaper, it would make it stop looking a few blocks in and
        // report that it had come out the far side of a hull it was still inside.
        SweptVolume.traverse(entry, walk.farEnd, walk.radius,
                MAX_VOXELS_EXAMINED * SweptVolume.candidatesPerSlice(walk.radius), walk);
        return walk.finish();
    }

    /**
     * One walk's state, told about each block the ray enters. It is an object rather than a loop only
     * because the traversal calls back; every decision is the one the loop made.
     */
    private static final class Walk implements SweptVolume.LayerVisitor {

        private final World world;
        private final Vec3d entry;
        private final WalkResult result;
        /** The far end of the reach, {@link #MAX_PATH_BLOCKS} blocks of RAY along the direction. */
        private final Vec3d farEnd;

        private final double areaFactor;
        /** Which material column this walk is priced against. */
        private final ImpactKind kind;
        /**
         * How wide the body is, in blocks, derived from the cross-section it was priced against —
         * there is one statement of a body's width and this is read from it, never declared twice.
         * Capped by config, because the work a sweep does grows with the square of it.
         */
        private final double radius;
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
                     double crossSectionArea, boolean resumesInside, ImpactKind kind) {
            this.kind = kind;
            this.skipThisVoxel = resumesInside;
            this.world = world;
            this.entry = entry;
            this.result = result;
            this.areaFactor = crossSectionArea / ImpactRequest.REFERENCE_AREA;
            this.radius = Math.min(Math.sqrt(Math.max(0.0D, crossSectionArea) / Math.PI),
                    ARConfiguration.getCurrentConfig().shotBodyRadiusCap);
            double length = Math.sqrt(direction.x * direction.x + direction.y * direction.y
                    + direction.z * direction.z);
            Vec3d unit = length <= 1.0E-9D ? direction : scale(direction, 1.0D / length);
            this.reach = Math.min(reachBlocks, MAX_PATH_BLOCKS);
            this.farEnd = entry.add(scale(unit, this.reach));
        }

        /**
         * One layer of the body's sweep: the blocks it reaches in one slice of its path.
         *
         * <p>The AXIS block still tells the story — whether the body is in material, whether it came
         * out the far side, how deep it got — because that is where the centre of the body is, and
         * every one of those is a question about the centre. What the width adds is who else gets
         * paid: each block of the layer is offered the fraction of the budget it actually covers, so
         * a body twice as wide spreads what it has over more blocks rather than doing twice the
         * damage. At the reference cross-section a layer is one block at a share of one, which is
         * precisely the walk this used to be.</p>
         */
        @Override
        public boolean visit(SweptVolume.Layer layer) {
            Vec3d here = entry.add(scale(farEnd.subtract(entry), layer.tEnter));
            if (previousWasSolid) {
                // The body left the previous solid slice exactly where it entered this one.
                lastSolidExit = here;
                previousWasSolid = false;
            }

            if (!world.isBlockLoaded(layer.axis)) {
                // Not "there is nothing here" - nobody looked. A caller that can retry should.
                return decide(entered ? DamageOutcome.ABSORBED : DamageOutcome.NOTHING_STRUCK,
                        StopReason.TARGET_UNLOADED, null);
            }

            boolean anySolid = false;
            for (BlockPos pos : layer.blocks) {
                if (world.isBlockLoaded(pos) && isStructure(world, pos, world.getBlockState(pos))) {
                    anySolid = true;
                    break;
                }
            }
            if (!anySolid) {
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
            result.distanceWalked = layer.tEnter * reach;
            previousWasSolid = true;

            if (skipThisVoxel) {
                // The slice this bore is standing in, already bought on an earlier tick.
                skipThisVoxel = false;
                return false;
            }

            // The pool every share is measured against is what the body had ON REACHING this layer,
            // not what is left part way through it: the blocks of one layer are met at once, so
            // charging the second against the first's leavings would make their listed order matter.
            int poolAtLayer = result.budgetLeft;
            for (int i = 0; i < layer.blocks.size(); i++) {
                BlockPos pos = layer.blocks.get(i);
                if (!world.isBlockLoaded(pos)) {
                    continue;
                }
                IBlockState state = world.getBlockState(pos);
                if (!isStructure(world, pos, state)) {
                    continue;
                }
                int allowance = Math.min(result.budgetLeft, allowanceFor(poolAtLayer, layer, i));
                if (isIndestructible(world, pos, state)) {
                    if (pos.equals(layer.axis)) {
                        // Nothing gets through this. The budget dies here rather than tunnelling past.
                        result.budgetSpent += result.budgetLeft;
                        result.budgetLeft = 0;
                        return decide(DamageOutcome.ABSORBED, StopReason.BUDGET_EXHAUSTED, null);
                    }
                    // Beside the hole rather than in it: it eats its own share and the body goes on.
                    result.budgetSpent += allowance;
                    result.budgetLeft -= allowance;
                    continue;
                }
                // Priced against the area THIS block is under, not the whole body: it is handed a
                // share of the budget, so charging it for the entire cross-section would take the
                // width out of the round twice and leave a wide shot feebler than any physics says.
                int spent = spendInto(world, pos, state, result,
                        areaFactor * layer.shares.get(i), allowance, kind);
                result.budgetSpent += spent;
                result.budgetLeft -= spent;
            }
            if (result.budgetLeft <= 0) {
                return decide(DamageOutcome.ABSORBED, StopReason.BUDGET_EXHAUSTED, null);
            }
            return false;
        }

        /** What one block of a layer may be charged: the pool times how much of the body covers it. */
        private int allowanceFor(int poolAtLayer, SweptVolume.Layer layer, int index) {
            return Math.max(0, (int) Math.floor(poolAtLayer * layer.shares.get(index)));
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

    /**
     * Spend up to {@code allowance} into one block, as much as its stages will take, and answer what
     * that came to. The caller owns the running budget — a block is told what it may have, never
     * handed the purse, which is what lets one layer be divided between several of them.
     */
    private static int spendInto(World world, BlockPos pos, IBlockState state, WalkResult result,
                                 double areaFactor, int allowance, ImpactKind kind) {
        int maxStage = DamageState.getMaxStage(world, pos);
        int stage = DamageState.getStage(world, pos);
        int stageBefore = stage;
        int stageCost = stageCost(world, pos, areaFactor, kind);

        int left = Math.max(0, allowance);
        int spent = 0;
        boolean advanced = false;
        while (stage < maxStage && left >= stageCost) {
            left -= stageCost;
            spent += stageCost;
            stage++;
            advanced = true;
        }
        if (!advanced) {
            return spent;
        }

        if (stage >= maxStage) {
            BlockDamageSavedData.get(world).recordDestroyed(pos, state.getBlock(),
                    state.getBlock().getMetaFromState(state));
            DamageState.setStage(world, pos, stage);
            // Taken out of the block while there still IS one. A unit's own destruction is the
            // occurrence it most needs — what a failing engine does about being killed is its own
            // business (a chemical one goes like TNT, an ion one merely ceases to exist) — and one
            // line below there is no tile left to ask. Captured here, told by the layer above.
            IDamageAware dying = CapabilityDamageAware.get(world.getTileEntity(pos));
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            result.blocksDestroyed++;
            result.touched.add(new Touched(pos, stageBefore, stage, maxStage, spent, dying));
        } else {
            DamageState.setStage(world, pos, stage);
            result.blocksStaged++;
            result.touched.add(new Touched(pos, stageBefore, stage, maxStage, spent, null));
        }
        return spent;
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
        return stageCost(world, pos, areaFactor, ImpactKind.KINETIC);
    }

    /**
     * What one stage costs a body of a given cross-section arriving as {@code kind}.
     *
     * <p>The kind picks the material constant and nothing else: a slug is priced against the block's
     * resistance to being pushed through, a beam against its resistance to being boiled away. Same
     * law, same units, different column — which is what makes a ceramic that shrugs off a beam and
     * shatters under a slug two rows of a table rather than two mechanics. A block with no ablation
     * row of its own has one derived from its toughness, so the mechanical price of every block in the
     * game is exactly what it always was.</p>
     */
    public static int stageCost(World world, BlockPos pos, double areaFactor, ImpactKind kind) {
        double resistance = WeightEngine.INSTANCE.getResistance(world, pos, kind);
        int maxStage = Math.max(1, DamageState.getMaxStage(world, pos));
        double perStage = (STAGE_COST_BASE + resistance * STAGE_COST_TOUGHNESS_MULT) / maxStage;
        return Math.max(1, (int) Math.ceil(perStage * Math.max(0.0D, areaFactor)
                * occupancyOf(world, pos)));
    }

    /**
     * How much of its voxel this block actually fills, in {@code [0, 1]}.
     *
     * <h3>Why a price has to know this at all</h3>
     * <p>The law is an energy per unit of VOLUME removed — that is the whole of why the mechanical and
     * ablation columns are the same law with different constants. A voxel is one cubic metre only when
     * something fills it, and until this was asked a glass pane cost a solid block of glass to shoot
     * through, a carpet cost a block of wool, and a coating filling an eighth of its voxel was eight
     * times dearer to bore than the physics says. Every hull the tests fire at is built of full cubes,
     * which is precisely the shape that hides it.</p>
     *
     * <h3>Why the collision LIST and not the bounding box</h3>
     * <p>A bounding box is one box, so a shape made of several can only be summarised by it — and
     * vanilla proves the point twice over: {@code BlockStairs} does not override {@code getBoundingBox}
     * at all and reports a full cube, while {@code BlockFence} reports the envelope of its post and
     * arms, which is mostly air. Both over-state, one enormously. The collision list is where a block
     * states its real shape, box by box, so that is what is summed. Overlapping boxes would double
     * count, which is why the sum is clamped: over-counting can only ever produce "a full cube", the
     * answer we started from.</p>
     *
     * <h3>There is no floor under it, and nothing is free anyway</h3>
     * <p>A floor was tried and removed: it was a MULTIPLIER, so it priced "the least a block can cost"
     * out of that block's own material, and what it was meant to represent — the work of breaking a
     * thing off its mounting — has nothing to do with what the thing is made of. What actually keeps a
     * near-empty voxel from being free is {@code STAGE_COST_BASE}, which is material-independent and
     * already inside the product: a standing torch answers 0.024 here and still costs 6 against the
     * 1000 a full block of stone costs. Below that the price itself floors at 1.</p>
     *
     * <p>The floor's stated reason — that a torch must not be a hole in a hull — does not survive
     * being looked at: a voxel holding a torch is a voxel holding no hull block. The hole is the
     * builder's, and pricing it dearly does not fill it.</p>
     */
    private static double occupancyOf(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return 1.0D;
        }
        IBlockState state = world.getBlockState(pos);
        try {
            List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
            state.addCollisionBoxToList(world, pos, FULL_VOXEL.offset(pos), boxes, null, true);
            double volume = 0.0D;
            for (AxisAlignedBB box : boxes) {
                volume += (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
            }
            if (volume > 0.0D) {
                return Math.min(1.0D, volume);
            }
            // No collision at all — a torch, a plant, a tripwire. It is still SOMETHING, so it falls
            // back to the shape it draws itself with rather than to nothing.
            AxisAlignedBB drawn = state.getBoundingBox(world, pos);
            if (drawn == null) {
                // It states no shape at all. The price floors at 1 rather than at nothing, which is
                // the whole of what "still SOMETHING" needs to mean here.
                return 0.0D;
            }
            double drawnVolume = (drawn.maxX - drawn.minX) * (drawn.maxY - drawn.minY)
                    * (drawn.maxZ - drawn.minZ);
            return Math.min(1.0D, drawnVolume);
        } catch (RuntimeException blockDidNotLikeBeingAsked) {
            // A block may compute its shape from neighbours it expects to be loaded. It costs a full
            // cube rather than throwing, which is the answer that changes nothing.
            return 1.0D;
        }
    }

    /**
     * Is this arrival intense enough to remove material at all?
     *
     * <p>Only the thermal channel has a threshold, and it is not a balance nicety: material conducts
     * heat away, so below some power density a beam warms a plate rather than drilling it. A linear
     * law without one says a one-watt laser held long enough cuts a battleship, and makes a big
     * emitter merely a faster small one. The intensity is the energy behind the body's own face —
     * spreading the same energy over a wider beam makes it dimmer, exactly as it should.</p>
     */
    private static boolean tooFaintToDrill(ImpactKind kind, int budget, double crossSectionArea) {
        if (!WeightEngine.isThermalChannel(kind)) {
            return false;
        }
        double threshold = ARConfiguration.getCurrentConfig().beamAblationIntensityThreshold;
        if (threshold <= 0.0D) {
            return false;
        }
        double area = crossSectionArea <= 0.0D ? ImpactRequest.REFERENCE_AREA : crossSectionArea;
        return budget / area < threshold;
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

    /**
     * One unit this walk advanced — the facts, and nothing derived from them.
     *
     * <p>The engine records rather than publishes because it does not know enough to publish: it walks
     * in the frame it was given and can name no ship, and it is handed a budget and a kind rather than
     * the request, so it can name no cause either. Both live one layer up, which is why that layer
     * does the telling.</p>
     */
    public static final class Touched {
        /** In the frame the walk ran in — subspace aboard a ship, the world's own otherwise. */
        public final BlockPos pos;
        public final int stageBefore;
        public final int stageAfter;
        public final int maxStage;
        public final int budgetSpent;
        /**
         * The unit's own listener, taken out of the block just before the block stopped existing;
         * {@code null} for a block that survived, whose tile can simply be looked up when the news is
         * delivered. A destroyed unit has no tile to look up any more, and it is the one that most
         * needs to hear.
         */
        public final IDamageAware dying;

        Touched(BlockPos pos, int stageBefore, int stageAfter, int maxStage, int budgetSpent,
                IDamageAware dying) {
            this.pos = pos;
            this.stageBefore = stageBefore;
            this.stageAfter = stageAfter;
            this.maxStage = maxStage;
            this.budgetSpent = budgetSpent;
            this.dying = dying;
        }
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
        /** Every unit this walk advanced, in the order it reached them. */
        public final List<Touched> touched = new ArrayList<Touched>();
        public Vec3d entryPoint;
        public Vec3d exitPoint;
    }
}
