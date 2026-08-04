package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import zmaster587.advancedRocketry.integration.vs.HullSweep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The hull-stand collision contract - a body standing on a ship's OUTER hull keeps WORLD-frame
 * semantics: it collides with the ship's blocks where their TRUE world geometry is — at any ship
 * attitude — and the stand-vs-slide decision follows the local gravity with unit friction (faces
 * within 45 degrees of gravity-up hold a body statically; steeper faces shed it).
 *
 * <p>Every expected number here is computed by hand from the geometry, so a wrong sweep cannot
 * pass by construction. The tilted cases are the ones the subspace-aligned sweep got wrong by
 * {@code h*sin(tilt/2)} (the "walking a block beside the visible blocks" playtest report).</p>
 */
public class HullSweepTest {

    private static final double SLOP_TOL = 1.0E-4;

    private static final double[][] IDENTITY = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    private static final double[] UP = {0, 1, 0};

    /** Ship axes for a roll of {@code deg} about the world Z axis. */
    private static double[][] rollZ(double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return new double[][]{{c, s, 0}, {-s, c, 0}, {0, 0, 1}};
    }

    /** A player-sized world AABB: 0.6 wide, 1.8 tall, feet at {@code feetY}. */
    private static double[] body(double cx, double feetY, double cz) {
        return new double[]{cx - 0.3, feetY, cz - 0.3, cx + 0.3, feetY + 1.8, cz + 0.3};
    }

    /** A unit cube of the ship: world center + half-extents (axis-aligned in the SHIP frame). */
    private static double[] cube(double cx, double cy, double cz) {
        return new double[]{cx, cy, cz, 0.5, 0.5, 0.5};
    }

    private static List<double[]> obstacles(double[]... boxes) {
        return new ArrayList<>(Arrays.asList(boxes));
    }

    // ---- level attitude: results must equal the vanilla AABB numbers ------------------------

    @Test
    public void aFallOntoALevelBlockStopsOnItsTop() {
        HullSweep.Result r = HullSweep.sweep(body(0.5, 2.5, 0.5), 0, -2.0, 0,
                obstacles(cube(0.5, 0.5, 0.5)), IDENTITY, UP, 0.6, false);
        assertTrue("fall must be clipped", r.collidedY);
        assertEquals("feet must stop on the block top (gap 1.5): got dy=" + r.dy,
                -1.5, r.dy, SLOP_TOL);
        assertEquals("contact normal must be world-up", 1.0, r.normalY, 1.0E-9);
    }

    @Test
    public void walkingIntoALevelWallStopsAtItsFace() {
        HullSweep.Result r = HullSweep.sweep(body(2.0, 0.0, 0.5), 1.0, 0, 0,
                obstacles(cube(3.5, 0.5, 0.5)), IDENTITY, null, 0.0, false);
        assertTrue("walk must be clipped", r.collidedX);
        assertEquals("body edge must stop at the wall face (gap 0.7): got dx=" + r.dx,
                0.7, r.dx, SLOP_TOL);
    }

    @Test
    public void stepAssistCarriesTheBodyOntoALowLedge() {
        // Standing on cube A (top y=1.0), walking +X into ledge B whose top is 0.25 higher: the
        // step assist must lift over it and settle on its top — vanilla staircase semantics.
        double[] ledge = {1.5, 0.75, 0.5, 0.5, 0.5, 0.5}; // spans y 0.25..1.25
        HullSweep.Result r = HullSweep.sweep(body(0.5, 1.001, 0.5), 0.5, -0.08, 0,
                obstacles(cube(0.5, 0.5, 0.5), ledge), IDENTITY, UP, 0.6, true);
        assertEquals("the step must not eat horizontal progress: dx=" + r.dx, 0.5, r.dx, 0.01);
        assertEquals("and must settle on the ledge top (feet 1.25): dy=" + r.dy,
                1.25 - 1.001, r.dy, 0.01);
    }

    // ---- tilted attitude: contact where the TRUE geometry is, not the phantom's ---------------

    @Test
    public void aFallOntoA45DegreeCubeStopsAtItsTrueTopVertex() {
        // A cube rolled 45 about Z presents its top vertex at cy + sqrt(2)/2.
        double topY = 0.5 + Math.sqrt(2) / 2.0;
        HullSweep.Result r = HullSweep.sweep(body(0.5, 3.0, 0.5), 0, -3.0, 0,
                obstacles(cube(0.5, 0.5, 0.5)), rollZ(45), UP, 0.6, false);
        assertTrue(r.collidedY);
        assertEquals("feet must stop at the rotated cube's real top (" + topY + "): dy=" + r.dy,
                -(3.0 - topY), r.dy, SLOP_TOL);
    }

    @Test
    public void aFallOntoASteeplyInvertedCubeStopsAtItsTrueTop() {
        // 160 degrees — the e2e attitude. True top of the rotated cube: cy + (|sin|+|cos|)/2.
        double s = Math.abs(Math.sin(Math.toRadians(160)));
        double c = Math.abs(Math.cos(Math.toRadians(160)));
        double topY = 0.5 + (s + c) / 2.0;
        // The top vertex sits at world x = cx - 0.299; keep the body over it.
        HullSweep.Result r = HullSweep.sweep(body(0.5 - 0.299, 3.0, 0.5), 0, -3.0, 0,
                obstacles(cube(0.5, 0.5, 0.5)), rollZ(160), UP, 0.6, false);
        assertTrue(r.collidedY);
        assertEquals("contact must be at the TRUE rotated top (" + topY + "), not displaced by "
                + "h*sin(tilt/2): dy=" + r.dy, -(3.0 - topY), r.dy, SLOP_TOL);
    }

    @Test
    public void theContactNormalNamesTheTiltedFace() {
        // Dropped left of a 30-degree cube's top vertex, the body lands on the upper-left face,
        // whose outward normal is (-sin30, cos30, 0).
        HullSweep.Result r = HullSweep.sweep(body(0.5 - 0.35, 3.0, 0.5), 0, -3.0, 0,
                obstacles(cube(0.5, 0.5, 0.5)), rollZ(30), UP, 0.6, false);
        assertTrue(r.collidedY);
        assertEquals(-Math.sin(Math.toRadians(30)), r.normalX, 1.0E-6);
        assertEquals(Math.cos(Math.toRadians(30)), r.normalY, 1.0E-6);
        assertEquals(0.0, r.normalZ, 1.0E-6);
    }

    // ---- stand vs slide: gravity-relative, unit friction, emerges from the numbers ----------

    @Test
    public void aFaceWithin45DegreesOfGravityHoldsTheBody() {
        double[] n30 = {-Math.sin(Math.toRadians(30)), Math.cos(Math.toRadians(30)), 0};
        assertNull("30 degrees to gravity-up: static hold",
                HullSweep.slideOfBlocked(0, -0.08, 0, n30, UP));
        double[] n44 = {-Math.sin(Math.toRadians(44)), Math.cos(Math.toRadians(44)), 0};
        assertNull("44 degrees: still holds",
                HullSweep.slideOfBlocked(0, -0.08, 0, n44, UP));
    }

    @Test
    public void aFaceSteeperThan45DegreesShedsTheBodyDownslope() {
        double[] n60 = {-Math.sin(Math.toRadians(60)), Math.cos(Math.toRadians(60)), 0};
        double[] slide = HullSweep.slideOfBlocked(0, -0.08, 0, n60, UP);
        assertNotNull("60 degrees to gravity-up must slide", slide);
        assertTrue("the slide must run downslope (-x, -y): " + Arrays.toString(slide),
                slide[0] < 0 && slide[1] < 0);
        // Tangential component of the blocked move: |b| * sin(angle between b and n)…
        // for b = (0,-0.08,0) and the 60-degree normal, |slide| = 0.08 * sin(60).
        double mag = Math.sqrt(slide[0] * slide[0] + slide[1] * slide[1] + slide[2] * slide[2]);
        assertEquals(0.08 * Math.sin(Math.toRadians(60)), mag, 1.0E-9);
    }

    @Test
    public void zeroGravityMeansNoSlideNoLiftNoStep() {
        assertNull("no gravity, no slide", HullSweep.slideOfBlocked(0, -0.08, 0,
                new double[]{0, 1, 0}, null));
        // An embedded start with up == null must not be lifted.
        HullSweep.Result r = HullSweep.sweep(body(0.5, 0.95, 0.5), 0, -0.05, 0,
                obstacles(cube(0.5, 0.5, 0.5)), IDENTITY, null, 0.6, true);
        assertEquals("zero-g: no de-penetration lift", 0.0, r.liftY, 0.0);
    }

    // ---- start de-penetration --------------------------------------------------------------

    @Test
    public void aShallowEmbedIsLiftedOutAndThenStands() {
        // Feet 0.05 inside the block top (a subspace round-trip noise magnified): the lift must
        // resolve it and the same tick's gravity must then STAND on the face, not fall through.
        HullSweep.Result r = HullSweep.sweep(body(0.5, 0.95, 0.5), 0, -0.08, 0,
                obstacles(cube(0.5, 0.5, 0.5)), IDENTITY, UP, 0.6, true);
        assertEquals("the lift must resolve the 0.05 embed", 0.05, r.liftY, 1.0E-3);
        assertTrue("gravity after the lift must be clipped by the face", r.collidedY);
        double feetAfter = 0.95 + r.liftY + r.dy;
        assertEquals("the body must end standing on the top (y=1.0): " + feetAfter,
                1.0, feetAfter, 1.0E-3);
    }

    @Test
    public void aDeepEmbedIsNotLiftedAndDoesNotClip() {
        // 0.5 inside — a real embed (teleport into a block): the bounded lift must not fire and
        // the pass stays permissive, exactly like the subspace sweep before it.
        HullSweep.Result r = HullSweep.sweep(body(0.5, 0.5, 0.5), 0, -0.08, 0,
                obstacles(cube(0.5, 0.5, 0.5)), IDENTITY, UP, 0.6, true);
        assertEquals("deep embeds are not lifted", 0.0, r.liftY, 0.0);
        assertFalse("a pass never clips an obstacle it starts inside", r.collidedY);
        assertEquals(-0.08, r.dy, 1.0E-9);
    }

    // ---- robustness ------------------------------------------------------------------------

    @Test
    public void aDiagonalApproachOntoARotatedEdgeStaysFiniteAndBounded() {
        double[][] axes = rollZ(37.3);
        HullSweep.Result r = HullSweep.sweep(body(-1.2, 0.4, 0.3), 0.9, -0.7, 0.4,
                obstacles(cube(0.5, 0.5, 0.5), cube(0.5, 0.5, 1.5), cube(1.5, 0.5, 0.5)),
                axes, UP, 0.6, false);
        assertTrue("finite dx", Double.isFinite(r.dx));
        assertTrue("finite dy", Double.isFinite(r.dy));
        assertTrue("finite dz", Double.isFinite(r.dz));
        assertTrue("|dx| bounded by want", Math.abs(r.dx) <= 0.9 + 1.0E-9);
        assertTrue("|dy| bounded by want", Math.abs(r.dy) <= 0.7 + 1.0E-9);
        assertTrue("|dz| bounded by want", Math.abs(r.dz) <= 0.4 + 1.0E-9);
    }

    @Test
    public void aMissingObstacleListClipsNothing() {
        HullSweep.Result r = HullSweep.sweep(body(0.5, 2.0, 0.5), 0.3, -0.5, -0.2,
                obstacles(), rollZ(135), UP, 0.6, true);
        assertFalse(r.collidedX);
        assertFalse(r.collidedY);
        assertFalse(r.collidedZ);
        assertEquals(0.3, r.dx, 1.0E-12);
        assertEquals(-0.5, r.dy, 1.0E-12);
        assertEquals(-0.2, r.dz, 1.0E-12);
    }
}
