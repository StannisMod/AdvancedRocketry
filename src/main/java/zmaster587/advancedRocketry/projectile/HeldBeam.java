package zmaster587.advancedRocketry.projectile;

import com.github.stannismod.affs.world.shield.ShieldStrike;
import com.github.stannismod.affs.world.shield.ShieldStrikeResult;
import com.github.stannismod.affs.world.shield.ShieldStrikeService;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.damage.ImpactKind;
import zmaster587.advancedRocketry.api.damage.TravellingBody;
import zmaster587.advancedRocketry.damage.ImpactKindMapping;

/**
 * One tick of a beam somebody is HOLDING on a target.
 *
 * <h3>A beam is not a shot, and the difference is what this class is</h3>
 * <p>A shot is a body with a budget: it is admitted once, it travels, and it spends what it was given
 * until it has none left. A beam has no budget and no flight — it is a LINE with a power, re-resolved
 * every tick for as long as the gun holds it, and its depth grows with dwell rather than being carried
 * in. So there is no record in the shot registry: a thing that does not travel has no flight to step,
 * and there is nothing to persist, because a beam's lifetime is exactly as long as its gun is lit.</p>
 *
 * <p>What is NOT different: everything below the muzzle. The same layer ordering decides what the line
 * meets first, the same shield seam prices what reaches a shell, and the same contact seam asks the
 * block what happens. A beam that grew a damage path of its own would be a weapon that armour does not
 * answer, and armour serving one weapon family is armour that will be wrong for the next.</p>
 *
 * <h3>Per tick, because a player has to be able to see it work</h3>
 * <p>The energy of ONE tick is declared each tick. Against a shell that is a rate against a reserve —
 * a beam the shell can pay for is held off, and one it cannot pay for gets through, which is what
 * makes a shield something to survive behind rather than something to sit behind. Against a hull it is
 * a small budget applied to the same walk over and over, so the hole deepens for as long as the trigger
 * is held.</p>
 */
public final class HeldBeam {

    /** What one tick of holding did — enough for a gun to decide, and for an instrument to report. */
    public static final class Emission {
        /** Where the beam actually ended this tick, in WORLD coordinates. */
        public final Vec3d endedAt;
        /** How far it reached before something stopped it, in blocks. */
        public final double distance;
        /** True when a shell took this tick's energy. */
        public final boolean hitShield;
        /** True when structure took it. */
        public final boolean hitStructure;
        /** What the block let through, when structure was met; the tick's whole power otherwise. */
        public final int residualEnergy;

        Emission(Vec3d endedAt, double distance, boolean hitShield, boolean hitStructure,
                 int residualEnergy) {
            this.endedAt = endedAt;
            this.distance = distance;
            this.hitShield = hitShield;
            this.hitStructure = hitStructure;
            this.residualEnergy = residualEnergy;
        }

        /** Did this tick's energy land on anything at all? */
        public boolean hitSomething() {
            return hitShield || hitStructure;
        }
    }

    private HeldBeam() {
    }

    /**
     * Resolve one tick of a beam running from {@code muzzle} along {@code direction} for at most
     * {@code reach} blocks, carrying {@code powerThisTick}.
     *
     * <p>Answers what happened; changes the world through the seams that already own those changes and
     * through no others. Server side only, like every other thing that spends damage.</p>
     */
    public static Emission emit(World world, Vec3d muzzle, Vec3d direction, double reach,
                                int powerThisTick, ImpactKind kind, double radius, String hullId) {
        if (world == null || world.isRemote || muzzle == null || direction == null
                || powerThisTick <= 0 || reach <= 0.0D
                || !ARConfiguration.getCurrentConfig().enableWeapons) {
            // The war switch is asked HERE and not only where a round is admitted. A held beam has no
            // record and never passes through the registry, so a gate on the registry alone let a
            // beam turret keep burning hulls on a server that had switched combat off — a switch
            // covering half a mechanic, which reads as a promise and is worse than none.
            return new Emission(muzzle, 0.0D, false, false, Math.max(0, powerThisTick));
        }
        double length = direction.lengthVector();
        if (length <= 1.0E-9D) {
            return new Emission(muzzle, 0.0D, false, false, powerThisTick);
        }
        Vec3d unit = direction.scale(1.0D / length);
        Vec3d farEnd = muzzle.add(unit.scale(reach));

        LayerCrossing.First first = LayerCrossing.along(world, muzzle, farEnd, radius, null);
        if (first.isNothing()) {
            // Into empty space. The energy leaves with it: a beam that met nothing warmed nothing.
            return new Emission(farEnd, reach, false, false, powerThisTick);
        }

        Vec3d contact = muzzle.add(unit.scale(first.distance));

        if (first.isField()) {
            // Priced through the one declared hull-kind to shield-kind mapping, and carrying NO body:
            // a beam has nothing to mirror. Its energy arrives and stays there, which is exactly why a
            // laser is the weapon that answers a shield and a slug is the one a shell can throw back.
            ShieldStrike strike = new ShieldStrike(muzzle, unit, reach, powerThisTick,
                    ImpactKindMapping.toShieldKind(kind), false, null);
            ShieldStrikeResult result = ShieldStrikeService.resolve(world, strike);
            if (result.isFullyAbsorbed()) {
                Vec3d at = result.getHitPoint() == null ? contact : result.getHitPoint();
                return new Emission(at, first.distance, true, false, 0);
            }
            // Either the shell paid nothing (it went down between the two questions) or it paid what
            // it could and that was not enough. Both mean the same thing to a beam: what the shell
            // could not buy carries on into whatever is behind it.
            //
            // ASKING THE WRONG QUESTION HERE INVERTED THE WHOLE LASER LINE. `isIntercepted` is true
            // on an UNDERPAY as well as on a full stop, so a beam that overpowered a shell died at it
            // — the exact opposite of the reason this weapon family exists: a beam whose power the
            // shell cannot pay for is supposed to get through. `isFullyAbsorbed` is the question that
            // means "the shell bought all of it".
            int throughShell = Math.max(0, result.isIntercepted()
                    ? result.getResidualImpactEnergy() : powerThisTick);
            if (throughShell <= 0) {
                Vec3d at = result.getHitPoint() == null ? contact : result.getHitPoint();
                return new Emission(at, first.distance, true, false, 0);
            }
            return emit(world, contact.add(unit.scale(CROSSING_EPSILON)), unit,
                    reach - first.distance - CROSSING_EPSILON, throughShell, kind, radius, hullId);
        }

        // Structure. The identity comes from the world's own counter, exactly as a shot's does: a beam
        // held for a minute declares sixty times as many impacts as one held for a second, and every
        // one of them has to be a distinct meeting or the dedup memory refuses the lot.
        TravellingBody body = new TravellingBody(ShotRegistry.get(world).nextImpactId(),
                unit.scale(BEAM_NOMINAL_SPEED), kind, powerThisTick, radius);
        ContactResolver.Resolution resolved = ContactResolver.resolve(world, body, first.structure,
                reach - first.distance, false);
        int residual = resolved.result.isStopped() ? 0 : resolved.result.getResidualEnergy();
        return new Emission(contact, first.distance, false, true, residual);
    }

    /**
     * A nominal speed for the body's facts, used for the ANGLE and for nothing else.
     *
     * <p>The contact seam reads a velocity to work out an incidence, and an incidence is what decides
     * a graze. A beam has no speed worth modelling — light crosses a battle in microseconds — so what
     * is handed over is a direction with a magnitude, and the magnitude is never read: ricochet is
     * gated on a body having MASS, and a beam has none, so nothing here can bounce.</p>
     */
    private static final double BEAM_NOMINAL_SPEED = 1.0D;

    /** How far past a crossing the line resumes, so a dead shell is not found again at distance zero. */
    private static final double CROSSING_EPSILON = 1.0E-4D;
}
