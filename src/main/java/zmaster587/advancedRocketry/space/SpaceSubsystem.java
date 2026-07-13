package zmaster587.advancedRocketry.space;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration;

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

    /**
     * Server-start hook. Registers the pool (once per JVM) and builds the production
     * {@link SpaceManager}. No-op under the test harness so it never collides with the probe's own pool.
     */
    public static void onServerStarting() {
        if (TestProbeCommandRegistration.isTestMode()) {
            return; // tests drive their own pool through the /artest space manager probe
        }
        if (instance != null) {
            return; // already built for this server session
        }
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();
        // Register the physical slot dimensions once per JVM; a single-player world re-open reuses the
        // already-registered dims (DimensionManager registration is JVM-global and re-registering throws).
        if (SpaceSlotPool.slotDims().isEmpty()) {
            SpaceSlotPool.registerPool(Math.max(1, cfg.spaceCellPoolSize));
        }
        SpaceManager.Config mgrConfig = new SpaceManager.Config(
                parseGcPolicy(cfg.spaceCellGcPolicy),
                cfg.spaceCellMaxAgeTicks,
                cfg.spaceMaxStoredCells);
        instance = new SpaceManager(new PoolSlotBinder(), SpaceSubsystem::serverTickCount, mgrConfig,
                SpaceSubsystem::onForcedTier1Eviction);
        transitManager = new ShipTransitManager(instance, new HyperspaceTiles(), new VSShipCrosser());
        gcTickCounter = 0;
        pressureGcRequested = false;
        AdvancedRocketry.logger.info("[SPACE] subsystem online: pool={} gcPolicy={} maxStored={} maxAgeTicks={}",
                SpaceSlotPool.slotDims().size(), mgrConfig.gcPolicy, mgrConfig.maxStoredCells, mgrConfig.maxAgeTicks);
    }

    /** Server-stop teardown. The slot dimensions stay registered (JVM-global); only the controller resets. */
    public static void onServerStopped() {
        instance = null;
        transitManager = null;
        gcTickCounter = 0;
        pressureGcRequested = false;
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

    /** Server tick count, the {@link SpaceManager} clock (drives last-visit / GC age). */
    private static long serverTickCount() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server != null ? server.getTickCounter() : 0L;
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
    }
}
