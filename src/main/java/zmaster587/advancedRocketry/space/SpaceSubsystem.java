package zmaster587.advancedRocketry.space;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production lifecycle for the movable-ship space subsystem: owns the single server-side
 * {@link SpaceManager} instance, registers the slot pool once per JVM, and drives the GC cadence.
 *
 * <p>Registration is an <b>explicit server-start hook</b> that <b>no-ops under the test harness</b>
 * ({@link TestProbeCommandRegistration#isTestMode()}). The spike/probe tests register their OWN pool
 * through the {@code /artest space manager} probe, and {@link SpaceSlotPool#registerPool(int)} appends
 * to a global static list with no idempotence guard - so a harness-side auto-register would stack
 * pools and shift slot ids out from under the green spike tests. Guarding the production register
 * behind {@code isTestMode()} keeps the two worlds apart.</p>
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
     * surface — factored out so the gate (test harness, {@code enableSpaceSubsystem} flag, Valkyrien Skies
     * presence, once-per-session idempotence) is unit-testable without booting a server.
     *
     * <ul>
     *   <li>{@code testMode} — the spike/probe tests register their OWN pool via {@code /artest space
     *       manager}; the production register must stand down or it stacks pools and shifts slot ids.</li>
     *   <li>{@code enabled} — the {@code enableSpaceSubsystem} config flag; when off the subsystem is fully
     *       disabled, registering no dimensions at all (a config toggle must return the vanilla baseline).</li>
     *   <li>{@code vsAvailable} — the subsystem only hosts tier-2 Valkyrien Skies ships; without VS there
     *       is nothing to host, so registering ~10 dimensions is pure dead weight.</li>
     *   <li>{@code alreadyBuilt} — a single-player re-open reuses the JVM-global registration.</li>
     * </ul>
     */
    public static boolean shouldRegister(boolean testMode, boolean enabled, boolean vsAvailable,
                                         boolean alreadyBuilt) {
        return !testMode && enabled && vsAvailable && !alreadyBuilt;
    }

    /**
     * Server-start hook. Registers the pool (once per JVM) and builds the production
     * {@link SpaceManager}, unless {@link #shouldRegister} says to stand down (test harness, the
     * {@code enableSpaceSubsystem} flag off, Valkyrien Skies absent, or already built).
     */
    public static void onServerStarting() {
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();
        boolean testMode = TestProbeCommandRegistration.isTestMode();
        boolean vsAvailable = VSIntegration.isAvailable();
        if (!shouldRegister(testMode, cfg.enableSpaceSubsystem, vsAvailable, instance != null)) {
            // Log the operator-facing reason for the two config/environment gates only (test mode and
            // already-built are internal, expected no-ops that must stay quiet).
            if (!testMode && instance == null) {
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
        transitManager = new ShipTransitManager(instance, new HyperspaceTiles(), new VSShipCrosser(),
                shipLedger, SpaceSubsystem::worldTime);
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
    private static long worldTime() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return 0L;
        }
        net.minecraft.world.WorldServer overworld = server.getWorld(0);
        return overworld != null ? overworld.getTotalWorldTime() : 0L;
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
         * dim 0). {@link ShipLedgerData#saveFrom} marks the store dirty so MC writes it in the same save
         * pass — the {@code UniverseRegistry} persistence idiom. A no-op while the subsystem is down.
         */
        @SubscribeEvent
        public void onWorldSave(net.minecraftforge.event.world.WorldEvent.Save event) {
            if (shipLedger == null || event.getWorld().provider.getDimension() != 0) {
                return;
            }
            ShipLedgerData data = ShipLedgerData.get(event.getWorld());
            if (data != null) {
                data.saveFrom(shipLedger);
            }
        }
    }
}
