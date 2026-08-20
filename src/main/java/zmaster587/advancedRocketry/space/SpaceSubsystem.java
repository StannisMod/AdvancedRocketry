package zmaster587.advancedRocketry.space;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production lifecycle for the movable-ship space subsystem: builds the server's stack, registers the
 * slot pool once per JVM, and drives the GC cadence.
 *
 * <p>It is also the subsystem's STATE OBJECT: the five services are its fields, wired by its one
 * constructor, and they live and die together. They used to be five separate mutable statics on this
 * class — a shape that let a test seam replace them with no way back, and let each probe wire a
 * different subset of what production wires. The Forge subscriptions that drive it live beside it in
 * {@link SpaceSubsystemEvents}, which is a class of static handlers reaching into this object rather
 * than an object pretending to be a handler.</p>
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

    /**
     * The live subsystem, or {@code null} when no server has one. ONE reference, and its only writers
     * are {@link #attach}/{@link #detach} on the server lifecycle hooks plus {@link #install} for the
     * test seam. The readers here are tile entities and event subscribers with no injection point, so
     * on this platform an accessor reachable from anywhere has to exist; what the rule asks for is
     * bought by the lifecycle and the single writer, not by the absence of the keyword.
     *
     * <p>Answers {@code null} on a remote client, which is the correct answer and not a fault — a
     * client has no server-side subsystem, exactly as {@link #spaceClock()} already reads a synced
     * value there instead of the server's counter.</p>
     */
    private static SpaceSubsystem current;

    /** Armed by {@link #armSaveFaultOnce()}; consumed by the next save point that reaches it. */
    private static boolean saveFaultArmed;

    // ---- this subsystem's own state: five services that live and die together -----------------

    // Final and public: this is a state object, and the code that drives it — the Forge handlers
    // beside it in this package, and a probe holding a subsystem it built for itself — reads its
    // fields. They cannot be reassigned, so "public" costs nothing the rule cares about: the defect
    // was five INDEPENDENTLY WRITABLE statics with no lifecycle, not a readable field on an object
    // somebody already has in hand.
    public final SpaceManager manager;
    public final ShipLedger ledger;
    public final ShipTransitManager transit;
    public final ShipEntryController entry;
    public final DescentController descent;
    public final CellCrossingController cellCrossings;
    private int gcTickCounter;
    /** Set by the pool-pressure eviction listener; consumed on the next server tick to run an extra GC. */
    private boolean pressureGcRequested;

    /**
     * Wire a subsystem. <b>This is the ONE construction site</b>, and every {@code null} argument
     * means "exactly what production uses" — which is the point of it. A probe needing its own slot
     * binder and its own clock says only that, and cannot end up without production's arrival standoff
     * or its offline-progress policy because nobody remembered to attach them.
     *
     * <p>Not hypothetical: the transit probe built its stack by hand and diverged from production on
     * four axes at once, while the entry probe, in the same file, re-attached two of them with a
     * comment explaining that forgetting them makes a whole suite "quietly measure a different game".
     *
     * @param binder {@code null} &rarr; the production pool binder
     * @param clock  {@code null} &rarr; the production space clock
     * @param config {@code null} &rarr; the manager config the current AR config asks for
     */
    public SpaceSubsystem(SlotBinder binder, java.util.function.LongSupplier clock,
                          SpaceManager.Config config) {
        // Read once, and tolerated as absent: a caller that supplies its own config is not asking
        // this constructor to consult the game's, and a unit test has no config at all. Every use of
        // cfg below is null-guarded for that reason, not by accident.
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();
        SlotBinder useBinder = binder == null ? new PoolSlotBinder() : binder;
        java.util.function.LongSupplier useClock = clock == null ? SpaceSubsystem::spaceClock : clock;
        SpaceManager.Config useConfig = config != null ? config
                : new SpaceManager.Config(parseGcPolicy(cfg == null ? null : cfg.spaceCellGcPolicy),
                        cfg == null ? 0L : cfg.spaceCellMaxAgeTicks,
                        cfg == null ? 0 : cfg.spaceMaxStoredCells);
        this.manager = new SpaceManager(useBinder, useClock, useConfig,
                SpaceSubsystem::onForcedTier1Eviction);
        this.ledger = new ShipLedger();
        // A cell is protected from garbage collection while a ship is parked in it. That fact already
        // lives in the ledger, so the manager asks it rather than keeping a second flag of its own.
        this.manager.setClaimedCells(cellKey -> this.ledger.holdsShipIn(cellKey));
        // The WORLD's lane allocator, never a fresh one: lanes are a property of the single hyperspace
        // world every subsystem parks in, so a per-subsystem allocator hands out lanes another one is
        // already using.
        this.transit = new ShipTransitManager(this.manager, HyperspaceWorld.lanes(),
                new VSShipCrosser(), this.ledger, useClock);
        this.transit.setOfflineProgress(new OfflineProgress(
                OfflineProgress.parseMode(cfg == null ? null : cfg.spaceTransitOfflineProgress),
                SpaceSubsystem::isPlayerOnline));
        this.transit.setArrivalPlacement(SpaceSubsystem::arrivalStandoff);
        this.transit.setFrames(SpaceSubsystem::cellFrameOriginAt);
        this.entry = new ShipEntryController(this.manager, this.ledger, new VSShipCrossingOps(),
                SpaceSubsystem::launchBodyAddress, useClock);
        this.descent = new DescentController(this.manager, this.ledger, new VSShipCrossingOps(),
                new VSDescentPasteResolver(), useClock);
        this.cellCrossings = new CellCrossingController(this.manager, this.ledger, new VSShipCrossingOps(),
                useClock);
        // A jump too short to be worth a hyperspace leg is performed by the same machinery that carries
        // a ship across a cell face — one crossing, ledger straight to the destination, no lane and no
        // mid-flight. The transit manager decides WHICH jumps those are; this hands it the means.
        this.transit.setDirectCrosser((shipId, origin, originSlotDim, originAnchor, target) -> {
            // The transit manager keys ships by STRING, the ledger and the crossing by UUID. Not every
            // string is one: a fixture may depart under a synthetic name, and a crossing cannot look
            // that up. Refuse it here rather than throw out of a departure the pilot has paid for.
            java.util.UUID durableId;
            try {
                durableId = java.util.UUID.fromString(shipId);
            } catch (IllegalArgumentException notADurableId) {
                AdvancedRocketry.logger.warn("[SPACE] direct crossing refused for ship '{}': it is not "
                        + "a durable id, so nothing can resolve it in the ledger", shipId);
                return false;
            }
            return this.cellCrossings.requestDirectJump(originSlotDim, originAnchor, durableId,
                    origin, target);
        });
    }

    /** The live subsystem, or {@code null} when none is attached (before server start, or on a client). */
    public static SpaceSubsystem get() {
        return current;
    }

    /** Take {@code subsystem} as the live one. The server lifecycle is the only caller. */
    static void attach(SpaceSubsystem subsystem) {
        if (current != null && subsystem != null) {
            // Loud, because it is not survivable-and-quiet: two live subsystems over one slot pool
            // means somebody's ships are being ticked by a manager that does not know about them.
            AdvancedRocketry.logger.error("[SPACE] a subsystem is being attached while one is already "
                    + "live - the previous one is dropped without a detach. This is a lifecycle bug; "
                    + "treat it as a report.");
        }
        current = subsystem;
    }

    /** Release the live subsystem. Paired with {@link #attach} on the server-stop hook. */
    static void detach() {
        current = null;
    }

    /**
     * Put {@code replacement} in place and hand back the way BACK. Closing the handle restores
     * whatever was live at install time — the production subsystem, a previous install, or nothing.
     *
     * <p>This is the whole test seam, and the shape it replaces was a filed defect: a setter that took
     * five services, kept no copy of them, and offered a "clear" that assigned five nulls. The
     * production subsystem was then gone for the rest of the boot, because the hook that builds it
     * runs once per server start.</p>
     */
    public static Handle install(SpaceSubsystem replacement) {
        SpaceSubsystem previous = current;
        current = replacement;
        return new Handle(previous, replacement);
    }

    /**
     * The way back from an {@link #install}. Idempotent, and it will not stamp on a THIRD party: if
     * something else has installed over us since, closing leaves that alone rather than resurrecting
     * a subsystem two generations old.
     */
    public static final class Handle implements AutoCloseable {
        private final SpaceSubsystem previous;
        private final SpaceSubsystem installed;
        private boolean closed;

        Handle(SpaceSubsystem previous, SpaceSubsystem installed) {
            this.previous = previous;
            this.installed = installed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (current == installed) {
                current = previous;
            }
        }
    }

    // ---- null-safe conveniences over get() -----------------------------------------------------
    // Readers are tiles and event subscribers that want "the live X, or nothing". These are READS of
    // ONE instance, which is a different animal from the five independently-writable fields this
    // class used to hold: there is one writer, one lifecycle, and no way for two of them to describe
    // different subsystems. Anything that needs more than a read takes the instance from get().

    /** The live cell manager, or {@code null} when no subsystem is attached. */
    public static SpaceManager space() {
        return current == null ? null : current.manager;
    }

    /** The live ship ledger, or {@code null} when no subsystem is attached. */
    public static ShipLedger ledger() {
        return current == null ? null : current.ledger;
    }

    /** The live transit state machine, or {@code null} when no subsystem is attached. */
    public static ShipTransitManager transit() {
        return current == null ? null : current.transit;
    }

    /** The live entry controller, or {@code null} when no subsystem is attached. */
    public static ShipEntryController entry() {
        return current == null ? null : current.entry;
    }

    /** The live cell-to-cell crossing controller (seam carries AND short jumps), or {@code null}
     *  when no subsystem is attached. */
    public static CellCrossingController cellCrossings() {
        return current == null ? null : current.cellCrossings;
    }

    /** The live descent controller, or {@code null} when no subsystem is attached. */
    public static DescentController descent() {
        return current == null ? null : current.descent;
    }

    /** One GC tick of this subsystem's cadence; {@code true} when a sweep ran. */
    boolean tickGc() {
        boolean run = false;
        if (pressureGcRequested) {
            pressureGcRequested = false;
            run = true;
        }
        if (++gcTickCounter >= GC_TICK_INTERVAL) {
            gcTickCounter = 0;
            run = true;
        }
        return run;
    }

    /** Ask for an extra GC sweep on the next tick (the pool-pressure trigger). */
    void requestPressureGc() {
        pressureGcRequested = true;
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
        boolean alreadyBuilt = current != null;
        if (!shouldRegister(cfg.enableSpaceSubsystem, vsAvailable, alreadyBuilt)) {
            // Log the operator-facing reason (already-built is an internal, expected no-op that
            // must stay quiet).
            if (!alreadyBuilt) {
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
        // Through the same factory every other caller uses, with no knob overridden: production IS
        // the default, so "the probe wired something production does not" and "production wired
        // something the probe does not" are both off the table by construction.
        // Through the same constructor every other caller uses, with no knob overridden: production
        // IS the default, so "the probe wired something production does not" and its mirror are both
        // off the table by construction.
        attach(new SpaceSubsystem(null, null, mgrConfig));
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
        SpaceSubsystem live = current;
        if (live == null) {
            return;
        }
        if (data != null) {
            data.loadInto(live.ledger);
            AdvancedRocketry.logger.info("[SPACE] restored {} settled ship(s) from disk", live.ledger.size());
            // Restore when each cell was last visited, or every stored cell looks freshly visited on
            // this boot and age-based collection can never reach an earlier session's leftovers.
            live.manager.importVisits(data.loadVisits());
            // Recreate any in-flight jump so a transit survives a restart: a record whose hull is still
            // standing in its lane resumes as that same ship, and one whose lane came back empty falls
            // back to the block snapshot it carries. The ledger is re-marked IN_TRANSIT inside
            // importTransit.
            //
            // LOAD hyperspace first, and this is load-bearing rather than tidy. Both readers below
            // ask what is standing in a lane, and both ask it of the world only IF IT IS LOADED -
            // an honest refusal to create a world as a side effect of inspecting one. Hyperspace is
            // otherwise loaded lazily by the first crossing, which happens long after this runs, so
            // without this every record would see an empty lane and take the snapshot path, and the
            // reconciliation below would find nothing to collect however many hulls were there.
            // Skipped entirely when the save has no hyperspace folder: then there is provably
            // nothing parked, and loading would pin an empty world on every boot of every save.
            if (SpaceSlotPool.hyperspaceStoreExists()) {
                HyperspaceWorld.getOrCreate();
            }
            java.util.List<TransitRecord> records = data.loadTransits();
            for (TransitRecord r : records) {
                live.transit.importTransit(r);
            }
            if (!records.isEmpty()) {
                AdvancedRocketry.logger.info("[SPACE] restored {} in-flight transit(s) from disk",
                        records.size());
            }
            // JUMP-10, and it belongs HERE - after the last record has been imported. Hyperspace
            // outlives the server, so a hull can outlive the record that put it there; every ship
            // found in it is matched against the transits that claim a lane and the rest are
            // disposed of. Run one record too early and a perfectly good ship looks unclaimed.
            int disposed = live.transit.reconcileParkedShips();
            if (disposed > 0) {
                AdvancedRocketry.logger.warn("[SPACE] disposed of {} ship(s) parked in hyperspace "
                        + "that no transit record claims", disposed);
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
        ShipTransitManager transit = transit();
        if (transit == null) {
            return;
        }
        try {
            int refreshed = transit.refreshSnapshots();
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
    static void failSavePointIfArmed() {
        if (saveFaultArmed) {
            saveFaultArmed = false;
            throw new IllegalStateException("armed ship-ledger save fault");
        }
    }

    /** Server-stop teardown. The slot dimensions stay registered (JVM-global); only the controller resets. */
    public static void onServerStopped() {
        // Released, not nulled field-by-field: the subsystem is one object with one lifetime, and
        // the server that is stopping is the one it belonged to.
        detach();
        // The clock belongs to the save that was just closed. A single-player client keeps this JVM
        // alive between worlds, so carrying the number over would date the next world's first jump
        // against the previous world's history; the next server-started hook reads its own.
        spaceTick = 0L;
        saveFaultArmed = false;
        SystemBodiesProducer.reset();
        zmaster587.advancedRocketry.universe.SystemContent.reset();
        HyperspaceWorld.reset();
        // The diagnostics describe the stack that has just gone; carrying them into the next server
        // is how "the last re-seat was blocked at X" ends up describing a jump from another session.
        SpaceDiagnostics.reset();
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
    /**
     * Advance the subsystem's clock by one tick. THE ONLY writer besides the restore, and it is
     * called from exactly one place ({@link SpaceSubsystemEvents}'s server tick) — two writers on the
     * same event would run the clock at twice the tick rate and nothing would report it.
     */
    static void advanceClock() {
        spaceTick++;
    }

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
        // The listener is a static callback handed to the manager at construction, so it reaches the
        // subsystem the way everyone else does. A pressure signal arriving with nothing attached is
        // simply nobody's.
        SpaceSubsystem live = current;
        if (live != null) {
            live.requestPressureGc();
        }
    }

}
