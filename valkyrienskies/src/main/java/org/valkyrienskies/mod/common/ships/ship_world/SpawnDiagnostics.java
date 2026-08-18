package org.valkyrienskies.mod.common.ships.ship_world;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.valkyrienskies.mod.common.ships.block_relocation.ShipSpawnDetector;
import org.valkyrienskies.mod.common.ships.block_relocation.SpatialDetector;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * What happened to a queued ship spawn, recorded so a test or a bug report can say WHERE it died.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A spawn that produces no ship is, from outside the JVM, three different failures wearing the
 * same face: the pass never ran, it ran and dropped the ship before registering it, or the ship
 * registered and was collected again a tick later. The pass's own refusal — "Ship too big or bedrock
 * detected!" — goes to {@code System.err}, which the test harness does not forward, and it does not
 * say WHICH of its two legs fired. So the interesting facts are recorded here instead:</p>
 *
 * <ul>
 *   <li><b>ran / returned</b> — a run count above a return count means the pass exited by THROW.</li>
 *   <li><b>the flood's size and its bedrock flag</b> — the two inputs to the refusal, so a refused
 *       spawn says which leg refused it.</li>
 *   <li><b>the blacklist size at flood time</b> — the blacklist is rebuilt non-atomically (cleared,
 *       then repopulated), and a flood that lands in that window can escape through terrain it would
 *       normally refuse. Without this number that escape looks like an oversized craft.</li>
 *   <li><b>the flood's geometry, for a big one</b> — its bounding box and the block at the corner
 *       farthest from the anchor, which together name the direction the flood escaped in and what it
 *       escaped through.</li>
 * </ul>
 *
 * <p><b>Always on, deliberately.</b> Every entry point is a handful of stores; the harness's child
 * JVMs have no test mode to gate on, and an instrument that is only switched on once somebody
 * suspects a spawn cannot answer the run that already failed.</p>
 *
 * <p>Kept as its own class rather than inline in the manager so the geometry walk does not sit in
 * the middle of the spawn pass. The counters themselves live on the mod's own integration class,
 * where the probe that reads them can reach them without touching physics-engine types.</p>
 */
final class SpawnDiagnostics {

    private SpawnDiagnostics() {}

    /**
     * A flood at least this big is treated as ESCAPED and gets its geometry recorded. Far above any
     * hand-built craft and far below the refusal threshold, so a healthy spawn never pays for the
     * walk and an escape is always described.
     */
    private static final int ESCAPED_FLOOD_BLOCKS = 500;

    /** The spawn pass is starting, with this many spawns queued. */
    static void noteEntry(int queuedSpawns) {
        VSIntegration.noteSpawnEntry(queuedSpawns);
    }

    /**
     * The spawn pass finished NORMALLY, with this many ships in the registry. Called from a
     * {@code finally}, so a throw is visible as a run that never returned.
     */
    static void noteReturn(int registeredShips) {
        VSIntegration.noteSpawnReturn();
        VSIntegration.noteQueryableCount(registeredShips);
    }

    /** A flood detector has just been built: record what it found, and where, if it ran away. */
    static void noteDetector(SpatialDetector detector, BlockPos anchor, World world) {
        if (detector == null) {
            return;
        }
        VSIntegration.noteDetector(detector.foundSet.size(), detector.cleanHouse,
                ShipSpawnDetector.blacklistSize());
        if (detector.foundSet.size() >= ESCAPED_FLOOD_BLOCKS) {
            VSIntegration.noteFloodShape(describe(detector, anchor, world));
        }
    }

    /** The found set's bounding box, plus the block at its corner farthest from the anchor. */
    private static String describe(SpatialDetector detector, BlockPos anchor, World world) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        BlockPos farthest = anchor;
        double bestDistSq = -1.0;
        for (BlockPos p : detector.getBlockPosArrayList()) {
            if (p.getX() < minX) { minX = p.getX(); }
            if (p.getY() < minY) { minY = p.getY(); }
            if (p.getZ() < minZ) { minZ = p.getZ(); }
            if (p.getX() > maxX) { maxX = p.getX(); }
            if (p.getY() > maxY) { maxY = p.getY(); }
            if (p.getZ() > maxZ) { maxZ = p.getZ(); }
            double d = p.distanceSq(anchor);
            if (d > bestDistSq) {
                bestDistSq = d;
                farthest = p;
            }
        }
        Block far = world.getBlockState(farthest).getBlock();
        return "bbox=[" + minX + ".." + maxX + "," + minY + ".." + maxY + "," + minZ + ".." + maxZ
                + "] anchor=" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
                + " farthest=" + farthest.getX() + "," + farthest.getY() + "," + farthest.getZ()
                + "(" + far.getRegistryName() + ")";
    }
}
