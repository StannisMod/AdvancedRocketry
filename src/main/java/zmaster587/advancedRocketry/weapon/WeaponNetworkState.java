package zmaster587.advancedRocketry.weapon;

import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;

/**
 * What a weapons network agrees on: where it is pointing, and whether it may shoot.
 *
 * <h3>One target, held by the network rather than by a console</h3>
 * <p>Two consoles on one network cannot disagree, because neither of them owns this — they both
 * edit it. That also survives the console being broken and rebuilt, and it is what makes "assign a
 * target" a network-level act rather than a message a console has to keep re-sending to each gun.</p>
 *
 * <h3>Hold-fire is a separate switch from having a target</h3>
 * <p>Aiming and shooting are different decisions: a battery tracking an approaching ship without
 * firing on it is the normal state of a defended station. So clearing the target is not how one
 * stops the shooting, and holding fire does not make the guns forget where the enemy is.</p>
 */
public class WeaponNetworkState extends SubsystemNetworkState {

    private Vec3d target;
    private boolean holdFire;

    /** Where the network's guns are pointed, in WORLD coordinates, or null when nothing is assigned. */
    public Vec3d getTarget() {
        return target;
    }

    public void setTarget(Vec3d target) {
        this.target = target;
    }

    public void clearTarget() {
        this.target = null;
    }

    /** True while the network's guns must track but not shoot. */
    public boolean isHoldFire() {
        return holdFire;
    }

    public void setHoldFire(boolean holdFire) {
        this.holdFire = holdFire;
    }

    @Override
    public SubsystemNetworkState copy() {
        WeaponNetworkState copy = new WeaponNetworkState();
        copyInto(copy);
        copy.target = target;
        copy.holdFire = holdFire;
        return copy;
    }
}
