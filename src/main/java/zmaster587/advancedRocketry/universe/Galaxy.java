package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * One galaxy: a seated object with a centre, a type, a size, an orientation and a density profile.
 *
 * <p>It is a VALUE, produced on demand from {@code (seed, galaxy cell)} and stored nowhere — exactly
 * as a {@link PlanetarySystem} is. Nothing here is persisted and no coordinate carries a galaxy index; the
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
    /**
     * What the profile is divided by, so that a point ON AN ARM at the sun-like galactic radius scores
     * 1. It is the disc term there, and it is scale-free — the exponentials are all in units of the
     * radius, so this one number normalises a galaxy of any size.
     */
    private static final double REFERENCE_LEVEL =
            Math.exp(-UniverseScale.HOME_GALAXY_ORIGIN_FRACTION / DISC_SCALE_FRACTION);

    /**
     * What {@link #densityAt} reads at a galaxy's own EDGE, in its plane. Derived from the profile
     * rather than written down, and scale-free for the same reason every other length here is: the
     * exponentials are in units of the radius, so this is one number for a galaxy of any size and of
     * either profile.
     *
     * <p>It is the anchor the {@linkplain #ejectaDensityAt ejecta halo} hangs from, which is what makes
     * the void's population a statement about the galaxies that threw it out rather than a second
     * field with its own normalisation.</p>
     */
    public static final double EDGE_LEVEL = Math.exp(-1d / DISC_SCALE_FRACTION) / REFERENCE_LEVEL;

    /**
     * How steeply a galaxy's ejecta thins outside it, as a power of the distance in radii.
     *
     * <p>Three, because that is what a population thrown out over a Hubble time and spread through a
     * growing volume comes to — the same slope the outer parts of a real stellar halo and the
     * intracluster light are measured at. It is not the disc's exponential: an exponential in units of
     * the radius is dead within a few of them, and the void is twenty-five across.</p>
     */
    private static final double EJECTA_FALLOFF = 3d;

    private final long cellX;
    private final long cellY;
    private final long cellZ;
    private final int satelliteIndex;
    private final GalacticCoord centre;
    private final LightYearVector seat;
    private final LightYearVector peculiarVelocity;
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
     * @param satelliteIndex {@code 0} for the cube's PRIMARY galaxy, {@code 1..n} for a satellite of
     *                   it. A cube holds one primary and its retinue, so the lattice index alone no
     *                   longer identifies a galaxy — this is what distinguishes them, in the name and
     *                   in every draw made per galaxy rather than per cell
     * @param centre     its centre, as a cell name
     * @param radiusLy   its declared radius in light years — drawn inside {@code type}'s band
     * @param tilt       the angle its pole makes with the static +Y axis, in radians
     * @param node       the direction that pole leans in, in radians about +Y
     * @param armPitch   the arms' pitch angle in radians (ignored when the type has no arms)
     * @param armPhase   where arm zero starts, in radians
     * @param peculiarVelocity its comoving velocity in light years per tick — its own motion through
     *                   the expanding universe, on top of the expansion. A satellite carries its
     *                   PRIMARY's, so a group travels together
     */
    public Galaxy(long cellX, long cellY, long cellZ, int satelliteIndex, GalacticCoord centre,
                  GalaxyGenConfig.GalaxyType type, double radiusLy, double tilt, double node,
                  double armPitch, double armPhase, LightYearVector peculiarVelocity) {
        this.cellX = cellX;
        this.cellY = cellY;
        this.cellZ = cellZ;
        this.satelliteIndex = Math.max(0, satelliteIndex);
        this.centre = centre;
        this.seat = LightYearVector.ofCell(centre);
        this.peculiarVelocity = (peculiarVelocity == null) ? LightYearVector.ZERO : peculiarVelocity;
        this.type = type;
        this.radiusLy = Math.max(1d, radiusLy);
        this.armPitch = armPitch;
        this.armPhase = armPhase;

        double[] basis = basisOf(tilt, node);
        this.ux = basis[0];
        this.uy = basis[1];
        this.uz = basis[2];
        this.vx = basis[3];
        this.vy = basis[4];
        this.vz = basis[5];
        this.wx = basis[6];
        this.wy = basis[7];
        this.wz = basis[8];
    }

    /**
     * The orthonormal frame of a galaxy with this orientation: {@code u} and {@code v} span its plane,
     * {@code w} is its pole. Laid out as {@code [ux,uy,uz, vx,vy,vz, wx,wy,wz]}.
     */
    private static double[] basisOf(double tilt, double node) {
        double st = Math.sin(tilt);
        double ct = Math.cos(tilt);
        double sn = Math.sin(node);
        double cn = Math.cos(node);
        return new double[] {
            ct * cn, -st, ct * sn,
            -sn, 0d, cn,
            st * cn, ct, st * sn,
        };
    }

    /**
     * A unit vector lying IN the plane of a galaxy with this orientation, at in-plane angle
     * {@code angle}. What a caller uses to put something at a stated galactic radius in the DISC,
     * rather than somewhere in the halo above it.
     */
    public static LightYearVector planeDirection(double tilt, double node, double angle) {
        double[] b = basisOf(tilt, node);
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return LightYearVector.of(c * b[0] + s * b[3], c * b[1] + s * b[4], c * b[2] + s * b[5]);
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

    /**
     * Where this galaxy's centre stands, as a cell NAME — the seat it was drawn at, at {@code t = 0}.
     * A name is not a place: for where the centre actually is at a tick, see {@link #centreAt}.
     */
    public GalacticCoord centre() {
        return centre;
    }

    /** Its comoving velocity, in light years per tick — its own motion, on top of the expansion. */
    public LightYearVector peculiarVelocity() {
        return peculiarVelocity;
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

    /**
     * {@code 0} for the cube's primary galaxy, {@code 1..n} for one of its satellites. A cube holds a
     * primary AND its retinue, so this is the second half of a galaxy's identity.
     */
    public int satelliteIndex() {
        return satelliteIndex;
    }

    /** Whether this galaxy is a satellite of the primary seated in the same cube. */
    public boolean isSatellite() {
        return satelliteIndex > 0;
    }

    /**
     * This galaxy's designation — procedurally-generated galaxy, named for the cell it is seated in,
     * and for its place in that cube's retinue when it is not the primary.
     *
     * <p>The suffix is not decoration: a satellite is a destination with an address, and two galaxies in
     * one cube sharing a name would be two places a player could neither tell apart nor write down.</p>
     */
    public String name() {
        String cell = "PGG-" + cellX + "." + cellY + "." + cellZ;
        return isSatellite() ? cell + "-S" + satelliteIndex : cell;
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
     * How dense this galaxy is at a point {@code (dx, dy, dz)} light years from its centre, relative to
     * a SUN-LIKE spot in its disc: {@code 0} outside the radius, about {@code 1} out where the home
     * galaxy puts the origin, and several times that in the nucleus.
     *
     * <p><b>Normalised at the sun-like radius, not at the nucleus, and that choice is load-bearing.</b>
     * The mean star separation is the primary quantity of this whole layer and it is REAL — it is the
     * separation in the solar neighbourhood. So the configured density has to mean "how full a sky
     * like ours is"; normalising at the nucleus instead would have made every configured density a
     * statement about the galactic core, and left the sky a player actually stands under five times
     * too empty. The centre goes above 1 and is clamped where the probability is used, which is the
     * honest place for a saturation.</p>
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
            return atLeastZero(Math.exp(-scaled / (radiusLy * DISC_SCALE_FRACTION)) / REFERENCE_LEVEL);
        }

        double scaleHeight = Math.max(1e-6d, radiusLy * type.scaleHeightRatio);
        double disc = Math.exp(-r / (radiusLy * DISC_SCALE_FRACTION))
                * Math.exp(-Math.abs(z) / scaleHeight);
        disc *= armFactor(r, Math.atan2(localY, localX));
        double bulge = Math.exp(-Math.hypot(r, z) / (radiusLy * BULGE_SCALE_FRACTION));
        return atLeastZero((disc + bulge) / REFERENCE_LEVEL);
    }

    /** The profile read at a cell name — the form the generator asks in. */
    public double densityAtSector(long sectorX, long sectorY, long sectorZ) {
        return densityAt(offsetLy(sectorX, centre.sectorX()),
                offsetLy(sectorY, centre.sectorY()),
                offsetLy(sectorZ, centre.sectorZ()));
    }

    /**
     * How dense this galaxy's UNBOUND material is at a point {@code (dx, dy, dz)} light years from its
     * centre — the planets and stars it has thrown out — on the same scale as {@link #densityAt}.
     *
     * <p>Zero INSIDE the radius, and that is a division of labour rather than a claim that a galaxy
     * ejects nothing into itself: inside its own sphere the bound profile is what says how much
     * material is at a point, and adding a second term there would double-count the same stars.</p>
     *
     * <p>Outside, it falls as {@code (R/r)³} from {@link #EDGE_LEVEL} — anchored at the edge, so a big
     * galaxy fills far more of the void than a dwarf and neither needs a normalisation of its own. It
     * is ISOTROPIC while the disc is not: ejection randomises a direction long before a body has
     * crossed the void, so a spiral's poles are not a dead cone. The step at the radius is therefore
     * real, and it is at the one surface this layer already declares as a boundary — the surface where
     * the frame flips and where the star field stops dead.</p>
     */
    public double ejectaDensityAt(double dxLy, double dyLy, double dzLy) {
        double r = Math.sqrt(dxLy * dxLy + dyLy * dyLy + dzLy * dzLy);
        if (r <= radiusLy) {
            return 0d;
        }
        return EDGE_LEVEL * Math.pow(radiusLy / r, EJECTA_FALLOFF);
    }

    /** The ejecta halo read at a cell name — the form the generator asks in. */
    public double ejectaDensityAtSector(long sectorX, long sectorY, long sectorZ) {
        return ejectaDensityAt(offsetLy(sectorX, centre.sectorX()),
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

    // ─── Where the galaxy itself is ────────────────────────────────────────────

    /**
     * Where this galaxy's centre stands at tick {@code t}: {@code C(t) = a(t) · (C₀ + v·t)}.
     *
     * <p>Expansion carries the centre and <b>nothing inside the galaxy</b>. A gravitationally bound
     * system does not expand, and scaling intra-galactic coordinates would grow every {@code r} and
     * corrupt {@code ω(r)} from within — so the split is structural rather than a rule someone has to
     * remember: everything below is written as an offset from this point.</p>
     *
     * <p>Expansion alone would let a galaxy only ever RECEDE, which makes an approaching neighbour
     * unrepresentable — and in a real group at short range peculiar motion dominates expansion. Hence
     * the velocity term, one hash draw, still analytic, still nothing integrated.</p>
     */
    public LightYearVector centreAt(long tick) {
        return seat.plus(peculiarVelocity.scale((double) tick))
                .scale(Cosmology.scaleFactorAt(tick));
    }

    /**
     * Where a point BOUND to this galaxy stands at tick {@code t}, absolutely.
     *
     * <p>It rides the galaxy: it turns with the disc at {@code ω(r)} and it does not expand. The
     * arguments are its galaxy-local cylindrical elements at {@code t = 0}, which are what a bound
     * thing actually has — a radius, an angle and a height, exactly as a planet has an orbit.</p>
     */
    public LightYearVector boundPositionAt(long tick, double rLy, double theta0, double heightLy) {
        double theta = thetaAt(theta0, rLy, tick);
        double localX = rLy * Math.cos(theta);
        double localY = rLy * Math.sin(theta);
        // Back out of the galaxy frame: the basis is orthonormal, so the inverse is its transpose.
        return centreAt(tick).plus(LightYearVector.of(
                localX * ux + localY * vx + heightLy * wx,
                localX * uy + localY * vy + heightLy * wy,
                localX * uz + localY * vz + heightLy * wz));
    }

    /** The galaxy-local radius of a static-frame offset from the centre, in light years. */
    public double localRadius(double dxLy, double dyLy, double dzLy) {
        return Math.hypot(dxLy * ux + dyLy * uy + dzLy * uz, dxLy * vx + dyLy * vy + dzLy * vz);
    }

    /** The galaxy-local angle of a static-frame offset from the centre, in radians. */
    public double localTheta(double dxLy, double dyLy, double dzLy) {
        return Math.atan2(dxLy * vx + dyLy * vy + dzLy * vz, dxLy * ux + dyLy * uy + dzLy * uz);
    }

    /** The height of a static-frame offset above this galaxy's plane, in light years. */
    public double localHeight(double dxLy, double dyLy, double dzLy) {
        return dxLy * wx + dyLy * wy + dzLy * wz;
    }

    /**
     * Where the cell named {@code cell} stands at tick {@code t}, IF it is bound to this galaxy.
     *
     * <p>Its elements are read once, off its offset from the seat at {@code t = 0} — that is what a
     * cell NAME means here, and it is why a name stays put while the place it names moves.</p>
     */
    public LightYearVector boundPositionOfCellAt(GalacticCoord cell, long tick) {
        double dx = offsetLy(cell.sectorX(), centre.sectorX());
        double dy = offsetLy(cell.sectorY(), centre.sectorY());
        double dz = offsetLy(cell.sectorZ(), centre.sectorZ());
        return boundPositionAt(tick, localRadius(dx, dy, dz), localTheta(dx, dy, dz),
                localHeight(dx, dy, dz));
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** A sector delta as a length in light years. Exact: the delta is bounded by one galaxy cell. */
    private static double offsetLy(long sector, long centreSector) {
        return UniverseScale.lightYearsForCells((double) (sector - centreSector));
    }

    private static double atLeastZero(double v) {
        return v > 0d ? v : 0d;
    }

    @Override
    public String toString() {
        return "Galaxy[" + name() + " " + type.name + " r=" + (long) radiusLy + "ly centre="
                + centre.cellKey() + "]";
    }
}
