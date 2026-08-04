package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;

import zmaster587.advancedRocketry.space.SpaceClockSync;
import zmaster587.advancedRocketry.space.SpaceSubsystem;
import zmaster587.libVulpes.network.BasePacket;

/**
 * Server&rarr;client baseline for the space clock — one {@code long}, the value
 * {@link SpaceSubsystem#spaceClock()} answers on the server.
 *
 * <p>Sent to each player at login and re-sent periodically thereafter. The client does not need one
 * of these per tick: it advances its own copy and uses the re-sends only to bound drift (see
 * {@link SpaceClockSync}).</p>
 *
 * <p><b>Cadence, stated as the PEAK rather than the average</b>: the periodic re-send gives each
 * player his own phase inside the interval, derived from his own id, so the cost is at most ONE
 * packet in any tick regardless of how many players are online — not "every player once per
 * interval", which would be every player in the SAME tick. The sender is
 * {@code SpaceEventHandler.onServerTick}.</p>
 */
public class PacketSpaceClockSync extends BasePacket {

    private long tick;

    public PacketSpaceClockSync() {
    }

    /** The server's current space clock. */
    public static PacketSpaceClockSync current() {
        PacketSpaceClockSync p = new PacketSpaceClockSync();
        p.tick = SpaceSubsystem.spaceClock();
        return p;
    }

    @Override
    public void write(ByteBuf out) {
        new PacketBuffer(out).writeLong(tick);
    }

    @Override
    public void readClient(ByteBuf in) {
        tick = new PacketBuffer(in).readLong();
    }

    @Override
    public void read(ByteBuf in) {
        // never read on the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        SpaceClockSync.accept(tick);
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
