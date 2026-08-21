package zmaster587.advancedRocketry.tile.sensor;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.EnergyStorage;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.sensor.SensorMode;
import zmaster587.advancedRocketry.api.sensor.TargetTrack;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.sensor.TacticalScan;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemSink;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkRegistry;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;
import zmaster587.advancedRocketry.weapon.TurretFireControl;
import zmaster587.advancedRocketry.weapon.WeaponNetworkDomain;
import zmaster587.advancedRocketry.weapon.WeaponNetworkState;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.TextureResources;
import zmaster587.libVulpes.inventory.modules.IButtonInventory;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleButton;
import zmaster587.libVulpes.inventory.modules.ModuleText;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The thing that finds a target, so that a human does not have to name one.
 *
 * <h3>It senses; it does not shoot</h3>
 * <p>This block owns no gun and gives no order. It publishes ONE contact — the best it is currently
 * holding — into the weapons network's shared state, and the guns on that network use it exactly as
 * they use anything else they were told. Which means a battery with no sensor is unchanged, a sensor
 * with no battery is a radar screen with nothing wired to it, and neither of them is a degraded
 * version of the pair.</p>
 *
 * <h3>Listening or illuminating</h3>
 * <p>{@link SensorMode#PASSIVE} emits nothing and is bounded by what the target itself radiates: a
 * hot or burning thing is held well, a cool quiet one may be detected and never resolved well enough
 * to shoot at. {@link SensorMode#ACTIVE} illuminates — steady quality against anything in range
 * including a cold one — and costs power and, once the EM layer exists, your own silence. A sensor
 * that cannot pay for the active mode falls back to listening rather than reporting a lock it does
 * not have.</p>
 *
 * <h3>On a ship, on the ground, same device</h3>
 * <p>Bolted to a hull it converts its own position out to the world before looking, so a
 * planetary-defence radar and a warship's fire control are one block and one code path.</p>
 */
public class TileFireControlSensor extends TileEntity implements ITickable, ISubsystemSink,
        IModularInventory, IButtonInventory {

    private static final int BUTTON_MODE = 0;

    /** Enough for a few seconds of illumination, so a momentary supply dip is not a lost lock. */
    private static final int MIN_ENERGY_BUFFER = 8_000;

    /**
     * How many scan intervals a published contact stays good for. Longer than one so a battery is
     * not blinking between "target" and "no target" between sweeps; short enough that a sensor which
     * stops publishing — broken, unloaded, unpowered — takes its battery's target with it.
     */
    private static final int TRACK_HOLD_INTERVALS = 3;

    private EnergyStorage energy = new EnergyStorage(MIN_ENERGY_BUFFER, MIN_ENERGY_BUFFER,
            MIN_ENERGY_BUFFER);
    private SensorMode mode = SensorMode.PASSIVE;
    private String accessCode = "";
    private boolean registered;
    private int scanCooldown;

    /** What the last sweep saw, best first. Diagnostics and the readout; the network gets the best. */
    private List<TargetTrack> contacts = Collections.emptyList();
    private boolean poweredForActive = true;

    /**
     * What the client was last told, and what it is holding. A sweep happens on the server and the
     * panel is read on a client, so the numbers a player looks at have to travel — a readout composed
     * from server-only state renders as zeroes over a real connection and only looks right in single
     * player, where both sides happen to share one JVM.
     */
    private SensorMode clientMode = SensorMode.PASSIVE;
    private SensorMode clientEffectiveMode = SensorMode.PASSIVE;
    private boolean clientUnderpowered;
    private int clientContacts;
    private double clientQuality;
    private double clientDistance;
    private boolean clientLocked;
    private int sentContacts = -1;
    private double sentQuality = -1.0D;
    private SensorMode sentMode;
    private boolean sentUnderpowered;

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        if (VSIntegration.isOnUnnamedShip(world, pos)) {
            // The same rule a gun follows: aboard a ship nobody has named yet, this block's position
            // is a shipyard address rather than a place in the world, so every contact it produced
            // would be measured from the wrong point. Waiting is the only correct behaviour.
            return;
        }
        if (!ARConfiguration.getCurrentConfig().enableWeapons
                || !ARConfiguration.getCurrentConfig().enableFireControlSensor) {
            // Two gates, one behaviour. The master says whether there is a war at all; the narrower
            // one says whether batteries find their own targets in it. A pack may want the second
            // without the first being in question, which is why both survive.
            // Switched off means OFF: no acquisition, nothing published, no power drawn and not even
            // a place in the network — a disabled sensor is not a node that quietly keeps its buffer
            // topped up. Anything it had already published expires on its own.
            contacts = Collections.emptyList();
            if (registered) {
                SubsystemNetworkRegistry.unregister(this);
                SubsystemNetworkManager.markDirty(WeaponNetworkDomain.INSTANCE, world);
                registered = false;
            }
            return;
        }
        if (!registered) {
            SubsystemNetworkRegistry.register(this);
            SubsystemNetworkManager.markDirty(WeaponNetworkDomain.INSTANCE, world);
            registered = true;
        }

        poweredForActive = payForMode();
        if (scanCooldown > 0) {
            scanCooldown--;
            return;
        }
        scanCooldown = Math.max(1, ARConfiguration.getCurrentConfig().fireControlSensorScanIntervalTicks);
        sweep();
        publish();
        syncReadoutIfChanged();
    }

    /**
     * Tell the client what its panel is supposed to say, when that has meaningfully changed.
     *
     * <p>Thresholded rather than sent every sweep: a sensor holding a slowly closing contact would
     * otherwise be a packet every few ticks per device forever, for a number a player reads to two
     * decimal places. A contact appearing or disappearing, the mode changing, the illuminator losing
     * its power, or the lock moving by more than a twentieth are the changes worth a packet.</p>
     */
    private void syncReadoutIfChanged() {
        TargetTrack best = getBestContact();
        int count = contacts.size();
        double quality = best == null ? 0.0D : best.getQuality();
        SensorMode effective = effectiveMode();
        boolean changed = count != sentContacts
                || effective != sentMode
                || isUnderpowered() != sentUnderpowered
                || Math.abs(quality - sentQuality) > 0.05D;
        if (!changed) {
            return;
        }
        sentContacts = count;
        sentQuality = quality;
        sentMode = effective;
        sentUnderpowered = isUnderpowered();
        IBlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 2);
    }

    /**
     * Draw what this tick's mode costs, and answer whether it was affordable. Listening is free;
     * illuminating is a machine that is running. An unaffordable active mode degrades to listening
     * for as long as it stays unaffordable — visibly, through the readout, rather than by quietly
     * producing worse tracks with no stated reason.
     */
    private boolean payForMode() {
        if (mode != SensorMode.ACTIVE) {
            return true;
        }
        int cost = Math.max(0, ARConfiguration.getCurrentConfig().fireControlSensorActiveEnergyPerTick);
        if (cost == 0) {
            return true;
        }
        if (energy.getEnergyStored() < cost) {
            return false;
        }
        energy.extractEnergy(cost, false);
        return true;
    }

    private void sweep() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        Vec3d origin = worldPosition();
        if (origin == null) {
            // Aboard a ship whose transform is not available: every distance measured from here
            // would be measured from a stale pose, so the sweep does not happen at all.
            contacts = Collections.emptyList();
            return;
        }
        contacts = TacticalScan.sweep(world, origin, shipId(), config.fireControlSensorRadius,
                effectiveMode(), config.fireControlSensorActiveLockQuality, effectiveAccessCode(),
                config.fireControlSensorAcquireHostilesOnly, config.fireControlSensorMaxTracks);
    }

    /**
     * Hand the network the best contact, or take away the one it was holding. Both halves matter:
     * a sensor that has stopped seeing anything must say so, or a battery goes on firing at the
     * place something used to be.
     */
    private void publish() {
        WeaponNetworkState state = networkState();
        if (state == null) {
            return;
        }
        if (contacts.isEmpty()) {
            state.clearAcquiredTrack();
            return;
        }
        int hold = TRACK_HOLD_INTERVALS
                * Math.max(1, ARConfiguration.getCurrentConfig().fireControlSensorScanIntervalTicks);
        state.setAcquiredTrack(contacts.get(0), world.getTotalWorldTime(), hold);
    }

    /** What this device is actually doing, as opposed to what it was set to. */
    public SensorMode effectiveMode() {
        if (isClient()) {
            return clientEffectiveMode;
        }
        return mode == SensorMode.ACTIVE && poweredForActive ? SensorMode.ACTIVE : SensorMode.PASSIVE;
    }

    public SensorMode getMode() {
        return isClient() ? clientMode : mode;
    }

    private boolean isClient() {
        return world != null && world.isRemote;
    }

    public void setMode(SensorMode mode) {
        if (mode != null) {
            this.mode = mode;
            markDirty();
        }
    }

    /** True while this sensor is set to illuminate and cannot afford to. */
    public boolean isUnderpowered() {
        return isClient() ? clientUnderpowered : mode == SensorMode.ACTIVE && !poweredForActive;
    }

    /** How many contacts the panel should show — the client's copy on a client. */
    public int getContactCount() {
        return isClient() ? clientContacts : contacts.size();
    }

    /** The best contact's quality, distance and whether it is a lock, for the panel on either side. */
    public double getBestQuality() {
        TargetTrack best = getBestContact();
        return isClient() ? clientQuality : (best == null ? 0.0D : best.getQuality());
    }

    public double getBestDistance() {
        TargetTrack best = getBestContact();
        return isClient() ? clientDistance : (best == null ? 0.0D : best.getDistance());
    }

    public boolean isBestLocked() {
        if (isClient()) {
            return clientLocked;
        }
        TargetTrack best = getBestContact();
        return best != null && best.isLocked(
                ARConfiguration.getCurrentConfig().fireControlSensorLockQualityToFire);
    }

    /**
     * Whether this device is currently emitting something another sensor could hear. Nothing
     * consumes it yet — the EM-signature layer is a separate subsystem — and it is stated anyway
     * because it is the whole price of the active mode, and because the "you are being locked"
     * warning a target gets is supposed to fall out of hearing this rather than being written.
     */
    public boolean isEmitting() {
        return effectiveMode().isEmitting();
    }

    public List<TargetTrack> getContacts() {
        return contacts;
    }

    /** The contact this sensor is handing its battery, or null when it holds nothing. */
    public TargetTrack getBestContact() {
        return contacts.isEmpty() ? null : contacts.get(0);
    }

    /** This sensor's own code, used when it is on no network or the network has none. */
    public void setAccessCode(String code) {
        this.accessCode = code == null ? "" : code;
        markDirty();
    }

    /**
     * The network's code when it has one, otherwise this sensor's own — the same rule a gun follows,
     * because "whose side are we on" is a property of the installation and a sensor that disagreed
     * with the guns it feeds would hand them their own crew.
     */
    public String effectiveAccessCode() {
        WeaponNetworkState state = networkState();
        if (state != null && !state.getAccessCode().isEmpty()) {
            return state.getAccessCode();
        }
        return accessCode;
    }

    private String shipId() {
        return TurretFireControl.shipIdAt(world, pos);
    }

    /** This block's position in WORLD coordinates: its own, or its ship's idea of where its own is. */
    private Vec3d worldPosition() {
        return TurretFireControl.worldPositionOf(world, pos, shipId());
    }

    public WeaponNetworkState networkState() {
        SubsystemNetworkState state = SubsystemNetworkManager.getState(WeaponNetworkDomain.INSTANCE,
                world, pos);
        return state instanceof WeaponNetworkState ? (WeaponNetworkState) state : null;
    }

    // ---- subsystem network: a sink, in the domain it feeds

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return WeaponNetworkDomain.INSTANCE;
    }

    @Override
    public World getNodeWorld() {
        return world;
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    @Override
    public int getRequested() {
        return getFreeCapacity();
    }

    @Override
    public int getFreeCapacity() {
        return energy.getMaxEnergyStored() - energy.getEnergyStored();
    }

    @Override
    public int receive(int amount) {
        return energy.receiveEnergy(Math.max(0, amount), false);
    }

    @Override
    public int getConsumptionPerTick() {
        return mode == SensorMode.ACTIVE
                ? Math.max(0, ARConfiguration.getCurrentConfig().fireControlSensorActiveEnergyPerTick)
                : 0;
    }

    /**
     * Ahead of the guns under a deficit. A battery that keeps its rounds and loses its eyes is a
     * battery firing at nothing; one that keeps its eyes and runs a round short still knows where
     * the enemy is when the supply comes back.
     */
    @Override
    public int getPriority() {
        return 1;
    }

    public int getEnergyStored() {
        return energy.getEnergyStored();
    }

    /** Fills the buffer directly. For creative placement and for tests that are not about wiring. */
    public void chargeFully() {
        energy.receiveEnergy(energy.getMaxEnergyStored(), false);
    }

    // ---- lifecycle

    @Override
    public void invalidate() {
        super.invalidate();
        SubsystemNetworkRegistry.unregister(this);
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.markDirty(WeaponNetworkDomain.INSTANCE, world);
        }
        registered = false;
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        SubsystemNetworkRegistry.unregister(this);
        registered = false;
    }

    // ---- energy capability

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityEnergy.ENERGY || super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityEnergy.ENERGY) {
            return CapabilityEnergy.ENERGY.cast(energy);
        }
        return super.getCapability(capability, facing);
    }

    // ---- GUI

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>();
        modules.add(new ModuleButton(10, 20, BUTTON_MODE,
                LibVulpes.proxy.getLocalizedString("msg.fireControlSensor.mode"), this,
                TextureResources.buttonBuild, 80, 18));
        modules.add(new ModuleText(10, 46, modeLine(), 0x2b2b2b));
        modules.add(new ModuleText(10, 58, contactLine(), 0x2b2b2b));
        modules.add(new ModuleText(10, 70, lockLine(), 0x2b2b2b));
        return modules;
    }

    /**
     * One readout line, translated where a translation exists.
     *
     * <p>A whole sentence per key with its placeholders in it, never a label concatenated with a
     * value: word order is not the same in every language, and a line assembled from fragments can
     * only ever come out in English order. {@code getModules} runs on both sides — the client proxy
     * translates and the common one hands the key straight back, which then formats to itself
     * because a key carries no format specifiers.</p>
     */
    private static String readoutText(String key, Object... args) {
        return String.format(LibVulpes.proxy.getLocalizedString(key), args);
    }

    private String modeLine() {
        // Two literal keys rather than one assembled from the enum name: a key built by
        // concatenation is invisible to the lang cross-reference scan, which is the only thing that
        // would notice it going missing from a catalogue.
        String mode = readoutText(effectiveMode() == SensorMode.ACTIVE
                ? "msg.fireControlSensor.mode.active" : "msg.fireControlSensor.mode.passive");
        return isUnderpowered() ? readoutText("msg.fireControlSensor.line.modeUnderpowered", mode)
                : readoutText("msg.fireControlSensor.line.mode", mode);
    }

    private String contactLine() {
        return readoutText("msg.fireControlSensor.line.contacts", getContactCount());
    }

    private String lockLine() {
        if (getContactCount() <= 0) {
            return readoutText("msg.fireControlSensor.line.lockNone");
        }
        return readoutText(isBestLocked() ? "msg.fireControlSensor.line.lock"
                        : "msg.fireControlSensor.line.lockTooPoor",
                getBestQuality(), getBestDistance());
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == BUTTON_MODE) {
            setMode(mode == SensorMode.ACTIVE ? SensorMode.PASSIVE : SensorMode.ACTIVE);
        }
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockFireControlSensor.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    // ---- what the client is told: the READOUT, because the sweep happens where it cannot see

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound nbt = super.getUpdateTag();
        nbt.setInteger("mode", mode.ordinal());
        nbt.setInteger("effMode", effectiveMode().ordinal());
        nbt.setBoolean("underpowered", isUnderpowered());
        nbt.setInteger("contacts", contacts.size());
        TargetTrack best = getBestContact();
        nbt.setDouble("quality", best == null ? 0.0D : best.getQuality());
        nbt.setDouble("distance", best == null ? 0.0D : best.getDistance());
        nbt.setBoolean("locked", isBestLocked());
        return nbt;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        handleUpdateTag(packet.getNbtCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound nbt) {
        SensorMode[] modes = SensorMode.values();
        clientMode = modeOf(modes, nbt.getInteger("mode"));
        clientEffectiveMode = modeOf(modes, nbt.getInteger("effMode"));
        clientUnderpowered = nbt.getBoolean("underpowered");
        clientContacts = nbt.getInteger("contacts");
        clientQuality = nbt.getDouble("quality");
        clientDistance = nbt.getDouble("distance");
        clientLocked = nbt.getBoolean("locked");
    }

    private static SensorMode modeOf(SensorMode[] modes, int ordinal) {
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : SensorMode.PASSIVE;
    }

    // ---- persistence

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setInteger("mode", mode.ordinal());
        nbt.setInteger("energy", energy.getEnergyStored());
        nbt.setString("accessCode", accessCode);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        SensorMode[] modes = SensorMode.values();
        int stored = nbt.getInteger("mode");
        mode = stored >= 0 && stored < modes.length ? modes[stored] : SensorMode.PASSIVE;
        energy = new EnergyStorage(MIN_ENERGY_BUFFER, MIN_ENERGY_BUFFER, MIN_ENERGY_BUFFER,
                Math.min(MIN_ENERGY_BUFFER, nbt.getInteger("energy")));
        accessCode = nbt.getString("accessCode");
    }
}
