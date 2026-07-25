package zmaster587.advancedRocketry.test.unit;

import com.github.stannismod.affs.world.FieldSource;
import com.github.stannismod.affs.world.FieldZoneMath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Pins the D134-3 "responsible area" contract of {@link FieldZoneMath}: every surface point belongs to
 * its nearest emitter (a Voronoi partition over emitter centres). This is the substrate the zoned
 * balancing (per-zone drop/regen, and later redistribution) stands on, so its ownership rule must be
 * stable regardless of how the physics or render layers evolve.
 */
public class FieldZoneMathTest {

    /** A point clearly closer to one emitter than the others is owned by that emitter. */
    @Test
    public void nearestEmitterOwnsThePoint() {
        FieldSource west = emitter(0, 64, 0, 4);
        FieldSource east = emitter(20, 64, 0, 4);
        List<FieldSource> emitters = Arrays.asList(west, east);

        // A point at x=3 sits well inside the west emitter's half of the axis.
        assertSame("a point near the west emitter must be owned by it",
                west, FieldZoneMath.nearestEmitter(emitters, new Vec3d(3.5D, 64.5D, 0.5D)));
        // A point at x=17 sits well inside the east emitter's half.
        assertSame("a point near the east emitter must be owned by it",
                east, FieldZoneMath.nearestEmitter(emitters, new Vec3d(17.5D, 64.5D, 0.5D)));
    }

    /** The partition follows CENTRE distance, not radius — a bigger emitter does not annex farther points. */
    @Test
    public void ownershipIsByCentreDistanceNotRadius() {
        FieldSource small = emitter(0, 64, 0, 2);
        FieldSource big = emitter(20, 64, 0, 16);
        List<FieldSource> emitters = Arrays.asList(small, big);

        // x=8 is nearer the small emitter's centre (dist 8) than the big one's (dist 12), even though the
        // big emitter's radius reaches far past it. Voronoi is centre-based.
        assertSame("ownership must be decided by centre distance, not by field radius",
                small, FieldZoneMath.nearestEmitter(emitters, new Vec3d(8.5D, 64.5D, 0.5D)));
    }

    /** A tie resolves deterministically to the earliest emitter in the list. */
    @Test
    public void tieResolvesToEarliestEmitter() {
        FieldSource first = emitter(0, 64, 0, 4);
        FieldSource second = emitter(10, 64, 0, 4);
        List<FieldSource> emitters = Arrays.asList(first, second);

        // The block whose centre is equidistant from both centres (x=5.5, both at 5.0) resolves to first.
        assertEquals(0, FieldZoneMath.nearestEmitterIndex(emitters, new Vec3d(5.5D, 64.5D, 0.5D)));
    }

    /** No emitters ⇒ no owner (the field is off; a starved network owns nothing). */
    @Test
    public void emptyNetworkHasNoOwner() {
        assertNull(FieldZoneMath.nearestEmitter(Collections.<FieldSource>emptyList(), new Vec3d(0, 0, 0)));
        assertEquals(-1, FieldZoneMath.nearestEmitterIndex(Collections.<FieldSource>emptyList(), new Vec3d(0, 0, 0)));
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
