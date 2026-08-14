package zmaster587.advancedRocketry.test.client;

/**
 * A private patch of world handed to exactly one scenario of a shared-harness class.
 *
 * <p>When one client harness carries several scenarios they also share one WORLD, and that
 * multiplies a specific failure: a "find the X" query answering with a DIFFERENT scenario's object,
 * so the assertion passes on scaffolding the test never built. Three queries in the client suite are
 * already global and would do exactly this — {@code artest rocket list 0} in
 * {@code RocketBuilderGuiE2ETest} and {@code FreeFlightModeE2ETest}, {@code artest station list} in
 * {@code SpaceDimGuardE2ETest}.</p>
 *
 * <p>The defence is spatial and it is deliberately dumb: <b>one plot per scenario, never
 * recycled</b>. Nothing has to be cleaned up afterwards, because nothing else is ever going to look
 * here — which is cheaper and far more reliable than a teardown that has to be remembered. A
 * scenario that must ask a global question narrows it with {@link #contains} instead of trusting the
 * answer.</p>
 *
 * <p>Plots only need to be unique WITHIN a class: every test class boots its own harness and
 * therefore its own world.</p>
 */
public final class Plot {

    /** Edge of a plot, in blocks. Wide enough for a rocket fixture plus its pad. */
    public static final int SIZE = 64;

    /** Open air above generated terrain, so a plot starts empty without clearing anything. */
    public static final int DEFAULT_Y = 150;

    /**
     * The ground a fixture that needs REAL TERRAIN stands on, measured rather than chosen.
     *
     * <p>A fixture built at a fixed Y needs the surface to be AT that Y, flat, dry and unobstructed
     * across its whole footprint, and the pinned seed grants that almost nowhere. Surveyed
     * 2026-08-14 with {@code artest worldgen survey} over each candidate's real 16x16 footprint:
     * <b>61 chunk-centre candidates across a 384x384 region, plus a 110-plot grid over the
     * neighbourhood, yielded exactly TWO clean plots</b> — this one and {@link #CLEAN_GROUND_Z2}.
     * Both read {@code relief=0, modeTopY=64, modeTopShare=1.0, liquidColumns=0,
     * solidObstructedColumns=0}, all grass, plains.</p>
     *
     * <p>What the same survey said about where these fixtures USED to stand is why this exists:
     * 7200/7220 sit under a mountain whose surface is y=80..99, so a fixture at y=64 was buried
     * sixteen to thirty-five blocks inside rock; 7420 had 49 water columns; 3000 sat under a forest
     * canopy on ground at y=71..79. None were marginal landscapes a luckier seed would have saved.</p>
     *
     * <p>A flat harness world would make every coordinate equally good and was tried on 2026-08-14;
     * it cost more heap, more wall clock and three unexplained reds, so the survey stands.
     * <b>Do not move these numbers without re-running the survey</b> —
     * {@code FixtureGroundOnPinnedSeedTest} asserts every ground fixture's own surface.</p>
     */
    public static final int CLEAN_GROUND_X = 7096;
    /** @see #CLEAN_GROUND_X */
    public static final int CLEAN_GROUND_Y = 64;
    /** @see #CLEAN_GROUND_X */
    public static final int CLEAN_GROUND_Z = 7224;
    /**
     * The second and last clean plot, for a class whose scenarios share one world. Sixteen blocks
     * from {@link #CLEAN_GROUND_Z}, which is as far apart as the pinned seed allows two clean plots
     * to be — the survey found no third within 160 blocks in any direction.
     */
    public static final int CLEAN_GROUND_Z2 = 7240;

    /** Edge of the surveyed-clean footprint around a clean-ground base, as an offset. */
    public static final int CLEAN_GROUND_FOOT_MIN = -4;
    /** @see #CLEAN_GROUND_FOOT_MIN */
    public static final int CLEAN_GROUND_FOOT_MAX = 11;

    /**
     * Where a class's plots live and how far apart they sit.
     *
     * <p>This is a per-class choice on purpose, and the reason is terrain. A plot at ground level
     * inherits whatever the fixed world seed generated there — a hill, an ocean, a forest — and this
     * repo has lost runs to all three. <b>A class migrating an existing test should keep the
     * coordinates that test already proved</b> rather than inherit {@link #DEFAULT}: those numbers
     * are backed by however many green runs the test has behind it, and a fresh lane is not.</p>
     */
    public static final class Lane {
        public final int originX;
        public final int originZ;
        /** Distance between successive plot origins. Must be at least {@link #plotSize}. */
        public final int stride;
        /**
         * Edge of every plot on this lane. Widen it for a class whose fixtures are bigger than
         * {@link Plot#SIZE} — a pair of linked machines standing 60 blocks apart, a multiblock plus
         * its clearance. Widening the plot is the honest move there; reaching past the plot's edge
         * into a neighbour's is what the allocator exists to prevent.
         */
        public final int plotSize;

        public Lane(int originX, int originZ, int stride) {
            this(originX, originZ, stride, SIZE);
        }

        public Lane(int originX, int originZ, int stride, int plotSize) {
            if (stride < plotSize) {
                throw new IllegalArgumentException("stride " + stride + " < plot size " + plotSize
                        + " — plots would overlap, which is the one thing they exist to prevent");
            }
            this.originX = originX;
            this.originZ = originZ;
            this.stride = stride;
            this.plotSize = plotSize;
        }

        /**
         * Far from every fixture range the existing suites use (200-350, 2100-2140, 3000-5100,
         * 6620/6820), and green 10/10 for the seal-detector pilot at {@link Plot#DEFAULT_Y}, which
         * is air and therefore terrain-independent. <b>Do not move it</b> — that green is what makes
         * it a default rather than a guess. A scenario that needs GROUND is on its own terrain and
         * should declare its own lane.
         */
        public static final Lane DEFAULT = new Lane(4000, 4000, SIZE);
    }

    private final int index;
    private final String owner;
    public final int dim;
    /** North-west corner of the plot. */
    public final int originX;
    public final int originZ;
    /** Edge of this plot, from its lane. Usually {@link #SIZE}. */
    public final int size;

    Plot(int index, String owner, int dim, Lane lane) {
        this.index = index;
        this.owner = owner;
        this.dim = dim;
        this.originX = lane.originX + index * lane.stride;
        this.originZ = lane.originZ;
        this.size = lane.plotSize;
    }

    /** Absolute X of a point {@code dx} blocks into the plot. */
    public int x(int dx) {
        if (dx < 0 || dx >= size) {
            throw new IllegalArgumentException("dx=" + dx + " leaves plot " + this
                    + " — a scenario that needs more room declares a wider lane, not a neighbour's plot");
        }
        return originX + dx;
    }

    /** Absolute Z of a point {@code dz} blocks into the plot. */
    public int z(int dz) {
        if (dz < 0 || dz >= size) {
            throw new IllegalArgumentException("dz=" + dz + " leaves plot " + this
                    + " — a scenario that needs more room declares a wider lane, not a neighbour's plot");
        }
        return originZ + dz;
    }

    public int centerX() {
        return originX + size / 2;
    }

    public int centerZ() {
        return originZ + size / 2;
    }

    /**
     * Is this world position inside the plot? Use it to filter a GLOBAL probe answer down to this
     * scenario's own objects — the answer to "is there a rocket in dim 0" is not the answer to "did
     * MY arrangement build one".
     */
    public boolean contains(double worldX, double worldZ) {
        return worldX >= originX && worldX < originX + size
                && worldZ >= originZ && worldZ < originZ + size;
    }

    public int index() {
        return index;
    }

    public String owner() {
        return owner;
    }

    @Override
    public String toString() {
        return "Plot#" + index + "[" + owner + " dim=" + dim
                + " x=" + originX + ".." + (originX + size - 1)
                + " z=" + originZ + ".." + (originZ + size - 1) + "]";
    }
}
