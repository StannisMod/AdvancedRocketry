package zmaster587.advancedRocketry.navigation;

import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.SpaceSubsystem;
import zmaster587.advancedRocketry.tile.TileNavigationComputer;

/**
 * What one real ship can answer about its ability to jump — the production side of
 * {@link JumpGate.ShipContext}.
 *
 * <p>A ship is identified the way everything else in the space layer identifies it: by its flight
 * computer's block and its durable id. The navigation computer is found by searching the ship's own
 * subspace claim for a computer that points back at THIS flight computer, so a second ship parked
 * alongside can never lend its navigation.</p>
 */
public final class ShipNavigation implements JumpGate.ShipContext {

    private final World world;
    private final BlockPos flightComputerPos;
    private final UUID shipId;

    public ShipNavigation(World world, BlockPos flightComputerPos, UUID shipId) {
        this.world = world;
        this.flightComputerPos = flightComputerPos;
        this.shipId = shipId;
    }

    @Override
    public boolean hasNavComputer() {
        return findNavComputer() != null;
    }

    @Override
    public boolean positionKnown() {
        ShipLedger ledger = SpaceSubsystem.ledger();
        return ledger == null || shipId == null || ledger.isPositionKnown(shipId);
    }

    @Override
    public GalacticCoord target() {
        TileNavigationComputer nav = findNavComputer();
        return nav == null ? null : nav.getTarget();
    }

    /**
     * The navigation computer of THIS ship, or {@code null}. Searched inside the ship's own subspace
     * claim — the only region whose blocks belong to it — and confirmed by the computer's own link
     * back to this flight computer.
     */
    public TileNavigationComputer findNavComputer() {
        if (world == null || flightComputerPos == null) {
            return null;
        }
        AxisAlignedBB yard = VSIntegration.shipyardBoundsAt(world,
                flightComputerPos.getX() + 0.5, flightComputerPos.getY() + 0.5,
                flightComputerPos.getZ() + 0.5);
        for (TileEntity te : world.loadedTileEntityList.toArray(new TileEntity[0])) {
            if (!(te instanceof TileNavigationComputer)) {
                continue;
            }
            TileNavigationComputer nav = (TileNavigationComputer) te;
            if (yard != null && !withinXZ(yard, nav.getPos())) {
                continue; // some other ship's computer, or one sitting on a planet
            }
            if (flightComputerPos.equals(nav.getFlightComputerPos())) {
                return nav;
            }
        }
        return null;
    }

    /** The claim gives an XZ region; a ship's blocks never leave their own claim's footprint. */
    private static boolean withinXZ(AxisAlignedBB yard, BlockPos pos) {
        return pos.getX() >= yard.minX && pos.getX() <= yard.maxX
                && pos.getZ() >= yard.minZ && pos.getZ() <= yard.maxZ;
    }
}
