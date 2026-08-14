package zmaster587.advancedRocketry.universe;

/**
 * One star cluster: a region of a galaxy where the star lattice is FINER by an integer factor.
 *
 * <p>The stratified lattice one level up reads correctly as randomness but produces no GROUPS, and
 * groups are what a real sky has: a lattice caps density at about three times the mean, while an open
 * cluster runs tens of times the field and a nucleus thousands. A cluster is the same seat one level
 * down, and the whole mechanism is one word — <b>commensurate</b>.</p>
 *
 * <h3>Why commensurate, and why that makes it cheap</h3>
 * <p>Each coarse super-cell inside a cluster is divided into {@code k³} sub-cells, so the fine lattice
 * tiles exactly the coarse cells it replaces. There is no partial cell at the edge, no seam, and
 * nothing to re-prove per ring — which is what a graded spacing would have cost. And because
 * <b>membership is decided per COARSE cell</b>, a cell is wholly in a cluster or wholly out of it, so
 * "which lattice does this coordinate live on" stays an O(1) question with one answer.</p>
 *
 * <p>The sub-cell bounds are computed by proportioning rather than by dividing: sub-cell {@code i}
 * runs from {@code floor(i·s/k)} to {@code floor((i+1)·s/k)}. That tiles a coarse cell of ANY edge
 * exactly, including one that {@code k} does not divide — where a plain {@code s/k} would leave a
 * remainder and a seam.</p>
 *
 * <h3>The separation floor becomes a property of a LATTICE LEVEL</h3>
 * <p>Inside a globular's core stars really are closer together than a wide binary, and encounters
 * really are frequent. The 10 000 AU floor is derived from whatever spacing is in force locally, so a
 * clustered region gets a proportionally smaller one — and a system there loses outer bodies by the
 * same rule that has always applied. A floor applied outside its domain of definition is precisely the
 * mistake this design keeps removing elsewhere.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class StarCluster {

    private final GalaxyGenConfig.ClusterType type;
    private final long centreSuperX;
    private final long centreSuperY;
    private final long centreSuperZ;
    private final long radiusSuperCells;

    public StarCluster(GalaxyGenConfig.ClusterType type, long centreSuperX, long centreSuperY,
                       long centreSuperZ, long radiusSuperCells) {
        this.type = type;
        this.centreSuperX = centreSuperX;
        this.centreSuperY = centreSuperY;
        this.centreSuperZ = centreSuperZ;
        this.radiusSuperCells = Math.max(1L, radiusSuperCells);
    }

    public GalaxyGenConfig.ClusterType type() {
        return type;
    }

    /** How many parts each coarse super-cell inside this cluster is divided into, per axis. */
    public int subdivision() {
        return type.subdivision;
    }

    /** Its radius, in COARSE super-cells — the unit its boundary is snapped to. */
    public long radiusSuperCells() {
        return radiusSuperCells;
    }

    public long centreSuperX() {
        return centreSuperX;
    }

    public long centreSuperY() {
        return centreSuperY;
    }

    public long centreSuperZ() {
        return centreSuperZ;
    }

    /**
     * Whether this coarse super-cell is inside the cluster.
     *
     * <p>Rounded: the test is on the super-cell INDEX, so the boundary lands on coarse cell faces
     * while the shape stays a ball rather than a box. That is what keeps the fine lattice exactly
     * tiling and the answer per-cell.</p>
     */
    public boolean containsSuperCell(long supX, long supY, long supZ) {
        double dx = supX - centreSuperX;
        double dy = supY - centreSuperY;
        double dz = supZ - centreSuperZ;
        return dx * dx + dy * dy + dz * dz <= (double) radiusSuperCells * radiusSuperCells;
    }

    /**
     * The lower bound of sub-cell {@code index} inside a coarse cell of edge {@code coarseEdge},
     * as an offset from that cell's own low corner.
     *
     * <p>Proportioned, never divided: this tiles a coarse cell of any edge exactly, where
     * {@code index · (coarseEdge / k)} would leave a remainder at the top of every cell.</p>
     */
    public long subCellLow(long index, long coarseEdge) {
        return Math.floorDiv(index * coarseEdge, (long) subdivision());
    }

    /** The edge of sub-cell {@code index} — within one of the neighbouring sub-cells' edge. */
    public long subCellEdge(long index, long coarseEdge) {
        return Math.max(1L, subCellLow(index + 1L, coarseEdge) - subCellLow(index, coarseEdge));
    }

    /** Which sub-cell an offset inside a coarse cell falls in, on one axis. */
    public long subCellIndex(long offsetInCoarse, long coarseEdge) {
        long k = subdivision();
        long index = Math.floorDiv(offsetInCoarse * k, Math.max(1L, coarseEdge));
        return Math.min(k - 1L, Math.max(0L, index));
    }

    @Override
    public String toString() {
        return "StarCluster[" + type.name + " k=" + subdivision() + " r=" + radiusSuperCells
                + " super-cells @ " + centreSuperX + "," + centreSuperY + "," + centreSuperZ + "]";
    }
}
