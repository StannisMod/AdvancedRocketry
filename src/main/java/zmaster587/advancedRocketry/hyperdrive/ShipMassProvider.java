package zmaster587.advancedRocketry.hyperdrive;

import java.util.UUID;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * How heavy a ship is — the one number the speed formula needs and the build does not yet produce.
 *
 * <p>This is a seam, on purpose and in exactly one method. The formula that consumes it, its tests,
 * the forecast the pilot reads and the transit that flies at the resulting speed are all real; what
 * is not real yet is the survey that would weigh a hull block by block. When that arrives it
 * replaces {@link #massOf} and nothing else moves.</p>
 *
 * <p>A placeholder that returns a plausible constant is the honest shape here. The alternative —
 * leaving the speed formula unbuilt until mass exists — would mean shipping a jump whose duration
 * is a magic number, and then having to rewrite every caller anyway.</p>
 */
public final class ShipMassProvider {

    private ShipMassProvider() {
    }

    /**
     * The mass of the ship anchored at {@code anchor}. Every hull currently answers the same, which
     * is why a light ship and a heavy one fly at the same speed today — the drive is the only lever
     * until this method learns to weigh a hull.
     */
    public static long massOf(World world, BlockPos anchor, UUID shipId) {
        return DriveTuning.PLACEHOLDER_SHIP_MASS;
    }

    /** Whether mass is still the placeholder. Read by the readouts, so the pilot is not misled. */
    public static boolean isPlaceholder() {
        return true;
    }
}
