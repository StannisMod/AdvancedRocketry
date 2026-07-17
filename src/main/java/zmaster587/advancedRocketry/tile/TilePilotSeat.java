package zmaster587.advancedRocketry.tile;

import io.netty.buffer.ByteBuf;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;

import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.libVulpes.util.INetworkMachine;

/**
 * Pilot seat for a tier-2 (Valkyrien Skies) ship: the in-world control station that hands a
 * seated player's Free Flight input to the ship's {@link TileAdvancedFlightComputer}.
 *
 * <p>A seat is <em>linked</em> to its computer at assembly time, when both blocks are still on
 * the pad: the launch-pad assembler stores the computer's position relative to the seat
 * ({@link #linkToFlightComputer}). That relative offset is invariant under the rigid
 * translation the physics mod applies when it relocates the craft into a ship (the ship's
 * blocks keep stable positions in the ship subspace), so at runtime the seat recovers its
 * computer with a plain {@code getTileEntity} at {@code seatPos + offset} — no physics-mod type
 * is referenced here, keeping the soft dependency intact.</p>
 *
 * <p>Control flow: the piloting client samples its Free Flight input each tick and sends it to
 * this seat as a {@code PacketMachine}; server-side {@link #useNetworkData} forwards it to the
 * linked computer's {@link TileAdvancedFlightComputer#setPilotInput}. The computer's own server
 * tick turns that into the force/torque the ship flies under.</p>
 */
public class TilePilotSeat extends TileEntity implements INetworkMachine {

    private static final String NBT_LINKED = "afcLinked";
    private static final String NBT_DX = "afcDx";
    private static final String NBT_DY = "afcDy";
    private static final String NBT_DZ = "afcDz";
    /** Sync-only: the linked computer's Flight-Assist flag, piggybacked on the seat's update tag
     *  so the piloting client's HUD shows the true value (the flag itself lives on the AFC). */
    private static final String NBT_FA = "afcFa";

    /** Control-packet id: the seated pilot's Free Flight input. */
    public static final byte PACKET_PILOT_INPUT = 0;
    /** Control-packet id: toggle the linked flight computer's Flight Assist on/off (no payload). */
    public static final byte PACKET_FLIGHT_ASSIST_TOGGLE = 1;
    /** Control-packet id: toggle the linked flight computer's AUTO-TAKEOFF autopilot (no payload). */
    public static final byte PACKET_AUTO_TAKEOFF_TOGGLE = 2;

    /** World-frame fallback range (blocks²) for accepting a control packet, used only when the
     *  rider's block position does not already match the seat (see {@link #isPilotOf}). */
    private static final double PILOT_RANGE_SQ = 36.0;

    private boolean linked = false;
    private int afcDx, afcDy, afcDz;

    /**
     * Client-only cache of the linked computer's Flight-Assist state, synced from the server via
     * the seat's update tag ({@link #getUpdateTag}). The piloting client's HUD reads this so it
     * shows the real on/off value instead of a hard-coded one. Server-authoritative - the truth
     * lives on {@link TileAdvancedFlightComputer#isFlightAssistEnabled()}.
     */
    private boolean clientFlightAssistOn = true;

    /**
     * Client-only: the Free Flight input queued for the next control packet. The piloting
     * client sets this immediately before sending a {@code PacketMachine} to this seat, and
     * {@link #writeDataToNetwork} serialises it. Never read server-side.
     */
    public FreeFlightInput pendingInput;

    /**
     * Record the flight computer's position as an offset from this seat, so it survives the
     * ship relocation. Called on the pad (both blocks at their build positions) by the assembler.
     */
    public void linkToFlightComputer(BlockPos afcPos) {
        this.afcDx = afcPos.getX() - pos.getX();
        this.afcDy = afcPos.getY() - pos.getY();
        this.afcDz = afcPos.getZ() - pos.getZ();
        this.linked = true;
        markDirty();
        // Push the linked state to clients so the piloting client knows this seat steers a ship.
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    /** Whether this seat has been linked to a flight computer (i.e. it belongs to a tier-2 ship). */
    public boolean isLinked() {
        return linked;
    }

    /** Client-side: the linked computer's Flight-Assist state, as last synced from the server.
     *  Used by the Free Flight HUD to show the true on/off value. */
    public boolean isFlightAssistOn() {
        return clientFlightAssistOn;
    }

    /**
     * The pilot seat a {@code riding} entity belongs to, or {@code null} if it is not a pilot-seat
     * mount. Resolves via the dummy's bound {@link EntityDummy#getSeatPos() seat position} — NOT
     * its own world position, which on a Valkyrien Skies ship differs from the seat block's
     * ship-subspace position. Falls back to the dummy's block position for an unbound (ordinary)
     * mount. Shared by every "is the player piloting a ship" check (input, HUD, key context).
     */
    public static TilePilotSeat forRider(Entity riding, World world) {
        if (!(riding instanceof EntityDummy) || world == null) {
            return null;
        }
        BlockPos seatPos = ((EntityDummy) riding).getSeatPos();
        if (seatPos == null) {
            seatPos = new BlockPos(riding);
        }
        TileEntity te = world.getTileEntity(seatPos);
        return te instanceof TilePilotSeat ? (TilePilotSeat) te : null;
    }

    /**
     * The linked flight computer's position (this seat's position plus the stored offset), or
     * {@code null} when unlinked. Pure — no world lookup — so the offset contract that must
     * survive the physics-mod relocation (a constant relative offset) is directly checkable.
     */
    public BlockPos getFlightComputerPos() {
        return linked ? pos.add(afcDx, afcDy, afcDz) : null;
    }

    /** The linked flight computer, or {@code null} if unlinked or it is no longer at the offset. */
    public TileAdvancedFlightComputer getFlightComputer() {
        BlockPos afcPos = getFlightComputerPos();
        if (afcPos == null || world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(afcPos);
        return te instanceof TileAdvancedFlightComputer ? (TileAdvancedFlightComputer) te : null;
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == PACKET_PILOT_INPUT) {
            (pendingInput != null ? pendingInput : FreeFlightInput.zero()).write(out);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        if (packetId == PACKET_PILOT_INPUT) {
            FreeFlightInput input = FreeFlightInput.read(in);
            nbt.setFloat("ffFwd", input.throttleForward);
            nbt.setFloat("ffVert", input.throttleVertical);
            nbt.setFloat("ffStrafe", input.strafeInput);
            nbt.setFloat("ffYaw", input.yawInput);
            nbt.setFloat("ffPitch", input.pitchInput);
            nbt.setFloat("ffRoll", input.rollInput);
            nbt.setFloat("ffBrake", input.brakeInput);
            nbt.setBoolean("ffCut", input.cutActive);
        }
    }

    /**
     * Whether {@code player} is the pilot seated on THIS seat, checked frame-agnostically so it
     * holds on a Valkyrien Skies ship where the seat lives in a distant subspace. On a ship the
     * rider's world position and the seat's subspace position can be thousands of blocks apart, so
     * a plain world-distance gate wrongly rejects every packet. Instead accept when the sender
     * rides the seat's dummy (its block position matches this seat — subspace-safe), OR, as a
     * world-frame fallback, when the sender is within {@link #PILOT_RANGE_SQ} of the seat.
     */
    private boolean isPilotOf(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        Entity riding = player.getRidingEntity();
        if (riding instanceof EntityDummy) {
            BlockPos seatPos = ((EntityDummy) riding).getSeatPos();
            if (seatPos == null) {
                seatPos = new BlockPos(riding);
            }
            if (seatPos.equals(pos)) {
                return true;
            }
        }
        return player.getDistanceSqToCenter(pos) <= PILOT_RANGE_SQ;
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (id == PACKET_PILOT_INPUT) {
            boolean pilot = isPilotOf(player);
            TileAdvancedFlightComputer afc = pilot ? getFlightComputer() : null;
            // Harness trace: the packet ARRIVED — log whether the pilot guard and the AFC resolve
            // passed, so a playtest with -Dadvancedrocketry.tests=true shows where a seated pilot's
            // input is dropped. No-op in normal play.
            if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()) {
                zmaster587.advancedRocketry.AdvancedRocketry.logger.info(
                        "[FF-TRACE/SEAT] recv pilotInput at " + pos + " pilotGuard=" + pilot
                                + " afcResolved=" + (afc != null));
            }
            // Reject a control packet from a player who is not actually at this seat.
            if (!pilot || afc == null) {
                return;
            }
            FreeFlightInput input = new FreeFlightInput(
                    nbt.getFloat("ffFwd"), nbt.getFloat("ffVert"), nbt.getFloat("ffStrafe"),
                    nbt.getFloat("ffYaw"), nbt.getFloat("ffPitch"), nbt.getFloat("ffRoll"),
                    nbt.getFloat("ffBrake"), nbt.getBoolean("ffCut"));
            afc.setPilotInput(input);
        } else if (id == PACKET_FLIGHT_ASSIST_TOGGLE) {
            // Only the seated pilot may flip the ship's Flight Assist.
            TileAdvancedFlightComputer afc = isPilotOf(player) ? getFlightComputer() : null;
            if (afc == null) {
                return;
            }
            afc.setFlightAssistEnabled(!afc.isFlightAssistEnabled());
            // Push the new state to clients so the piloting HUD updates (the flag rides the seat's
            // update tag, resent by this block update).
            if (world != null && !world.isRemote) {
                IBlockState state = world.getBlockState(pos);
                world.notifyBlockUpdate(pos, state, state, 3);
            }
        } else if (id == PACKET_AUTO_TAKEOFF_TOGGLE) {
            // Only the seated pilot may engage the auto-takeoff autopilot.
            TileAdvancedFlightComputer afc = isPilotOf(player) ? getFlightComputer() : null;
            if (afc != null) {
                afc.toggleAutoTakeoff();
            }
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    // ---- Client sync: the linked flag + offset travel to the client so a piloting client can
    // recognise a ship control seat (and resolve nothing itself — it only needs isLinked). ----

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound nbt = writeToNBT(new NBTTagCompound());
        // Piggyback the linked computer's Flight-Assist state (server-authoritative) so the
        // piloting client's HUD shows the true value. Kept out of writeLinkNbt so that method
        // stays pure (no world lookup) for the persistence unit test.
        TileAdvancedFlightComputer afc = getFlightComputer();
        if (afc != null) {
            nbt.setBoolean(NBT_FA, afc.isFlightAssistEnabled());
        }
        return nbt;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        return writeLinkNbt(nbt);
    }

    /**
     * Write only this seat's link fields (linked flag + computer offset) into {@code nbt}.
     * Split out from {@link #writeToNBT} so the link contract can be persistence-tested without
     * the parent {@code TileEntity.writeToNBT}, which needs a registered tile mapping.
     */
    public NBTTagCompound writeLinkNbt(NBTTagCompound nbt) {
        nbt.setBoolean(NBT_LINKED, linked);
        nbt.setInteger(NBT_DX, afcDx);
        nbt.setInteger(NBT_DY, afcDy);
        nbt.setInteger(NBT_DZ, afcDz);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        linked = nbt.getBoolean(NBT_LINKED);
        afcDx = nbt.getInteger(NBT_DX);
        afcDy = nbt.getInteger(NBT_DY);
        afcDz = nbt.getInteger(NBT_DZ);
        // Sync-only field: present on the client update tag, absent from disk saves (default on).
        if (nbt.hasKey(NBT_FA)) {
            clientFlightAssistOn = nbt.getBoolean(NBT_FA);
        }
    }
}
