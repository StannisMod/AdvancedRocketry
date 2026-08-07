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

    /** Far from every fixture range the existing suites use (200-350, 2100-2140, 6620/6820). */
    private static final int ORIGIN_X = 4000;
    private static final int ORIGIN_Z = 4000;

    /** Open air above generated terrain, so a plot starts empty without clearing anything. */
    public static final int DEFAULT_Y = 150;

    private final int index;
    private final String owner;
    public final int dim;
    /** North-west corner of the plot. */
    public final int originX;
    public final int originZ;

    Plot(int index, String owner, int dim) {
        this.index = index;
        this.owner = owner;
        this.dim = dim;
        this.originX = ORIGIN_X + index * SIZE;
        this.originZ = ORIGIN_Z;
    }

    /** Absolute X of a point {@code dx} blocks into the plot. */
    public int x(int dx) {
        if (dx < 0 || dx >= SIZE) {
            throw new IllegalArgumentException("dx=" + dx + " leaves plot " + this
                    + " — a scenario that needs more room needs a bigger SIZE, not a neighbour's plot");
        }
        return originX + dx;
    }

    /** Absolute Z of a point {@code dz} blocks into the plot. */
    public int z(int dz) {
        if (dz < 0 || dz >= SIZE) {
            throw new IllegalArgumentException("dz=" + dz + " leaves plot " + this
                    + " — a scenario that needs more room needs a bigger SIZE, not a neighbour's plot");
        }
        return originZ + dz;
    }

    public int centerX() {
        return originX + SIZE / 2;
    }

    public int centerZ() {
        return originZ + SIZE / 2;
    }

    /**
     * Is this world position inside the plot? Use it to filter a GLOBAL probe answer down to this
     * scenario's own objects — the answer to "is there a rocket in dim 0" is not the answer to "did
     * MY arrangement build one".
     */
    public boolean contains(double worldX, double worldZ) {
        return worldX >= originX && worldX < originX + SIZE
                && worldZ >= originZ && worldZ < originZ + SIZE;
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
                + " x=" + originX + ".." + (originX + SIZE - 1)
                + " z=" + originZ + ".." + (originZ + SIZE - 1) + "]";
    }
}
