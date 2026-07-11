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

    /**
     * Whether this ship holds station (hover + attitude) while unmanned. Set true the first time a pilot
     * flies it, and PERSISTED: the live {@link #attitudeReference} does not survive a world save/load, so
     * without a saved flag a hovering ship dropped out of the sky the instant its world was reloaded and
     * then tumbled inverted (live playtest 2026-07-11). Never auto-cleared - there is no engine-shutdown
     * yet, and a parked ship holding position (in the air, or resting on the ground) is the intent.
     */
    private boolean stationKeeping = false;

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
     * angular velocity {@code {x,y,z}} (rad/s), or {@code null} when none. Same game→physics
     * thread hand-off + AR-core-only contract as {@link #debugCommandedVelocity}; the mixin
     * turns it into torque. TODO: fold into per-tile pilot state with the linear channel.
     */
    public static volatile double[] debugCommandedAngVel = null;

    /**
     * Bring-up command for ATTITUDE HOLD: the target body→world orientation as a quaternion
     * {@code {w,x,y,z}}, or {@code null} when not holding an attitude. When set it supersedes
     * {@link #debugCommandedAngVel} — the controller reads the ship's current orientation on
     * the physics thread and turns the error into the angular velocity it drives toward. This
     * is the interface Free Flight feeds: its per-tick target quaternion (from
     * {@code integrateBodyRates} over the held pilot rates) is published here. Same game→physics
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

    /** Ship cruise speed cap (blocks/second) mapped from full throttle. Kept modest so a
     *  commanded velocity stays under the physics mod's "moving too fast" freeze. Public because the
     *  flight HUD scales its velocity bars by the craft's own top speed. */
    public static final double SHIP_MAX_SPEED = 8.0;

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
        FreeFlightPhysics.Quat attitude = VSIntegration.getShipAttitude(world, getPos());
        if (attitude == null) {
            return; // not on a physics ship (or physics mod absent)
        }
        // Telemetry first, and unconditionally: the pilot's HUD must keep reading the ship's real
        // velocity while he holds no key at all, which is exactly when the input channel is idle.
        publishHudTelemetry(attitude);

        // The seated pilot's per-tile input wins; the static channel is only a test-probe fallback.
        FreeFlightInput in = pilotInput != null ? pilotInput : debugFlightInput;
        if (in == null) {
            // Nobody is flying. A ship that has NEVER been flown this load stays inert - its physics is
            // off, so it just rests and there is nothing to hold. But a ship that WAS being flown must
            // HOLD STATION when the pilot stands up: hover in place, at the attitude he left it, until a
            // pilot returns. A hovering craft is not coasting - it needs continuous force to fight
            // gravity, so the instant the controller stops commanding it falls out of the sky (the
            // playtest: stood up mid-hover, the ship dropped and took the pilot down with it). So rather
            // than clearing the command, keep commanding a zero-velocity, hold-current-attitude station
            // keep. attitudeReference is non-null only once a pilot has actually flown the ship, so it is
            // the "was piloted" witness - hold it, do not clear it.
            // The "was flown" witness is the PERSISTED stationKeeping flag, not the live attitudeReference
            // (which is null after a reload). A never-flown ship (physics off) stays inert; a ship that has
            // been flown holds station, and does so again after a world reload instead of falling.
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
            commandedVelocity = new double[]{0.0, 0.0, 0.0};
            commandedAngVel = new double[]{0.0, 0.0, 0.0};
            targetAttitude = new double[]{attitudeReference.w, attitudeReference.x,
                    attitudeReference.y, attitudeReference.z};
            velocitySetpoint = new double[]{0.0, 0.0, 0.0};
            captureSetpointOnNextTick = true; // a returning pilot re-seeds cruise from the live velocity
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

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean(NBT_FLIGHT_ASSIST, flightAssistEnabled);
        nbt.setBoolean(NBT_STATION_KEEPING, stationKeeping);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        // Absent key → default ON (a freshly-placed computer, or a pre-FA save).
        flightAssistEnabled = !nbt.hasKey(NBT_FLIGHT_ASSIST) || nbt.getBoolean(NBT_FLIGHT_ASSIST);
        // Absent key → not station-keeping (a fresh, never-flown ship stays inert).
        stationKeeping = nbt.getBoolean(NBT_STATION_KEEPING);
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
