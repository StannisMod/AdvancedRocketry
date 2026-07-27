package zmaster587.advancedRocketry.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

/**
 * A block that belongs to one ship and has to keep belonging to it.
 *
 * <p>Every machine a ship's flight depends on has the same problem: it must be able to say WHICH
 * ship it is part of, and the answer has to survive the ship being cut out of the world and pasted
 * somewhere else — which happens on every assembly, every entry into space and every jump. An
 * absolute position survives none of those. A position RELATIVE to the flight computer survives all
 * of them, because a ship's blocks move as one rigid body.</p>
 *
 * <p>So the link is an offset, recorded once by the assembler while the whole craft is still sitting
 * on the pad, and read back with a plain lookup at {@code pos + offset} afterwards. A machine that
 * was never assembled into a ship — one sitting in a workshop, or a spare on a shelf — simply has no
 * link, and nothing on a ship will ever mistake it for part of one.</p>
 */
public abstract class TileShipComponent extends TileEntity {

    private static final String NBT_AFC_OFFSET = "afcOffset";
    private static final String NBT_HAS_AFC = "afcLinked";

    /** Offset from this block to its flight computer; {@code null} until the assembler links them. */
    private BlockPos flightComputerOffset;

    /**
     * Bind this block to the flight computer at {@code flightComputerPos}. Called on the pad, before
     * anything has moved.
     */
    public void linkToFlightComputer(BlockPos flightComputerPos) {
        this.flightComputerOffset =
                flightComputerPos == null ? null : flightComputerPos.subtract(pos);
        markDirty();
        if (world != null && !world.isRemote) {
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }

    /** The linked flight computer's position, or {@code null} when this block is not on a ship. */
    public BlockPos getFlightComputerPos() {
        return flightComputerOffset == null ? null : pos.add(flightComputerOffset);
    }

    /** Whether this block was assembled into a ship at all. */
    public boolean isLinked() {
        return flightComputerOffset != null;
    }

    /** Whether this block belongs to the ship whose flight computer is at {@code afcPos}. */
    public boolean belongsTo(BlockPos afcPos) {
        return afcPos != null && afcPos.equals(getFlightComputerPos());
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean(NBT_HAS_AFC, flightComputerOffset != null);
        if (flightComputerOffset != null) {
            nbt.setLong(NBT_AFC_OFFSET, flightComputerOffset.toLong());
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        flightComputerOffset = nbt.getBoolean(NBT_HAS_AFC)
                ? BlockPos.fromLong(nbt.getLong(NBT_AFC_OFFSET))
                : null;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(super.getUpdateTag());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }
}
