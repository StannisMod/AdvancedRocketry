package zmaster587.advancedRocketry.integration.vs;

import org.apache.logging.log4j.Logger;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

/**
 * The Valkyrien Skies-facing side of the integration. Every reference to an
 * {@code org.valkyrienskies.*} type lives in this package's bridge classes,
 * never in {@link VSIntegration}. The JVM loads this class only when
 * {@link VSIntegration#isAvailable()} is true, so its VS imports never need to
 * resolve on an AR install without VS.
 *
 * <p>Phase 1 scaffold: this currently only anchors the VS compile dependency and
 * confirms, at runtime, that the VS classpath resolved. Real ship assembly,
 * thrust, and gravity bridging land in later phases — see TASK-47. The chosen
 * entry points (for later): {@code ValkyrienUtils.assembleShipAsOrderedByPlayer}
 * / {@code createNewShip} for assembly, {@code IPhysicsBlockController} +
 * {@code PhysicsCalculations.addForceAtPointNew} for thrust.</p>
 */
final class VSBridge {

    private VSBridge() {}

    static void onValkyrienSkiesPresent(Logger logger) {
        // Touch a stable VS API type to anchor the compile dependency and to
        // prove, at runtime, that the VS classpath actually resolved.
        logger.info("Valkyrien Skies detected — true-spaceship integration active (API root: {}).",
                ValkyrienUtils.class.getName());
    }
}
