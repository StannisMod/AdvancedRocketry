package zmaster587.advancedRocketry.test.unit;

import com.github.stannismod.affs.world.FieldSource;
import com.github.stannismod.affs.world.FieldSurfaceMath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the ray/shell entry contract ({@link FieldSurfaceMath#raySphereEntry} /
 * {@link FieldSurfaceMath#rayShellEntry}) the cooperative strike service and the residual ray hook stand
 * on (D134-2). The load-bearing rule is that only a ray entering the shell <em>from outside</em> is
 * intercepted — an outgoing or interior ray never is, so a shooter is not billed by its own shield and
 * interaction among blocks inside the shell is unaffected.
 */
public class FieldStrikeMathTest {

    private static final double EPS = 1.0E-6D;

    /** A ray aimed at the centre from outside crosses the shell at (centreDistance - radius). */
    @Test
    public void inwardRayCrossesShellAtNearSurface() {
        Vec3d center = new Vec3d(0.0D, 64.0D, 0.0D);
        Vec3d origin = new Vec3d(10.0D, 64.0D, 0.0D);
        Vec3d dir = new Vec3d(-1.0D, 0.0D, 0.0D); // toward the centre
        double t = FieldSurfaceMath.raySphereEntry(center, 4.0D, origin, dir, 20.0D);
        assertEquals("entry distance must be centreDistance(10) - radius(4)", 6.0D, t, EPS);
    }

    /** A ray pointing away from the sphere never crosses it (no negative-distance hit). */
    @Test
    public void rayPointingAwayNeverHits() {
        Vec3d center = new Vec3d(0.0D, 64.0D, 0.0D);
        Vec3d origin = new Vec3d(10.0D, 64.0D, 0.0D);
        Vec3d dir = new Vec3d(1.0D, 0.0D, 0.0D); // away from the centre
        assertEquals(-1.0D, FieldSurfaceMath.raySphereEntry(center, 4.0D, origin, dir, 20.0D), EPS);
    }

    /** An origin inside the shell is never an interception — outgoing / interior rays pass freely. */
    @Test
    public void interiorOriginIsNeverIntercepted() {
        Vec3d center = new Vec3d(0.0D, 64.0D, 0.0D);
        Vec3d origin = new Vec3d(1.0D, 64.0D, 0.0D); // well inside radius 4
        // Whichever way it points, an interior ray is not billed.
        assertEquals(-1.0D, FieldSurfaceMath.raySphereEntry(center, 4.0D, origin, new Vec3d(1, 0, 0), 20.0D), EPS);
        assertEquals(-1.0D, FieldSurfaceMath.raySphereEntry(center, 4.0D, origin, new Vec3d(-1, 0, 0), 20.0D), EPS);
    }

    /** A ray that passes wide of the sphere (miss) returns no hit. */
    @Test
    public void rayMissingTheSphereReturnsNoHit() {
        Vec3d center = new Vec3d(0.0D, 64.0D, 0.0D);
        Vec3d origin = new Vec3d(10.0D, 64.0D, 10.0D); // offset by 10 on Z, radius only 4
        Vec3d dir = new Vec3d(-1.0D, 0.0D, 0.0D);
        assertEquals(-1.0D, FieldSurfaceMath.raySphereEntry(center, 4.0D, origin, dir, 20.0D), EPS);
    }

    /** A crossing beyond the ray's reach (short maxDist) is not counted. */
    @Test
    public void crossingBeyondMaxDistanceIsNotCounted() {
        Vec3d center = new Vec3d(0.0D, 64.0D, 0.0D);
        Vec3d origin = new Vec3d(10.0D, 64.0D, 0.0D);
        Vec3d dir = new Vec3d(-1.0D, 0.0D, 0.0D);
        // Entry is at 6; a 3-block ray never reaches it.
        assertEquals(-1.0D, FieldSurfaceMath.raySphereEntry(center, 4.0D, origin, dir, 3.0D), EPS);
    }

    /** {@link FieldSurfaceMath#rayShellEntry} resolves the source's world centre and agrees with the raw form. */
    @Test
    public void rayShellEntryUsesSourceWorldCentre() {
        FieldSource source = emitter(0, 64, 0, 4);
        double t = FieldSurfaceMath.rayShellEntry(source,
                new Vec3d(0.5D, 64.5D, 10.5D), new Vec3d(0, 0, -1), 20.0D);
        // Source centre is the block centre (0.5, 64.5, 0.5); origin is 10 blocks out on +Z, radius 4.
        assertTrue("expected an inward crossing near distance 6, got " + t, Math.abs(t - 6.0D) < 1.0E-3D);
    }

    private static FieldSource emitter(final int x, final int y, final int z, final int radius) {
        final BlockPos pos = new BlockPos(x, y, z);
        return new FieldSource() {
            @Override
            public BlockPos getPos() {
                return pos;
            }

            @Override
            public int getRadius() {
                return radius;
            }
        };
    }
}
