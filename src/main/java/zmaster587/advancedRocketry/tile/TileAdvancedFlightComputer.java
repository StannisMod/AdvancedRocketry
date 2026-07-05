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

    /** Ship cruise speed cap (blocks/second) mapped from full throttle. Kept modest so a
     *  commanded velocity stays under the physics mod's "moving too fast" freeze. */
    private static final double SHIP_MAX_SPEED = 8.0;

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
        FreeFlightInput in = debugFlightInput;
        if (in == null) {
            return;
        }
        FreeFlightPhysics.Quat attitude = VSIntegration.getShipAttitude(world, getPos());
        if (attitude == null) {
            return; // not on a physics ship (or physics mod absent)
        }
        VSIntegration.ensureShipPhysicsEnabled(world, getPos());

        // Target attitude: advance the ship's current orientation by the pilot's body rates.
        FreeFlightPhysics.Quat target = FreeFlightPhysics.integrateBodyRates(attitude,
                in.pitchInput * FreeFlightPhysics.MAX_PITCH_RATE,
                in.yawInput * FreeFlightPhysics.MAX_YAW_RATE,
                in.rollInput * FreeFlightPhysics.MAX_ROLL_RATE);

        // Desired world velocity: throttles map to body forward/right/up, rotated to world by
        // the target attitude. The controller's deadbeat force realizes it (Flight Assist).
        double[] vWorld = FreeFlightPhysics.bodyToWorldQ(
                in.throttleForward * SHIP_MAX_SPEED,
                in.strafeInput * SHIP_MAX_SPEED,
                in.throttleVertical * SHIP_MAX_SPEED,
                target);

        debugCommandedVelocity = new double[]{vWorld[0], vWorld[1], vWorld[2]};
        debugCommandedAngVel = null; // attitude target drives the angular channel
        debugTargetAttitude = new double[]{target.w, target.x, target.y, target.z};
    }

    /** Flight Assist on/off — the one piece of flight state the ship remembers.
     *  Defaults ON, matching Free Flight's default. */
    private boolean flightAssistEnabled = true;

    public boolean isFlightAssistEnabled() {
        return flightAssistEnabled;
    }

    public void setFlightAssistEnabled(boolean enabled) {
        this.flightAssistEnabled = enabled;
        markDirty();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean(NBT_FLIGHT_ASSIST, flightAssistEnabled);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        // Absent key → default ON (a freshly-placed computer, or a pre-FA save).
        flightAssistEnabled = !nbt.hasKey(NBT_FLIGHT_ASSIST) || nbt.getBoolean(NBT_FLIGHT_ASSIST);
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
