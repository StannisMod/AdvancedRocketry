package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * A telescope's survey: which sky it covers, how far through it the instrument has got, and when the
 * next batch of looks is resolved.
 *
 * <p>A survey <b>sweeps</b>. It walks its looks a bounded number at a time, writing what each one
 * holds as it goes — an operator points the instrument at a patch of sky once and the machine works
 * through it, rather than being re-aimed by hand for every cell. That bound is what keeps a
 * procedurally endless universe from being enumerated in a tick.</p>
 *
 * <p><b>Two shapes, and they are two different instruments.</b></p>
 * <ul>
 *   <li>A <b>pointing</b> ({@link #directed}) is a {@link ConeWalk}: an apex at the observatory, a
 *       direction, a half-angle, and a reach that comes from what the instrument can SEE rather than
 *       from a configured horizon. This is the telescope.</li>
 *   <li>A <b>local radar</b> ({@link #local}) is a box of the star territories around the
 *       observatory's own. This is the passive watch over the neighbourhood: near sky, no aiming, and
 *       its data ready.</li>
 * </ul>
 *
 * <p><b>It samples, it does not enumerate.</b> Between two looks of a pointing lies a whole star's
 * territory — the sweep strides by {@link Tuning#strideCells()}, the edge of the cube that holds at
 * most one system. Walking cell by cell would spend a whole sweep re-reading one system's own
 * neighbourhood, since every cell of a system's territory resolves to that same system. What a look
 * OWES its territory — one seat, or all of the sub-seats a cluster divides it into — is the resolving
 * side's business and lives in {@link TelescopeScan}, not here.</p>
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
    private static final String KEY_CONE = "cone";

    /** The pointing this survey walks, or {@code null} for the box-shaped local radar. */
    private final ConeWalk cone;
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

    private RegionScan(ConeWalk cone, GalacticCoord min, GalacticCoord max, long distanceCells,
                       long strideCells, long startTick, long stepDeadline, int cellsDone,
                       int cellsPerStep, int ticksPerStep) {
        this.cone = cone;
        this.distanceCells = Math.max(0L, distanceCells);
        this.strideCells = Math.max(1L, strideCells);
        this.startTick = startTick;
        this.stepDeadline = stepDeadline;
        this.cellsDone = cellsDone;
        this.cellsPerStep = Math.max(1, cellsPerStep);
        this.ticksPerStep = Math.max(0, ticksPerStep);
        if (cone == null) {
            this.min = min;
            this.max = max;
            this.totalCells = countLooks(min, max, this.strideCells);
        } else {
            // The corners a cone reports are its BOUNDING BOX and nothing it promises to fill: they
            // exist because a survey is asked "roughly where are you looking" by the status read-out
            // and by the obscured-count probe, and a cone has no corners of its own to answer with.
            long reach = cone.reachCells();
            this.min = GalacticCoord.ofSectorLocal(cone.apex().sectorX() - reach,
                    cone.apex().sectorY() - reach, cone.apex().sectorZ() - reach, 0L, 0L, 0L);
            this.max = GalacticCoord.ofSectorLocal(cone.apex().sectorX() + reach,
                    cone.apex().sectorY() + reach, cone.apex().sectorZ() + reach, 0L, 0L, 0L);
            this.totalCells = cone.totalLooks();
        }
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
     * Point the instrument from {@code origin} along a direction, {@code distanceSteps} star
     * territories deep.
     *
     * <p>Unlike a box, a pointing keeps its direction EXACTLY: the vector is used as given rather
     * than reduced to a sign per axis, because a cone that snapped to the twenty-six lattice
     * directions would not be an aim, it would be a menu.</p>
     *
     * <p>The depth is counted in STEPS — one step is one star's territory, the same stride the sweep
     * walks by — and is clamped into {@code [1, maxRangeSteps]} rather than refused: an operator who
     * asks for more than the instrument can reach gets the instrument's reach, which is what a horizon
     * means. That reach is {@link Tuning#maxRangeSteps()}, which is derived from the aperture's
     * limiting magnitude and is a fact about the instrument rather than a number someone set.</p>
     *
     * @throws IllegalArgumentException if there is no origin, or the direction is the zero vector —
     *                                  a pointing with no direction does not name a patch of sky.
     */
    public static RegionScan directed(GalacticCoord origin, int dirX, int dirY, int dirZ,
                                      int distanceSteps, long startTick, Tuning tuning) {
        if (origin == null) {
            throw new IllegalArgumentException("a survey needs an origin to aim from");
        }
        if (tuning == null) {
            throw new IllegalArgumentException("a survey needs its bounds");
        }
        long stride = tuning.strideCells();
        int steps = Math.max(1, Math.min(distanceSteps, tuning.maxRangeSteps()));
        ConeWalk aimed = tuning.fit(origin, dirX, dirY, dirZ, steps);
        int ticks = tuning.baseTicks();
        return new RegionScan(aimed, null, null, aimed.reachCells(), stride, startTick,
                startTick + ticks, 0, tuning.cellsPerStep(), ticks);
    }

    /**
     * The passive local radar: the observatory's own star territory and the {@code radiusSteps} rings
     * of territories around it.
     *
     * <p><b>Territories and not cells.</b> This walked cell by cell until 2026-08-19, on the ground
     * that "the planet in the next cell over is a different destination from its star" — which is
     * true and is not a reason, because one look already yields every body of the system that owns
     * it. What a cell-by-cell radius bought was nothing at all: two cells is 0.107 AU, a fifth of the
     * way to the innermost planet of the system the instrument is already standing in, and no radius
     * a cell-strided box could afford would ever have reached a NEIGHBOUR, which is a whole territory
     * away. A radius of one territory is twenty-seven looks and is what "watches the neighbourhood"
     * was always meant to say.</p>
     */
    public static RegionScan local(GalacticCoord origin, int radiusSteps, long startTick,
                                   Tuning tuning) {
        if (origin == null) {
            throw new IllegalArgumentException("a local radar needs the cell it is standing in");
        }
        if (tuning == null) {
            throw new IllegalArgumentException("a survey needs its bounds");
        }
        long stride = tuning.strideCells();
        long radius = Math.max(0, radiusSteps) * stride;
        int ticks = tuning.baseTicks();
        return new RegionScan(null,
                GalacticCoord.ofSectorLocal(origin.sectorX() - radius, origin.sectorY() - radius,
                        origin.sectorZ() - radius, 0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(origin.sectorX() + radius, origin.sectorY() + radius,
                        origin.sectorZ() + radius, 0L, 0L, 0L),
                radius, stride, startTick, startTick + ticks, 0, tuning.cellsPerStep(), ticks);
    }

    /** The pointing this survey walks, or {@code null} when it is the box-shaped local radar. */
    public ConeWalk cone() {
        return cone;
    }

    /** {@code true} when this survey is a pointing rather than the local radar. */
    public boolean isPointing() {
        return cone != null;
    }

    /** The inclusive low corner of the surveyed box; for a pointing, of the box that bounds it. */
    public GalacticCoord min() {
        return min;
    }

    /** The inclusive high corner of the surveyed box; for a pointing, of the box that bounds it. */
    public GalacticCoord max() {
        return max;
    }

    /** How far out the survey reaches, in cells, after the range clamp. */
    public long distanceCells() {
        return distanceCells;
    }

    /** The same reach in light years — the form the number is recognisable in. */
    public double distanceLightYears() {
        return UniverseRegistry.getGenerator().laws().lightYearsForCells(distanceCells);
    }

    /** How far apart the cells this survey looks at stand. One star's territory, or one cell. */
    public long strideCells() {
        return strideCells;
    }

    public long startTick() {
        return startTick;
    }

    /** The tick the next batch of looks is resolved. */
    public long stepDeadline() {
        return stepDeadline;
    }

    /** How many looks of the survey have been resolved so far. */
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
     * How many cells this survey LOOKS at — not how many the sky it covers contains. The two differ
     * by the stride: a pointing a hundred territories deep is a few thousand looks, not the hundreds
     * of millions of cells the cone encloses. Bounded at construction; never unbounded, and never a
     * clamped count standing in for a real one.
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

    /** {@code true} once every look of the survey has been resolved. */
    public boolean isComplete() {
        return cellsDone >= totalCells();
    }

    /** {@code true} when the next batch is due — a pure read of the clock against the deadline. */
    public boolean stepDue(long now) {
        return !isComplete() && now >= stepDeadline;
    }

    /** How much of the survey is done, in {@code [0,1]}. Looks resolved, not ticks elapsed. */
    public float progress() {
        int total = totalCells();
        if (total <= 0) {
            return 1f;
        }
        return Math.min(1f, cellsDone / (float) total);
    }

    /** Roughly how long the whole sweep takes — what a deeper pointing costs against a shallower one. */
    public long estimatedTicks() {
        int steps = (totalCells() + cellsPerStep - 1) / cellsPerStep;
        return (long) steps * ticksPerStep;
    }

    /**
     * The cell the look at {@code index} lands on.
     *
     * <p>For a pointing, the cone's own order: shell by shell outwards, so an aborted survey has
     * covered a SHORTER cone rather than a scatter. For the local radar, rows along X, then Z, then Y,
     * a stride apart. Both are deterministic, so a resumed sweep continues where it stopped rather
     * than starting over.</p>
     */
    public GalacticCoord cellAt(int index) {
        if (cone != null) {
            return cone.lookAt(index);
        }
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

    /**
     * Where the survey is looking FROM — the apex of a pointing, or the centre of the radar's box.
     *
     * <p>The resolving side needs it for something the box shape never had to answer: how far away
     * what it just found is, which is half of how bright the thing looks.</p>
     */
    public GalacticCoord observer() {
        if (cone != null) {
            return cone.apex();
        }
        return GalacticCoord.ofSectorLocal((min.sectorX() + max.sectorX()) / 2L,
                (min.sectorY() + max.sectorY()) / 2L, (min.sectorZ() + max.sectorZ()) / 2L,
                0L, 0L, 0L);
    }

    /** How many looks the batch due at {@code now} covers — the per-step bound, or what is left. */
    public int cellsDueAt(long now) {
        if (!stepDue(now)) {
            return 0;
        }
        return Math.min(cellsPerStep, totalCells() - cellsDone);
    }

    /** The survey after a batch of {@code resolved} looks has been written, with its next deadline. */
    public RegionScan advanced(long now, int resolved) {
        int done = Math.min(totalCells(), cellsDone + Math.max(0, resolved));
        return new RegionScan(cone, min, max, distanceCells, strideCells, startTick,
                now + ticksPerStep, done, cellsPerStep, ticksPerStep);
    }

    /** The survey with every look resolved — the instant path, where time is not the mechanic. */
    public RegionScan completed(long now) {
        return new RegionScan(cone, min, max, distanceCells, strideCells, startTick, now,
                totalCells(), cellsPerStep, ticksPerStep);
    }

    public void writeToNBT(NBTTagCompound nbt) {
        if (cone != null) {
            NBTTagCompound aim = new NBTTagCompound();
            cone.writeToNBT(aim);
            nbt.setTag(KEY_CONE, aim);
        } else {
            NBTTagCompound lo = new NBTTagCompound();
            min.writeToNBT(lo);
            nbt.setTag(KEY_MIN, lo);

            NBTTagCompound hi = new NBTTagCompound();
            max.writeToNBT(hi);
            nbt.setTag(KEY_MAX, hi);
        }

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
        if (nbt == null) {
            return null;
        }
        if (nbt.hasKey(KEY_CONE)) {
            return new RegionScan(ConeWalk.readFromNBT(nbt.getCompoundTag(KEY_CONE)), null, null,
                    nbt.getLong(KEY_DISTANCE),
                    nbt.getLong(KEY_STRIDE),
                    nbt.getLong(KEY_START),
                    nbt.getLong(KEY_STEP_DEADLINE),
                    nbt.getInteger(KEY_CELLS_DONE),
                    nbt.getInteger(KEY_CELLS_PER_STEP),
                    nbt.getInteger(KEY_TICKS_PER_STEP));
        }
        if (!nbt.hasKey(KEY_MIN) || !nbt.hasKey(KEY_MAX)) {
            return null;
        }
        return new RegionScan(null,
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
        if (cone != null) {
            return "RegionScan[" + cone + ", " + cellsDone + "/" + totalCells()
                    + " looks, next@" + stepDeadline + "]";
        }
        return "RegionScan[" + min.cellKey() + " .. " + max.cellKey()
                + ", " + cellsDone + "/" + totalCells() + " cells, next@" + stepDeadline + "]";
    }

    /**
     * What bounds a survey and what it costs in time.
     *
     * <p><b>The reach is not here</b>, and that is the point of the shape: an instrument reaches a
     * BRIGHTNESS, and how far that carries is derived from the aperture's limiting magnitude against
     * the brightest star the galaxy can produce ({@link StellarMagnitude#instrumentReachLightYears}).
     * A configured length was the wrong quantity — it made one number stand for a red dwarf and a blue
     * giant, whose ranges differ by eighty times, and it moved whenever the star spacing or the cell
     * edge was retuned. What remains configurable is the aperture, the width of the patch, and the
     * cost in time; those are balance, never contract.</p>
     */
    public static final class Tuning {

        private final double limitMagnitude;
        private final double reachLightYears;
        private final double halfAngleRadians;
        private final int maxCells;
        private final int baseTicks;
        private final int cellsPerStep;
        private final long strideCells;

        /**
         * @param archetypes the star types the sky can produce — the reach is DERIVED against the
         *                   brightest of them here and is never a field anyone can set, so an
         *                   instrument's horizon cannot disagree with its aperture
         */
        public Tuning(double limitMagnitude, Iterable<GalaxyGenConfig.StarType> archetypes,
                      double halfAngleRadians, int maxCells, int baseTicks, int cellsPerStep,
                      long strideCells) {
            this.limitMagnitude = limitMagnitude;
            this.reachLightYears = StellarMagnitude.instrumentReachLightYears(archetypes, limitMagnitude);
            this.halfAngleRadians = Math.max(0d, halfAngleRadians);
            this.maxCells = Math.max(1, maxCells);
            this.baseTicks = Math.max(0, baseTicks);
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
                    config.telescopeLimitingMagnitude,
                    // The STOCK sky when the installed generator describes none of its own. A
                    // generator with no star table has not said the sky is empty - it has said it
                    // does not place stars, and an authored pack's suns are real light an instrument
                    // has to be able to reach. Falling back to the reference table is the same move
                    // as reading an unstated bulk as one Earth; taking the empty list literally gave
                    // the instrument a reach of zero and collapsed every pointing to a single shell.
                    UniverseRegistry.getGenerator().tuning()
                            .map(c -> c.starTypes)
                            .filter(types -> !types.isEmpty())
                            .orElse(GalaxyGenConfig.defaults().starTypes),
                    Math.toRadians(config.telescopeConeHalfAngleDegrees),
                    config.telescopeScanMaxCells,
                    config.telescopeScanBaseTicks,
                    config.telescopeScanCellsPerStep,
                    UniverseRegistry.getGenerator().minSpacingCells());
        }

        /** How faint a star this instrument can still register. Magnitudes: larger is fainter. */
        public double limitMagnitude() {
            return limitMagnitude;
        }

        /** How wide a patch of sky one pointing covers, from its axis to its edge. */
        public double halfAngleRadians() {
            return halfAngleRadians;
        }

        /**
         * The instrument's horizon, as a length — DERIVED from the limiting magnitude against the
         * brightest archetype the active generator can produce, and zero for a generator that
         * produces no stars at all.
         */
        public double maxRangeLightYears() {
            return reachLightYears;
        }

        /** How far apart the cells a pointing looks at stand — one star's territory. */
        public long strideCells() {
            return strideCells;
        }

        /** The horizon as a number of steps, which is what an operator aims in. At least one. */
        public int maxRangeSteps() {
            long steps = UniverseRegistry.getGenerator().laws()
                    .cellsForLightYears(maxRangeLightYears()) / strideCells;
            return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, steps));
        }

        public int baseTicks() {
            return baseTicks;
        }

        public int cellsPerStep() {
            return cellsPerStep;
        }

        /** The hard ceiling on how many looks one survey may hold. */
        public int maxCells() {
            return maxCells;
        }

        /**
         * The deepest pointing of {@code steps} that still fits under {@link #maxCells()} — SHORTENED
         * rather than refused, exactly as a box survey's width used to be narrowed.
         *
         * <p>The ceiling wins over the depth. A survey may be long, but it may not be unbounded, and a
         * pointing that will not fit is one an operator gets less of rather than none of: he sees the
         * near sky and can point again. Halving is used rather than decrementing because the look
         * count grows as the cube of the depth — walking down one step at a time from a magnitude
         * limit that reaches a hundred thousand steps would be the same unbounded work in a
         * different place.</p>
         */
        public ConeWalk fit(GalacticCoord origin, int dirX, int dirY, int dirZ, int steps) {
            int depth = Math.max(1, steps);
            IllegalArgumentException refused = null;
            while (depth >= 1) {
                try {
                    ConeWalk aimed = ConeWalk.aimed(origin, dirX, dirY, dirZ, halfAngleRadians,
                            depth * strideCells, strideCells);
                    if (aimed.totalLooks() <= maxCells) {
                        return aimed;
                    }
                } catch (IllegalArgumentException tooLarge) {
                    refused = tooLarge; // too many looks to even count: the same answer, sooner
                }
                if (depth == 1) {
                    break;
                }
                depth = Math.max(1, depth / 2);
            }
            // A single shell that still will not fit means the aperture is wider than the ceiling can
            // ever afford, which is a configuration nobody can survey with — and the operator has to
            // be told which of the two numbers to change.
            throw new IllegalArgumentException("a pointing of half-angle "
                    + String.format("%.3f", Math.toDegrees(halfAngleRadians)) + " degrees holds more"
                    + " than " + maxCells + " looks in its very first shell."
                    + " Narrow telescopeConeHalfAngleDegrees or raise telescopeScanMaxCells."
                    + (refused == null ? "" : " (" + refused.getMessage() + ")"));
        }
    }
}
