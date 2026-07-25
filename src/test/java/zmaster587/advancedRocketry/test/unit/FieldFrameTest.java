package zmaster587.advancedRocketry.test.unit;

import com.github.stannismod.affs.world.FieldFrame;
import com.github.stannismod.affs.world.FieldFrames;
import com.github.stannismod.affs.world.WorldFieldFrame;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Pins the standalone (identity) leg of the {@link FieldFrame} seam (§4.3). The ship leg
 * ({@code ShipFieldFrame}) delegates to Valkyrien Skies and is verified at the client tier on a loaded
 * ship (the headless server cannot load a VS ship), but the identity leg — the base case that must
 * never break — is pure and unit-testable here.
 */
public class FieldFrameTest {

    /** The world frame is the identity: field coordinates ARE world coordinates. */
    @Test
    public void worldFrameIsIdentity() {
        FieldFrame frame = WorldFieldFrame.INSTANCE;
        Vec3d mapped = frame.fieldToWorld(12.5D, 64.5D, -7.5D);
        assertEquals(12.5D, mapped.x, 1.0E-9D);
        assertEquals(64.5D, mapped.y, 1.0E-9D);
        assertEquals(-7.5D, mapped.z, 1.0E-9D);
    }

    /** A standalone shell is static — it has zero surface velocity, so nothing is billed for "hull motion". */
    @Test
    public void worldFrameSurfaceIsStatic() {
        Vec3d v = WorldFieldFrame.INSTANCE.surfaceVelocityAt(100.0D, 70.0D, 200.0D);
        assertEquals(0.0D, v.x, 1.0E-9D);
        assertEquals(0.0D, v.y, 1.0E-9D);
        assertEquals(0.0D, v.z, 1.0E-9D);
    }

    /** The identity frame always resolves — a standalone shield never degrades to off for frame reasons. */
    @Test
    public void worldFrameIsAlwaysReady() {
        assertTrue(WorldFieldFrame.INSTANCE.isReady());
    }

    /** With no world (and, in the harness, no VS), the resolver returns the identity frame — a shield
     *  is standalone unless a ship actively claims its block. */
    @Test
    public void resolverDefaultsToWorldFrameOffAnyShip() {
        assertSame(WorldFieldFrame.INSTANCE, FieldFrames.forBlock(null, null));
    }
}
