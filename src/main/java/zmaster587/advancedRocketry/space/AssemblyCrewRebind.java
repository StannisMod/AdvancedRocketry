package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Carries a pre-assembly boarding across the asynchronous ship assembly. The launch-pad assembler
 * relocates a tier-2 craft's blocks into the ship's subspace on the physics mod's own schedule, so
 * at the moment {@code assembleRocket} returns there is nothing to rebind a seated pilot to yet.
 * The assembler enqueues each seated pilot here; every server tick each entry retries
 * {@link CrewTransfer#rebindAcrossAssembly} until the relocated seat resolves or the budget runs
 * out (after which the pilot is left on his stale mount and the failure is logged - never held in
 * limbo, and never silently dropped).
 *
 * <p>Same drain shape as the login-restore seating queue in {@link SpaceEventHandler}; kept
 * separate because this path is core assembly glue and must work with the space subsystem down.</p>
 */
public final class AssemblyCrewRebind {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry.assemblycrewrebind");

    /** Retry budget in server ticks (~15 s at 20 TPS); relocation normally completes within a
     *  couple of seconds, but a loaded server or a large craft stretches it. */
    private static final int MAX_ATTEMPTS = 300;

    private static final List<Pending> PENDING = new ArrayList<>();

    /** One seated pilot owed a rebind: who, off which stale mount, onto which ship's seat. */
    private static final class Pending {
        final int dimension;
        final UUID playerId;
        final int staleDummyId;
        final BlockPos anchor;
        final int afcDx, afcDy, afcDz;
        int attempts;

        Pending(int dimension, UUID playerId, int staleDummyId, BlockPos anchor,
                int afcDx, int afcDy, int afcDz) {
            this.dimension = dimension;
            this.playerId = playerId;
            this.staleDummyId = staleDummyId;
            this.anchor = anchor;
            this.afcDx = afcDx;
            this.afcDy = afcDy;
            this.afcDz = afcDz;
        }
    }

    /**
     * Queue {@code player} - currently riding the stale mount {@code staleDummyId} bound to the
     * seat's pre-assembly position - for a rebind onto the ship being assembled at {@code anchor},
     * whose seat is re-identified by the given AFC-link offset. Server main thread only.
     */
    public static void enqueue(WorldServer world, EntityPlayerMP player, int staleDummyId,
            BlockPos anchor, int afcDx, int afcDy, int afcDz) {
        PENDING.add(new Pending(world.provider.getDimension(), player.getUniqueID(),
                staleDummyId, anchor, afcDx, afcDy, afcDz));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance()
                .getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(pending.playerId);
            WorldServer world = player == null ? null : server.getWorld(pending.dimension);
            if (player == null || world == null) {
                it.remove(); // logged out mid-assembly; the login-restore path owns him now
                continue;
            }
            if (CrewTransfer.rebindAcrossAssembly(world, pending.anchor, player,
                    pending.staleDummyId, pending.afcDx, pending.afcDy, pending.afcDz)) {
                it.remove();
            } else if (++pending.attempts > MAX_ATTEMPTS) {
                LOGGER.warn("gave up rebinding {} onto the ship assembled at {} after {} ticks - "
                        + "the relocated seat never resolved; he keeps the stale mount",
                        player.getName(), pending.anchor, MAX_ATTEMPTS);
                it.remove();
            }
        }
    }
}
