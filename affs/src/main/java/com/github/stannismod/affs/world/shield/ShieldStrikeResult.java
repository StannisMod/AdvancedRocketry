package com.github.stannismod.affs.world.shield;

import net.minecraft.util.math.Vec3d;

/**
 * Outcome of {@link ShieldStrikeService#resolve} (D134-2). Either the strike never met a powered shell
 * ({@link #passed()} — nothing absorbed, the weapon applies its full effect), or it was intercepted:
 *
 * <ul>
 *   <li><b>fully</b> — the shield paid the whole cost; {@link #getResidualImpactEnergy()} is 0 and the
 *       weapon's effect stops at {@link #getHitPoint()} (a flash at the crossing);</li>
 *   <li><b>partially</b> — the shield spent everything it had but could not cover the cost; the
 *       remainder passes downstream ({@code residualImpactEnergy > 0}) and the shield is drained toward
 *       zero. This is the graceful-penetration "shields fall" case (D134-2), the same degrade shape as
 *       the kinetic path where a downed shield lets a body through.</li>
 * </ul>
 *
 * <p>A fully absorbed strike that declared a <em>travelling body</em> is additionally
 * {@link #reflected}: the shield mirrored the body off the shell and hands back its new velocity, so the
 * shot resumes at {@link #getHitPoint()} rather than ending there. Reflection is a <b>specialisation of
 * full interception</b>, not a fourth state — a caller that has never heard of it still reads
 * "intercepted, fully absorbed, nothing passed", which remains true.</p>
 */
public final class ShieldStrikeResult {

    private final boolean intercepted;
    private final Vec3d hitPoint;
    private final int absorbedShieldEnergy;
    private final int residualImpactEnergy;
    private final Vec3d reflectedVelocity;

    private ShieldStrikeResult(boolean intercepted, Vec3d hitPoint, int absorbedShieldEnergy,
                               int residualImpactEnergy, Vec3d reflectedVelocity) {
        this.intercepted = intercepted;
        this.hitPoint = hitPoint;
        this.absorbedShieldEnergy = absorbedShieldEnergy;
        this.residualImpactEnergy = residualImpactEnergy;
        this.reflectedVelocity = reflectedVelocity;
    }

    public static ShieldStrikeResult passed() {
        return new ShieldStrikeResult(false, null, 0, 0, null);
    }

    public static ShieldStrikeResult intercepted(Vec3d hitPoint, int absorbedShieldEnergy,
                                                 int residualImpactEnergy) {
        return new ShieldStrikeResult(true, hitPoint, Math.max(0, absorbedShieldEnergy),
                Math.max(0, residualImpactEnergy), null);
    }

    /**
     * A fully absorbed strike whose declared body was mirrored off the shell: the shot continues from
     * {@code hitPoint} with {@code newVelocity} instead of stopping there. Residual is 0 by
     * construction — a short pay never reflects, because there the shield spent everything it had and
     * the body carries on through unchanged.
     *
     * <p>A null {@code newVelocity} degrades to a plain full interception rather than claiming a
     * reflection with nowhere to go.</p>
     */
    public static ShieldStrikeResult reflected(Vec3d hitPoint, int absorbedShieldEnergy,
                                               Vec3d newVelocity) {
        return new ShieldStrikeResult(true, hitPoint, Math.max(0, absorbedShieldEnergy), 0, newVelocity);
    }

    /** True when the strike met a powered shell and at least some of it was absorbed. */
    public boolean isIntercepted() {
        return intercepted;
    }

    /** True when the shield paid the entire cost and the strike is stopped at the shell. */
    public boolean isFullyAbsorbed() {
        return intercepted && residualImpactEnergy == 0;
    }

    /** The shell crossing point (a flash / where the weapon effect stops), or null when it passed. */
    public Vec3d getHitPoint() {
        return hitPoint;
    }

    /** Shield energy actually spent absorbing this strike. */
    public int getAbsorbedShieldEnergy() {
        return absorbedShieldEnergy;
    }

    /** Declared impact energy that passes downstream (0 on a full absorb; {@literal >}0 on a short pay). */
    public int getResidualImpactEnergy() {
        return residualImpactEnergy;
    }

    /** True when the shield mirrored a declared travelling body — the shot resumes, it does not stop. */
    public boolean isReflected() {
        return reflectedVelocity != null;
    }

    /** The mirrored body's new world velocity, or null when nothing was reflected. */
    public Vec3d getReflectedVelocity() {
        return reflectedVelocity;
    }
}
