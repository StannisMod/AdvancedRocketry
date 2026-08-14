package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * One galaxy: a seated object with a centre, a type, a size, an orientation and a density profile.
 *
 * <p>It is a VALUE, produced on demand from {@code (seed, galaxy cell)} and stored nowhere — exactly
 * as a {@link StarSystem} is. Nothing here is persisted and no coordinate carries a galaxy index; the
 * index is {@code sector / galaxySpacing}, a derived grouping of the sector space that already
 * exists (see {@link GalaxyField#galaxyIndex}).</p>
 *
 * <h3>What a galaxy is FOR</h3>
 * <ul>
 *   <li><b>It draws the star field.</b> A super-cell hosts a system with a probability scaled by
 *       {@link #densityAt} at that point, so the disc, the bulge and the arms place the stars
 *       instead of an independent per-cell coin toss.</li>
 *   <li><b>It is the frame a bound thing rides.</b> Inside the declared {@link #radiusLy radius} a
 *       position co-rotates at {@link #angularSpeedAt}; outside it, it does not.</li>
 * </ul>
 *
 * <p><b>The inside/outside test is the DECLARED RADIUS, never a level of the profile.</b> A profile is
 * continuous and has no boundary, so a frame decided by "is the density high enough here" would flip
 * back and forth for anything hovering near the threshold. The radius is a sphere: the disc and its
 * halo are both inside it, which is right — a halo is bound to its galaxy too.</p>
 *
 * <h3>Rotation</h3>
 * <p>{@code θ(t) = θ₀ + ω(r)·t}, analytic in {@code t} and never integrated, so nothing accumulates
 * drift — the argument {@link BodyEphemeris} already makes one level down. The curve is
 * {@code v(r) = v∞ · r / √(r² + r_core²)}: solid-body near the centre, flat outside the core, and the
 * type's {@link GalaxyGenConfig.GalaxyType#coreRadiusFraction} says where the turnover is, so a dwarf
 * rotates almost rigidly (little shear) and a massive spiral shears strongly. Hence
 * {@code ω(r) = v∞ / √(r² + r_core²)}, which is finite at the centre rather than singular.</p>
 *
 * <p>The rate is slow enough to be invisible inside one save, which is the ratified position: the
 * mechanic exists even when slow, and the speed is tuning.</p>
 */
public final class Galaxy {

    /** How far the exponential disc reaches, as a fraction of the radius. */
    private static final double DISC_SCALE_FRACTION = 1d / 3d;
    /** How far the central bulge reaches, as a fraction of the radius. */
    private static final double BULGE_SCALE_FRACTION = 1d / 12d;
    /** How strongly the arms modulate the disc: the density between arms against the density on one. */
    private static final double ARM_CONTRAST = 0.6d;
    /** The centre is a singular point of the arm winding; inside this fraction the bulge speaks. */
    private static final double ARM_INNER_FRACTION = 1e-3d;

    private final long cellX;
    private final long cellY;
    private final long cellZ;
    private final GalacticCoord centre;
    private final GalaxyGenConfig.GalaxyType type;
    private final double radiusLy;
    private final double armPitch;
    private final double armPhase;

    // The galaxy frame, precomputed: (u, v) span its plane and w is its pole. A position's cylindrical
    // (r, theta, z) is read off these, so the profile below is written in the galaxy's own terms and
    // the orientation is applied exactly once.
    private final double ux;
    private final double uy;
    private final double uz;
    private final double vx;
    private final double vy;
    private final double vz;
    private final double wx;
    private final double wy;
    private final double wz;

    /**
     * @param cellX      the galaxy-lattice index this galaxy is seated in
     * @param centre     its centre, as a cell name
     * @param radiusLy   its declared radius in light years — drawn inside {@code type}'s band
     * @param tilt       the angle its pole makes with the static +Y axis, in radians
     * @param node       the direction that pole leans in, in radians about +Y
     * @param armPitch   the arms' pitch angle in radians (ignored when the type has no arms)
     * @param armPhase   where arm zero starts, in radians
     */
    public Galaxy(long cellX, long cellY, long cellZ, GalacticCoord centre,
                  GalaxyGenConfig.GalaxyType type, double radiusLy, double tilt, double node,
                  double armPitch, double armPhase) {
        this.cellX = cellX;
        this.cellY = cellY;
        this.cellZ = cellZ;
        this.centre = centre;
        this.type = type;
        this.radiusLy = Math.max(1d, radiusLy);
        this.armPitch = armPitch;
        this.armPhase = armPhase;

        double st = Math.sin(tilt);
        double ct = Math.cos(tilt);
        double sn = Math.sin(node);
        double cn = Math.cos(node);
        // w = the pole; u, v = an orthonormal pair spanning the plane it is normal to.
        this.wx = st * cn;
        this.wy = ct;
        this.wz = st * sn;
        this.ux = ct * cn;
        this.uy = -st;
        this.uz = ct * sn;
        this.vx = -sn;
        this.vy = 0d;
        this.vz = cn;
    }

    public long cellX() {
        return cellX;
    }

    public long cellY() {
        return cellY;
    }

    public long cellZ() {
        return cellZ;
    }

    /** Where this galaxy's centre stands, as a cell name. */
    public GalacticCoord centre() {
        return centre;
    }

    public GalaxyGenConfig.GalaxyType type() {
        return type;
    }

    /** The declared radius in light years — the boundary, and the only boundary. */
    public double radiusLy() {
        return radiusLy;
    }

    /** The arms' pitch angle in radians; meaningless when the type has no arms. */
    public double armPitch() {
        return armPitch;
    }

    /** Where arm zero starts, in radians. */
    public double armPhase() {
        return armPhase;
    }

    /** This galaxy's designation — procedurally-generated galaxy, named for the cell it is seated in. */
    public String name() {
        return "PGG-" + cellX + "." + cellY + "." + cellZ;
    }

    // ─── Membership and profile ────────────────────────────────────────────────

    /** Whether a point {@code (dx, dy, dz)} light years from the centre is inside this galaxy. */
    public boolean contains(double dxLy, double dyLy, double dzLy) {
        return dxLy * dxLy + dyLy * dyLy + dzLy * dzLy <= radiusLy * radiusLy;
    }

    /** Whether a cell named by this sector triple is inside this galaxy. */
    public boolean containsSector(long sectorX, long sectorY, long sectorZ) {
        double dx = offsetLy(sectorX, centre.sectorX());
        double dy = offsetLy(sectorY, centre.sectorY());
        double dz = offsetLy(sectorZ, centre.sectorZ());
        return contains(dx, dy, dz);
    }

    /**
     * How dense this galaxy is at a point {@code (dx, dy, dz)} light years from its centre, as a
     * fraction of its densest point: {@code 0} outside the radius, {@code 1} at the nucleus.
     *
     * <p>This is the ONE function that decides both where stars are placed and what shape a galaxy
     * reads as. A disc is an exponential disc times an exponential in height, modulated by arms and
     * added to a bulge; a spheroid is one isotropic exponential with the type's flattening applied to
     * its pole.</p>
     */
    public double densityAt(double dxLy, double dyLy, double dzLy) {
        if (!contains(dxLy, dyLy, dzLy)) {
            return 0d;
        }
        // Into the galaxy's own frame: the plane it spans, and the height above it.
        double localX = dxLy * ux + dyLy * uy + dzLy * uz;
        double localY = dxLy * vx + dyLy * vy + dzLy * vz;
        double z = dxLy * wx + dyLy * wy + dzLy * wz;
        double r = Math.hypot(localX, localY);

        if (type.profile == GalaxyGenConfig.GalaxyProfile.SPHEROID) {
            // Round, with the type's flattening squashing the pole. No plane, so no arms and no bulge
            // term — the whole thing IS the bulge.
            double scaled = Math.hypot(r, z / Math.max(1e-6d, type.scaleHeightRatio));
            return clamp01(Math.exp(-scaled / (radiusLy * DISC_SCALE_FRACTION)));
        }

        double scaleHeight = Math.max(1e-6d, radiusLy * type.scaleHeightRatio);
        double disc = Math.exp(-r / (radiusLy * DISC_SCALE_FRACTION))
                * Math.exp(-Math.abs(z) / scaleHeight);
        disc *= armFactor(r, Math.atan2(localY, localX));
        double bulge = Math.exp(-Math.hypot(r, z) / (radiusLy * BULGE_SCALE_FRACTION));
        return clamp01(disc + bulge);
    }

    /** The profile read at a cell name — the form the generator asks in. */
    public double densityAtSector(long sectorX, long sectorY, long sectorZ) {
        return densityAt(offsetLy(sectorX, centre.sectorX()),
                offsetLy(sectorY, centre.sectorY()),
                offsetLy(sectorZ, centre.sectorZ()));
    }

    /**
     * The arms' contribution as a multiplier in {@code (0, 1]}, normalised so a point ON an arm scores
     * 1 and the disc between them is dimmer. A type with no arms scores 1 everywhere, so a smooth disc
     * is the same code path with an empty term rather than a branch somewhere else.
     */
    private double armFactor(double r, double theta) {
        if (type.armCount <= 0) {
            return 1d;
        }
        double tan = Math.tan(armPitch);
        if (!(Math.abs(tan) > 1e-9d)) {
            return 1d; // a degenerate pitch would wind the arms into a circle; leave the disc smooth
        }
        double rArm = Math.max(r, radiusLy * ARM_INNER_FRACTION);
        double wind = Math.log(rArm / radiusLy) / tan;
        double phase = type.armCount * (theta - armPhase - wind);
        return (1d + ARM_CONTRAST * Math.cos(phase)) / (1d + ARM_CONTRAST);
    }

    // ─── Rotation ──────────────────────────────────────────────────────────────

    /**
     * The angular speed at galaxy-local radius {@code rLy}, in radians per tick — the SHEAR that makes
     * a galaxy a place that moves rather than a fixed backdrop.
     *
     * <p>Signed: the sign is the galaxy's spin direction about its own pole, and it is the same
     * everywhere in one galaxy. Positive always here; the pole's direction is what distinguishes two
     * galaxies spinning opposite ways, and that is carried by the orientation.</p>
     */
    public double angularSpeedAt(double rLy) {
        double core = radiusLy * type.coreRadiusFraction;
        double speed = UniverseScale.lightYearsPerTick(type.rotationSpeedKmS);
        return speed / Math.hypot(Math.max(0d, rLy), core);
    }

    /**
     * Where something that started at {@code theta0} and sits at radius {@code rLy} has got to by tick
     * {@code tick}. Evaluated, never integrated.
     */
    public double thetaAt(double theta0, double rLy, long tick) {
        return theta0 + angularSpeedAt(rLy) * (double) tick;
    }

    /** How long one turn at radius {@code rLy} takes, in ticks. Diagnostics and tests read this. */
    public double rotationPeriodTicks(double rLy) {
        double omega = angularSpeedAt(rLy);
        return omega > 0d ? 2d * Math.PI / omega : Double.POSITIVE_INFINITY;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** A sector delta as a length in light years. Exact: the delta is bounded by one galaxy cell. */
    private static double offsetLy(long sector, long centreSector) {
        return UniverseScale.lightYearsForCells((double) (sector - centreSector));
    }

    private static double clamp01(double v) {
        if (!(v > 0d)) {
            return 0d;
        }
        return v > 1d ? 1d : v;
    }

    @Override
    public String toString() {
        return "Galaxy[" + name() + " " + type.name + " r=" + (long) radiusLy + "ly centre="
                + centre.cellKey() + "]";
    }
}
