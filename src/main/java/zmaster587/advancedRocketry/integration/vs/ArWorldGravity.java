package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.world.World;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.mod.common.config.VSConfig;

import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.WorldProviderSpaceSlot;

/**
 * The gravity vector a ship feels in a given world, in the units the physics solver spends.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The engine applies ONE gravity vector to every craft in every world. A planet's own
 * gravitational multiplier never reached it, so a tier-2 ship felt Earth gravity on the Moon, on a
 * gas giant and in the void of a space cell alike — and the only reason ships did not visibly fall
 * wrong was that the flight controller's feed-forward happened to cancel exactly what the solver was
 * about to add. That is a coincidence holding up a mechanic, not a mechanic.</p>
 *
 * <p>Thrust-to-weight, hover cost, braking authority and stopping distance are all statements ABOUT a
 * gravity field. Until the field varies, none of them can be designed — only invented.</p>
 *
 * <h2>One function, two callers, on purpose</h2>
 *
 * <p>The solver asks this when it applies gravity, and the flight computer asks it when it feeds
 * gravity forward. They MUST agree: the controller cancels what the solver adds, so two answers would
 * make a hovering craft climb or sink by their difference. Correcting gravity in the controller alone
 * was the rejected alternative for exactly this reason — it would leave every hull without a flight
 * computer at Earth gravity, and put the knowledge of what gravity IS in two places.</p>
 *
 * <h2>The three cases</h2>
 *
 * <ul>
 *   <li><b>A space cell</b> — zero. There is nothing to fall towards.</li>
 *   <li><b>A registered Advanced Rocketry world</b> — the configured vector scaled by that body's own
 *       gravitational multiplier, which is the same number the rocket tier has always flown by.</li>
 *   <li><b>Anything else</b> — the configured vector, unchanged. A vanilla or foreign world behaves
 *       exactly as it did before this existed, which is what makes this safe to apply to every craft
 *       rather than only to ours.</li>
 * </ul>
 *
 * <p>Scaling the CONFIGURED vector rather than a constant of our own is deliberate: it keeps one
 * source for what a standard gravity is worth, so the config still means what it says, and any X/Z
 * component somebody sets is carried through instead of silently dropped.</p>
 */
public final class ArWorldGravity {

    private ArWorldGravity() {}

    /** Nothing to fall towards. Allocated once; callers must not mutate what they are handed. */
    private static final Vector3dc WEIGHTLESS = new Vector3d();

    /**
     * The gravity vector for {@code world}, in blocks per wall-second squared — the same units the
     * engine's configured vector is in, because it is that vector scaled.
     *
     * <p>A null world gets the configured vector: this is called from inside the physics step, and a
     * craft with no world to ask about is one whose gravity should not suddenly change.</p>
     */
    public static Vector3dc of(World world) {
        if (world == null) {
            return VSConfig.gravity();
        }
        if (world.provider instanceof WorldProviderSpaceSlot) {
            return WEIGHTLESS;
        }
        // The null-returning lookup, never the lenient one: the lenient form answers with Earth's
        // properties for any dimension it does not know, which would quietly give a foreign mod's
        // world our idea of gravity instead of leaving it alone. Earth itself reaches the same
        // answer through the fallback below, since its multiplier is one.
        DimensionProperties properties = DimensionManager.getInstance()
                .getDimensionPropertiesOrNull(world.provider.getDimension());
        if (properties == null) {
            return VSConfig.gravity();
        }
        float multiplier = properties.getGravitationalMultiplier();
        if (multiplier < 0.0f || Float.isNaN(multiplier)) {
            // A body configured with nonsense would otherwise invert or NaN the force, and a NaN
            // reaches the solver as a craft that vanishes rather than as a number anybody can read.
            return VSConfig.gravity();
        }
        return VSConfig.gravity().mul(multiplier, new Vector3d());
    }
}
