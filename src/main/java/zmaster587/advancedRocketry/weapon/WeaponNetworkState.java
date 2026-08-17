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
    private java.util.UUID targetEntity;
    private String accessCode = "";
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
        this.targetEntity = null;
    }

    /**
     * The entity every gun on this network is following, or null. Kept beside the point target
     * rather than replacing it: a battery told to shell a position and a battery told to track a
     * ship are different orders, and one of them survives the target moving.
     */
    public java.util.UUID getTargetEntity() {
        return targetEntity;
    }

    public void setTargetEntity(java.util.UUID entity) {
        this.targetEntity = entity;
    }

    /**
     * The network's access code — the credential a target may present to be recognised as friendly.
     * Empty means "no code set", which recognises nobody: an unarmed default that shoots everything
     * is safer than one that shoots nothing, because the second is indistinguishable from a broken
     * gun.
     */
    public String getAccessCode() {
        return accessCode == null ? "" : accessCode;
    }

    public void setAccessCode(String code) {
        this.accessCode = code == null ? "" : code;
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
        copy.targetEntity = targetEntity;
        copy.accessCode = accessCode;
        copy.holdFire = holdFire;
        return copy;
    }
}
