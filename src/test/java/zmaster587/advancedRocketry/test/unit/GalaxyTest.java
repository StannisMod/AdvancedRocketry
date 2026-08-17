package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.Cosmology;
import zmaster587.advancedRocketry.universe.Galaxy;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.LightYearVector;
import zmaster587.advancedRocketry.universe.UniverseScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for one galaxy as a shape: what it contains, how its density falls off, and how it
 * turns. Pure-JUnit; no MC bootstrap, no generator.
 *
 * <p>What is pinned is the SHAPE of each law, never a tuned number: that the boundary is the declared
 * radius and not a level of the profile, that density falls with radius and with height above the
 * plane, that a disc really is flatter than it is wide, that the arms modulate rather than gate, and
 * that rotation shears differently for a dwarf than for a massive spiral. The constants those laws
 * carry are balance knobs and are fed in as inputs.</p>
 */
public class GalaxyTest {

    private static final double RADIUS = 1500d;

    private static GalaxyGenConfig.GalaxyType spiral() {
        return new GalaxyGenConfig.GalaxyType("Spiral", GalaxyGenConfig.GalaxyProfile.DISC,
                900d, 2200d, 0.02d, 2, 220d, 0.08d, 1, 3, 7);
    }

    private static GalaxyGenConfig.GalaxyType smoothDisc() {
        return new GalaxyGenConfig.GalaxyType("Smooth", GalaxyGenConfig.GalaxyProfile.DISC,
                900d, 2200d, 0.02d, 0, 220d, 0.08d, 1, 3, 7);
    }

    private static GalaxyGenConfig.GalaxyType dwarf() {
        return new GalaxyGenConfig.GalaxyType("Dwarf", GalaxyGenConfig.GalaxyProfile.SPHEROID,
                120d, 500d, 0.70d, 0, 20d, 0.90d, 0, 0, 700);
    }

    /** A galaxy with its plane on the world's XZ plane, so a test can reason in plain coordinates. */
    private static Galaxy flat(GalaxyGenConfig.GalaxyType type) {
        return new Galaxy(0L, 0L, 0L, 0, GalacticCoord.ORIGIN, type, RADIUS, 0d, 0d,
                Math.toRadians(20d), 0d, LightYearVector.ZERO);
    }

    /** The same galaxy, seated away from the origin and moving — the subject of the R3 laws. */
    private static Galaxy adrift(GalacticCoord seat, LightYearVector velocity) {
        return new Galaxy(1L, 0L, 0L, 0, seat, smoothDisc(), RADIUS, 0d, 0d, Math.toRadians(20d), 0d,
                velocity);
    }

    @Test
    public void theBoundaryIsTheDeclaredRadius() {
        // Not a level of the profile. A profile is continuous and has no boundary, so a frame decided
        // by "is it dense enough here" would flip for anything hovering on the threshold — and the
        // frame decides whether a thing rotates with the galaxy or is carried by the void.
        Galaxy g = flat(spiral());
        assertTrue(g.contains(RADIUS * 0.999d, 0d, 0d));
        assertFalse(g.contains(RADIUS * 1.001d, 0d, 0d));
        // It is a SPHERE, so the halo well above a thin disc is still inside the galaxy.
        assertTrue("the halo above a disc is bound to the galaxy too", g.contains(0d, RADIUS * 0.9d, 0d));
        assertEquals("and there are no stars out there", 0d, g.densityAt(RADIUS * 1.5d, 0d, 0d), 0d);
    }

    @Test
    public void densityFallsWithRadiusAndWithHeight() {
        Galaxy g = flat(smoothDisc());
        double centre = g.densityAt(0d, 0d, 0d);
        double midway = g.densityAt(RADIUS * 0.4d, 0d, 0d);
        double rim = g.densityAt(RADIUS * 0.9d, 0d, 0d);
        assertTrue("the nucleus is the densest point", centre > midway);
        assertTrue("and it keeps thinning outwards", midway > rim);

        // Off the plane at the same radius: a disc is a disc.
        double inPlane = g.densityAt(RADIUS * 0.4d, 0d, 0d);
        double aloft = g.densityAt(RADIUS * 0.4d, RADIUS * 0.1d, 0d);
        assertTrue("a disc must thin out of its plane (" + inPlane + " vs " + aloft + ")",
                inPlane > aloft);
    }

    @Test
    public void aDiscIsFlatterThanItIsWide() {
        // The one claim that separates a disc from a sphere: the same fraction of the radius costs
        // far more density vertically than radially.
        Galaxy g = flat(smoothDisc());
        double outward = g.densityAt(RADIUS * 0.05d, 0d, 0d);
        double upward = g.densityAt(0d, RADIUS * 0.05d, 0d);
        assertTrue("going up must cost more than going out (" + upward + " vs " + outward + ")",
                upward < outward);
    }

    @Test
    public void armsModulateTheDiscTheyDoNotGateIt() {
        // An arm is where a disc is denser, not where it exists. If the between-arm density were zero
        // the galaxy would be a set of curves rather than a disc with structure in it.
        Galaxy armed = flat(spiral());
        double min = Double.MAX_VALUE;
        double max = 0d;
        double r = RADIUS * 0.5d;
        for (int i = 0; i < 360; i++) {
            double theta = Math.toRadians(i);
            double d = armed.densityAt(r * Math.cos(theta), 0d, r * Math.sin(theta));
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        assertTrue("arms must make the disc vary with angle", max > min);
        assertTrue("but between the arms there are still stars", min > 0d);
    }

    @Test
    public void aTypeWithNoArmsIsAxisymmetric() {
        // The no-arms case is the same code path with an empty term, so a smooth disc has to come out
        // genuinely smooth rather than nearly so.
        Galaxy smooth = flat(smoothDisc());
        double r = RADIUS * 0.5d;
        double reference = smooth.densityAt(r, 0d, 0d);
        for (int i = 0; i < 360; i += 15) {
            double theta = Math.toRadians(i);
            assertEquals("a smooth disc must not vary with angle", reference,
                    smooth.densityAt(r * Math.cos(theta), 0d, r * Math.sin(theta)), 1e-12d);
        }
    }

    @Test
    public void orientationRotatesTheDiscWithoutChangingItsShape() {
        // Two galaxies alike but for their orientation must be the same object seen from elsewhere:
        // the density a point sees depends on where it is IN THE GALAXY, never on the world axes.
        Galaxy flat = new Galaxy(0L, 0L, 0L, 0, GalacticCoord.ORIGIN, smoothDisc(), RADIUS, 0d, 0d,
                Math.toRadians(20d), 0d, LightYearVector.ZERO);
        Galaxy tilted = new Galaxy(0L, 0L, 0L, 0, GalacticCoord.ORIGIN, smoothDisc(), RADIUS,
                Math.toRadians(90d), 0d, Math.toRadians(20d), 0d, LightYearVector.ZERO);
        // The tilted galaxy's pole is +X, so ITS plane is the world's YZ plane.
        double r = RADIUS * 0.3d;
        assertEquals("the same point of the galaxy must read the same however it is oriented",
                flat.densityAt(r, 0d, 0d), tilted.densityAt(0d, 0d, r), 1e-12d);
        assertEquals("and so must its pole", flat.densityAt(0d, r, 0d), tilted.densityAt(r, 0d, 0d),
                1e-12d);
    }

    @Test
    public void rotationIsSolidBodyInTheCoreAndShearsOutside() {
        // omega(r) constant means no shear; omega falling with r IS the shear. A galaxy that sheared
        // nowhere would carry its arms round rigidly forever, and one that sheared everywhere would
        // tear its own nucleus apart.
        Galaxy g = flat(spiral());
        double core = RADIUS * spiral().coreRadiusFraction;
        assertEquals("well inside the core the curve is solid-body, so omega is flat",
                g.angularSpeedAt(core * 0.001d), g.angularSpeedAt(core * 0.01d),
                g.angularSpeedAt(0d) * 1e-3d);
        assertTrue("outside the core, omega must fall with radius",
                g.angularSpeedAt(RADIUS * 0.9d) < g.angularSpeedAt(RADIUS * 0.3d));
        assertTrue("and it is finite at the very centre", g.angularSpeedAt(0d) > 0d
                && !Double.isInfinite(g.angularSpeedAt(0d)));
    }

    @Test
    public void aDwarfShearsLessThanAMassiveSpiral() {
        // The type earns its keep here, and it is what a real rotation curve does: a dwarf turns
        // nearly as a solid body while a massive spiral's curve is flat and shears strongly. Measured
        // as the ratio of omega across the same FRACTIONAL radii, so it compares shapes, not speeds.
        Galaxy small = flat(dwarf());
        Galaxy big = flat(spiral());
        double dwarfShear = small.angularSpeedAt(RADIUS * 0.2d) / small.angularSpeedAt(RADIUS * 0.8d);
        double spiralShear = big.angularSpeedAt(RADIUS * 0.2d) / big.angularSpeedAt(RADIUS * 0.8d);
        assertTrue("a dwarf must shear less than a spiral (" + dwarfShear + " vs " + spiralShear + ")",
                dwarfShear < spiralShear);
    }

    @Test
    public void thetaIsEvaluatedNeverIntegrated() {
        // Analytic in t: theta at 2t must be exactly theta0 plus twice the advance, with no drift a
        // step-by-step accumulation would build up.
        Galaxy g = flat(spiral());
        double r = RADIUS * 0.5d;
        double theta0 = 1.234d;
        double advance = g.thetaAt(theta0, r, 1_000_000L) - theta0;
        assertEquals(theta0 + 2d * advance, g.thetaAt(theta0, r, 2_000_000L), 1e-12d);
    }

    @Test
    public void rotationIsSlowEnoughToBeInvisibleWithinASave() {
        // Recorded as a measurement, not a requirement: the mechanic exists even when slow, and the
        // speed is tuning. What this pins is that the law is expressed in the SAME clock the game
        // counts in — a period that came out in ticks-per-turn of order one would mean the km/s
        // conversion had lost a calendar somewhere.
        Galaxy g = flat(spiral());
        double turnTicks = g.rotationPeriodTicks(RADIUS * 0.5d);
        assertTrue("a galactic turn must dwarf any play session (" + turnTicks + " ticks)",
                turnTicks > 1e11d);
        assertFalse("but it must be a finite number of ticks", Double.isInfinite(turnTicks));
    }

    // ─── Expansion and peculiar motion (R3) ────────────────────────────────────

    @Test
    public void expansionIsMonotoneAndStartsAtOne() {
        // t = 0 is world creation, so the universe's age IS the save's age. And a(t) only ever grows:
        // shear separates reversibly (theta wraps), expansion does not. A galaxy that recedes past a
        // drive's reach has receded permanently, which is a stronger claim than "the sky moves".
        assertEquals("a(0) must be exactly 1", 1d, Cosmology.scaleFactorAt(0L), 0d);
        double previous = 1d;
        for (long t = 1_000_000L; t <= 1_000_000_000_000L; t *= 10L) {
            double a = Cosmology.scaleFactorAt(t);
            assertTrue("a(" + t + ") = " + a + " did not grow past " + previous, a > previous);
            previous = a;
        }
    }

    @Test
    public void expansionCarriesTheCentreAndNothingInsideTheGalaxy() {
        // The whole reason expansion is applied to the CENTRE only: a bound system does not expand,
        // and scaling intra-galactic coordinates would grow every r and corrupt omega(r) from within.
        // Measured as the separation between two bound points, which must not change with the scale
        // factor even while their galaxy is being carried away.
        Galaxy g = adrift(GalacticCoord.ofSectorLocal(4_000_000_000L, 0L, 0L, 0L, 0L, 0L),
                LightYearVector.of(1e-9d, 0d, 0d));
        double r = RADIUS * 0.4d;
        long far = 500_000_000L;

        // Two points at the same radius, so rotation carries them equally and only expansion could
        // separate them.
        double now = g.boundPositionAt(0L, r, 0d, 0d).distanceTo(g.boundPositionAt(0L, r, 1d, 0d));
        double later = g.boundPositionAt(far, r, 0d, 0d).distanceTo(g.boundPositionAt(far, r, 1d, 0d));
        assertEquals("two bound points must keep their separation while their galaxy is carried away",
                now, later, now * 1e-9d);
        assertTrue("and the galaxy itself must have moved",
                g.centreAt(far).distanceTo(g.centreAt(0L)) > 0d);
    }

    @Test
    public void aGalaxyMovesUnderBothExpansionAndItsOwnVelocity() {
        // Expansion alone lets a galaxy only RECEDE, so an approaching neighbour would be
        // unrepresentable — and at short range peculiar motion dominates expansion in a real group.
        GalacticCoord seat = GalacticCoord.ofSectorLocal(4_000_000_000L, 0L, 0L, 0L, 0L, 0L);
        Galaxy still = adrift(seat, LightYearVector.ZERO);
        Galaxy inbound = adrift(seat, LightYearVector.of(-1e-9d, 0d, 0d));
        long t = 100_000_000L;

        double seatLy = still.centreAt(0L).x();
        assertTrue("expansion alone can only push a galaxy outwards",
                still.centreAt(t).x() > seatLy);
        assertTrue("but its own velocity must be able to bring it closer",
                inbound.centreAt(t).x() < seatLy);
    }

    @Test
    public void theCentreLawIsEvaluatedNeverIntegrated() {
        // Analytic in t, like everything else in this layer: asking for tick N is one evaluation, so
        // there is no step size and nothing to accumulate.
        Galaxy g = adrift(GalacticCoord.ofSectorLocal(2_000_000_000L, 0L, 0L, 0L, 0L, 0L),
                LightYearVector.of(3e-10d, -1e-10d, 2e-10d));
        long t = 12_345_678L;
        double a = Cosmology.scaleFactorAt(t);
        LightYearVector expected = LightYearVector.ofCell(g.centre())
                .plus(g.peculiarVelocity().scale((double) t)).scale(a);
        assertEquals(expected.x(), g.centreAt(t).x(), Math.abs(expected.x()) * 1e-12d);
        assertEquals(expected.y(), g.centreAt(t).y(), 1e-9d);
        assertEquals(expected.z(), g.centreAt(t).z(), 1e-9d);
    }

    @Test
    public void aSectorReadingAgreesWithTheLengthItStandsFor() {
        // The generator asks in cell names; everything above is written in light years. The two have
        // to be the same question, or the star field would be placed by one metric and bounded by
        // another — which is the failure this whole layer keeps removing.
        Galaxy g = flat(smoothDisc());
        long cells = UniverseScale.cellsForLightYears(RADIUS * 0.5d);
        assertEquals(g.densityAt(UniverseScale.lightYearsForCells(cells), 0d, 0d),
                g.densityAtSector(cells, 0L, 0L), 1e-12d);
        assertFalse("and a sector past the radius is outside",
                g.containsSector(UniverseScale.cellsForLightYears(RADIUS * 1.5d), 0L, 0L));
    }
}
