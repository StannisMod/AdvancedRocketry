package zmaster587.advancedRocketry.space;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Computes the BLOCK paste-Y for a descent crossing into a real planet dimension, so the pasted VS
 * ship sits <b>above</b> the destination terrain and <b>under</b> the vanilla build height.
 *
 * <p>The ceiling clamp is not cosmetic. A crossing pastes the ship into clear sky and then lets VS's
 * {@code FIND_ALL_BLOCKS} flood-fill re-discover it; a paste that clips at the build height (Y=256)
 * splits the ship's block set and the flood-fill grabs only part of it, so the re-assembled physo is
 * wrong. The paste-Y therefore has two hard bounds: it must clear the terrain top (or the ship spawns
 * buried) and it must leave the whole ship height below {@link #MAX_BUILD_Y}.</p>
 *
 * <p>Unlike {@link CellWorldMapper} POSES (which use the ~2M honest range with no height cap), descent
 * lands on a real planet whose blocks live in the ordinary 0..{@link #MAX_BUILD_Y} band, so the two
 * bounds can genuinely conflict: a ship taller than the gap between the terrain and the build ceiling
 * cannot fit here at all. In that case {@link #pasteY} returns {@code -1} and the caller aborts the
 * descent rather than pasting a clipped ship.</p>
 *
 * <p><b>Status.</b> {@link #MAX_BUILD_Y} is this class's live contribution — it is the single owner of
 * "the top of the vanilla block band", and the descent paste band is derived from it. The terrain-fit
 * pair ({@link #pasteY} and {@link #terrainTopOfFootprint}) has no production caller any more: a
 * descent arrives in the AIR over the destination and the pilot flies it down, so no ground fit is
 * computed and no descent is refused for not fitting one. The pair is kept, with its tests, for a
 * future mechanic that genuinely needs to place a structure on a surface.</p>
 */
public final class TerrainHeightFinder {

    /**
     * The highest block Y a paste may occupy. A ship whose top would exceed this clips the flood-fill,
     * so the paste-Y is capped at {@code MAX_BUILD_Y - shipHeight}. {@code tunable} (structural — the
     * vanilla build-height ceiling).
     */
    public static final int MAX_BUILD_Y = 255;

    /**
     * Blocks of air left between the terrain top and the ship's underside on a normal (uncapped)
     * descent. {@code tunable} — a landing-feel balance number, never pinned by a test.
     */
    public static final int CLEARANCE_BLOCKS = 8;

    private TerrainHeightFinder() { }

    /**
     * The BLOCK Y at which to paste a ship of {@code shipHeight} blocks over terrain topping out at
     * {@code terrainTop}, leaving {@code clearance} blocks of air beneath it: normally
     * {@code terrainTop + clearance}, clamped down to {@code MAX_BUILD_Y - shipHeight} so the ship's
     * top never crosses the build ceiling. Returns {@code -1} when even the clamped result would sit
     * at or below {@code terrainTop} — the ship is too tall to fit above the terrain here and the
     * caller must abort the descent rather than bury or clip it.
     *
     * <p>Pure: no world access, so the paste geometry is unit-testable in isolation.</p>
     */
    public static int pasteY(int terrainTop, int clearance, int shipHeight) {
        int y = Math.min(terrainTop + clearance, MAX_BUILD_Y - shipHeight);
        if (y <= terrainTop) {
            return -1; // ship too tall for the gap between this terrain and the build ceiling
        }
        return y;
    }

    /**
     * The highest terrain top over the XZ paste footprint {@code [pasteX, pasteX+width) x
     * [pasteZ, pasteZ+depth)} in {@code dst} — the surface the ship must clear so no part of its
     * footprint lands underground. Force-loads the footprint chunks first (a freshly-materialized or
     * unvisited destination may have them unloaded, in which case the height query sees an all-air
     * column and reads the void floor), mirroring the paste-footprint force-load in
     * {@code VSIntegration.crossShip}.
     *
     * <p>Uses {@link net.minecraft.world.World#getTopSolidOrLiquidBlock(BlockPos)}, which stops on the
     * highest solid-or-liquid block. Caveat: that method ignores lava (it treats lava as non-blocking),
     * so a ship may land low over an exposed lava lake; acceptable for v1.</p>
     */
    public static int terrainTopOfFootprint(WorldServer dst, int pasteX, int pasteZ, int width, int depth) {
        // Force-load the footprint chunks so the height query reads real terrain, not an unloaded
        // (all-air) region. Range mirrors VSIntegration.crossShip's paste-footprint load.
        int cxMin = pasteX >> 4, cxMax = (pasteX + width) >> 4;
        int czMin = pasteZ >> 4, czMax = (pasteZ + depth) >> 4;
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                dst.getChunkProvider().provideChunk(cx, cz);
            }
        }
        int top = 0;
        for (int x = pasteX; x < pasteX + width; x++) {
            for (int z = pasteZ; z < pasteZ + depth; z++) {
                int y = dst.getTopSolidOrLiquidBlock(new BlockPos(x, MAX_BUILD_Y, z)).getY();
                if (y > top) {
                    top = y;
                }
            }
        }
        return top;
    }
}
