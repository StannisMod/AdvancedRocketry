package zmaster587.advancedRocketry.tile.weapon;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.block.state.IBlockState;
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
import zmaster587.advancedRocketry.api.weapon.GunSpec;
import zmaster587.advancedRocketry.api.weapon.TurretDriveState;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemSink;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkRegistry;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;
import zmaster587.advancedRocketry.weapon.GunAssembly;
import zmaster587.advancedRocketry.weapon.TurretFireControl;
import zmaster587.advancedRocketry.weapon.TurretMechanism;
import zmaster587.advancedRocketry.weapon.WeaponNetworkDomain;
import zmaster587.advancedRocketry.weapon.WeaponNetworkState;
import zmaster587.libVulpes.interfaces.ILinkableTile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.UUID;

/**
 * A gun's controller: the one block that knows what was built around it, where it is pointing, and
 * when it may shoot.
 *
 * <h3>It works alone</h3>
 * <p>Nothing below asks whether a network exists before deciding to aim or to fire. A turret with a
 * target, a charged buffer and a barrel shoots — on the ground, on a ship, wired to nothing. A
 * network, when there is one, can point it somewhere else and can feed it faster; that is the whole
 * of the difference, and it is a difference in convenience rather than in capability.</p>
 *
 * <h3>The build is re-read when it CHANGES</h3>
 * <p>A block cannot enter or leave the world without its own {@code onBlockAdded} / {@code breakBlock}
 * running, so "the build changed" is a fact the world hands us rather than something to go looking
 * for: a part marks every controller it can reach, and the walk happens on the next tick. There is no
 * polling — a gun that nobody is building is a gun that costs one boolean check per tick.</p>
 */
public class TileTurret extends TileEntity implements ITickable, ISubsystemSink, ILinkableTile {

    /** Smallest buffer a turret keeps, so a cheap gun still holds a few rounds' worth. */
    private static final int MIN_ENERGY_BUFFER = 20_000;

    /** How far the command must move before the client is told again. */
    private static final double COMMAND_SYNC_DEGREES = 2.0D;

    /** The longest barrel the renderer draws, however many parts were built. */
    private static final int MAX_DRAWN_BARREL = 5;

    private final TurretMechanism mechanism = TurretMechanism.standard();
    private final Random random = new Random();

    private EnergyStorage energy = new EnergyStorage(MIN_ENERGY_BUFFER, MIN_ENERGY_BUFFER,
            MIN_ENERGY_BUFFER);
    private GunSpec spec = GunSpec.EMPTY;
    private int assemblyReach;
    private boolean assemblyDirty = true;
    private int fireCooldown;
    private int heat;
    private boolean registered;

    private Vec3d localTarget;
    private String faction;
    private UUID owner;

    private long lastShotId = -1L;
    private int shotsFired;

    /** What the client was last told, so the server can tell when telling it again is worth a packet. */
    private double sentYaw = Double.NaN;
    private double sentPitch = Double.NaN;
    private TurretDriveState sentDrive;

    /** Client-side only: the rate and barrel length that came with the last update. */
    private double clientTraverseRate = 2.0D;
    private int clientBarrelLength = 1;

    @Override
    public void update() {
        if (world == null) {
            return;
        }
        if (world.isRemote) {
            // The client runs the SAME mechanism on the command it was sent, rather than being fed a
            // bearing every tick. A mount turns for several seconds and is commanded rarely, so
            // replicating the command costs one packet per engagement and replicating the pose would
            // cost one per tick — for a picture that would still be a tick behind.
            mechanism.tick(clientTraverseRate);
            return;
        }
        if (VSIntegration.isOnUnnamedShip(world, pos)) {
            // The blocks are loaded but the ship they belong to is not named yet, so every coordinate
            // this gun holds is a shipyard address rather than a place in the world. It does not aim,
            // does not fire, does not cool, and does not join a network: a subsystem that cannot say
            // where it is has nothing correct to do, and waiting is the cheapest correct behaviour.
            return;
        }
        if (!registered) {
            SubsystemNetworkRegistry.register(this);
            SubsystemNetworkManager.markDirty(WeaponNetworkDomain.INSTANCE, world);
            registered = true;
            assemblyDirty = true;
        }

        if (assemblyDirty) {
            GunAssembly assembly = GunAssembly.scan(world, pos);
            spec = assembly.getSpec();
            assemblyReach = assembly.getReach();
            resizeBufferFor(spec);
            assemblyDirty = false;
        }

        if (heat > 0) {
            heat = Math.max(0, heat - spec.getCoolingPerTick());
        }
        if (fireCooldown > 0) {
            fireCooldown--;
        }

        Vec3d target = getEffectiveTarget();
        if (target == null) {
            mechanism.clearCommand();
            return;
        }

        String shipId = TurretFireControl.shipIdAt(world, pos);
        Vec3d aim = TurretFireControl.aimDirection(world, pos, shipId, target);
        if (aim != null) {
            // A null aim means the ship's transform is not available; the mount then holds the
            // bearing it already has rather than swinging to one derived from a stale pose.
            mechanism.commandDirection(aim);
        }
        boolean onTarget = mechanism.tick(spec.getTraverseDegreesPerTick());
        syncCommandIfChanged();
        if (!onTarget || isHoldingFire() || !canFireNow()) {
            return;
        }

        long id = TurretFireControl.fire(world, pos, shipId, mechanism.getAimDirection(), spec,
                assemblyReach, owner, faction, random);
        if (id >= 0L) {
            lastShotId = id;
            shotsFired++;
            fireCooldown = spec.getFireIntervalTicks();
            heat += spec.getHeatPerShot();
            energy.extractEnergy(spec.getEnergyPerShot(), false);
            markDirty();
        }
    }

    /** Everything that must be true before a round leaves, other than pointing the right way. */
    private boolean canFireNow() {
        return spec.isOperable()
                && mechanism.getDriveState().permitsFiring()
                && fireCooldown <= 0
                && heat + spec.getHeatPerShot() <= spec.getHeatCapacity()
                && energy.getEnergyStored() >= spec.getEnergyPerShot();
    }

    /**
     * Where this gun is pointing: what the network was told, or failing that what the gun itself was
     * told. The network wins when it has an opinion — that is what "one console commands the
     * battery" means — and its silence is not an instruction to stop.
     */
    public Vec3d getEffectiveTarget() {
        WeaponNetworkState state = networkState();
        if (state != null && state.getTarget() != null) {
            return state.getTarget();
        }
        return localTarget;
    }

    private boolean isHoldingFire() {
        WeaponNetworkState state = networkState();
        return state != null && state.isHoldFire();
    }

    private WeaponNetworkState networkState() {
        SubsystemNetworkState state = SubsystemNetworkManager.getState(WeaponNetworkDomain.INSTANCE,
                world, pos);
        return state instanceof WeaponNetworkState ? (WeaponNetworkState) state : null;
    }

    /**
     * Grow the buffer with the gun, keeping what is in it. A gun that got bigger should not have to
     * refill from empty, and one that shrank should not be holding more than it can.
     */
    private void resizeBufferFor(GunSpec newSpec) {
        int wanted = Math.max(MIN_ENERGY_BUFFER, newSpec.getEnergyPerShot() * 40);
        if (wanted == energy.getMaxEnergyStored()) {
            return;
        }
        int carried = Math.min(energy.getEnergyStored(), wanted);
        energy = new EnergyStorage(wanted, wanted, wanted, carried);
    }

    /**
     * Re-count the build on the next tick. Called by a part entering or leaving the world; deferred
     * by one tick on purpose, because a player laying a run of barrel sections would otherwise pay
     * for a full walk per block placed, and because the walk during {@code breakBlock} would be
     * reading a world in the middle of being changed.
     */
    public void markAssemblyDirty() {
        assemblyDirty = true;
    }

    // ---- the gun's own controls, which no network is needed to reach

    /** Point this gun at a world point. Cleared with null. */
    public void setTarget(Vec3d target) {
        this.localTarget = target;
        markDirty();
    }

    public Vec3d getTarget() {
        return localTarget;
    }

    public GunSpec getSpec() {
        return spec;
    }

    public TurretMechanism getMechanism() {
        return mechanism;
    }

    public int getHeat() {
        return heat;
    }

    public int getEnergyStored() {
        return energy.getEnergyStored();
    }

    /** Test and diagnostic reads: how many rounds this gun has fired, and the last one's id. */
    public int getShotsFired() {
        return shotsFired;
    }

    public long getLastShotId() {
        return lastShotId;
    }

    public void setDriveState(TurretDriveState state) {
        mechanism.setDriveState(state);
        markDirty();
    }

    /** Whose side it is on. Travels with every round it fires so an impact can be attributed. */
    public void setFaction(String faction) {
        this.faction = faction;
        markDirty();
    }

    public String getFaction() {
        return faction;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
        markDirty();
    }

    /** Fills the buffer directly. For creative placement and for tests that are not about wiring. */
    public void chargeFully() {
        energy.receiveEnergy(energy.getMaxEnergyStored(), false);
    }

    /**
     * Tell the client where this gun has been TOLD to point, when that has meaningfully changed.
     *
     * <p>Thresholded rather than sent every tick: a mount tracking a slowly moving target would
     * otherwise be a packet per tick per gun, and a battery would put its whole rate of fire on the
     * connection twice — once for the rounds and once for the barrels. Two degrees is finer than any
     * player can see at the distance a turret is watched from.</p>
     */
    private void syncCommandIfChanged() {
        double yaw = mechanism.getCommandedYaw();
        double pitch = mechanism.getCommandedPitch();
        TurretDriveState drive = mechanism.getDriveState();
        boolean moved = Double.isNaN(sentYaw)
                || Math.abs(wrapDegrees(yaw - sentYaw)) > COMMAND_SYNC_DEGREES
                || Math.abs(pitch - sentPitch) > COMMAND_SYNC_DEGREES
                || drive != sentDrive;
        if (!moved) {
            return;
        }
        sentYaw = yaw;
        sentPitch = pitch;
        sentDrive = drive;
        IBlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 2);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped <= -180.0D) {
            wrapped += 360.0D;
        }
        if (wrapped > 180.0D) {
            wrapped -= 360.0D;
        }
        return wrapped;
    }

    /** How long a barrel the renderer should draw, in blocks. Derived from the build, sent with it. */
    public int getBarrelLength() {
        return world != null && world.isRemote ? clientBarrelLength
                : Math.max(1, Math.min(MAX_DRAWN_BARREL, spec.getPartCount() / 2));
    }

    // ---- subsystem network: a sink, and only while it wants energy

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
        int interval = Math.max(1, spec.getFireIntervalTicks());
        return spec.getEnergyPerShot() / interval;
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

    // ---- linker: the no-network way to give a gun a target

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        return true;
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        if (entity == null) {
            return false;
        }
        setTarget(TurretFireControl.center(entity.getPos()));
        return true;
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

    // ---- what the client is told: the COMMAND, not the pose

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound nbt = super.getUpdateTag();
        NBTTagCompound mount = new NBTTagCompound();
        mechanism.writeToNBT(mount);
        nbt.setTag("mount", mount);
        nbt.setDouble("rate", spec.getTraverseDegreesPerTick());
        nbt.setInteger("barrel", getBarrelLength());
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
        if (nbt.hasKey("mount")) {
            mechanism.readFromNBT(nbt.getCompoundTag("mount"));
        }
        clientTraverseRate = nbt.getDouble("rate");
        clientBarrelLength = Math.max(1, nbt.getInteger("barrel"));
    }

    // ---- persistence

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        NBTTagCompound mount = new NBTTagCompound();
        mechanism.writeToNBT(mount);
        nbt.setTag("mount", mount);
        nbt.setInteger("energy", energy.getEnergyStored());
        nbt.setInteger("energyMax", energy.getMaxEnergyStored());
        nbt.setInteger("heat", heat);
        nbt.setInteger("cooldown", fireCooldown);
        nbt.setInteger("shots", shotsFired);
        if (localTarget != null) {
            nbt.setDouble("targetX", localTarget.x);
            nbt.setDouble("targetY", localTarget.y);
            nbt.setDouble("targetZ", localTarget.z);
            nbt.setBoolean("hasTarget", true);
        }
        if (faction != null) {
            nbt.setString("faction", faction);
        }
        if (owner != null) {
            nbt.setUniqueId("owner", owner);
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        if (nbt.hasKey("mount")) {
            mechanism.readFromNBT(nbt.getCompoundTag("mount"));
        }
        int max = Math.max(MIN_ENERGY_BUFFER, nbt.getInteger("energyMax"));
        energy = new EnergyStorage(max, max, max, Math.min(max, nbt.getInteger("energy")));
        heat = nbt.getInteger("heat");
        fireCooldown = nbt.getInteger("cooldown");
        shotsFired = nbt.getInteger("shots");
        localTarget = nbt.getBoolean("hasTarget")
                ? new Vec3d(nbt.getDouble("targetX"), nbt.getDouble("targetY"), nbt.getDouble("targetZ"))
                : null;
        faction = nbt.hasKey("faction") ? nbt.getString("faction") : null;
        owner = nbt.hasUniqueId("owner") ? nbt.getUniqueId("owner") : null;
    }
}
