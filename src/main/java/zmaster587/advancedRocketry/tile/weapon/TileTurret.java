package zmaster587.advancedRocketry.tile.weapon;

import net.minecraft.entity.Entity;
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
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.EnergyStorage;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.sensor.TargetTrack;
import zmaster587.advancedRocketry.api.weapon.GunSpec;
import zmaster587.advancedRocketry.projectile.BeamReplication;
import zmaster587.advancedRocketry.projectile.HeldBeam;
import zmaster587.advancedRocketry.api.weapon.TurretDriveState;
import zmaster587.advancedRocketry.damage.DamageState;
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
    private boolean manualControl;
    private int fireCooldown;
    private int heat;
    private boolean registered;
    /** The condition this gun last saw itself in, so a change can re-walk the build exactly once. */
    private double lastConditionFraction;

    private Vec3d localTarget;
    private UUID localTargetEntity;
    private String accessCode = "";
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

        readOwnCondition();

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

        if (manualControl) {
            // Under a hand: the mount obeys the bearing it was given and nothing chooses a target
            // for it. Firing is a separate, deliberate act — see fireOnce.
            mechanism.tick(spec.getTraverseDegreesPerTick());
            syncCommandIfChanged();
            // Nothing under this hand lights a beam, so a gun taken over while burning is a gun that
            // has stopped: said out loud, or the last state it reported would stand forever.
            extinguishBeam();
            return;
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

        if (spec.isBeam()) {
            // A beam is not fired, it is HELD: there is no interval to wait out and no round to
            // admit, so the whole of "is it shooting" is whether it is lit this tick.
            holdBeam(onTarget && !isHoldingFire(), shipId);
            return;
        }
        // Rebuilt into a thrower while it was burning: the light goes out, and whoever was watching
        // is told so, exactly as if the trigger had been released.
        beamLit = false;
        beamChannel.update(world, pos, beamStartedAt, beamEndedAt, false);

        if (!onTarget || isHoldingFire() || !canFireNow()) {
            return;
        }

        launch(shipId);
    }

    /**
     * Read this mount's own condition and let it drive the traverse.
     *
     * <p>PULLED, not pushed: nothing tells a gun it was hit. The stage of the block it lives in is a
     * fact sitting in the world, and one lookup a tick is cheaper than a subscription — it also
     * survives a save, a chunk reload and the ship being reassembled for free, because the stage
     * does. Nothing about damage appears in this class beyond the two lines below; nothing about
     * guns appears in the damage engine at all.</p>
     *
     * <p>A change in the controller's own condition also re-walks the build, because the parts'
     * conditions are read during that walk and a shell that reached the controller has almost
     * certainly been through some of them.</p>
     */
    private void readOwnCondition() {
        double fraction = DamageState.getDamageFraction(world, pos);
        if (Math.abs(fraction - lastConditionFraction) > 1.0E-6D) {
            lastConditionFraction = fraction;
            assemblyDirty = true;
        }
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        mechanism.setDamageDriveState(TurretDriveState.fromDamage(fraction,
                config.turretDerateDamageFraction, config.turretJamDamageFraction));
    }

    /**
     * Send one round down the current bearing and answer whether it left, spending nothing unless it
     * did. Extracted so the automatic path and the manual one cannot drift apart: a manned gun that
     * skipped the heat or the cooldown would be strictly better than the same gun on a console,
     * which is a balance decision nobody made.
     */
    /**
     * One tick of holding the beam, or of not holding it.
     *
     * <h3>The duty cycle, which is the one place a continuous weapon is interesting</h3>
     * <p>A gun that cannot afford a shot simply does not fire it. A beam has no shot to skip, so a
     * starved one would otherwise flicker at whatever rate its feed happened to deliver — firing on
     * the ticks a little energy arrived and dying on the ones it did not. Instead it goes DARK,
     * accumulates a quantum — enough to burn for {@link #BEAM_QUANTUM_TICKS} ticks without help — and
     * only then lights again. So a weapon on a weak feed fires in real bursts a player can see and
     * plan around, rather than delivering the same average power as an unreadable stutter.</p>
     *
     * <p>The state is exposed rather than kept private: a ship's fire control cannot be flown if it
     * cannot tell "not shooting" from "cannot shoot yet".</p>
     */
    private void holdBeam(boolean wantsToFire, String shipId) {
        beamLit = burnOneTick(wantsToFire, shipId);
        // Told here and nowhere else, so every way of NOT burning — no trigger, too hot, saving up,
        // no line of fire — reaches the players watching by the same road as burning does.
        beamChannel.update(world, pos, beamStartedAt, beamEndedAt, beamLit);
    }

    /**
     * Burn for one tick if everything allows it, and answer whether the beam is lit.
     *
     * <p>Every refusal is a {@code false} rather than a silent return: what a player sees is decided
     * from the answer, and a path that ended without saying so would leave a beam drawn on a gun that
     * had stopped firing.</p>
     */
    private boolean burnOneTick(boolean wantsToFire, String shipId) {
        int perTick = spec.getBeamPowerPerTick();
        if (!wantsToFire || perTick <= 0 || !ARConfiguration.getCurrentConfig().enableWeapons) {
            // Asked here as well as under the muzzle: the emission itself refuses with the war off,
            // but a gun that called it anyway would still pay the tick's energy and heat for a beam
            // that never existed.
            return false;
        }
        if (heat >= spec.getHeatCapacity()) {
            return false;
        }
        int quantum = perTick * BEAM_QUANTUM_TICKS;
        if (beamRecharging) {
            if (energy.getEnergyStored() < quantum) {
                return false;
            }
            beamRecharging = false;
        } else if (energy.getEnergyStored() < perTick) {
            // The feed could not keep up. Go dark and start saving rather than sputtering.
            beamRecharging = true;
            markDirty();
            return false;
        }

        // The SAME muzzle a round leaves from, and the same refusal when the line of fire is not
        // clear. A beam that computed its own origin started inside the gun's own blocks and cut the
        // weapon apart from the inside on its first tick.
        TurretFireControl.Muzzle muzzle = TurretFireControl.muzzleOf(world, pos, shipId,
                mechanism.getAimDirection(), spec, assemblyReach, random);
        if (muzzle == null) {
            return false;
        }
        HeldBeam.Emission emission = HeldBeam.emit(world, muzzle.point, muzzle.direction,
                BEAM_RANGE_BLOCKS, perTick, spec.getKind(), spec.getProjectileRadius(), shipId);
        energy.extractEnergy(perTick, false);
        heat += spec.getHeatPerShot();
        beamStartedAt = muzzle.point;
        beamEndedAt = emission.endedAt;
        if (emission.hitSomething()) {
            shotsFired++;
        }
        markDirty();
        return true;
    }

    /**
     * How long a beam must be able to burn unaided before it may light again after being starved.
     * One second: long enough that a burst is a thing a player sees rather than a flicker.
     */
    private static final int BEAM_QUANTUM_TICKS = 20;

    /**
     * How far a held beam reaches. A beam does not fly, so it has no lifetime to run out — what
     * bounds it is a declared range, and the range is here rather than on the spec because nothing
     * about a gun's construction says how far light goes.
     */
    private static final double BEAM_RANGE_BLOCKS = 64.0D;

    /** Lit THIS tick. Not persisted: a beam's lifetime is exactly as long as its gun is holding it. */
    private boolean beamLit;
    /** Dark and saving up, because the feed could not keep up. Persisted — it is a real refusal. */
    private boolean beamRecharging;
    /** Where the beam left the gun last time it was lit — the muzzle, in world coordinates. */
    private Vec3d beamStartedAt;
    /** Where the beam ended last time it was lit; for instruments and for drawing it. */
    private Vec3d beamEndedAt;
    /**
     * What the players nearby have been told about this gun's beam. Owned by the gun because the
     * beam is: there is no register of live beams to look one up in.
     */
    private final BeamReplication.Channel beamChannel = new BeamReplication.Channel();

    /**
     * Is this gun mute because the server has combat switched off?
     *
     * <p>A distinct answer beside "holding fire" and "nothing left to fire with", for the same
     * reason those two are distinct: a gun that is disabled and a gun that is broken look identical
     * from outside, and the old switch made every gun on the server look broken.</p>
     */
    public boolean isDisabledByConfig() {
        return !ARConfiguration.getCurrentConfig().enableWeapons;
    }

    /** Is this gun burning right now? */
    public boolean isBeamLit() {
        return beamLit;
    }

    /**
     * Is this gun dark because it is saving up rather than because nobody asked it to fire? The
     * distinction fire control cannot be built without.
     */
    public boolean isBeamRecharging() {
        return beamRecharging;
    }

    /** Where the beam last ended, or null if it has not been lit. */
    public Vec3d getBeamEndedAt() {
        return beamEndedAt;
    }

    private boolean launch(String shipId) {
        String stamped = faction != null ? faction : getEffectiveAccessCode();
        long id = TurretFireControl.fire(world, pos, shipId, mechanism.getAimDirection(), spec,
                assemblyReach, owner, stamped, random);
        if (id < 0L) {
            return false;
        }
        lastShotId = id;
        shotsFired++;
        fireCooldown = spec.getFireIntervalTicks();
        heat += spec.getHeatPerShot();
        energy.extractEnergy(spec.getEnergyPerShot(), false);
        markDirty();
        return true;
    }

    // ---- the manual seam: present at the API, driven by nothing that ships today

    /**
     * Take the gun out of automatic control, or give it back.
     *
     * <p>The seat, the first-person view and the trigger are a later wave; what is here is the part
     * that has to exist for them not to be a rewrite — a mode in which nothing assigns a target, the
     * mount obeys a bearing it is handed, and firing is an explicit act. A gun left in manual with
     * nobody driving it simply holds still, which is the correct behaviour for an abandoned seat.</p>
     */
    public void setManualControl(boolean manual) {
        this.manualControl = manual;
        if (manual) {
            mechanism.clearCommand();
        }
        markDirty();
    }

    public boolean isManuallyControlled() {
        return manualControl;
    }

    /** Point the mount by hand. Ignored unless the gun is in manual control. */
    public void commandManualBearing(double yaw, double pitch) {
        if (manualControl) {
            mechanism.commandBearing(yaw, pitch);
        }
    }

    /**
     * Pull the trigger once. Answers whether a round left — the same conditions the automatic path
     * checks apply, including friend-or-foe, heat, charge and the line of fire.
     */
    public boolean fireOnce() {
        if (world == null || world.isRemote || !manualControl || !canFireNow()) {
            return false;
        }
        return launch(TurretFireControl.shipIdAt(world, pos));
    }

    /** Everything that must be true before a round leaves, other than pointing the right way. */
    private boolean canFireNow() {
        return ARConfiguration.getCurrentConfig().enableWeapons
                && !targetIsFriendly()
                && isLockedWellEnoughToFire()
                && spec.isOperable()
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
        Entity tracked = trackedEntity();
        if (tracked != null) {
            // Aim at the middle of the body rather than its feet: a round at foot height passes
            // under everything that is not standing on flat ground.
            return bodyCentre(tracked);
        }
        WeaponNetworkState state = networkState();
        if (state != null && state.getTarget() != null) {
            return state.getTarget();
        }
        if (localTarget != null) {
            return localTarget;
        }
        // Nobody has said anything, so what the installation's sensor found is what there is. Last
        // deliberately: an order a player gave stands until they retract it, and a sensor refreshing
        // its contact every few ticks would otherwise overrule them continuously.
        TargetTrack acquired = acquiredTrack();
        return acquired == null ? null : interceptOf(acquired);
    }

    /**
     * Where to point so the round and an acquired target arrive together.
     *
     * <p>The contact's POSITION is refreshed from the entity itself when it can still be found —
     * the sensor sweeps every few ticks and the mount follows every tick — while the VELOCITY comes
     * from the sensor, because measuring it is what a fire-control sensor is for. Aboard a moving
     * hull the shooter's own motion is taken out first: the round inherits the ship's velocity, so
     * the lead that matters is the target's motion relative to the gun and not its motion over the
     * ground.</p>
     */
    private Vec3d interceptOf(TargetTrack track) {
        Vec3d position = track.getPosition();
        Entity live = entityById(track.getEntity());
        if (live != null) {
            position = bodyCentre(live);
        }
        String shipId = TurretFireControl.shipIdAt(world, pos);
        Vec3d muzzle = TurretFireControl.worldPositionOf(world, pos, shipId);
        if (muzzle == null) {
            return position;
        }
        Vec3d relative = track.getVelocity();
        if (shipId != null) {
            double[] carried = VSIntegration.shipVelocityAtPointFor(world, shipId, muzzle.x, muzzle.y,
                    muzzle.z);
            if (carried != null) {
                relative = relative.subtract(new Vec3d(carried[0], carried[1], carried[2]));
            }
        }
        return TurretFireControl.interceptPoint(muzzle, position, relative, spec.getMuzzleSpeed());
    }

    /** The contact the installation's sensor is currently handing this gun, or null. */
    public TargetTrack acquiredTrack() {
        WeaponNetworkState state = networkState();
        return state == null || world == null ? null
                : state.getAcquiredTrack(world.getTotalWorldTime());
    }

    /**
     * Whether this gun is going on an acquisition rather than on an order. False under a hand and
     * false whenever anybody — a console, a linker, this gun's own controls — has named a target:
     * the acquisition is what is left when nothing else has been said.
     */
    private boolean engagementIsAcquired() {
        if (manualControl || trackedEntity() != null || localTarget != null) {
            return false;
        }
        WeaponNetworkState state = networkState();
        if (state != null && state.getTarget() != null) {
            return false;
        }
        return acquiredTrack() != null;
    }

    /**
     * Whether the contact is resolved well enough to shoot at.
     *
     * <p>Only ever asked of an ACQUISITION. A target a player named is a target a player named — the
     * sensor's opinion of how well it is resolved is not a veto over an order — so this gate exists
     * for exactly the case the sensor created: a battery that can see something out there and cannot
     * yet hold it well enough to hit it. That state is the reason to turn the illuminator on, and
     * turning it on is the reason it costs you your silence.</p>
     */
    private boolean isLockedWellEnoughToFire() {
        if (!engagementIsAcquired()) {
            return true;
        }
        TargetTrack acquired = acquiredTrack();
        return acquired != null && acquired.isLocked(
                ARConfiguration.getCurrentConfig().fireControlSensorLockQualityToFire);
    }

    /** The middle of a body: a round at foot height passes under everything on uneven ground. */
    private static Vec3d bodyCentre(Entity entity) {
        return entity.getPositionVector().addVector(0.0D, entity.height * 0.5D, 0.0D);
    }

    /**
     * The entity this gun is following, or null. The network's order wins over the gun's own, the
     * same way a point target does; a target that has died or logged out simply stops being found,
     * which leaves the gun holding its bearing rather than swinging to a remembered position.
     */
    private Entity trackedEntity() {
        UUID id = null;
        WeaponNetworkState state = networkState();
        if (state != null && state.getTargetEntity() != null) {
            id = state.getTargetEntity();
        } else if (localTargetEntity != null) {
            id = localTargetEntity;
        }
        return entityById(id);
    }

    /** One entity by id, or null if it has died, logged out or was never there. */
    private Entity entityById(UUID id) {
        if (id == null || !(world instanceof WorldServer)) {
            return null;
        }
        Entity entity = ((WorldServer) world).getEntityFromUuid(id);
        return entity == null || entity.isDead ? null : entity;
    }

    /**
     * Whether the thing this gun is pointed at has proved it is on our side.
     *
     * <p>The credential is carried by the TARGET, not held about it: an entity presenting the
     * installation's access code is a friend for exactly as long as it carries it, and nothing here
     * keeps a list of who is friendly. A gun with no code set recognises nobody — deliberately, since
     * a battery that shoots nothing is indistinguishable from a broken one.</p>
     *
     * <p>An acquired contact was screened for this before it ever became a contact, so asking again
     * here is depth rather than the primary check — and it is the half that answers within a tick
     * rather than within a sweep, which is the difference between a boarder who produced the code
     * and a boarder who produced it and was shot anyway.</p>
     */
    private boolean targetIsFriendly() {
        Entity engaged = engagedEntity();
        return engaged != null
                && com.github.stannismod.affs.util.CodeUtils.entityHasMatchingCode(engaged,
                        getEffectiveAccessCode());
    }

    /** The entity this gun is shooting at, whether it was named or found. Null for a point target. */
    private Entity engagedEntity() {
        Entity named = trackedEntity();
        if (named != null) {
            return named;
        }
        TargetTrack acquired = engagementIsAcquired() ? acquiredTrack() : null;
        return acquired == null ? null : entityById(acquired.getEntity());
    }

    /** The network's code when it has one, otherwise this gun's own. */
    public String getEffectiveAccessCode() {
        WeaponNetworkState state = networkState();
        if (state != null && !state.getAccessCode().isEmpty()) {
            return state.getAccessCode();
        }
        return accessCode;
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
        // A thrower is sized by what a shot costs; a BEAM has no shot, so sized by what it burns —
        // and it must hold more than one quantum, or the gun can never accumulate the thing it goes
        // dark to accumulate and is dark forever. That failure is silent: a permanently unlit beam
        // reports exactly what a correctly recharging one reports.
        int wanted = Math.max(MIN_ENERGY_BUFFER, newSpec.getEnergyPerShot() * 40);
        if (newSpec.isBeam()) {
            wanted = Math.max(wanted, newSpec.getBeamPowerPerTick() * BEAM_QUANTUM_TICKS * 2);
        }
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

    /** Follow an entity. Cleared with null; a point target set later replaces it. */
    public void setTargetEntity(UUID entity) {
        this.localTargetEntity = entity;
        markDirty();
    }

    public UUID getTargetEntity() {
        return localTargetEntity;
    }

    /** This gun's own access code, used when it is on no network or the network has none. */
    public void setAccessCode(String code) {
        this.accessCode = code == null ? "" : code;
        markDirty();
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

    /**
     * Whose side it is on. Travels with every round it fires so an impact can be attributed.
     *
     * <p><b>Nothing calls this yet, and nothing calls {@link #setOwner}.</b> Both fields persist and
     * both are stamped onto every round, but with no writer {@code owner} is always null and
     * {@code faction} always falls back to the network's access code — which is therefore what
     * actually marks a round as ours today. They are the seam a future attribution or permission
     * layer would use; until one exists, saying so here is more honest than a field that looks
     * wired.</p>
     *
     * <p>Commanding a gun is likewise unguarded on purpose: the console takes no player and asks
     * nothing, the same as most machines in this game. Keeping strangers away from a battery is a
     * protection mod's job, and one is already consulted before any block is taken.</p>
     */
    public void setFaction(String faction) {
        this.faction = faction;
        markDirty();
    }

    public String getFaction() {
        return faction;
    }

    /** Who built it. Unwired in the same way {@link #setFaction} is — see its note. */
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
        extinguishBeam();
        SubsystemNetworkRegistry.unregister(this);
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.markDirty(WeaponNetworkDomain.INSTANCE, world);
        }
        registered = false;
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        extinguishBeam();
        SubsystemNetworkRegistry.unregister(this);
        registered = false;
    }

    /**
     * Put out whatever was being drawn for this gun.
     *
     * <p>A gun that is blown up or unloaded stops burning without any tick in which to say so, and
     * the client would otherwise hold the last segment it was sent until it went stale. The staleness
     * timeout is still the backstop — this is only the fast path, and it is the one that runs when
     * somebody breaks the gun in front of you.</p>
     */
    private void extinguishBeam() {
        beamLit = false;
        beamChannel.update(world, pos, beamStartedAt, beamEndedAt, false);
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
        nbt.setBoolean("beamRecharging", beamRecharging);
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
        if (localTargetEntity != null) {
            nbt.setUniqueId("targetEntity", localTargetEntity);
        }
        nbt.setString("accessCode", accessCode);
        nbt.setBoolean("manual", manualControl);
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
        beamRecharging = nbt.getBoolean("beamRecharging");
        heat = nbt.getInteger("heat");
        fireCooldown = nbt.getInteger("cooldown");
        shotsFired = nbt.getInteger("shots");
        localTarget = nbt.getBoolean("hasTarget")
                ? new Vec3d(nbt.getDouble("targetX"), nbt.getDouble("targetY"), nbt.getDouble("targetZ"))
                : null;
        faction = nbt.hasKey("faction") ? nbt.getString("faction") : null;
        localTargetEntity = nbt.hasUniqueId("targetEntity") ? nbt.getUniqueId("targetEntity") : null;
        accessCode = nbt.getString("accessCode");
        manualControl = nbt.getBoolean("manual");
        owner = nbt.hasUniqueId("owner") ? nbt.getUniqueId("owner") : null;
    }
}
