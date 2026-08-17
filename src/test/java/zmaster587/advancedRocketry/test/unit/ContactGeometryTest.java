package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import zmaster587.advancedRocketry.api.damage.Contact;
import zmaster587.advancedRocketry.api.damage.ContactResult;
import zmaster587.advancedRocketry.api.damage.ImpactKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a block is told when something hits it, and what it is allowed to answer.
 *
 * <p>Two claims. <b>The angle means what its name says</b>: square-on is zero and a graze approaches
 * ninety, in that direction — a ricochet rule written against a reversed convention would bounce
 * exactly the shots that should punch through, and nothing else in the game would contradict it.
 * And <b>an answer cannot promise what it has not got</b>: a deflection with nowhere to go is a stop,
 * and a stop carries nothing onward.</p>
 *
 * <p>No angle threshold is pinned here. Where a ricochet begins is balance; that the angle grows as
 * the hit gets flatter is the contract.</p>
 */
public class ContactGeometryTest {

    private static final Vec3d POINT = new Vec3d(10.0D, 64.0D, 10.0D);

    /** Straight into the face: the flattest possible statement of "not a graze". */
    @Test
    public void aSquareHitReadsZeroDegrees() {
        // Travelling +X into a block entered through its WEST face (whose outward normal is -X).
        Contact contact = contact(EnumFacing.WEST, new Vec3d(2.0D, 0.0D, 0.0D));
        assertEquals("a body arriving square-on must read zero degrees of incidence, or every rule"
                + " written against this angle is inverted", 0.0D, contact.getIncidenceDegrees(), 1.0E-6D);
    }

    /** Along the face: the flattest possible graze, and the far end of the same scale. */
    @Test
    public void aGrazeReadsNinetyDegrees() {
        Contact contact = contact(EnumFacing.WEST, new Vec3d(0.0D, 0.0D, 3.0D));
        assertEquals("a body travelling ALONG the face must read ninety degrees", 90.0D,
                contact.getIncidenceDegrees(), 1.0E-6D);
    }

    /** The scale between the ends is monotone: flatter hit, larger angle. Never a magnitude. */
    @Test
    public void theAngleGrowsAsTheHitGetsFlatter() {
        // Forward is held at 2 and sideways is walked to 20, so the last sample really is the ten-to-one
        // graze the assertion below names — the first version of this loop stopped at two-to-one and
        // its message described an experiment it was not running.
        double previous = -1.0D;
        for (int sideways = 0; sideways <= 10; sideways++) {
            double degrees = contact(EnumFacing.WEST, new Vec3d(2.0D, 0.0D, sideways * 2.0D))
                    .getIncidenceDegrees();
            assertTrue("incidence must never FALL as the same hit is made flatter: " + degrees
                    + " after " + previous + " at sideways=" + sideways, degrees >= previous - 1.0E-9D);
            previous = degrees;
        }
        assertTrue("a hit ten times more sideways than forward must read as a graze, not a square"
                + " hit: " + previous, previous > 80.0D);
    }

    /** Whichever face is met, the normal points back at whoever fired. */
    @Test
    public void theNormalPointsBackTheWayTheBodyCame() {
        for (EnumFacing face : EnumFacing.values()) {
            Vec3d normal = contact(face, new Vec3d(1.0D, 0.0D, 0.0D)).getNormal();
            assertEquals("the outward normal must be the entry face's own direction", face.getFrontOffsetX(),
                    normal.x, 1.0E-9D);
            assertEquals(face.getFrontOffsetY(), normal.y, 1.0E-9D);
            assertEquals(face.getFrontOffsetZ(), normal.z, 1.0E-9D);
        }
    }

    /**
     * A body that began the step already inside the block has no face to have crossed. It must read as
     * a square hit rather than throwing or inventing a normal: the reading that never ricochets is the
     * safe one for a case nobody can compute an angle for.
     */
    @Test
    public void aBodyWithNoEntryFaceIsNotAGraze() {
        Contact contact = contact(null, new Vec3d(1.0D, 0.0D, 1.0D));
        assertNull(contact.getNormal());
        assertEquals(0.0D, contact.getIncidenceDegrees(), 1.0E-9D);
    }

    /** An answer that cannot deliver a deflection is a stop, not a deflection with a null course. */
    @Test
    public void aDeflectionWithNowhereToGoIsAStop() {
        assertTrue("a null course must degrade to stopped",
                ContactResult.deflected(null, 500).isStopped());
        assertTrue("a motionless course must degrade to stopped",
                ContactResult.deflected(new Vec3d(0.0D, 0.0D, 0.0D), 500).isStopped());
        assertEquals("and a stop carries nothing onward", 0,
                ContactResult.deflected(null, 500).getResidualEnergy());
    }

    /** The three states answer about themselves consistently, so a caller can branch on any one. */
    @Test
    public void eachStateReportsItselfAndNotAnother() {
        ContactResult through = ContactResult.passedThrough(1200);
        assertFalse(through.isStopped());
        assertFalse("passing through is not a deflection: the body kept its own course",
                through.isDeflected());
        assertEquals(1200, through.getResidualEnergy());

        ContactResult stopped = ContactResult.stopped();
        assertTrue(stopped.isStopped());
        assertFalse(stopped.isDeflected());
        assertNull(stopped.getDeflectedVelocity());

        ContactResult bounced = ContactResult.deflected(new Vec3d(0.0D, 1.0D, 0.0D), 800);
        assertFalse("a deflected body did not stop — that is the whole difference",
                bounced.isStopped());
        assertTrue(bounced.isDeflected());
        assertEquals(800, bounced.getResidualEnergy());
    }

    /** Energy and share are clamped where they are built, so no consumer has to re-check them. */
    @Test
    public void aContactCannotCarryNonsense() {
        Contact negative = new Contact(BlockPos.ORIGIN, POINT, EnumFacing.UP, new Vec3d(0, -1, 0),
                ImpactKind.KINETIC, -500, -2.0D, 4.0D, null);
        assertEquals("negative energy is no energy", 0, negative.getEnergy());
        assertEquals("a negative cross-section is a point", 0.0D, negative.getRadius(), 1.0E-9D);
        assertEquals("a share above the whole body is the whole body", 1.0D, negative.getShare(),
                1.0E-9D);
        assertEquals("negative residual energy is no energy", 0,
                ContactResult.passedThrough(-7).getResidualEnergy());
    }

    private static Contact contact(EnumFacing face, Vec3d velocity) {
        return new Contact(BlockPos.ORIGIN, POINT, face, velocity, ImpactKind.KINETIC, 5000, 0.5D,
                1.0D, null);
    }
}
