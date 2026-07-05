package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.entity.IFlightBackend;

/**
 * Soft-dependency gate for Valkyrien Skies.
 *
 * <p>AR depends on VS <em>optionally</em>: we compile against the VS API
 * ({@code compileOnly}) but never bundle or require it. The whole true-spaceship
 * feature lights up only when the user also installs VS; without VS, AR must boot
 * and behave exactly as before.</p>
 *
 * <p><b>Boundary rule — do not break:</b> this class MUST NOT import or reference
 * any {@code org.valkyrienskies.*} type, so it is always safe for the JVM to
 * load. Every VS-touching call goes through {@link VSBridge}, which is reached
 * only behind {@link #isAvailable()} — so a VS-importing class is never loaded on
 * an AR install without VS, and there is no {@code NoClassDefFoundError}. The
 * unit test {@code VSIntegrationTest} pins this contract. AR compiles against VS
 * but never requires it (a soft, optional dependency).</p>
 */
public final class VSIntegration {

    /** Valkyrien Skies Core mod id (the 1.12.2 line). */
    public static final String MODID = "valkyrienskies";

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/vs");

    private static Boolean available;

    private VSIntegration() {}

    /**
     * Whether Valkyrien Skies is installed. Defensive: any failure to consult
     * Forge's {@link Loader} (e.g. a non-FML test environment) is treated as
     * "VS absent" rather than propagating. The result is cached after the first
     * successful query.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached == null) {
            try {
                cached = Loader.isModLoaded(MODID);
            } catch (Throwable t) {
                cached = Boolean.FALSE;
            }
            available = cached;
        }
        return cached;
    }

    /**
     * Initialise the VS integration. A safe no-op when VS is absent. Call once
     * during AR init.
     */
    public static void init() {
        if (!isAvailable()) {
            LOGGER.info("Valkyrien Skies not present — true-spaceship features disabled.");
            return;
        }
        // Only here, behind the gate, do we touch a VS-importing class.
        VSBridge.onValkyrienSkiesPresent(LOGGER);
    }

    /**
     * Assemble the structure anchored at {@code anchorPos} into a movable ship.
     * A safe no-op when Valkyrien Skies is absent. Only vanilla/AR types appear in
     * this signature — every VS-importing call stays inside {@link VSBridge}, which
     * is reached only past the {@link #isAvailable()} gate, so no VS class is
     * loaded on an AR install without VS.
     */
    public static void assembleTier2Ship(World world, BlockPos anchorPos) {
        if (!isAvailable()) {
            return;
        }
        VSBridge.assembleTier2Ship(world, anchorPos, LOGGER);
    }

    /**
     * Create a flight backend that drives the Valkyrien Skies ship anchored at
     * {@code anchorPos} as a velocity setpoint (model A), or {@code null} when VS is
     * absent. The return type is the AR-core {@link IFlightBackend}, so a caller in
     * AR core (e.g. the Advanced Flight Computer tile) never references a VS type —
     * the VS-importing {@code VSFlightBackend} is loaded only past this gate.
     */
    public static IFlightBackend createShipFlightBackend(World world, BlockPos anchorPos) {
        if (!isAvailable()) {
            return null;
        }
        return new VSFlightBackend(world, anchorPos);
    }

    /**
     * The body&rarr;world attitude of the Valkyrien Skies ship managing the block at
     * {@code pos}, or {@code null} when VS is absent or no ship manages it. Returns
     * the AR-core {@link FreeFlightPhysics.Quat} so a caller in AR core never sees a
     * VS type. Free Flight integrates the pilot's body rates over this each tick.
     */
    public static FreeFlightPhysics.Quat getShipAttitude(World world, BlockPos pos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.getShipAttitude(world, pos);
    }
}
