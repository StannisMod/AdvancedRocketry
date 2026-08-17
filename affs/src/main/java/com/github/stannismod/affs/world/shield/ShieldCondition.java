package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.config.ModConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.damage.DamageState;

/**
 * What a shield block's CONDITION does to what it delivers.
 *
 * <p>The stage is PULLED from the world, never pushed: nothing tells a shield generator it was shot.
 * A node reads the stage of the block it lives in when it needs it, which is one map lookup, and
 * which survives a save, a chunk reload and a hull being reassembled for free — because the stage
 * does. Nothing about damage appears in the network solve, and nothing about shields appears in the
 * damage engine.</p>
 *
 * <p>Two different consequences live here because a shield has two kinds of organ:</p>
 * <ul>
 *   <li>a <b>scalar</b> node — a generator converts less, a cable carries less, an accumulator holds
 *       less — which is the same shape a worn motor's thrust already has;</li>
 *   <li>an <b>emitter</b>, whose consequence is its RADIUS. A shrinking sphere is the one failure a
 *       player can see BEFORE it matters: the shell draws in, zone ownership shifts, and a stretch
 *       of hull that used to be covered stops being covered while there is still time to react.</li>
 * </ul>
 *
 * <p>Neither is gated on the wear config flag. That flag gates where wear ACCRUES; a consequence read
 * unconditionally is the whole reason a ship shot to pieces stops working, and gating it would let a
 * modpack that turned wear off field indestructible shields.</p>
 */
public final class ShieldCondition {

    private ShieldCondition() {
    }

    /**
     * How much of its rated delivery a scalar shield node still provides: 1.0 pristine, falling to
     * {@code 1 - maxPenalty} at the last stage before destruction. Never negative — a wrecked node
     * delivers nothing, it does not consume.
     */
    public static double scale(double damageFraction, double maxPenalty) {
        if (maxPenalty <= 0.0D) {
            return 1.0D;
        }
        double fraction = damageFraction < 0.0D ? 0.0D : (damageFraction > 1.0D ? 1.0D : damageFraction);
        double factor = 1.0D - maxPenalty * fraction;
        return factor < 0.0D ? 0.0D : factor;
    }

    /**
     * The radius a damaged emitter actually projects, given the one it was DECLARED to hold.
     *
     * <p>Rounded DOWN so that any real damage is visible rather than absorbed by rounding, and floored
     * at {@code minRadius}: an emitter that still stands still projects something, and the rung below
     * that is destruction, which removes the block.</p>
     */
    public static int shrinkRadius(int declaredRadius, double damageFraction, double maxPenalty, int minRadius) {
        int shrunk = (int) Math.floor(declaredRadius * scale(damageFraction, maxPenalty));
        return Math.max(minRadius, Math.min(declaredRadius, shrunk));
    }

    /** Delivery factor for the scalar node at {@code pos}, read from the world's own damage record. */
    public static double deliveryFactor(World world, BlockPos pos) {
        return scale(DamageState.getDamageFraction(world, pos), ModConfig.shieldNodeDamagePenaltyMax);
    }

    /** Scale a scalar node's rated per-tick figure by the condition of the block at {@code pos}. */
    public static int derate(World world, BlockPos pos, int rated) {
        if (rated <= 0) {
            return 0;
        }
        return (int) Math.round(rated * deliveryFactor(world, pos));
    }

    /** The radius the emitter at {@code pos} actually projects, given its declared one. */
    public static int effectiveRadius(World world, BlockPos pos, int declaredRadius, int minRadius) {
        return shrinkRadius(declaredRadius, DamageState.getDamageFraction(world, pos),
                ModConfig.emitterRadiusDamagePenaltyMax, minRadius);
    }
}
