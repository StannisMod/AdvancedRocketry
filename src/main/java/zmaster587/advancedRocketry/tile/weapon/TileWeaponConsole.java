package zmaster587.advancedRocketry.tile.weapon;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.sensor.TargetTrack;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkController;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkRegistry;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkStatus;
import zmaster587.advancedRocketry.weapon.TurretFireControl;
import zmaster587.advancedRocketry.weapon.TurretMechanism;
import zmaster587.advancedRocketry.weapon.WeaponNetworkDomain;
import zmaster587.advancedRocketry.weapon.WeaponNetworkState;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.IButtonInventory;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleButton;
import zmaster587.libVulpes.inventory.modules.ModuleText;
import zmaster587.libVulpes.interfaces.ILinkableTile;
import zmaster587.libVulpes.inventory.TextureResources;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * One place to point a battery, and the only thing the weapons network adds that a gun cannot do
 * alone.
 *
 * <h3>It owns nothing</h3>
 * <p>A console is a stateless editor of the network's own state: it holds no target, no hold-fire
 * flag and no copy of anything. Two consoles on one network therefore cannot disagree — they are
 * both looking at the same object — and breaking one loses nothing but the window onto it. That is
 * why every button below writes to {@link WeaponNetworkState} and every readout reads from it.</p>
 *
 * <h3>What it is FOR</h3>
 * <p>Convenience, not capability. Every gun on the network already aims and fires by itself; what a
 * console buys is doing it to a dozen guns at once, and being able to say "track but do not shoot"
 * without walking to each of them. A network with no console is a working battery whose guns are
 * commanded individually — which is exactly what the guns' own tests pin.</p>
 */
public class TileWeaponConsole extends TileEntity implements ITickable, ISubsystemNetworkController,
        ILinkableTile, IModularInventory, IButtonInventory {

    private static final int BUTTON_HOLD_FIRE = 0;
    private static final int BUTTON_CLEAR_TARGET = 1;

    private boolean registered;

    /** Client-side text, rebuilt each time the GUI is opened. */
    private final List<ModuleText> readouts = new ArrayList<>();

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        if (VSIntegration.isOnUnnamedShip(world, pos)) {
            // Same rule as a gun's: aboard a ship nobody has named, this console's own position is a
            // shipyard address, so it must not join a network or command anything.
            return;
        }
        if (!registered) {
            SubsystemNetworkRegistry.register(this);
            SubsystemNetworkManager.markDirty(WeaponNetworkDomain.INSTANCE, world);
            registered = true;
        }
    }

    // ---- network membership

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

    /**
     * The network hands its state over after every rebuild. Nothing is copied out of it: a console
     * that cached the target would be a second source of truth, and the two would disagree the first
     * time somebody used the other console.
     */
    @Override
    public void applyNetworkState(SubsystemNetworkState state) {
    }

    /** The network this console is on, or null when it stands alone. */
    public WeaponNetworkState network() {
        SubsystemNetworkState state = SubsystemNetworkManager.getState(WeaponNetworkDomain.INSTANCE,
                world, pos);
        return state instanceof WeaponNetworkState ? (WeaponNetworkState) state : null;
    }

    // ---- the commands a console exists to give

    /** Point every gun on this network at a world point. */
    public boolean assignTarget(Vec3d target) {
        WeaponNetworkState state = network();
        if (state == null) {
            return false;
        }
        state.setTarget(target);
        return true;
    }

    /** Point every gun on this network at an entity, and keep pointing as it moves. */
    public boolean assignTargetEntity(java.util.UUID entity) {
        WeaponNetworkState state = network();
        if (state == null) {
            return false;
        }
        state.setTargetEntity(entity);
        return true;
    }

    /**
     * The credential a target may present to be recognised as friendly. Set on the network rather
     * than per gun, because "who is on our side" is a property of the installation, and a battery
     * whose guns disagreed about it would shoot its own crew at random.
     */
    public boolean setAccessCode(String code) {
        WeaponNetworkState state = network();
        if (state == null) {
            return false;
        }
        state.setAccessCode(code);
        return true;
    }

    public String getAccessCode() {
        WeaponNetworkState state = network();
        return state == null ? "" : state.getAccessCode();
    }

    public java.util.UUID getTargetEntity() {
        WeaponNetworkState state = network();
        return state == null ? null : state.getTargetEntity();
    }

    public boolean clearTarget() {
        WeaponNetworkState state = network();
        if (state == null) {
            return false;
        }
        state.clearTarget();
        return true;
    }

    /**
     * Track but do not shoot. Deliberately a separate switch from having a target: a battery
     * watching an approaching ship without firing on it is the normal state of a defended station,
     * and clearing the target to stop the shooting would lose the tracking too.
     */
    public boolean setHoldFire(boolean hold) {
        WeaponNetworkState state = network();
        if (state == null) {
            return false;
        }
        state.setHoldFire(hold);
        return true;
    }

    public boolean isHoldFire() {
        WeaponNetworkState state = network();
        return state != null && state.isHoldFire();
    }

    public Vec3d getTarget() {
        WeaponNetworkState state = network();
        return state == null ? null : state.getTarget();
    }

    /**
     * What the installation's sensor is currently holding, or null. Shown beside the assigned target
     * rather than instead of it: the two are different things, and a crew that cannot see which one
     * their guns are going on cannot tell an acquisition they want from one they need to override.
     */
    public TargetTrack getAcquiredTrack() {
        WeaponNetworkState state = network();
        return state == null || world == null ? null
                : state.getAcquiredTrack(world.getTotalWorldTime());
    }

    /** How many guns this console is commanding, as the last solve counted them. */
    public int getGunCount() {
        WeaponNetworkState state = network();
        return state == null ? 0 : state.getSinkCount();
    }

    /**
     * How many of this network's guns are pointing where they were told, and how many are asking for
     * a bearing they cannot reach.
     *
     * <p>Read off the member tiles rather than accumulated into the network state: the mounts already
     * know, and a second copy updated on a different cadence would be a readout that disagrees with
     * the guns it describes. A saturated count above zero is the console's answer to "why is nothing
     * being hit" — the target is outside somebody's arc, which is a fact about the BUILD, not a
     * fault.</p>
     *
     * @return {@code [onTarget, saturated, total]}
     */
    public int[] getMountTelemetry() {
        WeaponNetworkState state = network();
        int onTarget = 0, saturated = 0, total = 0;
        if (state == null || world == null) {
            return new int[] {0, 0, 0};
        }
        for (BlockPos member : state.getMemberPositions()) {
            TileEntity tile = world.getTileEntity(member);
            if (!(tile instanceof TileTurret)) {
                continue;
            }
            total++;
            TurretMechanism mount = ((TileTurret) tile).getMechanism();
            if (mount.isOnTarget()) {
                onTarget++;
            }
            if (mount.isSaturated()) {
                saturated++;
            }
        }
        return new int[] {onTarget, saturated, total};
    }

    public String getNetworkStatusText() {
        WeaponNetworkState state = network();
        if (state == null) {
            return "no network";
        }
        switch (state.getStatus()) {
            case SubsystemNetworkStatus.DISCONNECTED:
                return "disconnected";
            case SubsystemNetworkStatus.SOURCE_LIMITED:
                return "power limited";
            case SubsystemNetworkStatus.SINK_LIMITED:
                return "idle";
            case SubsystemNetworkStatus.CABLE_LIMITED:
                return "cable limited";
            case SubsystemNetworkStatus.BALANCED:
                return "balanced";
            default:
                return "unknown";
        }
    }

    // ---- linker: the way a player names a target without typing coordinates

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        return true;
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        return entity != null && assignTarget(TurretFireControl.center(entity.getPos()));
    }

    // ---- GUI

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>();
        readouts.clear();

        modules.add(new ModuleButton(10, 20, BUTTON_HOLD_FIRE,
                LibVulpes.proxy.getLocalizedString("msg.weaponConsole.holdFire"), this,
                TextureResources.buttonBuild, 80, 18));
        modules.add(new ModuleButton(10, 42, BUTTON_CLEAR_TARGET,
                LibVulpes.proxy.getLocalizedString("msg.weaponConsole.clearTarget"), this,
                TextureResources.buttonBuild, 80, 18));

        addReadout(modules, 10, 68, statusLine());
        addReadout(modules, 10, 80, gunLine());
        addReadout(modules, 10, 92, targetLine());
        addReadout(modules, 10, 104, sensorLine());
        return modules;
    }

    private void addReadout(List<ModuleBase> modules, int x, int y, String text) {
        ModuleText module = new ModuleText(x, y, text, 0x2b2b2b);
        readouts.add(module);
        modules.add(module);
    }

    private String statusLine() {
        return "Network: " + getNetworkStatusText() + (isHoldFire() ? " (holding fire)" : "");
    }

    private String gunLine() {
        int[] mounts = getMountTelemetry();
        return "Guns: " + getGunCount() + "  on target: " + mounts[0]
                + (mounts[1] > 0 ? "  out of arc: " + mounts[1] : "");
    }

    private String targetLine() {
        Vec3d target = getTarget();
        return target == null ? "Target: none"
                : String.format("Target: %.0f, %.0f, %.0f", target.x, target.y, target.z);
    }

    private String sensorLine() {
        TargetTrack acquired = getAcquiredTrack();
        if (acquired == null) {
            return "Sensor: no contact";
        }
        boolean locked = acquired.isLocked(ARConfiguration.getCurrentConfig()
                .fireControlSensorLockQualityToFire);
        return String.format("Sensor: contact at %.0fm, lock %.2f%s", acquired.getDistance(),
                acquired.getQuality(), locked ? "" : " (too poor to fire)");
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == BUTTON_HOLD_FIRE) {
            setHoldFire(!isHoldFire());
        } else if (buttonId == BUTTON_CLEAR_TARGET) {
            clearTarget();
        }
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockWeaponConsole.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    // ---- lifecycle

    @Override
    public void invalidate() {
        super.invalidate();
        SubsystemNetworkRegistry.unregister(this);
        if (world != null && !world.isRemote) {
            // The domain clears the target when a component loses its last console: a battery left
            // firing at a point nobody can retract is the one failure a player cannot fix by
            // breaking something.
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

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        // Nothing of its own to save: the network owns the target and the hold-fire switch, and a
        // console that persisted a copy would come back disagreeing with them.
        return super.writeToNBT(nbt);
    }
}
