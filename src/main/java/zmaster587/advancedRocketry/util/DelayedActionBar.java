package zmaster587.advancedRocketry.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Sends a player an action-bar message a few ticks FROM NOW. The action bar holds exactly one
 * line, and the client itself writes to it: vanilla shows its "press X to dismount" hint when the
 * mount packet arrives, which the entity tracker flushes AFTER the server code that called
 * {@code startRiding} has returned — so a status message sent in the same breath as a mount is
 * OVERWRITTEN by the hint before the player can read it. Queuing the message a few ticks out puts
 * it after the tracker's flush, so it lands last and stays visible.
 *
 * <p>Server main thread only (queued and drained there).</p>
 */
public final class DelayedActionBar {

    private static final class Entry {
        final UUID playerId;
        final ITextComponent message;
        int ticksLeft;

        Entry(UUID playerId, ITextComponent message, int ticksLeft) {
            this.playerId = playerId;
            this.message = message;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final List<Entry> PENDING = new ArrayList<>();

    /** Queue {@code message} for {@code player}'s action bar, {@code delayTicks} server ticks out. */
    public static void send(EntityPlayerMP player, ITextComponent message, int delayTicks) {
        PENDING.add(new Entry(player.getUniqueID(), message, Math.max(1, delayTicks)));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            PENDING.clear();
            return;
        }
        Iterator<Entry> it = PENDING.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (--entry.ticksLeft > 0) {
                continue;
            }
            it.remove();
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(entry.playerId);
            if (player != null) {
                player.sendStatusMessage(entry.message, true);
            }
        }
    }
}
