package zmaster587.advancedRocketry.space;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production lifecycle for the movable-ship space subsystem: owns the single server-side
 * {@link SpaceManager} instance, registers the slot pool once per JVM, and drives the GC cadence.
 *
 * <p>Registration is an <b>explicit server-start hook</b> and runs wherever the mod runs: it is NOT
 * conditioned on the JVM's test property. Space is the mod's subject, so a session that can fly is
 * the only useful default - conditioning it on a diagnostic property once disabled the very
 * subsystem a playtest was diagnosing, with the ship stopping dead at the physics clamp and no
 * feedback. Probe-driven tests that want scratch cells of their own take them from
 * {@link SpaceSlotPool#registerAdditionalSlots(int)}, which APPENDS fresh dimensions, while
 * {@link SpaceSlotPool#registerPool(int)} is idempotent - so the two cannot fight over slot ids.</p>
 *
 * <p>GC cadence (maintainer-ratified): a periodic tick sweep ({@link #GC_TICK_INTERVAL}) plus a
 * pool-pressure trigger. A single WARN fires only when the pool is saturated and a live bubble slot is
 * force-evicted (the real overload signal); tier-2 store GC over idle cells stays quiet.</p>
 *
 * <p>Server main thread only.</p>
 */
public final class SpaceSubsystem {

    /** Periodic GC sweep interval, in server ticks (~30 s at 20 tps). Internal cadence, not a config knob. */
    private static final int GC_TICK_INTERVAL = 600;

    private static SpaceManager instance;
    private static ShipTransitManager transitManager;
    private static ShipLedger shipLedger;
    private static ShipEntryController entryController;
    private static DescentController descentController;
    private static int gcTickCounter;
    /** Set by the pool-pressure eviction listener; consumed on the next server tick to run an extra GC. */
    private static boolean pressureGcRequested;

    private SpaceSubsystem() { }

    /** The live production controller, or {@code null} before server start / in test mode. */
    public static SpaceManager get() {
        return instance;
    }

    /** The live production transit manager, or {@code null} before server start / in test mode. */
    public static ShipTransitManager transit() {
        return transitManager;
    }

    /** The live ship ledger, or {@code null} before server start / in test mode. */
    public static ShipLedger ledger() {
        return shipLedger;
    }

    /** The live entry controller, or {@code null} before server start / in test mode. */
    public static ShipEntryController entry() {
        return entryController;
    }

    /** The live descent controller, or {@code null} before server start / in test mode. */
    public static DescentController descent() {
        return descentController;
    }

    /**
     * TEST/HEADLESS: install a probe-built stack so the production trigger path (flight-computer
     * tick &rarr; {@link #entry()}) is exercisable under the test harness, where
     * {@link #onServerStarting()} deliberately stands down. Pass nulls to clear.
     */
    public static void installProbeStack(SpaceManager manager, ShipLedger ledger,
                                         ShipEntryController entry, ShipTransitManager transit,
                                         DescentController descent) {
        instance = manager;
        shipLedger = ledger;
        entryController = entry;
        transitManager = transit;
        descentController = descent;
    }

    /**
     * Whether the production subsystem should register the space dimensions on server start. Pure decision
     * surface — factored out so the gate ({@code enableSpaceSubsystem} flag, Valkyrien Skies presence,
     * once-per-session idempotence) is unit-testable without booting a server.
     *
     * <p>The decision deliberately does NOT consider whether the JVM runs in test mode. Space is the
     * point of this mod, so it registers wherever the mod runs — an interactive session launched with
     * the probe property is a session that wants to fly, and a harness run that needs scratch cells
     * takes them from {@link SpaceSlotPool#registerAdditionalSlots(int)}, which APPENDS to the pool
     * and therefore cannot disturb what production already registered.</p>
     *
     * <ul>
     *   <li>{@code enabled} — the {@code enableSpaceSubsystem} config flag; when off the subsystem is fully
     *       disabled, registering no dimensions at all (a config toggle must return the vanilla baseline).</li>
     *   <li>{@code vsAvailable} — the subsystem only hosts tier-2 Valkyrien Skies ships; without VS there
     *       is nothing to host, so registering ~10 dimensions is pure dead weight.</li>
     *   <li>{@code alreadyBuilt} — a single-player re-open reuses the JVM-global registration.</li>
     * </ul>
     */
    public static boolean shouldRegister(boolean enabled, boolean vsAvailable, boolean alreadyBuilt) {
        return enabled && vsAvailable && !alreadyBuilt;
    }

    /** Extra headroom above the cells' topmost realizable pose, so a ship can maneuver at the very
     *  top of a cell without touching the physics clamp. {@code tunable}. */
    private static final double SHIP_CEILING_MARGIN = 2_000d;

    /**
     * The ship-altitude ceiling the slot cells require: the top of the realized pose band
     * ({@link CellWorldMapper#POSE_BAND_Y} + {@link GalacticCoord#CELL}) plus a maneuvering
     * margin. Pure, so the "every realizable cell pose is below the initialized ceiling" contract
     * is directly checkable.
     */
    public static double requiredShipCeiling() {
        return (double) CellWorldMapper.POSE_BAND_Y + GalacticCoord.CELL + SHIP_CEILING_MARGIN;
    }

    /**
     * Server-start hook. Registers the pool (once per JVM) and builds the production
     * {@link SpaceManager}, unless {@link #shouldRegister} says to stand down (the
     * {@code enableSpaceSubsystem} flag off, Valkyrien Skies absent, or already built).
     */
    public static void onServerStarting() {
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();
        boolean vsAvailable = VSIntegration.isAvailable();
        if (!shouldRegister(cfg.enableSpaceSubsystem, vsAvailable, instance != null)) {
            // Log the operator-facing reason (already-built is an internal, expected no-op that
            // must stay quiet).
            if (instance == null) {
                if (!cfg.enableSpaceSubsystem) {
                    AdvancedRocketry.logger.info("[SPACE] subsystem disabled (enableSpaceSubsystem=false) - "
                            + "no space dimensions registered");
                } else if (!vsAvailable) {
                    AdvancedRocketry.logger.info("[SPACE] Valkyrien Skies not installed - space subsystem "
                            + "not registered (no tier-2 ships to host)");
                }
            }
            return;
        }
        // The cells realize ship poses across the whole [POSE_BAND_Y, CELL + POSE_BAND_Y) band
        // (top ~ world Y 4M) while the physics mod's stock altitude clamp sits at 1000 and a
        // ship's own thrust can never carry it past that clamp. Raise the ceiling ONCE here,
        // deterministically, so the full vertical range of every cell is flyable from the first
        // tick - not ratcheted up arrival-by-arrival, which left each ship a mere ~1000-block
        // corridor above wherever it happened to enter.
        VSIntegration.raiseShipCeilingTo(requiredShipCeiling());
        // Register the physical slot dimensions once per JVM; a single-player world re-open reuses the
        // already-registered dims (DimensionManager registration is JVM-global and re-registering throws).
        if (SpaceSlotPool.slotDims().isEmpty()) {
            SpaceSlotPool.registerPool(Math.max(1, cfg.spaceCellPoolSize));
        }
        // Register the shared hyperspace dim UPFRONT here, exactly like the pool (cheap - a Forge map
        // entry, no world loaded until a ship first transits). Idempotent, so safe on a single-player
        // re-open. Consistent with the pool + gives a predictable id at a known point.
        HyperspaceWorld.register();
        SpaceManager.Config mgrConfig = new SpaceManager.Config(
                parseGcPolicy(cfg.spaceCellGcPolicy),
                cfg.spaceCellMaxAgeTicks,
                cfg.spaceMaxStoredCells);
        instance = new SpaceManager(new PoolSlotBinder(), SpaceSubsystem::worldTime, mgrConfig,
                SpaceSubsystem::onForcedTier1Eviction);
        shipLedger = new ShipLedger();
        // A cell is protected from garbage collection while a ship is parked in it. That fact already
        // lives in the ledger, so the manager asks it rather than keeping a second flag of its own.
        instance.setClaimedCells(cellKey -> shipLedger != null && shipLedger.holdsShipIn(cellKey));
        transitManager = new ShipTransitManager(instance, new HyperspaceTiles(), new VSShipCrosser(),
                shipLedger, SpaceSubsystem::worldTime);
        transitManager.setOfflineProgress(new OfflineProgress(
                OfflineProgress.parseMode(cfg.spaceTransitOfflineProgress), SpaceSubsystem::isPlayerOnline));
        transitManager.setArrivalPlacement(SpaceSubsystem::arrivalStandoff);
        entryController = new ShipEntryController(instance, shipLedger, new VSShipCrossingOps(),
                SpaceSubsystem::launchBodyAddress, SpaceSubsystem::worldTime);
        descentController = new DescentController(instance, shipLedger, new VSShipCrossingOps(),
                new VSDescentPasteResolver(), SpaceSubsystem::worldTime);
        gcTickCounter = 0;
        pressureGcRequested = false;
        AdvancedRocketry.logger.info("[SPACE] subsystem online: pool={} gcPolicy={} maxStored={} maxAgeTicks={}",
                SpaceSlotPool.slotDims().size(), mgrConfig.gcPolicy, mgrConfig.maxStoredCells, mgrConfig.maxAgeTicks);
    }

    /**
     * Server-STARTED hook (worlds are up, MapStorage reachable): restore the persisted ship ledger so
     * the server's knowledge of every settled ship survives a restart. Runs before any player login.
     * A no-op when the subsystem stood down (test harness / disabled / no VS -> {@code shipLedger} null).
     */
    public static void onServerStarted() {
        if (shipLedger == null) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        ShipLedgerData data = ShipLedgerData.get(server);
        if (data != null) {
            data.loadInto(shipLedger);
            AdvancedRocketry.logger.info("[SPACE] restored {} settled ship(s) from disk", shipLedger.size());
            // Restore when each cell was last visited, or every stored cell looks freshly visited on
            // this boot and age-based collection can never reach an earlier session's leftovers.
            if (instance != null) {
                instance.importVisits(data.loadVisits());
            }
            // Recreate any in-flight jump so a transit survives a restart: each record advances logically
            // and, on arrival, pastes its persisted block snapshot into the target cell (the hyperspace
            // world it was parked in is ephemeral). The ledger is re-marked IN_TRANSIT inside importTransit.
            if (transitManager != null) {
                java.util.List<TransitRecord> records = data.loadTransits();
                for (TransitRecord r : records) {
                    transitManager.importTransit(r);
                }
                if (!records.isEmpty()) {
                    AdvancedRocketry.logger.info("[SPACE] restored {} in-flight transit(s) from disk",
                            records.size());
                }
            }
        }
    }

    /**
     * The launch BODY's full galactic address for a planet dimension: its zone cell via the
     * universe registry (the C-1 lookup), refined to the body's own local offset when the zone
     * content lists it. {@code null} (no placement / registry unreachable) makes the entry fall
     * back to the configured home-system anchor. Public: the production resolver is also what a
     * probe-built entry stack wires, so tests exercise the real lookup chain.
     */
    public static GalacticCoord launchBodyAddress(int dimId) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        zmaster587.advancedRocketry.universe.UniverseRegistry reg =
                zmaster587.advancedRocketry.universe.UniverseRegistry.get(server);
        if (reg != null) {
            java.util.Optional<GalacticCoord> cell = reg.coordForPlanet(dimId);
            if (cell.isPresent()) {
                for (zmaster587.advancedRocketry.universe.SystemBody body : reg.bodiesAt(cell.get())) {
                    if (body.dimId() == dimId) {
                        return body.address(); // the body's own local offset inside its zone cell
                    }
                }
                return cell.get();
            }
        }
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();
        return cfg == null ? null
                : zmaster587.advancedRocketry.universe.UniverseRegistry.parseAnchor(cfg.spaceHomeSystemCoord);
    }

    /**
     * Where a jump aimed at {@code target} actually ends: standing the ship off every descend-target
     * body of the target's own cell, by the same ring an entry uses.
     *
     * <p>Without this an arrival lands ON its destination. A planet's address IS its cell centre, and
     * the arrival settles the ship exactly onto the coordinate it aimed at, so the ship comes out of
     * hyperspace at distance zero from the body — well inside the descent radius — and the pilot's
     * first control input drops him onto the surface he had just spent a jump reaching. The entry
     * path has said this for as long as it has existed ({@link ShipEntryController#ENTRY_RING_BLOCKS}
     * is twice the descent radius for exactly this reason); the arrival path never had a counterpart.
     *
     * <p>A cell with no descend-target body — deep space, a hand-typed coordinate — is returned
     * UNTOUCHED. There is nothing to stand off from, and displacing a destination the pilot chose
     * rather than derived would be its own kind of wrong. Public for the same reason
     * {@link #launchBodyAddress(int)} is: a probe-built stack wires the production resolver.
     */
    public static GalacticCoord arrivalStandoff(String shipId, GalacticCoord target, long worldTick) {
        if (target == null) {
            return null;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        zmaster587.advancedRocketry.universe.UniverseRegistry reg =
                zmaster587.advancedRocketry.universe.UniverseRegistry.get(server);
        if (reg == null) {
            return target;
        }
        // EVERY body, not only the ones a ship can land on. The question here is "is something there",
        // not "could I descend to it": arriving on top of a gas giant trips no descent trigger, but it
        // does put the ship at zero distance from the body, and an observer→body vector of zero is
        // dropped by the sky renderer — so the pilot spends a jump and arrives at a destination his
        // own sky does not draw.
        java.util.List<GalacticCoord> occupied = new java.util.ArrayList<>();
        for (zmaster587.advancedRocketry.universe.SystemBody body : reg.bodiesAt(target)) {
            occupied.add(body.address());
        }
        return StandoffRing.standoffFrom(target, occupied, ShipEntryController.ENTRY_RING_BLOCKS,
                ShipEntryController.DESCENT_RADIUS_BLOCKS,
                shipId == null ? 0 : shipId.hashCode());
    }

    /** Server-stop teardown. The slot dimensions stay registered (JVM-global); only the controller resets. */
    public static void onServerStopped() {
        instance = null;
        transitManager = null;
        shipLedger = null;
        entryController = null;
        descentController = null;
        gcTickCounter = 0;
        pressureGcRequested = false;
        SystemBodiesProducer.reset();
        zmaster587.advancedRocketry.universe.SystemContent.reset();
        HyperspaceWorld.reset();
    }

    /** Parse the {@code spaceCellGcPolicy} config string, defaulting to {@code BOTH} on an unknown value. */
    private static SpaceManager.GcPolicy parseGcPolicy(String value) {
        if (value == null) {
            return SpaceManager.GcPolicy.BOTH;
        }
        try {
            return SpaceManager.GcPolicy.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException bad) {
            AdvancedRocketry.logger.warn("[SPACE] unknown spaceCellGcPolicy '{}' - defaulting to BOTH", value);
            return SpaceManager.GcPolicy.BOTH;
        }
    }

    /**
     * The overworld's total world time — the persist-safe clock for the space subsystem (last-visit /
     * GC age, transit {@code arrivalTick}/{@code lastTicked}). Unlike {@code getTickCounter()} it survives
     * a restart, so a persisted age/ETA stays meaningful across reboots (universe-model §7 lazy-catch-up).
     */
    /**
     * The one clock every space-side elapsed-time computation reads. Public so machines that carry a
     * lazy resource — a capacitor that is charged by arithmetic rather than by ticking — measure
     * their elapsed time against exactly the same counter a transit does, and so a ship parked in an
     * unloaded cell is never quietly on a different clock from one in a loaded chunk.
     */
    public static long spaceClock() {
        return worldTime();
    }

    private static long worldTime() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return 0L;
        }
        net.minecraft.world.WorldServer overworld = server.getWorld(0);
        return overworld != null ? overworld.getTotalWorldTime() : 0L;
    }

    /** Whether {@code player} is currently connected — the offline-progress crew-online check. */
    private static boolean isPlayerOnline(java.util.UUID player) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server != null && server.getPlayerList().getPlayerByUUID(player) != null;
    }

    /** Pool-pressure signal: a live bubble slot was force-evicted because the working set is saturated. */
    private static void onForcedTier1Eviction(String cellKey, boolean wasDirty) {
        AdvancedRocketry.logger.warn("[SPACE] pool pressure - force-evicted live cell {} ({}); "
                        + "raise spaceCellPoolSize if this recurs",
                cellKey, wasDirty ? "flushed to store" : "discarded");
        pressureGcRequested = true;
    }

    /** Registered once per JVM; runs the periodic + pressure-triggered GC while a controller is live. */
    public static final class Ticker {

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
            SpaceManager mgr = instance;
            if (mgr == null) {
                return;
            }
            // Advance in-flight ships every tick (parked ships step their coordinate logically; arrivals
            // perform the second crossing). Cheap when nothing is in transit.
            if (transitManager != null) {
                transitManager.tick();
            }
            // Advance in-flight ENTRIES (crossed, waiting on async re-assembly to re-seat + settle).
            if (entryController != null) {
                entryController.tick();
            }
            // Advance in-flight DESCENTS (the inverse crossing, same async re-seat + settle).
            if (descentController != null) {
                descentController.tick();
            }
            // Rebroadcast the per-slot render bodies (throttled) so the slot-world sky (BoundarySky)
            // tracks each settled ship's direction to the bodies of its cell.
            SystemBodiesProducer.onBroadcastTick(FMLCommonHandler.instance().getMinecraftServerInstance());
            boolean run = false;
            if (pressureGcRequested) {
                pressureGcRequested = false;
                run = true;
            }
            if (++gcTickCounter >= GC_TICK_INTERVAL) {
                gcTickCounter = 0;
                run = true;
            }
            if (run) {
                mgr.gc();
            }
        }

        /**
         * Persist the ship ledger on the overworld save cadence (autosave + shutdown both fire this on
         * dim 0). A no-op while the subsystem is down.
         *
         * <p>The snapshot is written out EXPLICITLY at the end rather than merely marked dirty. This
         * looks redundant and is not: the world's save routine writes its map storage and only then
         * posts the save event, so anything dirtied from inside this handler has already missed that
         * pass. On an autosave that would just make the stored ledger one cycle stale — but the
         * shutdown save is the last one there is, and nothing writes map storage after it, so the
         * final state of every ship would be silently dropped on a clean server stop. For a subsystem
         * whose entire purpose is surviving a restart, that is the one save that must not be lost.</p>
         */
        @SubscribeEvent
        public void onWorldSave(net.minecraftforge.event.world.WorldEvent.Save event) {
            if (shipLedger == null || event.getWorld().provider.getDimension() != 0) {
                return;
            }
            ShipLedgerData data = ShipLedgerData.get(event.getWorld());
            if (data != null) {
                data.saveFrom(shipLedger);
                if (transitManager != null) {
                    data.saveTransits(transitManager.exportTransits());
                }
                if (instance != null) {
                    data.saveVisits(instance.exportVisits());
                }
                net.minecraft.world.storage.MapStorage storage = event.getWorld().getMapStorage();
                if (storage != null) {
                    storage.saveAllData();
                }
            }
        }
    }
}
