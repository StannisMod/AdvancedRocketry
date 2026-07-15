package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.libVulpes.network.BasePacket;

/**
 * Server -> client: capture the recipient (a just-dismounted pilot) in his ship's frame, standing on a
 * DECK point expressed in the ship's own SUBSPACE.
 *
 * <p>Crew movement is client-authoritative, so the capture that actually holds a body must live on the
 * client's own player object; the server can only ask for it. A server-side capture (or a server
 * teleport onto the deck) instead moves the client body, which {@link ShipFrameTravel}'s ~1&nbsp;mm
 * external-move guard reads as "someone else moved it" and drops the capture. The deck point therefore
 * travels as a SUBSPACE triple, never a world position: the client maps it to world through its OWN ship
 * transform, snaps there and stores that same world value, so its position and its stored anchor match
 * exactly and the guard holds. A world position computed on the server would differ here by more than the
 * guard and drop instantly. Re-sent across the short dismount window; a no-op once the capture has
 * taken.</p>
 */
public class PacketDeckCapture extends BasePacket {

    /** UUID string of the ANCHOR ship, resolved server-side from the seat's SUBSPACE block (claims of
     *  distinct ships never overlap, so the resolution is unambiguous). The client seeds the capture
     *  through THIS ship's transform — never by picking a ship from overlapping world boxes. */
    private String shipId;
    private double subX;
    private double subY;
    private double subZ;

    public PacketDeckCapture(String shipId, double subX, double subY, double subZ) {
        this.shipId = shipId;
        this.subX = subX;
        this.subY = subY;
        this.subZ = subZ;
    }

    public PacketDeckCapture() {
    }

    @Override
    public void write(ByteBuf out) {
        net.minecraftforge.fml.common.network.ByteBufUtils.writeUTF8String(
                out, shipId == null ? "" : shipId);
        out.writeDouble(subX);
        out.writeDouble(subY);
        out.writeDouble(subZ);
    }

    @Override
    public void readClient(ByteBuf in) {
        String id = net.minecraftforge.fml.common.network.ByteBufUtils.readUTF8String(in);
        shipId = id.isEmpty() ? null : id;
        subX = in.readDouble();
        subY = in.readDouble();
        subZ = in.readDouble();
    }

    @Override
    public void read(ByteBuf in) {
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void executeClient(EntityPlayer thePlayer) {
        // Only if not already resolved on a deck - a re-send after the capture took must not teleport him
        // again. seedShipFrameCapture no-ops when the anchor ship is not loaded on the client (yet).
        if (thePlayer != null && !ShipFrameTravel.isResolving(thePlayer)) {
            ShipFrameTravel.seedShipFrameCapture(thePlayer, shipId, subX, subY, subZ);
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
