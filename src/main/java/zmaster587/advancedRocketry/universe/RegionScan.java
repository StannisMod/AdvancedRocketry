package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * A telescope's survey of one region of the galaxy: which box of sectors it covers, how far through
 * it the instrument has got, and when the next batch of cells is resolved.
 *
 * <p>A survey <b>sweeps</b>. It walks its region cell by cell, a bounded number of cells per step,
 * writing what each one holds as it goes — an operator points the instrument at a patch of sky once
 * and the machine works through it, rather than being re-aimed by hand for every cell. That bound is
 * what keeps a procedurally endless universe from being enumerated in a tick; the reach bound keeps
 * the patch inside the local cluster.</p>
 *
 * <p>Each step is a <b>deadline, never a counter</b>: the tick the next batch lands is stored, so a
 * survey whose observatory unloads mid-sweep resumes exactly where it stood, owing no replay.</p>
 *
 * <p>Immutable — advancing returns a new survey. The NBT shape is a same-version save contract: a
 * sweep outlives the chunk it started in.</p>
 */
public final class RegionScan {

    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";
    private static final String KEY_DISTANCE = "dist";
    private static final String KEY_START = "start";
    private static final String KEY_STEP_DEADLINE = "stepDeadline";
    private static final String KEY_CELLS_DONE = "cellsDone";
    private static final String KEY_CELLS_PER_STEP = "cellsPerStep";
    private static final String KEY_TICKS_PER_STEP = "ticksPerStep";

    private final GalacticCoord min;
    private final GalacticCoord max;
    private final int distanceSectors;
    private final long startTick;
    private final long stepDeadline;
    private final int cellsDone;
    private final int cellsPerStep;
    private final int ticksPerStep;

    private RegionScan(GalacticCoord min, GalacticCoord max, int distanceSectors, long startTick,
                       long stepDeadline, int cellsDone, int cellsPerStep, int ticksPerStep) {
        this.min = min;
        this.max = max;
        this.distanceSectors = distanceSectors;
        this.startTick = startTick;
        this.stepDeadline = stepDeadline;
        this.cellsDone = cellsDone;
        this.cellsPerStep = Math.max(1, cellsPerStep);
        this.ticksPerStep = Math.max(0, ticksPerStep);
    }

    /**
     * Aim a survey from {@code origin} along a direction, {@code distanceSectors} sectors out.
     *
     * <p>The direction is taken as a sign per axis, so any vector pointing the same way aims the same
     * survey. The distance is clamped into {@code [1, maxRange]} rather than refused: an operator who
     * asks for more than the instrument can reach gets the instrument's reach, which is what a
     * horizon means.</p>
     *
     * @throws IllegalArgumentException if there is no origin, or the direction is the zero vector —
     *                                  a survey with no direction does not name a region.
     */
    public static RegionScan directed(GalacticCoord origin, int dirX, int dirY, int dirZ,
                                      int distanceSectors, long startTick, Tuning tuning) {
        if (origin == null) {
            throw new IllegalArgumentException("a region survey needs an origin to aim from");
        }
        if (tuning == null) {
            throw new IllegalArgumentException("a region survey needs its bounds");
        }
        int dx = Integer.signum(dirX);
        int dy = Integer.signum(dirY);
        int dz = Integer.signum(dirZ);
        if (dx == 0 && dy == 0 && dz == 0) {
            throw new IllegalArgumentException("a survey with no direction does not name a region");
        }

        int distance = Math.max(1, Math.min(distanceSectors, tuning.maxRangeSectors()));
        int half = tuning.effectiveHalfWidthSectors();

        long cx = origin.sectorX() + (long) dx * distance;
        long cy = origin.sectorY() + (long) dy * distance;
        long cz = origin.sectorZ() + (long) dz * distance;

        return box(GalacticCoord.ofSectorLocal(cx - half, cy - half, cz - half, 0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(cx + half, cy + half, cz + half, 0L, 0L, 0L),
                distance, startTick, tuning);
    }

    /**
     * A survey of an explicit box — how the passive local radar states its own neighbourhood, where
     * there is no direction to aim and the distance is simply how far the box reaches.
     */
    public static RegionScan box(GalacticCoord lo, GalacticCoord hi, int distanceSectors,
                                 long startTick, Tuning tuning) {
        int ticksPerStep = Math.max(0, tuning.baseTicks()
                + tuning.ticksPerSector() * Math.max(0, distanceSectors));
        return new RegionScan(lo, hi, distanceSectors, startTick, startTick + ticksPerStep,
                0, tuning.cellsPerStep(), ticksPerStep);
    }

    /** The inclusive low corner of the surveyed sector box. */
    public GalacticCoord min() {
        return min;
    }

    /** The inclusive high corner of the surveyed sector box. */
    public GalacticCoord max() {
        return max;
    }

    /** How far out the survey was aimed, after the range clamp. */
    public int distanceSectors() {
        return distanceSectors;
    }

    public long startTick() {
        return startTick;
    }

    /** The tick the next batch of cells is resolved. */
    public long stepDeadline() {
        return stepDeadline;
    }

    /** How many cells of the region have been resolved so far. */
    public int cellsDone() {
        return cellsDone;
    }

    public int cellsPerStep() {
        return cellsPerStep;
    }

    public int ticksPerStep() {
        return ticksPerStep;
    }

    /** How many cells the region holds. Bounded at construction; never unbounded. */
    public int totalCells() {
        long sx = max.sectorX() - min.sectorX() + 1L;
        long sy = max.sectorY() - min.sectorY() + 1L;
        long sz = max.sectorZ() - min.sectorZ() + 1L;
        long cells = sx * sy * sz;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, cells));
    }

    /** {@code true} once every cell of the region has been resolved. */
    public boolean isComplete() {
        return cellsDone >= totalCells();
    }

    /** {@code true} when the next batch is due — a pure read of the clock against the deadline. */
    public boolean stepDue(long now) {
        return !isComplete() && now >= stepDeadline;
    }

    /** How much of the region is surveyed, in {@code [0,1]}. Cells resolved, not ticks elapsed. */
    public float progress() {
        int total = totalCells();
        if (total <= 0) {
            return 1f;
        }
        return Math.min(1f, cellsDone / (float) total);
    }

    /** Roughly how long the whole sweep takes — what a farther region costs against a nearer one. */
    public long estimatedTicks() {
        int steps = (totalCells() + cellsPerStep - 1) / cellsPerStep;
        return (long) steps * ticksPerStep;
    }

    /**
     * The cell at {@code index} in the sweep order: rows along X, then Z, then Y. The order is
     * deterministic so a resumed sweep continues where it stopped rather than starting over.
     */
    public GalacticCoord cellAt(int index) {
        long width = max.sectorX() - min.sectorX() + 1L;
        long depth = max.sectorZ() - min.sectorZ() + 1L;
        long perLayer = width * depth;
        long y = index / perLayer;
        long rest = index % perLayer;
        long z = rest / width;
        long x = rest % width;
        return GalacticCoord.ofSectorLocal(min.sectorX() + x, min.sectorY() + y, min.sectorZ() + z,
                0L, 0L, 0L);
    }

    /** How many cells the batch due at {@code now} covers — the per-step bound, or what is left. */
    public int cellsDueAt(long now) {
        if (!stepDue(now)) {
            return 0;
        }
        return Math.min(cellsPerStep, totalCells() - cellsDone);
    }

    /** The survey after a batch of {@code resolved} cells has been written, with its next deadline. */
    public RegionScan advanced(long now, int resolved) {
        int done = Math.min(totalCells(), cellsDone + Math.max(0, resolved));
        return new RegionScan(min, max, distanceSectors, startTick, now + ticksPerStep, done,
                cellsPerStep, ticksPerStep);
    }

    /** The survey with every cell resolved — the instant path, where time is not the mechanic. */
    public RegionScan completed(long now) {
        return new RegionScan(min, max, distanceSectors, startTick, now, totalCells(),
                cellsPerStep, ticksPerStep);
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound lo = new NBTTagCompound();
        min.writeToNBT(lo);
        nbt.setTag(KEY_MIN, lo);

        NBTTagCompound hi = new NBTTagCompound();
        max.writeToNBT(hi);
        nbt.setTag(KEY_MAX, hi);

        nbt.setInteger(KEY_DISTANCE, distanceSectors);
        nbt.setLong(KEY_START, startTick);
        nbt.setLong(KEY_STEP_DEADLINE, stepDeadline);
        nbt.setInteger(KEY_CELLS_DONE, cellsDone);
        nbt.setInteger(KEY_CELLS_PER_STEP, cellsPerStep);
        nbt.setInteger(KEY_TICKS_PER_STEP, ticksPerStep);
    }

    /** The survey stored in {@code nbt}, or {@code null} when nothing was stored. */
    public static RegionScan readFromNBT(NBTTagCompound nbt) {
        if (nbt == null || !nbt.hasKey(KEY_MIN) || !nbt.hasKey(KEY_MAX)) {
            return null;
        }
        return new RegionScan(
                GalacticCoord.readFromNBT(nbt.getCompoundTag(KEY_MIN)),
                GalacticCoord.readFromNBT(nbt.getCompoundTag(KEY_MAX)),
                nbt.getInteger(KEY_DISTANCE),
                nbt.getLong(KEY_START),
                nbt.getLong(KEY_STEP_DEADLINE),
                nbt.getInteger(KEY_CELLS_DONE),
                nbt.getInteger(KEY_CELLS_PER_STEP),
                nbt.getInteger(KEY_TICKS_PER_STEP));
    }

    @Override
    public String toString() {
        return "RegionScan[" + min.cellKey() + " .. " + max.cellKey()
                + ", " + cellsDone + "/" + totalCells() + " cells, next@" + stepDeadline + "]";
    }

    /**
     * What bounds a survey and what it costs in time. Every number here is balance, not contract: the
     * reach, the size of the patch, how many cells one step resolves and how long a step takes.
     */
    public static final class Tuning {

        private final int maxRangeSectors;
        private final int halfWidthSectors;
        private final int maxSectors;
        private final int baseTicks;
        private final int ticksPerSector;
        private final int cellsPerStep;

        public Tuning(int maxRangeSectors, int halfWidthSectors, int maxSectors,
                      int baseTicks, int ticksPerSector, int cellsPerStep) {
            this.maxRangeSectors = Math.max(1, maxRangeSectors);
            this.halfWidthSectors = Math.max(0, halfWidthSectors);
            this.maxSectors = Math.max(1, maxSectors);
            this.baseTicks = Math.max(0, baseTicks);
            this.ticksPerSector = Math.max(0, ticksPerSector);
            this.cellsPerStep = Math.max(1, cellsPerStep);
        }

        /** The tuning the running game is configured with. */
        public static Tuning fromConfig() {
            ARConfiguration config = ARConfiguration.getCurrentConfig();
            return new Tuning(
                    config.telescopeScanRangeSectors,
                    config.telescopeScanHalfWidthSectors,
                    config.telescopeScanMaxSectors,
                    config.telescopeScanBaseTicks,
                    config.telescopeScanTicksPerSector,
                    config.telescopeScanCellsPerStep);
        }

        public int maxRangeSectors() {
            return maxRangeSectors;
        }

        public int baseTicks() {
            return baseTicks;
        }

        public int ticksPerSector() {
            return ticksPerSector;
        }

        public int cellsPerStep() {
            return cellsPerStep;
        }

        /**
         * The half-width a survey actually gets: the configured one, narrowed until the region fits
         * inside the sector ceiling. The ceiling wins over the width — a sweep may be long, but it
         * may not be unbounded.
         */
        public int effectiveHalfWidthSectors() {
            int half = halfWidthSectors;
            while (half > 0 && volumeOf(half) > maxSectors) {
                half--;
            }
            return half;
        }

        private static long volumeOf(int half) {
            long side = 2L * half + 1L;
            return side * side * side;
        }
    }
}
