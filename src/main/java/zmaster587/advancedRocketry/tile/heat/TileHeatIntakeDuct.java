package zmaster587.advancedRocketry.tile.heat;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

import zmaster587.advancedRocketry.atmosphere.AirState;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;

/**
 * The air-intake duct: what gives a chiller a cold side made of COMPARTMENT AIR instead of coolant.
 * <p>
 * <b>It is not plumbing and it stores nothing.</b> A chiller on its own talks only to coolant loops;
 * bolt one of these to its cold face and the machine draws from the room instead. Everything the duct
 * does is answer one question — which zone am I breathing from — so that the thermal system can take
 * heat out of that zone's air and the chiller can pay to put it somewhere hotter.
 * <p>
 * <b>Where it sits is the whole interface.</b> It occupies the chiller's cold face, which is the
 * position the machine already computes for the loop it would otherwise draw from. That makes the two
 * kinds of cold side mutually exclusive by geometry rather than by a precedence rule nobody would
 * remember: the block in that place is either coolant or a duct, and whichever it is decides what the
 * chiller is cooling.
 * <p>
 * <b>The zone it serves is a NEIGHBOUR, never its own position.</b> A zone is made of air cells and
 * the block occupying a solid position is by definition not one of them, so a duct set into a wall
 * would otherwise resolve nothing and sit idle forever. This is the same rule the recirculator learnt
 * the hard way, and it gives the natural behaviour for a duct built into a partition: it breathes from
 * one of the rooms it touches rather than from neither.
 */
public class TileHeatIntakeDuct extends TileEntity {

    /**
     * The air cell this duct breathes from, or null when it touches no zone at all — a duct in the
     * open, or one whose room has been opened to vacuum.
     */
    @Nullable
    public BlockPos findServedCell() {
        if (world == null || world.isRemote)
            return null;
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        if (handler == null)
            return null;
        for (EnumFacing dir : EnumFacing.VALUES) {
            BlockPos side = pos.offset(dir);
            if (handler.getAirStateAt(side) != null)
                return side;
        }
        return null;
    }

    /** The air this duct is drawing on, or null when it is not touching a zone. */
    @Nullable
    public AirState getServedAir() {
        BlockPos cell = findServedCell();
        if (cell == null)
            return null;
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        return handler == null ? null : handler.getAirStateAt(cell);
    }

    /** How many blocks of air that zone is, which is what decides how much heat it takes to move it. */
    public int getServedVolume() {
        BlockPos cell = findServedCell();
        if (cell == null || world == null)
            return 0;
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        return handler == null ? 0 : handler.getBlobSizeAt(cell);
    }

    /** The duct at {@code pos}, or null if that block is anything else. */
    @Nullable
    public static TileHeatIntakeDuct at(net.minecraft.world.World world, BlockPos pos) {
        if (world == null || pos == null || !world.isBlockLoaded(pos))
            return null;
        TileEntity tile = world.getTileEntity(pos);
        return tile instanceof TileHeatIntakeDuct ? (TileHeatIntakeDuct) tile : null;
    }
}
