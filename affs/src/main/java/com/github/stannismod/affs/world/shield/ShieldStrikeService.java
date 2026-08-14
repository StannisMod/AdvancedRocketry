package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.config.ModConfig;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.world.FieldSurfaceMath;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * The cooperative strike seam (D134-2 tier-1). A cooperating weapon builds a {@link ShieldStrike} and
 * calls {@link #resolve}; the service finds the nearest active, powered shell the strike's ray crosses
 * and absorbs it precisely through the {@link com.github.stannismod.affs.world.FieldFrame} seam — the
 * caller does not care whether the shield is world- or ship-framed. Absorption is graceful (D134-2): the
 * shield spends {@code min(stored, impactEnergy x rate x kindMult / tierEff)}; a full pay stops the
 * strike at the shell, a short pay lets the remainder through and drops the shield toward zero.
 *
 * <p>A fully paid KINETIC strike that declares a travelling body is <em>reflected</em> rather than
 * stopped: the shield owns that computation because the surface normal is field geometry the caller has
 * no access to, and because the moving-shell correction already lives here.</p>
 *
 * <p>Server-authoritative: energy is spent on the logical server only. This is the tier the mod builds
 * against; non-cooperating fire is covered separately (explosions and travelling projectiles already,
 * a residual hitscan-ray hook as best-effort future work).</p>
 */
public final class ShieldStrikeService {

    private ShieldStrikeService() {
    }

    public static ShieldStrikeResult resolve(World world, ShieldStrike strike) {
        if (world == null || world.isRemote || strike == null || strike.isUnblockable()
                || strike.getImpactEnergy() <= 0) {
            return ShieldStrikeResult.passed();
        }
        // Cheap global short-circuit before any per-generator geometry.
        if (!TileEntityFieldGenerator.hasActiveGenerators()) {
            return ShieldStrikeResult.passed();
        }

        List<TileEntityFieldGenerator> generators = FieldSurfaceMath.getActiveGenerators(world);
        TileEntityFieldGenerator nearest = null;
        double nearestT = Double.POSITIVE_INFINITY;
        for (TileEntityFieldGenerator generator : generators) {
            double t = FieldSurfaceMath.rayShellEntry(generator, strike.getOrigin(), strike.getDirection(),
                    strike.getMaxDistance());
            if (t >= 0.0D && t < nearestT) {
                nearestT = t;
                nearest = generator;
            }
        }
        if (nearest == null) {
            return ShieldStrikeResult.passed();
        }

        Vec3d hitPoint = strike.getOrigin().add(FieldSurfaceMath.scale(strike.getDirection(), nearestT));
        return absorb(nearest, strike, hitPoint);
    }

    private static ShieldStrikeResult absorb(TileEntityFieldGenerator generator, ShieldStrike strike,
                                             Vec3d hitPoint) {
        double kindMult = generator.getStrikeKindMultiplier(strike.getKind());
        double tierEff = Math.max(1.0D, generator.getImpactEfficiencyMultiplier());
        int cost = (int) Math.ceil(strike.getImpactEnergy() * ModConfig.shieldStrikeAbsorptionRate
                * kindMult / tierEff);
        cost = Math.max(1, cost);

        int spent = generator.absorbShieldEnergy(cost);
        if (spent <= 0) {
            return ShieldStrikeResult.passed(); // shield down — no impediment, nothing spent
        }

        generator.onFieldTouched(hitPoint, null); // flash at the crossing
        if (spent >= cost) {
            // Full pay + a declared travelling body => the shell mirrors it, with the law the entity
            // scan already uses (shell velocity out, reflect, shell velocity back in). RADIANT never
            // reflects however it arrives — a beam has no velocity to mirror.
            if (strike.getKind() == ShieldStrikeKind.KINETIC && strike.hasBody()) {
                Vec3d newVelocity = generator.reflectBodyVelocity(hitPoint, strike.getBodyVelocity());
                return ShieldStrikeResult.reflected(hitPoint, spent, newVelocity);
            }
            return ShieldStrikeResult.intercepted(hitPoint, spent, 0);
        }
        // Short pay: the shield covered only a fraction, the remainder passes downstream. It never
        // reflects, body or not — graceful penetration means exactly what it always meant.
        double fractionStopped = (double) spent / (double) cost;
        int residual = (int) Math.round(strike.getImpactEnergy() * (1.0D - fractionStopped));
        return ShieldStrikeResult.intercepted(hitPoint, spent, Math.max(1, residual));
    }
}
