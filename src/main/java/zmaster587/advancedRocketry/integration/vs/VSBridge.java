package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.apache.logging.log4j.Logger;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.block_relocation.BlockFinder;
import org.valkyrienskies.mod.common.ships.ship_world.WorldServerShipManager;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

/**
 * The Valkyrien Skies-facing side of the integration. Every reference to an
 * {@code org.valkyrienskies.*} type lives in this package's bridge classes,
 * never in {@link VSIntegration}. The JVM loads this class only when
 * {@link VSIntegration#isAvailable()} is true, so its VS imports never need to
 * resolve on an AR install without VS.
 */
final class VSBridge {

    private VSBridge() {}

    static void onValkyrienSkiesPresent(Logger logger) {
        // Touch a stable VS API type to anchor the compile dependency and to
        // prove, at runtime, that the VS classpath actually resolved.
        logger.info("Valkyrien Skies detected — true-spaceship integration active (API root: {}).",
                ValkyrienUtils.class.getName());
    }

    /**
     * Assemble the connected structure seeded at {@code anchorPos} into a movable
     * ship. This is the player-less equivalent of VS's
     * {@code assembleShipAsOrderedByPlayer}: create a ship keyed on the anchor
     * block, then queue VS to relocate every connected block into it. Called
     * server-side; VS performs the relocation on its own physics thread, so the
     * ship does not exist synchronously when this returns.
     *
     * <p>Scope note: this queues the block relocation only. Making the resulting
     * ship pilotable (thrust, attitude) is handled by the flight-control layer;
     * runtime behaviour can only be exercised with VS actually installed, not in a
     * headless test.</p>
     */
    static void assembleTier2Ship(World world, BlockPos anchorPos, Logger logger) {
        ShipData ship = ValkyrienUtils.createNewShip(world, anchorPos);
        WorldServerShipManager manager = ValkyrienUtils.getServerShipManager(world);
        manager.queueShipSpawn(ship, anchorPos, BlockFinder.BlockFinderType.FIND_ALL_BLOCKS);
        logger.info("Queued tier-2 ship assembly at {} (ship '{}').", anchorPos, ship.getName());
    }
}
