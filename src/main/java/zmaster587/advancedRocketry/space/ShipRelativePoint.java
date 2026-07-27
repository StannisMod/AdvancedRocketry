package zmaster587.advancedRocketry.space;

import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;

/**
 * The one definition of a <b>ship-relative point</b>: a position on a ship expressed as an offset
 * from that ship's flight computer, and the two conversions that carry it to and from the world.
 *
 * <p><b>Why the flight computer.</b> A ship's blocks live in a subspace that is re-minted on every
 * re-assembly — a jump, an entry, a descent — so an absolute subspace coordinate names nothing the
 * moment the ship is rebuilt. The flight computer is the landmark that survives: it rides the
 * relocation with the rest of the ship's blocks, and it is what mints the ship's durable id
 * ({@link TileAdvancedFlightComputer#shipIdOrNull}). A point measured from it is therefore invariant
 * under the rigid relocation, exactly like the seat link offset the crew transfer matches seats by.</p>
 *
 * <p><b>Why the writer and the reader must share this class.</b> The offset is defined against the
 * computer's RAW BLOCK ORIGIN — {@code afcPos.getX()}, not its centre and not any mount-point
 * fudge. A hidden {@code +0.5} on one side of the round trip and not the other is a silent
 * half-block drift per save/load cycle, which is why both directions live here and nowhere else.</p>
 *
 * <p>Server-side in practice. Every method is a safe no-op ({@code null}) when the physics mod is
 * absent, the ship is not loaded, or the computer cannot be found.</p>
 */
public final class ShipRelativePoint {

    private ShipRelativePoint() { }

    /** The subspace point {@code offset} denotes on the ship whose computer sits at {@code afcPos}. */
    public static double[] subspacePointOf(BlockPos afcPos, double dx, double dy, double dz) {
        if (afcPos == null) {
            return null;
        }
        return new double[] {afcPos.getX() + dx, afcPos.getY() + dy, afcPos.getZ() + dz};
    }

    /** The offset of a subspace point from the computer at {@code afcPos} — the inverse of
     *  {@link #subspacePointOf}, and the only other place the origin convention is written down. */
    public static double[] offsetOfSubspacePoint(BlockPos afcPos, double sx, double sy, double sz) {
        if (afcPos == null) {
            return null;
        }
        return new double[] {sx - afcPos.getX(), sy - afcPos.getY(), sz - afcPos.getZ()};
    }

    /**
     * Where the ship-relative {@code (dx,dy,dz)} is in the WORLD right now, for the ship
     * {@code vsShipId} whose computer sits at subspace {@code afcPos}; {@code null} while that ship
     * is not loaded.
     */
    public static double[] worldPointOf(World world, String vsShipId, BlockPos afcPos,
                                        double dx, double dy, double dz) {
        double[] sub = subspacePointOf(afcPos, dx, dy, dz);
        if (sub == null || vsShipId == null) {
            return null;
        }
        return VSIntegration.toWorldFrameFor(world, vsShipId, sub[0], sub[1], sub[2]);
    }

    /**
     * The ship-relative offset of the WORLD point {@code (wx,wy,wz)} on the ship {@code vsShipId}
     * whose computer sits at subspace {@code afcPos}; {@code null} while that ship is not loaded.
     * This is the writer's half of the round trip.
     */
    public static double[] offsetOfWorldPoint(World world, String vsShipId, BlockPos afcPos,
                                              double wx, double wy, double wz) {
        if (vsShipId == null || afcPos == null) {
            return null;
        }
        double[] sub = VSIntegration.toShipFrameFor(world, vsShipId, wx, wy, wz);
        return sub == null ? null : offsetOfSubspacePoint(afcPos, sub[0], sub[1], sub[2]);
    }

    /**
     * The subspace position of the flight computer of the loaded ship {@code vsShipId}, or
     * {@code null} when that ship carries none / is not loaded.
     *
     * <p>A scan of the world's loaded tile entities filtered by type, NOT the shipyard block scan
     * {@link VSIntegration#flightComputerAt} performs: that one force-loads a chunk claim and reads
     * every block of a 256-high column, which is affordable once per crossing and not at all on a
     * per-second cadence. The type filter here rejects everything but the handful of flight
     * computers in the world before any physics query is made.</p>
     */
    public static BlockPos flightComputerOfShip(World world, String vsShipId) {
        if (world == null || vsShipId == null) {
            return null;
        }
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TileAdvancedFlightComputer)) {
                continue;
            }
            if (vsShipId.equals(VSIntegration.shipIdManagingBlock(world, te.getPos()))) {
                return te.getPos();
            }
        }
        return null;
    }

    /**
     * The subspace position of the flight computer carrying the DURABLE id {@code shipId}, or
     * {@code null} when no loaded ship in {@code world} has it. The reader's half: a returning
     * player's record names his ship by this id precisely because it outlives the physics mod's own
     * (re-minted) one. Same loaded-tile scan as {@link #flightComputerOfShip}.
     */
    public static BlockPos flightComputerOfDurableShip(World world, UUID shipId) {
        if (world == null || shipId == null) {
            return null;
        }
        for (TileEntity te : world.loadedTileEntityList) {
            if (te instanceof TileAdvancedFlightComputer
                    && shipId.equals(((TileAdvancedFlightComputer) te).shipIdOrNull())) {
                return te.getPos();
            }
        }
        return null;
    }
}
