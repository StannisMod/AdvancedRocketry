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

        TileEntityFieldGenerator nearest = nearestShell(world, strike.getOrigin(),
                strike.getDirection(), strike.getMaxDistance());
        if (nearest == null) {
            return ShieldStrikeResult.passed();
        }

        double nearestT = FieldSurfaceMath.rayShellEntry(nearest, strike.getOrigin(),
                strike.getDirection(), strike.getMaxDistance());
        Vec3d hitPoint = strike.getOrigin().add(FieldSurfaceMath.scale(strike.getDirection(), nearestT));
        return absorb(nearest, strike, hitPoint);
    }

    /**
     * How far along {@code dir} this ray first meets a powered shell, or {@code -1} when it meets
     * none within {@code maxDist}. A pure geometric question: <b>nothing is absorbed and no shield is
     * charged</b>.
     *
     * <p>It exists for a caller that has to decide which of several layers a travelling body meets
     * FIRST — the field, or the hull behind it — and therefore has to know where the field is before
     * committing to hitting it. {@link #resolve} finds its shell through the same search, so the two
     * cannot answer differently about where the shell is.</p>
     */
    public static double nearestShellCrossing(World world, Vec3d origin, Vec3d dir, double maxDist) {
        if (world == null || world.isRemote || origin == null || dir == null) {
            return -1.0D;
        }
        TileEntityFieldGenerator nearest = nearestShell(world, origin, dir, maxDist);
        return nearest == null ? -1.0D
                : FieldSurfaceMath.rayShellEntry(nearest, origin, dir, maxDist);
    }

    /** The powered shell this ray enters first, or null. The one shell search in this service. */
    private static TileEntityFieldGenerator nearestShell(World world, Vec3d origin, Vec3d dir,
                                                         double maxDist) {
        // Cheap global short-circuit before any per-generator geometry.
        if (!TileEntityFieldGenerator.hasActiveGenerators()) {
            return null;
        }
        List<TileEntityFieldGenerator> generators = FieldSurfaceMath.getActiveGenerators(world);
        TileEntityFieldGenerator nearest = null;
        double nearestT = Double.POSITIVE_INFINITY;
        for (TileEntityFieldGenerator generator : generators) {
            double t = FieldSurfaceMath.rayShellEntry(generator, origin, dir, maxDist);
            if (t >= 0.0D && t < nearestT) {
                nearestT = t;
                nearest = generator;
            }
        }
        return nearest;
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
