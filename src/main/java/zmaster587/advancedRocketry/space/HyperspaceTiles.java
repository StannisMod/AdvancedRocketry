package zmaster587.advancedRocketry.space;

import java.util.TreeSet;

import net.minecraft.util.math.BlockPos;

/**
 * Allocates parking "lanes" in the shared hyperspace world for ships in transit ("transit
 * hosting"). Every in-flight ship is parked at a distinct tile; the star-tunnel is a
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

    /**
     * Take lane {@code index} for a transit being restored from disk, so the ship parked there keeps
     * the lane it left in and no later allocation is handed it.
     *
     * <p>Restoration runs before any departure, and the indices it reclaims are arbitrary — a save
     * may hold lanes 0 and 4 with nothing in between. So this both marks {@code index} used and makes
     * the gap it opens allocatable: without that, reclaiming lane 4 would push {@code next} past 0-3
     * and quietly retire three perfectly good lanes on every boot.</p>
     */
    public void reserve(int index) {
        if (index < 0) {
            return;
        }
        if (index >= next) {
            for (int gap = next; gap < index; gap++) {
                free.add(gap);
            }
            next = index + 1;
            return;
        }
        free.remove(index);
    }

    /**
     * The lane the point {@code (x,z)} sits in, or {@code -1} when it is in none of them.
     *
     * <p>Lanes are {@link #SPACING_BLOCKS} apart on a fixed grid and a parked ship never moves, so
     * "which lane is this ship in" is answered by the point alone: half a lane's spacing can only
     * ever contain one lane's parking spot, so the nearest grid point either IS the lane or there is
     * no lane. Used at boot to attribute the ships found in hyperspace to the transits that claim
     * them.</p>
     *
     * <p><b>The caller may not bound this, and that is the whole point.</b> It used to take a search
     * limit, which every caller could only source from how far the ALLOCATOR had extended — and at
     * boot the allocator knows nothing but what the surviving records reclaimed. A hull whose record
     * did not survive is exactly the hull the reconciliation exists to find, and it is exactly the
     * one that sits beyond such a bound: with no surviving records at all the bound was one lane,
     * and the search reported "in no lane" for everything but lane 0. The ship's own position is the
     * one source that cannot be wrong about which lane it is standing in.</p>
     */
    public static int laneIndexAt(double x, double z) {
        int gx = (int) Math.round(x / SPACING_BLOCKS);
        int gz = (int) Math.round(z / SPACING_BLOCKS);
        double dx = (double) gx * SPACING_BLOCKS - x;
        double dz = (double) gz * SPACING_BLOCKS - z;
        if (dx * dx + dz * dz >= (SPACING_BLOCKS / 2.0) * (SPACING_BLOCKS / 2.0)) {
            return -1; // between lanes: nothing parks there
        }
        // Which ring that grid cell belongs to is fixed by the cell, and a ring is a contiguous run
        // of indices, so only that run has to be walked - a few dozen steps for any lane a save can
        // realistically have reached, and no dependence on allocator state.
        int ring = Math.max(Math.abs(gx), Math.abs(gz));
        int from = ring == 0 ? 0 : (2 * ring - 1) * (2 * ring - 1);
        int to = (2 * ring + 1) * (2 * ring + 1);
        for (int index = from; index < to; index++) {
            int[] cell = ringXZ(index);
            if (cell[0] == gx && cell[1] == gz) {
                return index;
            }
        }
        return -1;
    }

    /** Release {@code tile}'s lane back to the free set so a later transit can reuse it. */
    public void free(Tile tile) {
        if (tile != null && tile.index >= 0 && tile.index < next) {
            free.add(tile.index);
        }
    }

    /**
     * Give up {@code tile} WITHOUT returning it to the free set, so no later transit is ever parked
     * there. For a lane whose ship could not be cut back out: it is still a registered ship standing
     * at that lane's position, and {@link #allocate()} hands out the lowest free index first, so
     * releasing the lane would drop the next departing ship on top of it. Retiring costs an index out
     * of a supply the class doc calls effectively unbounded.
     *
     * <p><b>It does not have to survive a restart, and that is a fact about what a lane IS.</b> A lane
     * is a world position a ship's transform is placed at, not a region its blocks are written into -
     * a crossing re-assembles into a fresh subspace shipyard and only the transform lands here. So a
     * hull still registered here is found again next boot from its own position, whatever this
     * allocator remembers, and a hull that was successfully deregistered no longer stands anywhere:
     * its blocks are in a shipyard nothing addresses by lane. Neither case needs the retirement
     * itself to be durable.</p>
     */
    public void retire(Tile tile) {
        // Deliberately nothing: not adding the index back to `free` IS the retirement. Written as a
        // method rather than a comment at the call site so the intent survives a later reader who sees
        // an allocate() with no matching free() and assumes a leak.
    }

    /** Number of lanes currently in use (allocated minus freed, counting retired lanes as in use). */
    public int inUseCount() {
        return next - free.size();
    }

    /**
     * Lane {@code index} as a value, without allocating it — for a transit restored from disk, which
     * already HAS its lane and is only re-acquiring the handle to it. Pair it with {@link #reserve}:
     * this hands out the value, that one tells the allocator the index is spoken for.
     */
    public static Tile tile(int index) {
        return new Tile(index, tilePos(index));
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
