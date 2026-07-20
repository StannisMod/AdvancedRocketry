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

    /** Retry budget in server ticks (~60 s at 20 TPS). Giving up strands the pilot on a mount
     *  bound to vacated coordinates - a dead cockpit - so the budget errs long: the physics mod
     *  relocates on its own thread, and on a heavily loaded server (many ships, many worlds) that
     *  thread can lag far behind the server tick that counts these attempts. A 15 s budget was
     *  measured expiring under an 8-fork test load. */
    private static final int MAX_ATTEMPTS = 1200;

    /** How many CONSECUTIVE ticks the pilot must be observed off his stale mount before the entry
     *  is cancelled. A single observation is not trustworthy - a transient read during entity
     *  churn once cancelled a rebind whose pilot never stood up, stranding him. */
    private static final int NOT_ON_MOUNT_DEBOUNCE = 3;

    private static final List<Pending> PENDING = new ArrayList<>();

    // ---- Outcome diagnostics (ungated statics, e2e/probe-readable) ---------------------------
    /** Rebinds queued by the assembler in this JVM. */
    public static volatile int enqueuedCount;
    /** Rebinds that completed (the pilot got a fresh mount on the relocated seat). */
    public static volatile int reboundCount;
    /** Entries dropped because the retry budget expired (the WARN path - a stranded pilot). */
    public static volatile int expiredCount;
    /** Entries dropped because the pilot was observed off his stale mount (debounced). */
    public static volatile int cancelledCount;
    /** The last entry's outcome, for post-mortems. */
    public static volatile String lastOutcome = "";

    /** One seated pilot owed a rebind: who, off which stale mount, onto which ship's seat. */
    private static final class Pending {
        final int dimension;
        final UUID playerId;
        final int staleDummyId;
        final BlockPos anchor;
        final int afcDx, afcDy, afcDz;
        /** The assembling ship's durable id, so the rebind can never grab an equal-offset seat of
         *  a neighbouring craft ({@code null} when the assembler had none to give). */
        final UUID shipId;
        int attempts;
        /** Consecutive ticks the pilot was observed NOT riding the stale mount (see debounce). */
        int notOnMountStreak;

        Pending(int dimension, UUID playerId, int staleDummyId, BlockPos anchor,
                int afcDx, int afcDy, int afcDz, UUID shipId) {
            this.dimension = dimension;
            this.playerId = playerId;
            this.staleDummyId = staleDummyId;
            this.anchor = anchor;
            this.afcDx = afcDx;
            this.afcDy = afcDy;
            this.afcDz = afcDz;
            this.shipId = shipId;
        }
    }

    /**
     * Queue {@code player} - currently riding the stale mount {@code staleDummyId} bound to the
     * seat's pre-assembly position - for a rebind onto the ship being assembled at {@code anchor},
     * whose seat is re-identified by the given AFC-link offset on the ship with durable id
     * {@code shipId}. Server main thread only.
     */
    public static void enqueue(WorldServer world, EntityPlayerMP player, int staleDummyId,
            BlockPos anchor, int afcDx, int afcDy, int afcDz, UUID shipId) {
        enqueuedCount++;
        PENDING.add(new Pending(world.provider.getDimension(), player.getUniqueID(),
                staleDummyId, anchor, afcDx, afcDy, afcDz, shipId));
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
                // Debounced like the mount check: a transient lookup miss must not strand a
                // still-connected pilot on a dead binding.
                if (++pending.notOnMountStreak >= NOT_ON_MOUNT_DEBOUNCE) {
                    cancelledCount++;
                    lastOutcome = "cancelled(playerGone) anchor=" + pending.anchor;
                    it.remove(); // logged out mid-assembly; the login-restore path owns him now
                }
                continue;
            }
            CrewTransfer.RebindOutcome outcome = CrewTransfer.rebindAcrossAssembly(world,
                    pending.anchor, player, pending.staleDummyId,
                    pending.afcDx, pending.afcDy, pending.afcDz, pending.shipId);
            if (outcome == CrewTransfer.RebindOutcome.REBOUND) {
                reboundCount++;
                lastOutcome = "rebound anchor=" + pending.anchor + " after=" + pending.attempts;
                it.remove();
                continue;
            }
            if (outcome == CrewTransfer.RebindOutcome.NOT_ON_STALE_MOUNT) {
                if (++pending.notOnMountStreak >= NOT_ON_MOUNT_DEBOUNCE) {
                    cancelledCount++;
                    lastOutcome = "cancelled(offMount) anchor=" + pending.anchor
                            + " after=" + pending.attempts;
                    it.remove(); // genuinely stood up / re-seated - never force him back
                }
                continue;
            }
            pending.notOnMountStreak = 0; // still on the stale mount, ship just not up yet
            if (++pending.attempts > MAX_ATTEMPTS) {
                expiredCount++;
                lastOutcome = "expired anchor=" + pending.anchor;
                LOGGER.warn("gave up rebinding {} onto the ship assembled at {} after {} ticks - "
                        + "the relocated seat never resolved; he keeps the stale mount",
                        player.getName(), pending.anchor, MAX_ATTEMPTS);
                it.remove();
            }
        }
    }
}
