package zmaster587.advancedRocketry.tile;

import java.util.LinkedList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
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
public class TileAdvancedFlightComputer extends TileEntity implements IModularInventory {

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
