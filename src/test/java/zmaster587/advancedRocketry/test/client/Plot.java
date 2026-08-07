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
