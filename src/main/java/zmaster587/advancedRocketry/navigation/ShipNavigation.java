package zmaster587.advancedRocketry.navigation;

import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import zmaster587.advancedRocketry.hyperdrive.JumpSpeed;
import zmaster587.advancedRocketry.hyperdrive.JumpWindow;
import zmaster587.advancedRocketry.hyperdrive.ShipDrive;
import zmaster587.advancedRocketry.hyperdrive.ShipDriveStats;
import zmaster587.advancedRocketry.hyperdrive.ShipMassProvider;
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

    @Override
    public boolean targetResolved() {
        TileNavigationComputer nav = findNavComputer();
        return nav == null || nav.isTargetResolved();
    }

    /** Where the ledger says this ship is — the gate's "am I already there?" clause reads this. */
    @Override
    public GalacticCoord currentCell() {
        return currentCoord();
    }

    // ─── What the drive answers ────────────────────────────────────────────────

    @Override
    public long drivePower() {
        return drive().stats().drivePower();
    }

    @Override
    public long burstCost() {
        return drive().stats().burstCost();
    }

    @Override
    public int capacitorCount() {
        return drive().capacitors().size();
    }

    @Override
    public long capacitorCapacity() {
        return drive().capacitorCapacity();
    }

    @Override
    public long capacitorCharge() {
        return drive().capacitorCharge(SpaceSubsystem.spaceClock());
    }

    @Override
    public long hullOutsideWindow() {
        JumpWindow.Coverage coverage = drive().coverage();
        // A craft whose hull extent was never recorded is not a craft with a hull sticking out of
        // its window - it is a craft nobody has measured. Warning about it would be inventing a
        // fault, so an unmeasured hull raises no objection.
        return coverage == null ? 0L : coverage.uncoveredBlocks();
    }

    @Override
    public long storedEnergy() {
        return drive().storedEnergy();
    }

    @Override
    public long flightEnergyCost() {
        ShipDriveStats stats = drive().stats();
        if (!stats.present()) {
            return 0L;
        }
        return stats.inFlightDraw() * plannedTransitTicks();
    }

    /** The ship's drive, resolved fresh — a drive is measured when asked, never remembered. */
    public ShipDrive drive() {
        return new ShipDrive(world, flightComputerPos);
    }

    /** Blocks per tick this ship would fly at, given its drive and its hull. */
    public long plannedSpeed() {
        return JumpSpeed.blocksPerTick(drive().stats().drivePower(),
                ShipMassProvider.massOf(world, flightComputerPos, shipId));
    }

    /** How long the flight to the current target would take, in ticks. Zero without a target. */
    public long plannedTransitTicks() {
        GalacticCoord target = target();
        GalacticCoord origin = currentCoord();
        if (target == null || origin == null) {
            return 0L;
        }
        return JumpSpeed.transitTicks(
                SpaceSubsystem.frames().distanceBetween(origin, target, SpaceSubsystem.spaceClock()),
                plannedSpeed());
    }

    /** Where the ship is now, as the durable ledger records it, or {@code null}. */
    public GalacticCoord currentCoord() {
        ShipLedger ledger = SpaceSubsystem.ledger();
        if (ledger == null || shipId == null) {
            return null;
        }
        ShipLedger.Entry entry = ledger.get(shipId);
        return entry == null ? null : entry.coord;
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
