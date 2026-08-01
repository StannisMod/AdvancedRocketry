package zmaster587.advancedRocketry.tile;

import java.util.LinkedList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;

import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;

/**
 * Advanced Flight Computer — the block that marks an assembled craft as a
 * <em>tier-2 movable ship</em> rather than a tier-1 rocket.
 *
 * <p>The launch-pad assembler decides a craft's tier from its content: a build
 * that contains an Advanced Flight Computer is assembled into a walk-on, physics
 * driven ship; without one it stays an ordinary rocket. Naming is deliberately
 * distinct from the Guidance Computer and other on-board computers so the two are
 * never confused in code or GUI.</p>
 *
 * <p>On a tier-2 ship this tile is the flight computer: a seated pilot's Free Flight
 * input, plus the ship's own attitude read back from the physics mod, drive a
 * velocity setpoint on the ship. That control loop lands in a later phase; the only
 * state persisted here is the pilot's Flight-Assist on/off choice (per the design,
 * the ship remembers only its FA setting — the velocity setpoint is captured live
 * on enable, and the engine-start ritual is not persisted). All physics-mod calls
 * stay behind the optional integration gate, so this class never hard-depends on it.</p>
 */
public class TileAdvancedFlightComputer extends TileEntity implements IModularInventory, ITickable {

    private static final String NBT_FLIGHT_ASSIST = "faEnabled";
    private static final String NBT_STATION_KEEPING = "stationKeeping";
    private static final String NBT_SHIP_ID = "shipId";
    private static final String NBT_ENTRY_LATCHED = "entryLatched";

    /**
     * The ship's durable identity: a UUID minted once (at tier-2 assembly, or lazily on first
     * use for a pre-existing computer) and persisted in this tile's NBT. Crossings carry tile
     * NBT verbatim, so this id survives every jump/re-assembly — unlike the physics mod's own
     * ship UUID, which is re-minted on every re-assembly and must never key durable state
     * (ledger, transit, persistence all key on THIS id).
     */
    private java.util.UUID shipId = null;

    /**
     * Whether the AUTO-TAKEOFF autopilot is engaged: a diagonal climb to orbit, driven from this
     * computer instead of a held throttle. Live state, never persisted (an autopilot must not
     * survive a reload and resume climbing an unattended ship). Counts as "a pilot is flying" for
     * the entry ceiling check; self-disengages when the corridor is blocked (a surfaced decline —
     * fall back to manual) or once the ship enters space.
     */
    private volatile boolean autoTakeoffEngaged = false;

    /**
     * Whether this ship holds station (hover + attitude) while unmanned. Set true the first time a pilot
     * flies it, and PERSISTED: the live {@link #attitudeReference} does not survive a world save/load, so
     * without a saved flag a hovering ship dropped out of the sky the instant its world was reloaded and
     * then tumbled inverted (live playtest 2026-07-11). Never auto-cleared - there is no engine-shutdown
     * yet, and a parked ship holding position (in the air, or resting on the ground) is the intent.
     */
    private boolean stationKeeping = false;

    /**
     * Entry-trigger hysteresis after a descent. A descent puts the ship down in the AIR, well above
     * the destination's terrain — which can be ABOVE that dimension's own orbit line. The entry
     * on-ramp fires on "a piloted ship is above the orbit line", so without this the ship would be
     * taken straight back into space on the tick it arrived, and the pilot could never reach the
     * surface of a body whose orbit line sits low.
     *
     * <p>Set true by the descent as it commits the crossing, and cleared the first time this ship is
     * observed AT OR BELOW the effective entry line. It is a LATCH, not a countdown: a timer would
     * expire while the ship was still up high and let the bounce happen anyway. The clear condition
     * is the exact complement of the trigger condition ({@code y > line}), so one evaluation can
     * never both re-arm and fire.</p>
     *
     * <p>Persisted, and for the same reason {@link #stationKeeping} is: the state has to outlive a
     * relog, a server restart and the ship being unloaded and re-loaded, or a player who logs out
     * over a body and back in is bounced on his first tick of flight. It lives HERE rather than in
     * the ship ledger because a descended ship has LEFT the ledger (the descent drops its entry when
     * the crossing cuts it out of its cell), and because crossings carry tile NBT verbatim — so the
     * latch set on the source ship rides the crossing to the destination on its own.</p>
     *
     * <p>Absent key -&gt; false: an unmanned or newly assembled ship starts with entry ARMED. Only a
     * descent arrival ever latches it.</p>
     */
    private boolean entryLatched = false;

    /**
     * Said once per tile, not once per tick: a ship sitting against its cell's boundary would
     * otherwise report it twenty times a second. Not persisted — a fresh tile after a relocation
     * genuinely should say it again if the ship is still there.
     */
    private transient boolean cellEdgeReported = false;

    /**
     * Bring-up command for the force-mode flight controller: the desired world-frame
     * velocity {@code {x,y,z}} (blocks/s), or {@code null} when nothing is commanded.
     * Written from the GAME thread (a test probe today; the seated pilot's input later);
     * read on the Valkyrien Skies PHYSICS thread by the flight-controller mixin, which turns
     * it into force. {@code volatile} for cross-thread visibility. AR-core only — carries no
     * physics-mod type, so this class still loads fine without the physics mod installed.
     * TODO: replace this static bring-up channel with per-tile pilot state once the pilot
     * seat + input retarget land.
     */
    public static volatile double[] debugCommandedVelocity = null;

    /**
     * Bring-up command for the force controller's ANGULAR channel: the desired world-frame
     * angular velocity {@code {x,y,z}} (rad/s), or {@code null} when none. Same game&rarr;physics
     * thread hand-off + AR-core-only contract as {@link #debugCommandedVelocity}; the mixin
     * turns it into torque. TODO: fold into per-tile pilot state with the linear channel.
     */
    public static volatile double[] debugCommandedAngVel = null;

    /**
     * Bring-up command for ATTITUDE HOLD: the target body&rarr;world orientation as a quaternion
     * {@code {w,x,y,z}}, or {@code null} when not holding an attitude. When set it supersedes
     * {@link #debugCommandedAngVel} — the controller reads the ship's current orientation on
     * the physics thread and turns the error into the angular velocity it drives toward. This
     * is the interface Free Flight feeds: its per-tick target quaternion (from
     * {@code integrateBodyRates} over the held pilot rates) is published here. Same game&rarr;physics
     * hand-off + AR-core-only contract as the other channels.
     */
    public static volatile double[] debugTargetAttitude = null;

    /**
     * Bring-up channel for the pilot's held {@link FreeFlightInput}. When set, this tile's
     * server tick runs the Free Flight decision layer over the ship's current attitude and
     * publishes the resulting desired velocity + target attitude to the controller channels
     * above. AR-core only. TODO: replace this static bring-up input with per-seat pilot state.
     */
    public static volatile FreeFlightInput debugFlightInput = null;

    /**
     * The seated pilot's live {@link FreeFlightInput} for THIS computer, or {@code null} when
     * nobody is piloting. Written from the server game thread when a pilot-seat packet arrives
     * (see {@code TilePilotSeat}); read by {@link #update()}. Takes precedence over the static
     * {@link #debugFlightInput} bring-up channel — that static one stays only as a test-probe
     * fallback. {@code volatile} for visibility across the seat-packet and tick call sites.
     */
    public volatile FreeFlightInput pilotInput = null;

    /**
     * Per-tile commanded world-frame velocity (blocks/s) that the force controller realizes,
     * or {@code null} when this computer commands nothing. Written by {@link #update()} from the
     * pilot's input; read on the physics thread by the flight-controller mixin, which prefers it
     * over the static {@link #debugCommandedVelocity} probe channel. {@code volatile} for the
     * game&rarr;physics thread hand-off; carries no physics-mod type (AR-core safe).
     */
    public volatile double[] commandedVelocity = null;

    /** Per-tile angular-velocity command (rad/s), mixin-preferred over {@link #debugCommandedAngVel}. */
    public volatile double[] commandedAngVel = null;

    /** Per-tile attitude-hold target quaternion {@code {w,x,y,z}}, mixin-preferred over
     *  {@link #debugTargetAttitude}. Supersedes {@link #commandedAngVel} when set. */
    public volatile double[] targetAttitude = null;

    /**
     * Ship cruise speed cap (blocks/second) mapped from full throttle. Public because the flight HUD
     * scales its velocity bars by the craft's own top speed. {@code tunable}.
     *
     * <p><b>A STOPGAP.</b> Every craft has the same top speed because nothing yet derives one from
     * what the ship is built out of; when thrust is computed from the engines and the mass, this
     * constant is what that calculation replaces. Until then it is a flat number chosen for how long
     * a climb to orbit takes, not for physical plausibility.</p>
     *
     * <p>The physics mod's own "moving too fast" freeze is not the binding constraint it was once
     * assumed to be: it trips at {@code |v|² > 50000}, i.e. ~223 blocks/s
     * ({@code PhysicsCalculations.isPhysicsBroken}), so this leaves a factor of five in hand. What
     * does scale with it is collision: the ship advances {@code SHIP_MAX_SPEED/20} blocks per tick,
     * so a cap raised far beyond this starts stepping whole blocks between physics steps.</p>
     */
    public static final double SHIP_MAX_SPEED = 40.0;

    /** Setpoint ramp (blocks/s per tick) while a throttle is held: full deflection sweeps an axis
     *  from rest to {@link #SHIP_MAX_SPEED} in 60 ticks (3 s), matching Free Flight's feel. */
    private static final double SHIP_SETPOINT_RAMP = SHIP_MAX_SPEED / 60.0;

    /**
     * The pilot's body-frame velocity setpoint (blocks/s) while Flight Assist is on - the ship's
     * cruise control. Holding a throttle ramps it; RELEASING LEAVES IT (the ship keeps cruising);
     * cut (X) or brake (Shift) zero it. Live state only: not persisted, and re-captured from the
     * ship's actual velocity whenever the pilot switches Flight Assist back on.
     */
    private double[] velocitySetpoint = new double[]{0.0, 0.0, 0.0};

    /** Set when the pilot enables Flight Assist, so the next tick seeds {@link #velocitySetpoint}
     *  from the ship's live velocity instead of jerking the ship to the stale setpoint. */
    private boolean captureSetpointOnNextTick = false;

    /** Diagnostic only ({@code -Dadvancedrocketry.tests=true}): last-logged presence of a live
     *  {@link #pilotInput}, so a playtest trace prints one line each time the seated pilot's input
     *  appears or is cleared. Not gameplay state. */
    private transient Boolean arLastPilotPresent = null;

    /**
     * The attitude the ship is being held at - a PERSISTENT reference the pilot's rates steer, not a
     * fresh reading of where the ship happens to be pointing.
     *
     * <p>This is the whole difference between a ship and a rocket. A rocket's attitude IS its state:
     * zero input freezes it, because there is nothing else moving it. A ship is a force-controlled
     * rigid body that carries angular momentum, so if the controller re-anchors its target to the
     * measured attitude every tick, a centred cursor commands "hold wherever you have drifted to" and
     * the spin never stops. Holding the reference makes zero input mean zero rotation, as the pilot
     * expects from Free Flight.</p>
     *
     * <p>Live state, not persisted. It is the pilot's own while he is turning; the moment he stops
     * asking for rotation it is pinned to wherever the ship actually is, so the controller brakes the
     * spin rather than hauling the craft back through the lag it had built up. It is also re-seeded
     * whenever the ship has been knocked far enough off it (a collision) that chasing it would lurch.</p>
     */
    private FreeFlightPhysics.Quat attitudeReference = null;

    /** Beyond this much orientation error (radians, ~60 degrees) the reference is abandoned and
     *  re-seeded from the ship's real attitude. Something the pilot did not command moved the ship -
     *  a collision, a chunk reload - and hauling it back would be a lurch, not a correction. */
    private static final double ATTITUDE_REFERENCE_RESEED = Math.PI / 3.0;

    /** Body-frame velocity (blocks/tick) and setpoint published for the pilot's HUD. Written every
     *  server tick, with or without pilot input; read by the seat's dummy to sync to the client. */
    private volatile double[] hudBodyVelocity = new double[]{0.0, 0.0, 0.0};
    private volatile double[] hudSetpoint = new double[]{0.0, 0.0, 0.0};

    /** Ticks per second, to convert the ship's blocks/second physics values into the blocks/tick the
     *  Free Flight HUD speaks (tier-1 fills the same fields from entity motion, which is per-tick). */
    private static final double TICKS_PER_SECOND = 20.0;

    /**
     * What the force controller last did, written from the PHYSICS thread and read by a test probe.
     * The controller runs where no breakpoint and no log line is welcome, so without this the only way
     * to tell an under-powered brake from a mis-framed torque is to guess.
     * {@code {dt, alphaX, alphaY, alphaZ, omegaX, omegaY, omegaZ, errorAngle}}
     */
    public static volatile double[] debugControllerState = null;

    /**
     * Set (or clear) the seated pilot's Free Flight input for this computer. Server-side; called
     * by the pilot seat when a control packet arrives, and with {@code null} when the pilot
     * leaves. A {@code null} pilotInput lets {@link #update()} fall back to the static bring-up
     * channel and, absent that, leaves the last command in place (the ship coasts). A pilot
     * wanting to stop sends an idle input, which {@link #update()} turns into a hover.
     */
    public void setPilotInput(FreeFlightInput input) {
        this.pilotInput = input;
    }

    /**
     * The pilot's control station was DESTROYED (his seat mined, blown up, or otherwise removed
     * outside a relocation) while this computer survives. Drop the live command state: the last
     * received input and the cruise setpoint are zeroed, so the ship reverts to an unmanned
     * station-hold instead of flying the destroyed station's last command forever (with Flight
     * Assist on, a held throttle would otherwise keep ramping the cruise — an uncontrollable
     * runaway). The Flight-Assist MODE and the station-keeping flag are settings, not commands —
     * both are retained.
     */
    public void onControlStationLost() {
        pilotInput = null;
        velocitySetpoint = new double[]{0.0, 0.0, 0.0};
    }

    /**
     * This tile is being removed (block broken, chunk cut for a relocation, world teardown). The
     * live command channels die with it: the physics-thread force controller reads them off the
     * tile object it captured, so a destroyed computer whose channels stayed set could keep
     * thrusting a ship nobody can control. A relocation's fresh tile starts with clean channels
     * anyway, so clearing here is always safe.
     */
    @Override
    public void invalidate() {
        super.invalidate();
        pilotInput = null;
        commandedVelocity = null;
        commandedAngVel = null;
        targetAttitude = null;
        velocitySetpoint = new double[]{0.0, 0.0, 0.0};
    }

    /**
     * Server tick: when a Free Flight input is held and this tile's block is part of a physics
     * ship, run the FF decision layer and publish the command the controller realizes. Reads
     * the ship's current attitude from the physics mod (through the AR-core gate — no physics
     * type here), advances it by the pilot's body rates for the target attitude, and maps the
     * throttles into a world-frame desired velocity. A safe no-op without the physics mod, off
     * a ship, or with no input.
     */
    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        // The jump wind-up runs before anything else, and before the physics checks below can bail
        // out: a spool that quietly stopped counting because the ship's attitude was momentarily
        // unresolvable would leave a pilot waiting for a window that is never going to open.
        tickJumpSpool();
        FreeFlightPhysics.Quat attitude = VSIntegration.getShipAttitude(world, getPos());
        if (attitude == null) {
            return; // not on a physics ship (or physics mod absent)
        }
        // Telemetry first, and unconditionally: the pilot's HUD must keep reading the ship's real
        // velocity while he holds no key at all, which is exactly when the input channel is idle.
        publishHudTelemetry(attitude);

        // Parked in hyperspace: FLIGHT input is ignored for the whole transit — the pilot must not
        // fight the park (the transit integrator owns the ship's coordinate; its physics is off).
        // The single source of this gate is the ship's presence in the shared hyperspace world:
        // ships exist there exactly while parked mid-transit. Deliberate-exit and the exit-warning
        // channel stay OUTSIDE this gate when they land — they are the survival path mid-transit.
        int hyperDim = zmaster587.advancedRocketry.space.HyperspaceWorld.dimId();
        if (hyperDim != Integer.MIN_VALUE && world.provider.getDimension() == hyperDim) {
            commandedVelocity = null;
            commandedAngVel = null;
            targetAttitude = null;
            return;
        }

        // Settled in a cell: self-report the ship's galactic coordinate to the ledger each tick
        // (the inverse honest-3D pose mapping). The descent proximity check reads the ledger, so
        // no per-tick VS ship enumeration is ever needed — symmetric with the ascent ceiling read.
        if (world.provider instanceof zmaster587.advancedRocketry.space.WorldProviderSpaceSlot
                && shipId != null) {
            zmaster587.advancedRocketry.space.ShipLedger ledger =
                    zmaster587.advancedRocketry.space.SpaceSubsystem.ledger();
            String cellKey = zmaster587.advancedRocketry.space.SpaceSlotPool
                    .cellKeyFor(world.provider.getDimension());
            zmaster587.advancedRocketry.space.GalacticCoord cell =
                    zmaster587.advancedRocketry.space.GalacticCoord.fromCellKey(cellKey);
            if (ledger != null && cell != null) {
                double[] pose = VSIntegration.getShipWorldPosition(world, getPos());
                if (pose != null) {
                    // A ship reports its position WITHIN its cell. It may not rename the cell by moving:
                    // the name is the world it is in, the slot it is bound to and the ledger row that
                    // protects that cell from collection, and none of those follow a pose over a cell
                    // face. A pose outside the local range is therefore saturated, not carried.
                    ledger.updatePosition(shipId, zmaster587.advancedRocketry.space.CellWorldMapper
                            .coordOfPoseWithin(cell, pose[0], pose[1], pose[2]));
                    if (!cellEdgeReported && zmaster587.advancedRocketry.space.CellWorldMapper
                            .poseEscapesCell(pose[0], pose[1], pose[2])) {
                        cellEdgeReported = true;
                        zmaster587.advancedRocketry.AdvancedRocketry.logger.warn(
                                "[SPACE] ship {} reached the edge of cell {} (pose {},{},{}) - its position "
                                        + "is held at the boundary. Leaving a neighbourhood is a jump, not a "
                                        + "flight.",
                                shipId, cellKey, pose[0], pose[1], pose[2]);
                    }
                }
            }
        }

        // The seated pilot's per-tile input wins; the static channel is only a test-probe fallback.
        FreeFlightInput in = pilotInput != null ? pilotInput : debugFlightInput;
        // "Flying" for the entry ceiling check = a held input OR the auto-takeoff autopilot driving.
        boolean flying = in != null || autoTakeoffEngaged;

        // Entry on-ramp: a ship climbing past the launch dimension's orbit ceiling enters space.
        // Never from a space-subsystem world (slot cells and hyperspace share the void provider),
        // and only while a pilot is flying (manual throttle or the autopilot) — a station-keeping
        // hulk drifting high stays put.
        boolean onPlanetSide =
                !(world.provider instanceof zmaster587.advancedRocketry.space.WorldProviderSpaceSlot);

        // Descent trigger (the inverse of the entry ceiling check): a SETTLED slot-world ship whose
        // pilot is flying, closed within the descent radius of a descend-target body, drops into that
        // body's planet dim. Proximity reads the ledger coord (self-reported above) + the body POIs of
        // the ship's own cell — no VS enumeration. Only planets/moons with a real dim are targets.
        if (flying && !onPlanetSide && shipId != null) {
            zmaster587.advancedRocketry.space.DescentController descentCtl =
                    zmaster587.advancedRocketry.space.SpaceSubsystem.descent();
            zmaster587.advancedRocketry.space.ShipLedger descentLedger =
                    zmaster587.advancedRocketry.space.SpaceSubsystem.ledger();
            net.minecraft.server.MinecraftServer server = world.getMinecraftServer();
            if (descentCtl != null && descentLedger != null && server != null) {
                zmaster587.advancedRocketry.space.ShipLedger.Entry settled = descentLedger.get(shipId);
                if (settled != null
                        && settled.state == zmaster587.advancedRocketry.space.ShipLedger.State.SETTLED) {
                    zmaster587.advancedRocketry.universe.UniverseRegistry reg =
                            zmaster587.advancedRocketry.universe.UniverseRegistry.get(server);
                    if (reg != null) {
                        zmaster587.advancedRocketry.space.GalacticCoord shipCoord = settled.coord;
                        long radius = zmaster587.advancedRocketry.space.ShipEntryController.DESCENT_RADIUS_BLOCKS;
                        for (zmaster587.advancedRocketry.universe.SystemBody body : reg.bodiesAt(shipCoord)) {
                            if (!body.isDescendTarget()) {
                                continue;
                            }
                            double distance = Math.sqrt(shipCoord.distanceSqTo(body.address()));
                            if (zmaster587.advancedRocketry.space.DescentController
                                        .shouldTriggerDescent(true, true, distance, radius)
                                    && descentCtl.requestDescent(world.provider.getDimension(),
                                            getPos(), shipId, body.dimId())) {
                                // The crossing started: this tile was cut out of the slot world - stop
                                // publishing from a stale tick. The re-assembled ship resumes planet-side.
                                return;
                            }
                        }
                    }
                }
            }
        }

        // RE-ARM the post-descent entry latch. Runs whether or not anyone is flying: a ship that
        // drifts or is carried back down below the line has satisfied the condition just as much as
        // one that was flown down, and the pilot must not have to be at the controls at the exact
        // tick it happens. Costs a position read only while the latch is actually set, which is the
        // rare case (it is set by a descent arrival and cleared on the way down).
        if (onPlanetSide && entryLatched) {
            double[] latchPos = VSIntegration.getShipWorldPosition(world, getPos());
            if (latchPos != null && latchPos[1] <= entryCeiling()) {
                entryLatched = false;
                markDirty();
            }
        }

        if (flying && onPlanetSide) {
            zmaster587.advancedRocketry.space.ShipEntryController entryCtl =
                    zmaster587.advancedRocketry.space.SpaceSubsystem.entry();
            double[] shipPos = VSIntegration.getShipWorldPosition(world, getPos());
            int ceiling = entryCeiling();
            if (entryCtl != null && shipPos != null && !entryLatched
                    && zmaster587.advancedRocketry.space.ShipEntryController
                            .shouldTriggerEntry(false, true, shipPos[1], ceiling)
                    && entryCtl.requestEntry(world.provider.getDimension(), getPos(),
                            getOrCreateShipId())) {
                // The crossing started: this tile has just been cut out of the world - do not
                // publish commands from a stale tick. The re-assembled ship's own computer
                // resumes in the cell. (A REFUSED entry falls through: the pilot keeps full control
                // to fly back below the ceiling; the autopilot keeps climbing for the next retry.)
                autoTakeoffEngaged = false; // entry took over; the climb is done
                return;
            }
            // AUTO-TAKEOFF autopilot: drive a diagonal climb toward orbit while engaged and below the
            // ceiling. A blocked corridor is a NORMAL surfaced decline (disengage, fall back to manual).
            if (autoTakeoffEngaged && shipPos != null && in == null) {
                if (driveAutoTakeoff(attitude, shipPos, ceiling)) {
                    return; // the autopilot published this tick's command
                }
                // else: corridor blocked — disengaged inside driveAutoTakeoff; fall through to
                // station-keeping so the ship holds instead of coasting.
            }
        }
        // Playtest trace ([FF-TRACE/AFC], -Dadvancedrocketry.tests=true): log each null<->set flip of
        // the seated pilot's input. If it flips to null while the pilot holds a key, the ship falls into
        // the station-keeping branch below and reads as "unresponsive"; a stable SET means the input
        // reaches the AFC and any freeze is downstream (mixin/VS). No-op in normal play.
        if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()) {
            boolean present = pilotInput != null;
            if (arLastPilotPresent == null || arLastPilotPresent != present) {
                zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/AFC] " + getPos()
                        + " pilotInput=" + (present ? "SET" : "null") + " stationKeeping=" + stationKeeping);
                arLastPilotPresent = present;
            }
        }
        if (in == null) {
            // Nobody is flying. A ship that has NEVER been flown this load stays inert - its physics is
            // off, so it just rests and there is nothing to hold. But a ship that WAS being flown keeps
            // EXECUTING its retained Flight-Assist setting when the pilot stands up: with FA on and a
            // non-zero cruise setpoint it KEEPS CRUISING at that setpoint (that is what makes it an
            // autopilot - the pilot dismounts mid-flight and the ship flies on); with a zero setpoint,
            // or FA off, it degenerates to holding station: hover in place, at the attitude he left it,
            // until a pilot returns. A hovering craft is not coasting - it needs continuous force to
            // fight gravity, so the instant the controller stops commanding it falls out of the sky (the
            // playtest: stood up mid-hover, the ship dropped and took the pilot down with it).
            // The "was flown" witness is the PERSISTED stationKeeping flag, not the live attitudeReference
            // (which is null after a reload). A never-flown ship (physics off) stays inert; a ship that has
            // been flown holds its setting, and holds station again after a world reload instead of falling.
            // The setpoint is deliberately NOT zeroed and NOT re-captured here: the dismounted pilot's
            // cruise setting is his to come back to, never a reset-from-live-velocity.
            if (!stationKeeping) {
                commandedVelocity = null;
                commandedAngVel = null;
                targetAttitude = null;
                return;
            }
            if (attitudeReference == null) {
                attitudeReference = attitude; // re-seed from the ship's current attitude after a reload
            }
            VSIntegration.ensureShipPhysicsEnabled(world, getPos());
            // FA on: an idle input over the retained setpoint IS the cruise command (zero setpoint =
            // hover). FA off: no cruise control exists - hold station at zero velocity explicitly
            // (shipVelocityCommand would answer "coast", and a coasting hover falls).
            commandedVelocity = flightAssistEnabled
                    ? FreeFlightPhysics.shipVelocityCommand(FreeFlightInput.zero(), attitudeReference,
                            true, velocitySetpoint, SHIP_MAX_SPEED)
                    : new double[]{0.0, 0.0, 0.0};
            commandedAngVel = new double[]{0.0, 0.0, 0.0};
            targetAttitude = new double[]{attitudeReference.w, attitudeReference.x,
                    attitudeReference.y, attitudeReference.z};
            return;
        }
        // A pilot is flying: from now on this ship holds station when unmanned - persisted, so the hold
        // survives a world reload.
        if (!stationKeeping) {
            stationKeeping = true;
            markDirty();
        }
        VSIntegration.ensureShipPhysicsEnabled(world, getPos());

        double pitchRate = in.pitchInput * FreeFlightPhysics.MAX_PITCH_RATE;
        double yawRate = in.yawInput * FreeFlightPhysics.MAX_YAW_RATE;
        double rollRate = in.rollInput * FreeFlightPhysics.MAX_ROLL_RATE;
        boolean turning = pitchRate != 0.0 || yawRate != 0.0 || rollRate != 0.0;

        // The attitude the pilot is steering.
        //
        // While he asks for no rotation, the reference is pinned to where the ship IS. That makes the
        // controller a pure rate brake: "stop turning", not "fly back to where you were steering
        // toward". The two are very different to fly. A ship lags the reference it is chasing, so a
        // reference that stayed put the moment the pilot centred his controls would haul the craft back
        // through that lag - it would keep swinging after he asked it to stop, which is the very thing
        // he complained about. Once the spin is gone the reference stops moving with it, and the same
        // law holds the attitude against anything that tries to turn the ship.
        //
        // While he IS turning, the reference is his: advanced by his rates, independent of where the
        // ship has got to. Re-seeded only when something uncommanded threw the ship far off it.
        if (!turning || attitudeReference == null
                || attitudeError(attitudeReference, attitude) > ATTITUDE_REFERENCE_RESEED) {
            attitudeReference = attitude;
        }
        FreeFlightPhysics.Quat target = FreeFlightPhysics.integrateBodyRates(attitudeReference,
                pitchRate, yawRate, rollRate);
        attitudeReference = target;

        if (flightAssistEnabled) {
            // Engaging Flight Assist adopts the ship's CURRENT velocity as the cruise setpoint, so
            // the cruise control takes over smoothly instead of braking a coasting ship to a stop.
            if (captureSetpointOnNextTick) {
                double[] vWorld = VSIntegration.getShipVelocity(world, getPos());
                velocitySetpoint = vWorld == null
                        ? new double[]{0.0, 0.0, 0.0}
                        : FreeFlightPhysics.worldToBodyQ(vWorld[0], vWorld[1], vWorld[2], attitude);
                captureSetpointOnNextTick = false;
            }
            // Cruise control: held throttles ramp the setpoint, releasing keeps it, cut/brake zero it.
            velocitySetpoint = FreeFlightPhysics.shipRampSetpoint(
                    velocitySetpoint[0], velocitySetpoint[1], velocitySetpoint[2],
                    in, SHIP_MAX_SPEED, SHIP_SETPOINT_RAMP);
        }

        // Publish to the PER-TILE channels the controller mixin prefers (falls back to the
        // static probe channels only when these are null). Writing them here means each ship's
        // own computer drives its own ship, independent of any other computer or the probe. The
        // command honours the Flight-Assist mode + cut/brake (a null velocity means "coast").
        commandedVelocity = FreeFlightPhysics.shipVelocityCommand(
                in, target, flightAssistEnabled, velocitySetpoint, SHIP_MAX_SPEED);
        // The angular channel is an attitude target PLUS the rate that target is turning at. The rate
        // is the feed-forward: a proportional law chasing a moving reference settles at a standing
        // error of rate/gain, so without it the ship visibly lags the pilot's hand.
        targetAttitude = new double[]{target.w, target.x, target.y, target.z};
        commandedAngVel = FreeFlightPhysics.bodyRatesToWorldOmega(target, pitchRate, yawRate, rollRate);
    }

    /** The shortest-arc angle (radians) between two attitudes. */
    private static double attitudeError(FreeFlightPhysics.Quat a, FreeFlightPhysics.Quat b) {
        double dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z;
        if (dot < 0.0) dot = -dot;
        if (dot > 1.0) dot = 1.0;
        return 2.0 * Math.acos(dot);
    }

    /** Snapshot the ship's body-frame velocity and the cruise setpoint, in blocks/tick, for the HUD. */
    private void publishHudTelemetry(FreeFlightPhysics.Quat attitude) {
        double[] vWorld = VSIntegration.getShipVelocity(world, getPos());
        if (vWorld == null) {
            hudBodyVelocity = new double[]{0.0, 0.0, 0.0};
        } else {
            double[] body = FreeFlightPhysics.worldToBodyQ(vWorld[0], vWorld[1], vWorld[2], attitude);
            hudBodyVelocity = new double[]{
                    body[0] / TICKS_PER_SECOND, body[1] / TICKS_PER_SECOND, body[2] / TICKS_PER_SECOND};
        }
        hudSetpoint = new double[]{
                velocitySetpoint[0] / TICKS_PER_SECOND,
                velocitySetpoint[1] / TICKS_PER_SECOND,
                velocitySetpoint[2] / TICKS_PER_SECOND};
    }

    /** The ship's body-frame velocity {forward, right, up} in blocks/tick, for the pilot's HUD. */
    public double[] getHudBodyVelocity() {
        return hudBodyVelocity;
    }

    /** The Flight-Assist cruise setpoint {forward, right, up} in blocks/tick, for the pilot's HUD. */
    public double[] getHudSetpoint() {
        return hudSetpoint;
    }

    /**
     * Toggle the auto-takeoff autopilot (server-side; the pilot seat forwards its packet here). A
     * safe idempotent flip: engaging arms the diagonal climb, disengaging returns full manual
     * control. Re-engaging after a decline lets the pilot retry once he has repositioned.
     */
    public void toggleAutoTakeoff() {
        autoTakeoffEngaged = !autoTakeoffEngaged;
    }

    /** Whether the auto-takeoff autopilot is currently engaged (server truth; test/HUD observable). */
    public boolean isAutoTakeoffEngaged() {
        return autoTakeoffEngaged;
    }

    /**
     * Drive one tick of the auto-takeoff climb. Raycasts the diagonal corridor from the ship toward
     * the ceiling; on obstruction it DISENGAGES, messages the pilot, and returns {@code false} (the
     * caller falls back to station-keeping). On a clear corridor it publishes the diagonal climb
     * velocity + holds the ship's current heading and returns {@code true}. The ship's own blocks
     * live in a far subspace, so the ray tests only world terrain/structures in the climb path.
     */
    private boolean driveAutoTakeoff(FreeFlightPhysics.Quat attitude, double[] shipPos, int ceiling) {
        // Heading = the ship's nose (+Z) projected onto the world XZ plane.
        double[] nose = attitude.rotate(0.0, 0.0, 1.0);
        double[] dir = zmaster587.advancedRocketry.space.AutoTakeoffPlanner
                .climbDirection(nose[0], nose[2]);
        double length = zmaster587.advancedRocketry.space.AutoTakeoffPlanner
                .corridorLength(shipPos[1], ceiling);
        boolean clear = zmaster587.advancedRocketry.space.AutoTakeoffPlanner.corridorClear(
                shipPos[0], shipPos[1], shipPos[2], dir, length,
                p -> {
                    net.minecraft.util.math.BlockPos bp =
                            new net.minecraft.util.math.BlockPos(p[0], p[1], p[2]);
                    return world.getBlockState(bp).getMaterial().isSolid();
                });
        if (!clear) {
            autoTakeoffEngaged = false;
            messageSeatedPilot("msg.shipentry.autotakeoff.blocked");
            return false;
        }
        VSIntegration.ensureShipPhysicsEnabled(world, getPos());
        // Hold the current attitude while climbing (no rotation commanded); drive the diagonal velocity.
        if (attitudeReference == null) {
            attitudeReference = attitude;
        }
        commandedVelocity = zmaster587.advancedRocketry.space.AutoTakeoffPlanner
                .climbVelocity(nose[0], nose[2]);
        commandedAngVel = new double[]{0.0, 0.0, 0.0};
        targetAttitude = new double[]{attitudeReference.w, attitudeReference.x,
                attitudeReference.y, attitudeReference.z};
        if (!stationKeeping) {
            stationKeeping = true;
            markDirty();
        }
        return true;
    }

    /**
     * Send a translation-key message to the pilot seated on THIS ship, if any. The AFC has no
     * stored back-link to its seat, so it locates the seated rider by the seat&harr;AFC offset that
     * survives relocation: find a bound pilot dummy whose seat's flight computer is this tile.
     * One-shot events only (a decline), so the small enumeration cost is acceptable.
     */
    private void messageSeatedPilot(String langKey) {
        if (!(world instanceof net.minecraft.world.WorldServer)) {
            return;
        }
        for (Object obj : ((net.minecraft.world.WorldServer) world).loadedEntityList) {
            if (!(obj instanceof zmaster587.advancedRocketry.entity.EntityDummy)) {
                continue;
            }
            zmaster587.advancedRocketry.entity.EntityDummy dummy =
                    (zmaster587.advancedRocketry.entity.EntityDummy) obj;
            zmaster587.advancedRocketry.tile.TilePilotSeat seat =
                    zmaster587.advancedRocketry.tile.TilePilotSeat.forRider(dummy, world);
            if (seat == null || !getPos().equals(seat.getFlightComputerPos())) {
                continue;
            }
            for (net.minecraft.entity.Entity rider : dummy.getPassengers()) {
                if (rider instanceof net.minecraft.entity.player.EntityPlayerMP) {
                    rider.sendMessage(new net.minecraft.util.text.TextComponentTranslation(langKey));
                }
            }
        }
    }

    /**
     * The altitude this dimension's entry on-ramp fires above: the dimension's own orbit line (or the
     * global config value when it declares none), capped below the physics mod's pose clamp. ONE
     * owner, so the trigger and the latch's re-arm can never read a different line.
     */
    private int entryCeiling() {
        zmaster587.advancedRocketry.dimension.DimensionProperties props =
                zmaster587.advancedRocketry.dimension.DimensionManager.getInstance()
                        .getDimensionProperties(world.provider.getDimension());
        return zmaster587.advancedRocketry.space.ShipEntryController.effectiveEntryCeiling(
                props != null ? props.getOrbitHeight()
                        : zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig().orbit,
                VSIntegration.shipYPositionMaximum());
    }

    /**
     * Hold the entry on-ramp off this ship until it has next been at or below the entry line — what a
     * descent calls as it commits, so its own in-air arrival cannot be read as a climb to orbit. See
     * {@link #entryLatched}. Idempotent.
     */
    public void latchEntryUntilBelowTheLine() {
        if (!entryLatched) {
            entryLatched = true;
            markDirty();
        }
    }

    /** Whether the entry on-ramp is currently held off this ship. Read-only; for probes and tests. */
    public boolean isEntryLatched() {
        return entryLatched;
    }

    /**
     * The ship's durable id, minting one if this computer has none yet (see {@link #shipId}).
     * Server-side; the mint is persisted immediately.
     */
    public java.util.UUID getOrCreateShipId() {
        if (shipId == null) {
            shipId = java.util.UUID.randomUUID();
            markDirty();
        }
        return shipId;
    }

    /** The ship's durable id, or {@code null} if none has been minted yet. */
    public java.util.UUID shipIdOrNull() {
        return shipId;
    }

    /** Flight Assist on/off — the one piece of flight state the ship remembers.
     *  Defaults ON, matching Free Flight's default. */
    private boolean flightAssistEnabled = true;

    public boolean isFlightAssistEnabled() {
        return flightAssistEnabled;
    }

    public void setFlightAssistEnabled(boolean enabled) {
        if (enabled && !this.flightAssistEnabled) {
            // Re-engaging: seed the cruise setpoint from the ship's live velocity next tick.
            this.captureSetpointOnNextTick = true;
        }
        this.flightAssistEnabled = enabled;
        markDirty();
    }

    // ─── Jumping ───────────────────────────────────────────────────────────────

    /**
     * The wind-up between the pilot committing and the window opening. Lives here because this
     * computer is the ship's command authority and the only thing aboard that ticks every tick.
     * Deliberately not persisted: a spool a restart interrupted resolves exactly like an abort, and
     * a jump that never happened must not cost anything.
     */
    private final zmaster587.advancedRocketry.hyperdrive.JumpSpool jumpSpool =
            new zmaster587.advancedRocketry.hyperdrive.JumpSpool();

    /**
     * One press of the helm's jump key. Free in every branch — it refuses, warns, winds up or stops
     * winding up, and none of those spend anything.
     */
    public void onJumpKey() {
        if (world == null || world.isRemote) {
            return;
        }
        zmaster587.advancedRocketry.hyperdrive.JumpTrigger.Result result =
                zmaster587.advancedRocketry.hyperdrive.JumpTrigger.press(world, getPos(),
                        shipIdOrNull(), jumpSpool,
                        zmaster587.advancedRocketry.space.SpaceSubsystem.spaceClock());
        messageSeatedPilot(result.langKey());
        if (result.outcome() == zmaster587.advancedRocketry.hyperdrive.JumpTrigger.Outcome.WARNED) {
            // An ADVISORY is a question, and a question the game never asks reads as a refusal: the
            // pilot is told "the window does not enclose the whole hull" and nothing says the press
            // that raised it is also the press that can be repeated to go anyway. The confirm line
            // existed unused - measured in playtest 2026-07-28, where an advisory was reported as a
            // blocker.
            messageSeatedPilot(zmaster587.advancedRocketry.hyperdrive.JumpTrigger.MSG_CONFIRM);
        }
    }

    /** Whether the drive is currently winding up. Read by tests and readouts, never by the flight. */
    public boolean isSpooling() {
        return jumpSpool.spooling(
                zmaster587.advancedRocketry.space.SpaceSubsystem.spaceClock());
    }

    private void tickJumpSpool() {
        long now = zmaster587.advancedRocketry.space.SpaceSubsystem.spaceClock();
        if (!jumpSpool.ready(now)) {
            return;
        }
        zmaster587.advancedRocketry.hyperdrive.JumpTrigger.Result result =
                zmaster587.advancedRocketry.hyperdrive.JumpTrigger.commit(world, getPos(),
                        shipIdOrNull(), jumpSpool, now);
        messageSeatedPilot(result.langKey());
    }

    // ─── The hull's own size ───────────────────────────────────────────────────

    private static final String NBT_HULL = "hullExtent";

    /**
     * The craft's bounding box, as offsets from this computer. Recorded by the assembler, which is
     * the one moment anything knows the whole craft's extent — after assembly the blocks are a
     * physics body and re-deriving the box means walking it.
     *
     * <p>Offsets rather than positions, for the same reason every other ship link is an offset: the
     * ship moves as a rigid body, so the offsets stay true and the positions do not. {@code null}
     * until an assembly records it.</p>
     */
    private int[] hullExtent;

    /** Record the craft's bounding box relative to this computer. Called on the pad, once. */
    public void setHullExtent(int minDx, int minDy, int minDz, int maxDx, int maxDy, int maxDz) {
        this.hullExtent = new int[] {minDx, minDy, minDz, maxDx, maxDy, maxDz};
        markDirty();
    }

    /**
     * The hull box in world coordinates as {@code {minX,minY,minZ,maxX,maxY,maxZ}}, or {@code null}
     * when no assembly ever recorded one.
     */
    public int[] hullBox() {
        if (hullExtent == null) {
            return null;
        }
        return new int[] {
            pos.getX() + hullExtent[0], pos.getY() + hullExtent[1], pos.getZ() + hullExtent[2],
            pos.getX() + hullExtent[3], pos.getY() + hullExtent[4], pos.getZ() + hullExtent[5]
        };
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean(NBT_FLIGHT_ASSIST, flightAssistEnabled);
        nbt.setBoolean(NBT_STATION_KEEPING, stationKeeping);
        nbt.setBoolean(NBT_ENTRY_LATCHED, entryLatched);
        if (shipId != null) {
            nbt.setString(NBT_SHIP_ID, shipId.toString());
        }
        if (hullExtent != null) {
            nbt.setIntArray(NBT_HULL, hullExtent);
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        // Absent key -> default ON (a freshly-placed computer, or a pre-FA save).
        flightAssistEnabled = !nbt.hasKey(NBT_FLIGHT_ASSIST) || nbt.getBoolean(NBT_FLIGHT_ASSIST);
        // Absent key -> not station-keeping (a fresh, never-flown ship stays inert).
        stationKeeping = nbt.getBoolean(NBT_STATION_KEEPING);
        // Absent key -> entry ARMED. Only a descent arrival latches it; a fresh or unmanned ship
        // must never load latched, or it could never leave the planet it was built on.
        entryLatched = nbt.getBoolean(NBT_ENTRY_LATCHED);
        // Absent/malformed key -> no id yet (minted on first use); never re-mint over a valid one.
        hullExtent = null;
        if (nbt.hasKey(NBT_HULL)) {
            int[] stored = nbt.getIntArray(NBT_HULL);
            if (stored.length == 6) {
                hullExtent = stored;
            }
        }
        shipId = null;
        if (nbt.hasKey(NBT_SHIP_ID)) {
            try {
                shipId = java.util.UUID.fromString(nbt.getString(NBT_SHIP_ID));
            } catch (IllegalArgumentException ignored) {
                // corrupt id: treat as unminted rather than crash the tile load
            }
        }
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        // Placeholder: flight-control modules are added here in a later phase.
        return new LinkedList<>();
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockAdvancedFlightComputer.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }
}
