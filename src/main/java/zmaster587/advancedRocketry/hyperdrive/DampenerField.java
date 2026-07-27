package zmaster587.advancedRocketry.hyperdrive;

import java.util.List;

import net.minecraft.util.math.BlockPos;

/**
 * What the gravity dampeners spare the crew when a flight ends badly.
 *
 * <p>A planned arrival costs nobody anything. An emergency exit — the window collapsing mid-flight —
 * dumps the transit's speed into everything aboard, and a dampener is what stands between that and a
 * crew smeared along the far bulkhead.</p>
 *
 * <p>Two levers, because one dampener for a shuttle and for a dreadnought is no lever at all:</p>
 * <ul>
 *   <li><b>Tier</b> decides how much speed a dampener eats. Above that, what is left through.</li>
 *   <li><b>Coverage</b> decides who is protected: a crew member is covered when a powered dampener
 *       is close enough to him at the moment the window fails. A big ship needs several.</li>
 * </ul>
 *
 * <p>Nothing here runs per tick. It is sampled once, at the exit.</p>
 */
public final class DampenerField {

    private DampenerField() {
    }

    /**
     * The speed left over after the dampeners covering {@code position} have taken their share.
     * Dampeners do not stack their absorption: the best one covering a body is the one protecting
     * it, so a wall of cheap dampeners never substitutes for a better one.
     */
    public static long residualSpeed(long exitSpeed, BlockPos position,
                                     List<BlockPos> poweredDampeners, long absorbedSpeed) {
        if (exitSpeed <= 0L || position == null || poweredDampeners == null) {
            return Math.max(0L, exitSpeed);
        }
        long radius = DriveTuning.DAMPENER_RADIUS;
        long radiusSq = radius * radius;
        boolean covered = false;
        for (BlockPos dampener : poweredDampeners) {
            if (dampener == null) {
                continue;
            }
            long dx = dampener.getX() - position.getX();
            long dy = dampener.getY() - position.getY();
            long dz = dampener.getZ() - position.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                covered = true;
                break;
            }
        }
        if (!covered) {
            return exitSpeed;
        }
        return Math.max(0L, exitSpeed - Math.max(0L, absorbedSpeed));
    }

    /**
     * The crew damage a residual speed does. Scaled like a fall: nothing at all below what the
     * dampeners absorbed, and rising with what got through.
     */
    public static float crewImpact(long residualSpeed) {
        if (residualSpeed <= 0L) {
            return 0.0F;
        }
        double damage = residualSpeed * DriveTuning.DAMPENER_RESIDUAL_DAMAGE_PER_SPEED;
        return (float) Math.min(Float.MAX_VALUE, damage);
    }
}
