package zmaster587.advancedRocketry.space;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import zmaster587.advancedRocketry.AdvancedRocketry;

/**
 * The Forge subscriptions that drive the space subsystem: the per-tick advance and the world-save
 * staging.
 *
 * <p>A class of handlers that reaches into the STATE OBJECT ({@link SpaceSubsystem}) rather than an
 * object pretending to be a handler. That split is the point: the subsystem owns its five services
 * and its lifetime, and this class owns only the question "which event, and when" — so the state can
 * be replaced under a test without the subscription being re-registered, and the subscription can be
 * read without wading through what it drives.</p>
 *
 * <p>Server main thread only.</p>
 */
public final class SpaceSubsystemEvents {

    /**
     * Slot-dim client sync at login: a joining player's client learns the slot {@code DimensionType}
     * + dim ids BEFORE anything (login restore, entry, docking) can relocate him into a slot world —
     * the sequencing contract of the slot-dim registration sync. Independent of the production
     * controller so a probe-registered pool (test harness) syncs too; a no-op while no pool exists.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof net.minecraft.entity.player.EntityPlayerMP)
                || SpaceSlotPool.slotDims().isEmpty()) {
            return;
        }
        zmaster587.advancedRocketry.network.PacketSlotDimSync sync =
                zmaster587.advancedRocketry.network.PacketSlotDimSync.current();
        if (!sync.isEmpty()) {
            zmaster587.libVulpes.network.PacketHandler.sendToPlayer(
                    sync, (net.minecraft.entity.player.EntityPlayerMP) event.player);
        }
        // After the slot dims are registered client-side, seed the joining player's render bodies
        // (the BoundarySky feed) so a login restore into a settled cell draws them immediately.
        SystemBodiesProducer.sendToPlayer((net.minecraft.entity.player.EntityPlayerMP) event.player);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // THE SUBSYSTEM'S ONLY ADVANCE SITE. One increment per server tick, before anything else
        // here can return: the clock is not the controller's, it is the subsystem's, and a
        // session with the controller down (config off, no Valkyrien Skies, a harness that
        // installs its own stack) must still get a number that MOVES when it asks the time —
        // a clock frozen at zero is the defect this counter replaced, not an acceptable
        // stand-down. Nothing else in the mod may increment it; the other server-tick handler in
        // this subsystem (SpaceEventHandler) deliberately only READS it, because two writers on
        // the same event would run the clock at twice the tick rate and nothing would report it.
        SpaceSubsystem.advanceClock();
        // One read of the live stack per tick: the five used to be read independently, so a swap
        // landing mid-tick could tick one stack's transits against another's entries.
        SpaceSubsystem live = SpaceSubsystem.get();
        if (live == null) {
            return;
        }
        SpaceManager mgr = live.manager;
        // Advance in-flight ships every tick (parked ships step their coordinate logically; arrivals
        // perform the second crossing). Cheap when nothing is in transit.
        live.transit.tick();
        // Advance in-flight ENTRIES (crossed, waiting on async re-assembly to re-seat + settle).
        live.entry.tick();
        // Advance in-flight DESCENTS (the inverse crossing, same async re-seat + settle).
        live.descent.tick();
        // Advance in-flight CELL-SEAM carries (a ship that flew out of its cell into the next one).
        live.seam.tick();
        // Rebroadcast the per-slot render bodies (throttled) so the slot-world sky (BoundarySky)
        // tracks each settled ship's direction to the bodies of its cell.
        SystemBodiesProducer.onBroadcastTick(FMLCommonHandler.instance().getMinecraftServerInstance());
        if (live.tickGc()) {
            mgr.gc();
        }
    }

    /**
     * Persist the space clock and the ship ledger on the overworld save cadence (autosave +
     * shutdown both fire this on dim 0). Three steps, in this order and for this reason: stage the
     * CLOCK (always — it is read by code that does not know or care whether space registered, see
     * {@link SpaceSubsystem#onServerStarted()}), stage the FLEET (only while the subsystem is up,
     * and all-or-nothing), then write out whatever was staged in a {@code finally} — so a fleet
     * step that refuses or fails still cannot take the clock down with it.
     *
     * <p>The snapshot is written out EXPLICITLY at the end rather than merely marked dirty. This
     * looks redundant and is not: the world's save routine writes its map storage and only then
     * posts the save event, so anything dirtied from inside this handler has already missed that
     * pass. On an autosave that would just make the stored ledger one cycle stale — but the
     * shutdown save is the last one there is, and nothing writes map storage after it, so the
     * final state of every ship would be silently dropped on a clean server stop. For a subsystem
     * whose entire purpose is surviving a restart, that is the one save that must not be lost.</p>
     *
     * <p><b>Nothing here may destroy, and nothing recoverable may escape.</b> This handler once ran
     * as a sequence of destructive steps — empty the stored ships, refill them, then go and fetch
     * the in-flight ones — and a failure between two of those steps left the store holding an empty
     * fleet, which the next flush made permanent. It also took the server down with it, because a
     * throw out of a dim-0 save event aborts the loop over the remaining worlds (that loop catches
     * only its own world exceptions) and then the tick loop itself. So the whole body is gathered
     * first and applied in one step that cannot half-run, and a failure it can carry on past is
     * logged rather than propagated: a save point that fails must cost one stale cycle, never a
     * fleet and never the server.</p>
     */
    @SubscribeEvent
    public void onWorldSave(net.minecraftforge.event.world.WorldEvent.Save event) {
        if (event.getWorld().provider.getDimension() != 0) {
            return;
        }
        try {
            stageClock(event.getWorld());
            if (SpaceSubsystem.ledger() != null) {
                stageFleet(event.getWorld());
            }
        } finally {
            flush(event.getWorld());
        }
    }

    /**
     * Stage the space clock. UNCONDITIONAL - it runs before the fleet, on every dim-0 save,
     * whether or not the subsystem is up, and it is not part of the fleet's all-or-nothing write.
     *
     * <p><b>Why it is not bundled with the fleet, which is where it started.</b> Bundling looks
     * right: the clock dates what the fleet stores, so a pass that keeps an older fleet should
     * keep the older clock. But the fleet is not the only thing this clock dates. A jump
     * capacitor's {@code since} lives in TILE NBT and a memory crystal's {@code observedTick}
     * lives in ITEM NBT, and Minecraft commits both BEFORE this handler is ever called - the
     * chunks are written, then the save event is posted. A clock left behind on a refused or
     * failed pass therefore comes back EARLIER than stamps already on disk, and the elapsed time
     * they are measured against goes negative: every capacitor in the world reads frozen at its
     * last level, with the pilot unable to jump and nothing in the log tying it to a save.
     *
     * <p>Written forward instead, the worst case is a clock at most one save cycle AHEAD of a
     * stale fleet: a cell looks a cycle older and a jump lands a cycle sooner. A clock that runs
     * backwards breaks arithmetic; a clock that runs a little ahead of one stale snapshot does
     * not. So the clock is monotonic and the fleet is atomic, and they are written separately
     * because they are different KINDS of state.</p>
     */
    private void stageClock(net.minecraft.world.World overworld) {
        try {
            ShipLedgerData data = ShipLedgerData.get(overworld);
            if (data != null) {
                data.setClock(SpaceSubsystem.spaceClock());
            }
        } catch (Exception failed) {
            AdvancedRocketry.logger.error("[SPACE] the space clock could not be staged this save "
                    + "pass; it will resume from the last value that reached disk", failed);
        }
    }

    /** Stage the whole fleet in one all-or-nothing write. Never propagates - see the class body. */
    private void stageFleet(net.minecraft.world.World overworld) {
        try {
            ShipLedgerData data = ShipLedgerData.get(overworld);
            if (data == null) {
                AdvancedRocketry.logger.error("[SPACE] the durable ship ledger could not be resolved "
                        + "on this save - every ship's position is going unwritten this pass");
                return;
            }
            // Gather EVERYTHING before touching the store. Whatever fails in here - a physics-mod
            // hiccup, a class that will not load - leaves the previously persisted snapshot exactly
            // as it was, which is a stale answer rather than a lost fleet.
            // ONE read of the stack, so the fleet, the jumps and the visits written in a single
            // pass are the same stack's — three independent reads could straddle a swap.
            SpaceSubsystem stack = SpaceSubsystem.get();
            if (stack == null) {
                return;
            }
            java.util.Map<java.util.UUID, ShipLedger.Entry> live = stack.ledger.snapshot();
            java.util.List<TransitRecord> inFlight = stack.transit.exportTransits();
            java.util.Map<String, Long> visits = stack.manager.exportVisits();
            SpaceSubsystem.failSavePointIfArmed();
            java.util.List<java.util.UUID> dropped = data.replaceAll(live, inFlight, visits);
            if (!dropped.isEmpty()) {
                AdvancedRocketry.logger.error("[SPACE] refusing to persist a ship ledger that would "
                        + "lose {} ship(s) - {} is/are recorded as flying but no in-flight jump "
                        + "carries them, so this save would store them nowhere. The previously saved "
                        + "state is kept instead. This state should be unreachable - treat it as a "
                        + "bug report.", dropped.size(), dropped);
            }
        } catch (Exception failed) {
            // Exceptions, and deliberately nothing wider. What this can meaningfully carry on past
            // is a mistake in the gathering above - a null nobody expected, a collection changed
            // under an iterator - and there one stale cycle is a far better price than the whole
            // save pass. An Error is a different animal: the JVM or the class loader is already
            // broken, this handler cannot mend it, and swallowing one would trade a crash report -
            // which is exactly how the bug behind this rewrite was found - for an ERROR line every
            // autosave forever. The fleet does not depend on this catch either way: the gather
            // above touches the store only once it holds every value, so a throw of ANY kind
            // leaves the previously persisted snapshot intact.
            AdvancedRocketry.logger.error("[SPACE] the ship-ledger save step failed; the previously "
                    + "persisted snapshot is left untouched and the server keeps running", failed);
        }
    }

    /**
     * Write whatever was staged. In a {@code finally}, so a fleet step that failed or refused
     * still lets the CLOCK reach disk - which is the whole point of staging it first.
     */
    private void flush(net.minecraft.world.World overworld) {
        try {
            net.minecraft.world.storage.MapStorage storage = overworld.getMapStorage();
            if (storage != null) {
                storage.saveAllData();
            }
        } catch (Exception failed) {
            AdvancedRocketry.logger.error("[SPACE] the space save could not be written out this "
                    + "pass; the previously persisted snapshot is left untouched and the server "
                    + "keeps running", failed);
        }
    }
}
