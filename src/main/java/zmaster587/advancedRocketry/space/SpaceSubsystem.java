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
    /** Armed by {@link #armSaveFaultOnce()}; consumed by the next save point that reaches it. */
    private static boolean saveFaultArmed;

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
        instance = new SpaceManager(new PoolSlotBinder(), SpaceSubsystem::spaceClock, mgrConfig,
                SpaceSubsystem::onForcedTier1Eviction);
        shipLedger = new ShipLedger();
        // A cell is protected from garbage collection while a ship is parked in it. That fact already
        // lives in the ledger, so the manager asks it rather than keeping a second flag of its own.
        instance.setClaimedCells(cellKey -> shipLedger != null && shipLedger.holdsShipIn(cellKey));
        transitManager = new ShipTransitManager(instance, new HyperspaceTiles(), new VSShipCrosser(),
                shipLedger, SpaceSubsystem::spaceClock);
        transitManager.setOfflineProgress(new OfflineProgress(
                OfflineProgress.parseMode(cfg.spaceTransitOfflineProgress), SpaceSubsystem::isPlayerOnline));
        transitManager.setArrivalPlacement(SpaceSubsystem::arrivalStandoff);
        transitManager.setFrames(SpaceSubsystem::cellFrameOriginAt);
        entryController = new ShipEntryController(instance, shipLedger, new VSShipCrossingOps(),
                SpaceSubsystem::launchBodyAddress, SpaceSubsystem::spaceClock);
        descentController = new DescentController(instance, shipLedger, new VSShipCrossingOps(),
                new VSDescentPasteResolver(), SpaceSubsystem::spaceClock);
        gcTickCounter = 0;
        pressureGcRequested = false;
        AdvancedRocketry.logger.info("[SPACE] subsystem online: pool={} gcPolicy={} maxStored={} maxAgeTicks={}",
                SpaceSlotPool.slotDims().size(), mgrConfig.gcPolicy, mgrConfig.maxStoredCells, mgrConfig.maxAgeTicks);
    }

    /**
     * Server-STARTED hook (worlds are up, MapStorage reachable): restore the space clock, and then
     * the persisted ship ledger so the server's knowledge of every settled ship survives a restart.
     * Runs before any player login. The LEDGER half is a no-op when the subsystem stood down (test
     * harness / disabled / no VS -&gt; {@code shipLedger} null); the CLOCK half is not — see below.
     */
    public static void onServerStarted() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        ShipLedgerData data = ShipLedgerData.get(server);
        // The clock FIRST, and BEFORE the stand-down check. Every value restored below is dated
        // against it, and a ledger age or a transit ETA read at tick zero while its stamp came from
        // last session is not merely stale, it is in the future.
        //
        // It is restored even with the subsystem down, because the clock is not the CONTROLLER's:
        // spaceClock() is public and is read by code that has no idea whether space registered - a
        // memory crystal stamps the freshness of every address it is seeded with, from any world,
        // with or without Valkyrien Skies - and such a stamp OUTLIVES the session in storage of its
        // own. A counter that restarted at zero would leave every one of them permanently in the
        // future, so the freshest observation could never win a merge again. A world with none
        // stored (a new save) starts at zero, which is where a new clock starts.
        if (data != null) {
            spaceTick = data.clock();
        }
        if (shipLedger == null) {
            return;
        }
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
                        // The body's own offset inside its zone cell, as of now: a moon is a live
                        // point inside its parent's neighbourhood, and a ship leaving it has to be
                        // put beside where the moon IS, not beside where its cell is named.
                        return body.addressAt(spaceClock());
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
            occupied.add(body.addressAt(worldTick));
        }
        return StandoffRing.standoffFrom(target, occupied, ShipEntryController.ENTRY_RING_BLOCKS,
                ShipEntryController.DESCENT_RADIUS_BLOCKS,
                shipId == null ? 0 : shipId.hashCode());
    }

    /**
     * Where the cell NAMED {@code name} is, absolutely, at {@code tick} — the production
     * {@link zmaster587.advancedRocketry.space.CellFrames} lookup, resolved against the live universe
     * registry. Falls back to the static reading ({@code sector * CELL}) with no registry, which is
     * what a void cell really does anyway.
     *
     * <p>Public and static for the same reason {@link #launchBodyAddress(int)} is: a probe-built
     * stack wires the production resolver rather than a second one that could disagree with it.</p>
     */
    public static AbsolutePos cellFrameOriginAt(GalacticCoord name, long tick) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        zmaster587.advancedRocketry.universe.UniverseRegistry reg =
                zmaster587.advancedRocketry.universe.UniverseRegistry.get(server);
        return reg == null ? AbsolutePos.ofCellName(name) : reg.originAt(name, tick);
    }

    /** The production frame lookup as a {@link CellFrames}. Never {@code null}. */
    public static CellFrames frames() {
        return SpaceSubsystem::cellFrameOriginAt;
    }

    /**
     * Take a final cut of every parked ship before the server writes its last save. Called from the
     * server-STOPPING hook, which runs while the worlds are still up and before {@code stopServer} saves
     * them; the periodic re-cut alone would leave the shutdown snapshot up to one period out of date, and
     * the shutdown save is the one a returning player actually resumes from. A no-op while the subsystem
     * is down, and it never propagates: a stop must not be turned into a crash by a snapshot.
     */
    public static void onServerStopping() {
        if (transitManager == null) {
            return;
        }
        try {
            int refreshed = transitManager.refreshSnapshots();
            if (refreshed > 0) {
                AdvancedRocketry.logger.info("[SPACE] re-cut {} in-flight ship(s) before the shutdown save",
                        refreshed);
            }
        } catch (Exception failed) {
            AdvancedRocketry.logger.error("[SPACE] could not re-cut the in-flight ships before shutdown; "
                    + "each jump keeps the snapshot it already carries", failed);
        }
    }

    /**
     * Arm a one-shot failure inside the next ship-ledger save point. The subsystem promises that a save
     * which fails part-way leaves the previously persisted fleet intact and leaves the server running,
     * and that promise is only worth what a test can make fail — the gather it protects is otherwise
     * total, which is the whole point of it and also why nothing can be made to break from outside.
     * Fired and disarmed by the first save that reaches it.
     */
    public static void armSaveFaultOnce() {
        saveFaultArmed = true;
    }

    /**
     * Whether an armed save fault is still waiting to fire. It going false is how an observer knows a
     * save point actually reached the fault — which matters because the save that can take the server
     * down is the world autosave, not one a command asked for.
     */
    public static boolean isSaveFaultArmed() {
        return saveFaultArmed;
    }

    /**
     * The armed fault, thrown from the middle of a save point's gather — where a mistake in that gather
     * would land, which is the one failure the handler undertakes to survive.
     */
    private static void failSavePointIfArmed() {
        if (saveFaultArmed) {
            saveFaultArmed = false;
            throw new IllegalStateException("armed ship-ledger save fault");
        }
    }

    /** Server-stop teardown. The slot dimensions stay registered (JVM-global); only the controller resets. */
    public static void onServerStopped() {
        instance = null;
        transitManager = null;
        shipLedger = null;
        entryController = null;
        descentController = null;
        // The clock belongs to the save that was just closed. A single-player client keeps this JVM
        // alive between worlds, so carrying the number over would date the next world's first jump
        // against the previous world's history; the next server-started hook reads its own.
        spaceTick = 0L;
        gcTickCounter = 0;
        pressureGcRequested = false;
        saveFaultArmed = false;
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
     * The subsystem's own clock, in ticks: the counter {@link #spaceClock()} answers with on the
     * server. Advanced once per server tick by {@link Ticker}, written out with the rest of the
     * subsystem's durable state and read back on server start, so a persisted age or ETA still means
     * what it meant before the reboot.
     *
     * <p>Plain static state and not a world's counter, because a world's counter belongs to that
     * world. The overworld's is the only one that advances unconditionally, and it is also the one
     * anything that wants to age a save writes to; every other dimension's advances only while that
     * dimension ticks; and neither is resolvable in the windows around server start and stop, where
     * asking for one used to answer <b>tick zero</b> — silently dating a body's address, a transit's
     * elapsed time or a capacitor's charge to the beginning of the world.</p>
     */
    private static long spaceTick;

    /**
     * The one clock every space-side elapsed-time computation reads, on EITHER side. Public so
     * machines that carry a lazy resource — a capacitor that is charged by arithmetic rather than by
     * ticking — measure their elapsed time against exactly the same counter a transit does, and so a
     * ship parked in an unloaded cell is never quietly on a different clock from one in a loaded
     * chunk.
     *
     * <p><b>Side-agnostic on purpose.</b> On the server this is {@link #spaceTick}, the subsystem's
     * own counter; on a client it is {@link SpaceClockSync}, the synced copy of that same counter. No
     * caller needs to know which side it is on, and none may reach for a world's own clock instead:
     * every dimension except the overworld carries a clock that advances only while it ticks, so "the
     * total time of whatever world I am in" is a DIFFERENT quantity that merely looks like this one.
     * A jump aim once read that other quantity and put arrivals thousands of blocks off their target.
     * There is now no world clock anywhere in this answer, so that class of mistake has nothing left
     * to be made out of.</p>
     */
    public static long spaceClock() {
        return FMLCommonHandler.instance().getEffectiveSide().isClient()
                ? SpaceClockSync.now()
                : spaceTick;
    }

    /**
     * TEST/HEADLESS: put the owned clock at {@code tick}. Ages the universe by arithmetic instead of
     * by waiting, which is the only way a dwell measured in days is testable at all — and, unlike the
     * counter this used to be, moving it touches no world, so a shared server's day cycle, mob spawns
     * and every other {@code totalTime % N} gate are left exactly where they were.
     *
     * <p>Production has no other writer: the clock is advanced by {@link Ticker} and restored by
     * {@link #onServerStarted()}, and nothing else may set it.</p>
     */
    public static void setSpaceClock(long tick) {
        spaceTick = tick;
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
            // THE SUBSYSTEM'S ONLY ADVANCE SITE. One increment per server tick, before anything else
            // here can return: the clock is not the controller's, it is the subsystem's, and a
            // session with the controller down (config off, no Valkyrien Skies, a harness that
            // installs its own stack) must still get a number that MOVES when it asks the time —
            // a clock frozen at zero is the defect this counter replaced, not an acceptable
            // stand-down. Nothing else in the mod may increment it; the other server-tick handler in
            // this subsystem (SpaceEventHandler) deliberately only READS it, because two writers on
            // the same event would run the clock at twice the tick rate and nothing would report it.
            spaceTick++;
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
                if (shipLedger != null) {
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
                    data.setClock(spaceTick);
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
                java.util.Map<java.util.UUID, ShipLedger.Entry> live = shipLedger.snapshot();
                java.util.List<TransitRecord> inFlight = transitManager == null
                        ? java.util.Collections.<TransitRecord>emptyList()
                        : transitManager.exportTransits();
                java.util.Map<String, Long> visits = instance == null
                        ? java.util.Collections.<String, Long>emptyMap() : instance.exportVisits();
                failSavePointIfArmed();
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
}
