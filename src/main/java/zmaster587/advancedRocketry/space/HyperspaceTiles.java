package zmaster587.advancedRocketry.space;

import java.util.TreeSet;

import net.minecraft.util.math.BlockPos;

/**
 * Allocates parking "lanes" in the shared hyperspace world for ships in transit (space-model
 * §10 "Transit hosting"). Every in-flight ship is parked at a distinct tile; the star-tunnel is a
 * client animation and {@link ShipTransit} advances the ship's coordinate logically, so the parked
 * ships never physically move and must simply not overlap.
 *
 * <p>Tiles are laid out on a square spiral (the same shape {@code SpaceObjectManager} uses for
 * stations) with adjacent lanes {@link #SPACING_BLOCKS} apart - 2048 blocks = 128 chunks, beyond both
 * the modded render ceiling (~64 chunks) and the ~512-block player entity-tracking range, so riders
 * never see, collide with, or track across ships on pure vanilla mechanics. The void between lanes is
 * never chunk-loaded, so spacing is near-free and capacity is effectively unbounded.</p>
 *
 * <p>Pure and server-main-thread only: hands out and recycles integer indices and maps each to a
 * world {@link BlockPos}. Freed indices are reused (lowest-first) to keep the used region compact.</p>
 */
public final class HyperspaceTiles {

    /** Blocks between adjacent transit lanes (128 chunks - beyond render + entity-tracking range). */
    public static final int SPACING_BLOCKS = 2048;
    /** The Y all lanes park at (well clear of any procedural floor; the world is void anyway). */
    public static final int BASE_Y = 128;

    /** A single allocated parking lane: its stable index and the world position ships park at. */
    public static final class Tile {
        public final int index;
        public final BlockPos pos;

        Tile(int index, BlockPos pos) {
            this.index = index;
            this.pos = pos;
        }
    }

    private final TreeSet<Integer> free = new TreeSet<>();
    private int next;

    /** Reserve the lowest free lane (reusing a freed index before extending the used region). */
    public Tile allocate() {
        int idx = free.isEmpty() ? next++ : free.pollFirst();
        return new Tile(idx, tilePos(idx));
    }

    /** Release {@code tile}'s lane back to the free set so a later transit can reuse it. */
    public void free(Tile tile) {
        if (tile != null && tile.index >= 0 && tile.index < next) {
            free.add(tile.index);
        }
    }

    /** Number of lanes currently in use (allocated minus freed). */
    public int inUseCount() {
        return next - free.size();
    }

    /** The world position of lane {@code index} - a fixed function of the index (no state). */
    public static BlockPos tilePos(int index) {
        int[] xz = ringXZ(index);
        return new BlockPos(xz[0] * SPACING_BLOCKS, BASE_Y, xz[1] * SPACING_BLOCKS);
    }

    /**
     * The {@code (x,z)} ring cell of spiral index {@code index}. Mirrors the square-spiral placement in
     * {@code SpaceObjectManager.registerSpaceObject} (top/bottom rows filled first, then the sides), so
     * successive indices step outward one ring at a time and never collide.
     */
    static int[] ringXZ(int index) {
        int radius = (int) Math.floor(Math.ceil(Math.sqrt(index + 1)) / 2);
        int ringIndex = (int) (index - Math.pow((radius * 2) - 1, 2));
        int x;
        int z;
        if (ringIndex < (radius * 2 + 1) * 2) {
            x = ringIndex % (radius * 2 + 1) - radius;
            z = ringIndex < (radius * 2 + 1) ? -radius : radius;
        } else {
            int newIndex = ringIndex - (radius * 2 + 1) * 2;
            z = newIndex % ((radius - 1) * 2 + 1) - (radius - 1);
            x = newIndex < ((radius - 1) * 2 + 1) ? -radius : radius;
        }
        return new int[]{x, z};
    }
}
