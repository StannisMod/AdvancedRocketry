package zmaster587.advancedRocketry.test.unit;

import org.joml.Matrix3d;
import org.joml.Vector3dc;
import org.junit.Test;

import zmaster587.advancedRocketry.ship.mass.MassContributor;
import zmaster587.advancedRocketry.ship.mass.MassContributor.Kind;
import zmaster587.advancedRocketry.ship.mass.ShipMassFrame;
import zmaster587.advancedRocketry.ship.mass.ShipMassFrameBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The mass frame is what every flight characteristic is derived from, so these pin the properties
 * the rest of the ship model is entitled to assume — not the arithmetic, which is textbook.
 *
 * <p>Concretely: the categories add up and nothing is counted twice; the centre of mass is the
 * mass-weighted mean, so loading cargo to one side moves it; the inertia tensor is symmetric and
 * remains invertible even for the hull shapes that would degenerate a point-mass model; and mass
 * knows nothing about gravity, which is what keeps a craft the same craft on every world. The
 * particular masses used here are arbitrary — the relationships are what is asserted.</p>
 */
public class ShipMassFrameTest {

    private static final double EPS = 1.0e-9D;

    /** Two identical blocks either side of the origin, one metre apart. */
    private static ShipMassFrame symmetricPair(double mass) {
        return new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-0.5D, 0.0D, 0.0D, mass, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, mass, Kind.STRUCTURAL))
                .build();
    }

    /**
     * Translating a frame moves its centre of mass and leaves its inertia alone.
     *
     * <p>This is the property that lets a hull be MEASURED near itself and REPORTED in the address
     * space the physics record keeps — a ship's own subspace, which starts past five million blocks
     * along X. Accumulating second moments about a point that far away spends most of a double's
     * precision on a constant that cancels at the end; measuring locally and translating does not.</p>
     *
     * <p>The inertia leg is the load-bearing half: the tensor is expressed <em>about the centre of
     * mass</em>, so it must be invariant here. If it ever stopped being, every craft's handling would
     * silently depend on where its shipyard happened to be allocated.</p>
     */
    @Test
    public void translatingMovesTheCentreAndLeavesTheInertiaAlone() {
        ShipMassFrame local = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-1.5D, 0.0D, 0.0D, 800.0D, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(2.5D, 0.0D, 0.0D, 200.0D, Kind.STRUCTURAL))
                .build();

        double dx = 5120000.0D, dy = 128.0D, dz = 51200.0D;
        ShipMassFrame moved = local.translated(dx, dy, dz);

        assertEquals("translation must not invent or lose mass",
                local.getTotalMass(), moved.getTotalMass(), EPS);
        assertEquals("the centre moves by exactly the offset, at a real shipyard distance",
                local.getCentreOfMass().x() + dx, moved.getCentreOfMass().x(), 1.0e-6D);
        assertEquals(local.getCentreOfMass().y() + dy, moved.getCentreOfMass().y(), 1.0e-6D);
        assertEquals(local.getCentreOfMass().z() + dz, moved.getCentreOfMass().z(), 1.0e-6D);

        Matrix3d before = new Matrix3d(local.getInertia());
        Matrix3d after = new Matrix3d(moved.getInertia());
        assertEquals("inertia about the centre of mass cannot depend on where the centre IS",
                before.m00(), after.m00(), EPS);
        assertEquals(before.m11(), after.m11(), EPS);
        assertEquals(before.m22(), after.m22(), EPS);
        assertEquals(before.m01(), after.m01(), EPS);
        assertEquals(before.m02(), after.m02(), EPS);
        assertEquals(before.m12(), after.m12(), EPS);
    }

    @Test
    public void totalIsExactlyTheThreeCategories() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0, 0, 0, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(1, 0, 0, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(1, 0, 0, 300, Kind.CONTENT))
                .add(MassContributor.of(0, 1, 0, 80, 0.6D, Kind.CREW))
                .build();

        assertEquals(1000.0D, frame.getStructuralMass(), EPS);
        assertEquals(300.0D, frame.getContentMass(), EPS);
        assertEquals(80.0D, frame.getCrewMass(), EPS);
        assertEquals(frame.getStructuralMass() + frame.getContentMass() + frame.getCrewMass(),
                frame.getTotalMass(), EPS);
    }

    @Test
    public void centreOfMassIsTheMassWeightedMean() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 300, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(4.0D, 0.0D, 0.0D, 100, Kind.STRUCTURAL))
                .build();

        // 300 kg at 0 and 100 kg at 4 balance at 1.
        assertEquals(1.0D, frame.getCentreOfMass().x(), EPS);
        assertEquals(0.0D, frame.getCentreOfMass().y(), EPS);
        assertEquals(0.0D, frame.getCentreOfMass().z(), EPS);
    }

    @Test
    public void cargoLoadedToOneSideMovesTheCentreOfMass() {
        ShipMassFrame empty = symmetricPair(500);
        ShipMassFrame loaded = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, 400, Kind.CONTENT))
                .build();

        assertEquals("a symmetric hull balances at its middle", 0.0D,
                empty.getCentreOfMass().x(), EPS);
        assertTrue("cargo on one side must pull the centre of mass that way",
                loaded.getCentreOfMass().x() > empty.getCentreOfMass().x() + 1.0e-6D);
    }

    @Test
    public void massAddedAtTheCentreOfMassDoesNotMoveIt() {
        ShipMassFrame before = symmetricPair(500);
        Vector3dc com = before.getCentreOfMass();

        ShipMassFrame after = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.of(com.x(), com.y(), com.z(), 2000, 0.5D, Kind.CONTENT))
                .build();

        assertEquals(com.x(), after.getCentreOfMass().x(), EPS);
        assertEquals(com.y(), after.getCentreOfMass().y(), EPS);
        assertEquals(com.z(), after.getCentreOfMass().z(), EPS);
    }

    @Test
    public void loadingCargoStrictlyIncreasesTotalMassAndNeverThrustLikeQuantities() {
        ShipMassFrame light = symmetricPair(500);
        ShipMassFrame heavy = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(-0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.5D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.0D, 1.0D, 0.0D, 750, Kind.CONTENT))
                .build();

        assertTrue("more cargo must mean more mass, with no ceiling folded into the model",
                heavy.getTotalMass() > light.getTotalMass());
        assertEquals("structure is untouched by what is loaded into the ship",
                light.getStructuralMass(), heavy.getStructuralMass(), EPS);
    }

    @Test
    public void negativeContributionsCannotCancelPartOfTheShip() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(5.0D, 0.0D, 0.0D, -400, Kind.CONTENT))
                .build();

        assertEquals("a negative mass must not subtract from the hull", 500.0D,
                frame.getTotalMass(), EPS);
        assertEquals("nor drag the centre of mass toward it", 0.0D,
                frame.getCentreOfMass().x(), EPS);
    }

    @Test
    public void inertiaIsSymmetric() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(2.0D, 0.0D, 1.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(-1.0D, 3.0D, 0.0D, 700, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0.0D, -2.0D, 4.0D, 300, Kind.CONTENT))
                .build();

        Matrix3d i = new Matrix3d(frame.getInertia());
        assertEquals(i.m01(), i.m10(), EPS);
        assertEquals(i.m02(), i.m20(), EPS);
        assertEquals(i.m12(), i.m21(), EPS);
    }

    @Test
    public void aSingleBlockHullStillHasAnInvertibleInertia() {
        ShipMassFrame frame = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .build();

        assertInvertible(frame);
    }

    @Test
    public void aCollinearHullStillHasAnInvertibleInertia() {
        // A mast: every block on one line. A point-mass model gives this a zero moment about the
        // line, and the solver inverts the tensor every step.
        ShipMassFrameBuilder builder = new ShipMassFrameBuilder();
        for (int y = 0; y < 12; y++) {
            builder.add(MassContributor.ofBlock(0.0D, y, 0.0D, 500, Kind.STRUCTURAL));
        }
        assertInvertible(builder.build());
    }

    @Test
    public void anEmptyFrameIsWellFormedRatherThanUndefined() {
        ShipMassFrame frame = new ShipMassFrameBuilder().build();

        assertEquals(0.0D, frame.getTotalMass(), EPS);
        assertEquals(0.0D, frame.getCentreOfMass().x(), EPS);
        assertEquals(0.0D, frame.getCentreOfMass().y(), EPS);
        assertEquals(0.0D, frame.getCentreOfMass().z(), EPS);
    }

    @Test
    public void theOrderContributorsArriveInDoesNotChangeTheShip() {
        // Block iteration order is an accident of how chunks are walked; handling must not depend on it.
        ShipMassFrame forwards = new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(3.0D, 1.0D, 0.0D, 200, Kind.CONTENT))
                .add(MassContributor.of(1.0D, 2.0D, 0.0D, 90, 0.6D, Kind.CREW))
                .build();
        ShipMassFrame backwards = new ShipMassFrameBuilder()
                .add(MassContributor.of(1.0D, 2.0D, 0.0D, 90, 0.6D, Kind.CREW))
                .add(MassContributor.ofBlock(3.0D, 1.0D, 0.0D, 200, Kind.CONTENT))
                .add(MassContributor.ofBlock(0.0D, 0.0D, 0.0D, 500, Kind.STRUCTURAL))
                .build();

        assertEquals(forwards.getTotalMass(), backwards.getTotalMass(), 1.0e-9D);
        assertEquals(forwards.getCentreOfMass().x(), backwards.getCentreOfMass().x(), 1.0e-9D);
        assertEquals(forwards.getCentreOfMass().y(), backwards.getCentreOfMass().y(), 1.0e-9D);
        Matrix3d a = new Matrix3d(forwards.getInertia());
        Matrix3d b = new Matrix3d(backwards.getInertia());
        assertEquals(a.m00(), b.m00(), 1.0e-9D);
        assertEquals(a.m11(), b.m11(), 1.0e-9D);
        assertEquals(a.m22(), b.m22(), 1.0e-9D);
    }

    @Test
    public void aLongHullResistsRollingLessThanYawing() {
        // A property a player feels: a needle-shaped ship spins about its long axis far more readily
        // than it swings its nose. If this ever inverts, the tensor axes have been transposed.
        ShipMassFrameBuilder builder = new ShipMassFrameBuilder();
        for (int x = -10; x <= 10; x++) {
            builder.add(MassContributor.ofBlock(x, 0.0D, 0.0D, 500, Kind.STRUCTURAL));
        }
        Matrix3d i = new Matrix3d(builder.build().getInertia());

        assertTrue("about the long axis must be the cheapest rotation",
                i.m00() < i.m11() && i.m00() < i.m22());
        assertNotEquals("and the two transverse axes are not degenerate", 0.0D, i.m11(), 1.0D);
    }

    private static void assertInvertible(ShipMassFrame frame) {
        Matrix3d inertia = new Matrix3d(frame.getInertia());
        double det = inertia.determinant();
        assertTrue("the solver inverts this tensor every step, so a zero determinant is a NaN torque,"
                + " not a rounding error (got " + det + ")", Math.abs(det) > 1.0e-6D);

        Matrix3d inverse = new Matrix3d(inertia).invert();
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                assertTrue("inverse entry (" + r + "," + c + ") must be finite",
                        !Double.isNaN(inverse.get(c, r)) && !Double.isInfinite(inverse.get(c, r)));
            }
        }
    }
}
