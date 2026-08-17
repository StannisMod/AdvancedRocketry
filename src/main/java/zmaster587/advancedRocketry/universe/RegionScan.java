package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * A telescope's survey of one region of the galaxy: which box of cells it covers, how far through
 * it the instrument has got, and when the next batch of cells is resolved.
 *
 * <p>A survey <b>sweeps</b>. It walks its region a cell at a time, a bounded number of cells per
 * step, writing what each one holds as it goes — an operator points the instrument at a patch of sky
 * once and the machine works through it, rather than being re-aimed by hand for every cell. That
 * bound is what keeps a procedurally endless universe from being enumerated in a tick; the reach
 * bound keeps the patch inside the local cluster.</p>
 *
 * <p><b>It samples, it does not enumerate.</b> Between two cells it looks at lies a whole star's
 * territory — the survey strides by {@link Tuning#strideCells()}, which is the edge of the cube that
 * holds at most one system. Walking cell by cell would spend a whole sweep re-reading one system's
 * own neighbourhood, since every cell of a system's territory resolves to that same system; striding
 * by the territory means a sweep of N cells looks at N candidate systems. In a star CLUSTER, where
 * the lattice is subdivided below that edge, a survey therefore samples the cluster rather than
 * emptying it: what is inside one stride and off the sampled cell is left for another look.</p>
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
    private static final String KEY_DISTANCE = "distCells";
    private static final String KEY_STRIDE = "stride";
    private static final String KEY_START = "start";
    private static final String KEY_STEP_DEADLINE = "stepDeadline";
    private static final String KEY_CELLS_DONE = "cellsDone";
    private static final String KEY_CELLS_PER_STEP = "cellsPerStep";
    private static final String KEY_TICKS_PER_STEP = "ticksPerStep";

    private final GalacticCoord min;
    private final GalacticCoord max;
    private final long distanceCells;
    private final long strideCells;
    private final long startTick;
    private final long stepDeadline;
    private final int cellsDone;
    private final int cellsPerStep;
    private final int ticksPerStep;
    private final int totalCells;

    private RegionScan(GalacticCoord min, GalacticCoord max, long distanceCells, long strideCells,
                       long startTick, long stepDeadline, int cellsDone, int cellsPerStep,
                       int ticksPerStep) {
        this.min = min;
        this.max = max;
        this.distanceCells = Math.max(0L, distanceCells);
        this.strideCells = Math.max(1L, strideCells);
        this.startTick = startTick;
        this.stepDeadline = stepDeadline;
        this.cellsDone = cellsDone;
        this.cellsPerStep = Math.max(1, cellsPerStep);
        this.ticksPerStep = Math.max(0, ticksPerStep);
        this.totalCells = countLooks(min, max, this.strideCells);
    }

    /**
     * How many looks the region between two corners holds at {@code stride} &mdash; computed once,
     * here, and REFUSED rather than clamped when it will not fit an {@code int}.
     *
     * <p>A survey is walked by an {@code int} cursor, so a region with more looks than an {@code int}
     * can index is not a long survey: it is one that would report itself complete at 2·10⁹ looks with
     * the rest of the region never visited, and progress would read 100 % while the sky was untouched.
     * That was unreachable while a scan's reach was a few hundred cells and becomes reachable the
     * moment survey ranges grow with the galaxy, so the bound is stated where the survey is built.</p>
     *
     * <p>The product is checked in {@code double} first: the three counts are {@code long}s and their
     * product overflows one long before it passes an {@code int}, so multiplying to find out would be
     * the same silent wrap in a different place. Fifty-three bits of mantissa is far more than a
     * comparison against 2<sup>31</sup> needs.</p>
     */
    private static int countLooks(GalacticCoord min, GalacticCoord max, long stride) {
        long x = countAlong(min.sectorX(), max.sectorX(), stride);
        long y = countAlong(min.sectorY(), max.sectorY(), stride);
        long z = countAlong(min.sectorZ(), max.sectorZ(), stride);
        if ((double) x * (double) y * (double) z > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a survey of " + x + "x" + y + "x" + z
                    + " looks cannot be walked: " + min.cellKey() + " .. " + max.cellKey()
                    + " at a stride of " + stride + " cells. Narrow the region or widen the stride.");
        }
        return (int) (x * y * z);
    }

    /**
     * Aim a survey from {@code origin} along a direction, {@code distanceSteps} star territories out.
     *
     * <p>The direction is taken as a sign per axis, so any vector pointing the same way aims the same
     * survey. The distance is counted in STEPS — one step is one star's territory, the same stride
     * the sweep walks by — so an aim of 3 means "three stars out", not three cells, which would be a
     * fraction of one system. It is clamped into {@code [1, maxRangeSteps]} rather than refused: an
     * operator who asks for more than the instrument can reach gets the instrument's reach, which is
     * what a horizon means.</p>
     *
     * @throws IllegalArgumentException if there is no origin, or the direction is the zero vector —
     *                                  a survey with no direction does not name a region.
     */
    public static RegionScan directed(GalacticCoord origin, int dirX, int dirY, int dirZ,
                                      int distanceSteps, long startTick, Tuning tuning) {
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

        long stride = tuning.strideCells();
        int steps = Math.max(1, Math.min(distanceSteps, tuning.maxRangeSteps()));
        long distance = steps * stride;
        long half = tuning.effectiveHalfWidthSteps() * stride;

        long cx = origin.sectorX() + (long) dx * distance;
        long cy = origin.sectorY() + (long) dy * distance;
        long cz = origin.sectorZ() + (long) dz * distance;

        return new RegionScan(
                GalacticCoord.ofSectorLocal(cx - half, cy - half, cz - half, 0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(cx + half, cy + half, cz + half, 0L, 0L, 0L),
                distance, stride, startTick, startTick + stepTicks(distance, tuning),
                0, tuning.cellsPerStep(), stepTicks(distance, tuning));
    }

    /**
     * The passive local radar: a box of {@code radiusCells} cells around {@code origin}, walked cell
     * by cell.
     *
     * <p>Its stride is ONE CELL and that is deliberate — this is a radar over the observatory's own
     * neighbourhood, where the cells really are the interesting granularity (the planet in the next
     * cell over is a different destination from its star). The directed survey is the one that looks
     * far away, and it is the one that strides by star territories.</p>
     */
    public static RegionScan local(GalacticCoord origin, int radiusCells, long startTick,
                                   Tuning tuning) {
        if (origin == null) {
            throw new IllegalArgumentException("a local radar needs the cell it is standing in");
        }
        if (tuning == null) {
            throw new IllegalArgumentException("a region survey needs its bounds");
        }
        long radius = Math.max(0, radiusCells);
        int ticks = stepTicks(radius, tuning);
        return new RegionScan(
                GalacticCoord.ofSectorLocal(origin.sectorX() - radius, origin.sectorY() - radius,
                        origin.sectorZ() - radius, 0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(origin.sectorX() + radius, origin.sectorY() + radius,
                        origin.sectorZ() + radius, 0L, 0L, 0L),
                radius, 1L, startTick, startTick + ticks, 0, tuning.cellsPerStep(), ticks);
    }

    /**
     * What one step of a survey aimed {@code distanceCells} away costs, in ticks: a fixed cost for
     * holding the instrument on a patch of sky at all, plus a price per light year of distance.
     *
     * <p>The distance is converted to light years before it is priced, so what a far look costs stays
     * put when the cell edge or the star spacing is retuned.</p>
     */
    private static int stepTicks(long distanceCells, Tuning tuning) {
        double ticks = tuning.baseTicks()
                + tuning.ticksPerLightYear() * UniverseScale.lightYearsForCells(distanceCells);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, Math.round(ticks)));
    }

    /** The inclusive low corner of the surveyed sector box. */
    public GalacticCoord min() {
        return min;
    }

    /** The inclusive high corner of the surveyed sector box. */
    public GalacticCoord max() {
        return max;
    }

    /** How far out the survey was aimed, in cells, after the range clamp. */
    public long distanceCells() {
        return distanceCells;
    }

    /** The same reach in light years — the form the number is recognisable in. */
    public double distanceLightYears() {
        return UniverseScale.lightYearsForCells(distanceCells);
    }

    /** How far apart the cells this survey looks at stand. One star's territory, or one cell. */
    public long strideCells() {
        return strideCells;
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

    /**
     * How many cells this survey LOOKS at — not how many the region contains. The two differ by the
     * stride: a region a hundred territories wide is a hundred looks, not a hundred million cells.
     * Bounded at construction; never unbounded, and never a clamped count standing in for a real one.
     */
    public int totalCells() {
        return totalCells;
    }

    /** How many sampled cells one axis of the region holds, at a given stride. */
    private static long countAlong(long lo, long hi, long stride) {
        return Math.max(0L, (hi - lo) / Math.max(1L, stride) + 1L);
    }

    /** The same, at this survey's own stride — what the sweep order is built from. */
    private long countAlong(long lo, long hi) {
        return countAlong(lo, hi, strideCells);
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
     * The cell at {@code index} in the sweep order: rows along X, then Z, then Y, a stride apart. The
     * order is deterministic so a resumed sweep continues where it stopped rather than starting over.
     */
    public GalacticCoord cellAt(int index) {
        long width = countAlong(min.sectorX(), max.sectorX());
        long depth = countAlong(min.sectorZ(), max.sectorZ());
        long perLayer = width * depth;
        long y = index / perLayer;
        long rest = index % perLayer;
        long z = rest / width;
        long x = rest % width;
        return GalacticCoord.ofSectorLocal(min.sectorX() + x * strideCells,
                min.sectorY() + y * strideCells, min.sectorZ() + z * strideCells, 0L, 0L, 0L);
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
        return new RegionScan(min, max, distanceCells, strideCells, startTick, now + ticksPerStep,
                done, cellsPerStep, ticksPerStep);
    }

    /** The survey with every cell resolved — the instant path, where time is not the mechanic. */
    public RegionScan completed(long now) {
        return new RegionScan(min, max, distanceCells, strideCells, startTick, now, totalCells(),
                cellsPerStep, ticksPerStep);
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound lo = new NBTTagCompound();
        min.writeToNBT(lo);
        nbt.setTag(KEY_MIN, lo);

        NBTTagCompound hi = new NBTTagCompound();
        max.writeToNBT(hi);
        nbt.setTag(KEY_MAX, hi);

        nbt.setLong(KEY_DISTANCE, distanceCells);
        nbt.setLong(KEY_STRIDE, strideCells);
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
                nbt.getLong(KEY_DISTANCE),
                nbt.getLong(KEY_STRIDE),
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
     *
     * <p>The reach is stated as a LENGTH — light years, the unit a telescope's horizon is quoted in —
     * and converted here against the stride. Stating it as a count of anything would make the
     * instrument's horizon move whenever the star spacing or the cell edge was retuned, which is how
     * a reach came to mean a fifth of the way to Mercury.</p>
     */
    public static final class Tuning {

        private final double maxRangeLightYears;
        private final int halfWidthSteps;
        private final int maxCells;
        private final int baseTicks;
        private final double ticksPerLightYear;
        private final int cellsPerStep;
        private final long strideCells;

        public Tuning(double maxRangeLightYears, int halfWidthSteps, int maxCells, int baseTicks,
                      double ticksPerLightYear, int cellsPerStep, long strideCells) {
            this.maxRangeLightYears = Math.max(0d, maxRangeLightYears);
            this.halfWidthSteps = Math.max(0, halfWidthSteps);
            this.maxCells = Math.max(1, maxCells);
            this.baseTicks = Math.max(0, baseTicks);
            this.ticksPerLightYear = Math.max(0d, ticksPerLightYear);
            this.cellsPerStep = Math.max(1, cellsPerStep);
            this.strideCells = Math.max(1L, strideCells);
        }

        /**
         * The tuning the running game is configured with — including the stride, which is the active
         * generator's own star spacing and never a number of its own: a survey that strode by
         * anything else would either re-read one system or step over whole ones.
         */
        public static Tuning fromConfig() {
            ARConfiguration config = ARConfiguration.getCurrentConfig();
            return new Tuning(
                    config.telescopeScanRangeLightYears,
                    config.telescopeScanHalfWidthSteps,
                    config.telescopeScanMaxCells,
                    config.telescopeScanBaseTicks,
                    config.telescopeScanTicksPerLightYear,
                    config.telescopeScanCellsPerStep,
                    UniverseRegistry.getGenerator().minSpacingCells());
        }

        /** The instrument's horizon, as a length. */
        public double maxRangeLightYears() {
            return maxRangeLightYears;
        }

        /** How far apart the cells a directed survey looks at stand — one star's territory. */
        public long strideCells() {
            return strideCells;
        }

        /** The horizon as a number of steps, which is what an operator aims in. At least one. */
        public int maxRangeSteps() {
            long steps = UniverseScale.cellsForLightYears(maxRangeLightYears) / strideCells;
            return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, steps));
        }

        public int baseTicks() {
            return baseTicks;
        }

        public double ticksPerLightYear() {
            return ticksPerLightYear;
        }

        public int cellsPerStep() {
            return cellsPerStep;
        }

        /**
         * The half-width a survey actually gets, in steps: the configured one, narrowed until the
         * number of cells it would look at fits inside the ceiling. The ceiling wins over the width —
         * a sweep may be long, but it may not be unbounded.
         */
        public int effectiveHalfWidthSteps() {
            int half = halfWidthSteps;
            while (half > 0 && volumeOf(half) > maxCells) {
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
