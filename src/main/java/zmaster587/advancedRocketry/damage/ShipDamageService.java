package zmaster587.advancedRocketry.damage;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.capability.CapabilityDamageAware;
import zmaster587.advancedRocketry.api.damage.DamageOccurrence;
import zmaster587.advancedRocketry.api.damage.DamageOutcome;
import zmaster587.advancedRocketry.api.damage.IDamageAware;
import zmaster587.advancedRocketry.api.damage.DamageReport;
import zmaster587.advancedRocketry.api.damage.ImpactRequest;
import zmaster587.advancedRocketry.api.damage.SelectionMode;
import zmaster587.advancedRocketry.api.damage.StopReason;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one entry point for damaging structure. A weapon, a hazard or a collision declares an impact
 * here and reads back what happened; it names no block, no stage and no toughness, and it is told no
 * decision — only facts it can decide from.
 *
 * <h3>One call for a ship and for a building</h3>
 * <p>The call takes a WORLD and a point, never a ship id. Nearly every ship system has to work on a
 * planetary base as well, and ground batteries have to be able to engage ships; if the caller had to
 * know which kind of thing it was shooting at, every weapon would grow two code paths that drift
 * apart. So this service resolves the target itself: a ship whose blocks actually occupy the impact
 * point, or, when there is none, the ordinary blocks of that world.</p>
 *
 * <h3>Frames</h3>
 * <p>Callers work in the world frame. Ship blocks do not live there — they live at fixed addresses in
 * a shipyard subspace while the ship flies around — so the point and the direction are mapped into
 * that frame here, once, and the report's points are mapped back. No caller and no engine below sees
 * two frames.</p>
 */
public final class ShipDamageService {

    /**
     * How long an applied impact identity is remembered, in ticks. Long enough to cover the retries
     * that make duplicates real (an impact deferred because its region was unloaded, a shot
     * re-examined across a load transition), short enough that the set stays small.
     */
    private static final long IMPACT_MEMORY_TICKS = 600L;

    /** Hard cap on remembered identities, so a runaway caller cannot grow this without bound. */
    private static final int IMPACT_MEMORY_MAX = 4096;

    /**
     * How far along its own direction an impact may look for the ship it is hitting, when the declared
     * point itself is not yet inside one. Small on purpose: it exists to forgive a point declared just
     * off the plating, not to let an impact reach out and find a hull it never met.
     */
    private static final double TARGET_LEAD_BLOCKS = 8.0D;
    private static final double LEAD_STEP = 0.5D;

    /**
     * Recently applied impact identities → the tick they were applied on. Written only from here.
     *
     * <p>Keyed by DIMENSION as well as identity. Identities are minted per world, so two worlds hand
     * out the same numbers as a matter of course; a memory shared between them would refuse a round
     * in one world for an impact declared in another, and the refusal is silent — the budget comes
     * back whole and the round flies on through the hull it was aimed at.</p>
     *
     * <p><b>It outlives a scenario</b>: on a server shared by several tests, an id used by one is
     * still refused for the next, so a test that reuses ids must call {@link #clearRecentImpacts()}
     * between them rather than assume a fresh service.</p>
     */
    private static final Map<String, Long> RECENT_IMPACTS = new LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > IMPACT_MEMORY_MAX;
        }
    };

    /** One world's identity space, kept apart from every other world's. */
    private static String memoryKey(World world, long impactId) {
        return world.provider.getDimension() + ":" + impactId;
    }

    private ShipDamageService() {
    }

    /**
     * Apply a declared impact to whatever structure occupies its point.
     *
     * <p>Server side only: damage is world state, and a client that computed its own would be
     * describing a different game from the one everybody else is playing.</p>
     */
    public static DamageReport apply(World world, ImpactRequest request) {
        if (world == null || request == null || world.isRemote) {
            return DamageReport.nothingStruck(request == null ? 0 : request.getBudget(),
                    StopReason.NO_CANDIDATES);
        }
        if (request.getBudget() <= 0) {
            return DamageReport.nothingStruck(0, StopReason.NO_CANDIDATES);
        }
        if (request.getSelectionMode() != SelectionMode.PENETRATING) {
            throw new UnsupportedOperationException("selection mode " + request.getSelectionMode()
                    + " is not implemented yet; only PENETRATING resolves today. Failing loudly rather"
                    + " than reporting an undamaged hull, which would read as a miss.");
        }
        if (isDuplicate(world, request.getImpactId())) {
            return DamageReport.duplicate(request.getBudget());
        }

        Vec3d point = request.getPoint();
        String shipId = shipAt(world, point, request.getDirection());
        if (shipId == null) {
            remember(world, request.getImpactId());
            StructureDamageEngine.WalkResult walked = StructureDamageEngine.penetrate(world, point,
                    request.getDirection(), request.getBudget(), request.getReachBlocks(),
                    request.getCrossSectionArea(), request.resumesInside(), request.getKind());
            tellTheUnits(world, walked, request, null);
            return toReport(walked, null, world);
        }

        double[] shipPoint = VSIntegration.toShipFrameFor(world, shipId, point.x, point.y, point.z);
        Vec3d direction = request.getDirection();
        double[] shipDir = VSIntegration.rotateToShipFrameFor(world, shipId, direction.x, direction.y,
                direction.z);
        if (shipPoint == null || shipDir == null) {
            // The ship was there a moment ago and its transform is not answering now: that is "ask
            // again", not "clean miss".
            return DamageReport.nothingStruck(request.getBudget(), StopReason.TARGET_UNLOADED);
        }

        remember(world, request.getImpactId());
        StructureDamageEngine.WalkResult walk = StructureDamageEngine.penetrate(world,
                new Vec3d(shipPoint[0], shipPoint[1], shipPoint[2]),
                new Vec3d(shipDir[0], shipDir[1], shipDir[2]), request.getBudget(),
                request.getReachBlocks(), request.getCrossSectionArea(), request.resumesInside(),
                request.getKind());
        tellTheUnits(world, walk, request, shipId);
        return toReport(walk, shipId, world);
    }

    /**
     * The non-geometric overload, for a caller that already knows the ship and has no impact point at
     * all — a failing drive tearing its own hull apart from the inside. Not implemented yet: it needs
     * the subsystem-weighted selection mode, and answering with an undamaged hull would read as "the
     * ship is fine".
     */
    public static DamageReport apply(World world, String shipId, ImpactRequest request) {
        throw new UnsupportedOperationException("the by-ship overload needs POWER_BIASED selection,"
                + " which is not implemented yet");
    }

    /**
     * Forget every remembered impact identity. Owned here because the memory is owned here; a shared
     * server hands it from one scenario to the next otherwise.
     */
    public static void clearRecentImpacts() {
        RECENT_IMPACTS.clear();
    }

    /**
     * The tick an identity was remembered on, or {@code null} when it is not remembered at all.
     * Diagnostics: a refusal reports only that the id was seen, and "seen when, and how long ago"
     * is what separates a genuine retry from one caller's ids colliding with another's.
     */
    public static Long rememberedTickOf(World world, long impactId) {
        return world == null ? null : RECENT_IMPACTS.get(memoryKey(world, impactId));
    }

    /** How many identities are currently remembered (diagnostics and tests). */
    public static int rememberedImpactCount() {
        return RECENT_IMPACTS.size();
    }

    /**
     * Which ship an impact at this point would be charged to, or null for "the world's own blocks".
     * Exposed for diagnostics: the report itself deliberately does not name a ship, but an instrument
     * that cannot say which target was resolved cannot tell a wrong target from no target.
     */
    public static String resolveTargetShip(World world, Vec3d point, Vec3d direction) {
        return world == null || point == null ? null : shipAt(world, point, direction);
    }

    /**
     * The ship whose blocks the impact meets, or null for "no ship here, use the world's own blocks".
     *
     * <p>Two things this has to get right, and the second is not obvious. Candidate ships come from
     * their grown world boxes, which overlap and overstate, so each candidate is asked whether its own
     * subspace actually holds a block there — a near miss past one hull is not charged to it.</p>
     *
     * <p>And the search runs <b>along the ray</b>, not only at the declared point. A caller says "the
     * impact happened here", and here is a point in the air a little off the plating as often as it is
     * the plating itself — a shot resolves its crossing geometrically and hands over where it met the
     * surface, give or take. Resolving only at the origin would answer "no ship" for such an impact and
     * walk the world frame instead, where the ship has no blocks at all; the shot would read as a clean
     * miss while sitting on the hull. So the ray is sampled forward a short lead distance and the first
     * ship whose blocks it enters wins.</p>
     */
    private static String shipAt(World world, Vec3d point, Vec3d direction) {
        String found = shipManagingPoint(world, point);
        if (found != null || direction == null
                || (direction.x == 0.0D && direction.y == 0.0D && direction.z == 0.0D)) {
            return found;
        }
        for (double t = LEAD_STEP; t <= TARGET_LEAD_BLOCKS; t += LEAD_STEP) {
            found = shipManagingPoint(world, point.add(new Vec3d(direction.x * t, direction.y * t,
                    direction.z * t)));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The ship holding a block of its own at exactly this world point, or null. */
    private static String shipManagingPoint(World world, Vec3d point) {
        for (String candidate : VSIntegration.shipIdsAt(world, point.x, point.y, point.z)) {
            double[] local = VSIntegration.toShipFrameFor(world, candidate, point.x, point.y, point.z);
            if (local == null) {
                continue;
            }
            BlockPos pos = new BlockPos(Math.floor(local[0]), Math.floor(local[1]), Math.floor(local[2]));
            if (candidate.equals(VSIntegration.shipIdManagingBlock(world, pos))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Tell every unit the walk advanced what happened to it.
     *
     * <p>Published HERE rather than by the engine because the two facts a unit needs and the engine
     * cannot supply live at this layer: the CAUSE (the engine is handed a budget and a kind, never the
     * request) and the HULL (the engine walks in whatever frame it was given and names no ship). The
     * engine's job was to notice; this one's is to say who and why.</p>
     *
     * <p><b>A unit that no longer exists is told anyway.</b> The engine hands back the listener it took
     * out of a block on the way to destroying it, because the blow that ends a unit is the occurrence
     * that unit most needs — and by now there is no tile at that position to look up. A survivor is
     * looked up normally.</p>
     *
     * <p>An exception from a unit's own reaction is contained: the stage is already written and the
     * budget already spent, so one unit throwing must not cost the rest of the hull its news, and it
     * must not turn a resolved impact into a failed one.</p>
     */
    private static void tellTheUnits(World world, StructureDamageEngine.WalkResult walk,
                                     ImpactRequest request, String shipId) {
        if (walk == null || walk.touched.isEmpty()) {
            return;
        }
        for (StructureDamageEngine.Touched t : walk.touched) {
            IDamageAware unit = t.dying != null ? t.dying
                    : CapabilityDamageAware.get(world.getTileEntity(t.pos));
            if (unit == null) {
                continue;
            }
            Vec3d where = toWorld(world, shipId,
                    new Vec3d(t.pos.getX() + 0.5D, t.pos.getY() + 0.5D, t.pos.getZ() + 0.5D));
            try {
                unit.onDamage(new DamageOccurrence(request.getCause(), request.getKind(), world,
                        t.pos, where, t.stageBefore, t.stageAfter, t.maxStage, t.budgetSpent, shipId));
            } catch (RuntimeException unitThrew) {
                AdvancedRocketry.logger.error("a unit threw while reacting to damage at " + t.pos
                        + " (" + request.getCause() + "): the damage stands, the reaction is lost",
                        unitThrew);
            }
        }
    }

    private static DamageReport toReport(StructureDamageEngine.WalkResult walk, String shipId, World world) {
        Vec3d entry = toWorld(world, shipId, walk.entryPoint);
        Vec3d exit = walk.outcome == DamageOutcome.EXITED ? toWorld(world, shipId, walk.exitPoint) : null;
        // The distance needs no frame conversion: a ship's transform is rigid, so a length in its
        // subspace is that same length in the world.
        return new DamageReport(walk.outcome, walk.stopReason, walk.budgetSpent, walk.budgetLeft,
                walk.blocksStaged, walk.blocksDestroyed, entry, exit, walk.penetrationDepth,
                walk.distanceWalked);
    }

    private static Vec3d toWorld(World world, String shipId, Vec3d local) {
        if (local == null) {
            return null;
        }
        if (shipId == null) {
            return local;
        }
        double[] w = VSIntegration.toWorldFrameFor(world, shipId, local.x, local.y, local.z);
        return w == null ? null : new Vec3d(w[0], w[1], w[2]);
    }

    private static boolean isDuplicate(World world, long impactId) {
        String key = memoryKey(world, impactId);
        Long appliedAt = RECENT_IMPACTS.get(key);
        if (appliedAt == null) {
            return false;
        }
        if (world.getTotalWorldTime() - appliedAt > IMPACT_MEMORY_TICKS) {
            RECENT_IMPACTS.remove(key);
            return false;
        }
        return true;
    }

    private static void remember(World world, long impactId) {
        RECENT_IMPACTS.put(memoryKey(world, impactId), world.getTotalWorldTime());
    }
}
