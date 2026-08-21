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

import zmaster587.advancedRocketry.api.damage.ContactResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        /** What the last thing met let through; the tick's whole power if it met nothing. */
        public final int residualEnergy;
        /**
         * The line the beam actually occupied this tick, muzzle first and {@link #endedAt} last.
         *
         * <p>Two points for the ordinary beam, which is straight. More only where something BENT it:
         * a mirror returns the beam along a new direction, and the corner is a point in this list. It
         * is here because a bent beam drawn as one straight muzzle-to-end line would be drawn through
         * the very plating that turned it.</p>
         */
        public final List<Vec3d> path;

        Emission(Vec3d endedAt, double distance, boolean hitShield, boolean hitStructure,
                 int residualEnergy, List<Vec3d> path) {
            this.endedAt = endedAt;
            this.distance = distance;
            this.hitShield = hitShield;
            this.hitStructure = hitStructure;
            this.residualEnergy = residualEnergy;
            this.path = Collections.unmodifiableList(path);
        }

        /** Whether anything turned the beam, which is the only case the path has a corner in it. */
        public boolean isBent() {
            return path.size() > 2;
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
            // beam turret keep burning hulls on a server that had switched combat off - a switch
            // covering half a mechanic, which reads as a promise and is worse than none.
            return ended(muzzle, muzzle, 0.0D, Math.max(0, powerThisTick));
        }
        double length = direction.lengthVector();
        if (length <= 1.0E-9D) {
            return ended(muzzle, muzzle, 0.0D, powerThisTick);
        }

        List<Vec3d> path = new ArrayList<Vec3d>(2);
        path.add(muzzle);

        Vec3d unit = direction.scale(1.0D / length);
        Vec3d from = muzzle;
        double reachLeft = reach;
        double travelled = 0.0D;
        int power = powerThisTick;
        boolean hitShield = false;
        boolean hitStructure = false;

        for (int segment = 0; segment < MAX_BEAM_SEGMENTS; segment++) {
            Vec3d farEnd = from.add(unit.scale(reachLeft));
            LayerCrossing.First first = LayerCrossing.along(world, from, farEnd, radius, null);
            if (first.isNothing()) {
                // Into empty space. The energy leaves with it: a beam that met nothing warmed nothing.
                path.add(farEnd);
                return new Emission(farEnd, travelled + reachLeft, hitShield, hitStructure, power,
                        path);
            }

            Vec3d contact = from.add(unit.scale(first.distance));
            double reachAtContact = reachLeft;
            travelled += first.distance;
            reachLeft -= first.distance;

            if (first.isField()) {
                hitShield = true;
                // Priced through the one declared hull-kind to shield-kind mapping, and carrying NO
                // body: a beam has nothing to mirror. Its energy arrives and stays there, which is
                // exactly why a laser is the weapon that answers a shield and a slug is the one a
                // shell can throw back.
                ShieldStrike strike = new ShieldStrike(from, unit, reachAtContact, power,
                        ImpactKindMapping.toShieldKind(kind), false, null);
                ShieldStrikeResult result = ShieldStrikeService.resolve(world, strike);
                Vec3d at = result.getHitPoint() == null ? contact : result.getHitPoint();
                // ASKING THE WRONG QUESTION HERE INVERTED THE WHOLE LASER LINE. `isIntercepted` is
                // true on an UNDERPAY as well as on a full stop, so a beam that overpowered a shell
                // died at it - the exact opposite of the reason this weapon family exists.
                // `isFullyAbsorbed` is the question that means "the shell bought all of it".
                int throughShell = result.isFullyAbsorbed() ? 0
                        : Math.max(0, result.isIntercepted()
                                ? result.getResidualImpactEnergy() : power);
                if (throughShell <= 0) {
                    path.add(at);
                    return new Emission(at, travelled, true, hitStructure, 0, path);
                }
                power = throughShell;
                from = contact.add(unit.scale(CROSSING_EPSILON));
                travelled += CROSSING_EPSILON;
                reachLeft -= CROSSING_EPSILON;
                if (reachLeft <= 0.0D) {
                    path.add(from);
                    return new Emission(from, travelled, true, hitStructure, power, path);
                }
                continue;
            }

            hitStructure = true;
            // The identity comes from the world's own counter, exactly as a shot's does: a beam held
            // for a minute declares sixty times as many impacts as one held for a second, and every
            // one of them has to be a distinct meeting or the dedup memory refuses the lot.
            TravellingBody body = new TravellingBody(ShotRegistry.get(world).nextImpactId(),
                    unit.scale(BEAM_NOMINAL_SPEED), kind, power, radius);
            ContactResolver.Resolution resolved =
                    ContactResolver.resolve(world, body, first.structure, reachLeft, false);
            ContactResult answer = resolved.result;
            int residual = answer.isStopped() ? 0 : answer.getResidualEnergy();
            if (!answer.isDeflected() && !resolved.leftTheStructure) {
                // Worth something still, but it did not get out: the walk stalled inside the
                // material, or could not run at all because the far side is not loaded. Either way
                // there is nothing to hand it on to this tick.
                residual = 0;
            }
            if (residual <= 0) {
                path.add(contact);
                return new Emission(contact, travelled, hitShield, true, 0, path);
            }

            // It is still worth something, so it goes on - and it goes on from where the WALK says it
            // got to, which is what Resolution.distance is for. Ending here instead was two bugs at
            // once: a mirror's reflection went nowhere at all, and a film that melted through cost a
            // whole extra tick before the block behind it was reached, though the plating's own
            // answer says the rest of the beam continues into whatever stood there.
            power = residual;
            if (answer.isDeflected()) {
                Vec3d away = answer.getDeflectedVelocity();
                double awayLength = away == null ? 0.0D : away.lengthVector();
                if (awayLength <= 1.0E-9D) {
                    // Deflected to a standstill, or nobody could say which way "out" points.
                    // Absorbing is the recoverable answer; inventing a direction is not.
                    path.add(contact);
                    return new Emission(contact, travelled, hitShield, true, 0, path);
                }
                // The corner is a real point on the line the beam occupies, so it is a point on the
                // path: a bent beam drawn muzzle-to-end would be drawn straight through the mirror.
                path.add(contact);
                unit = away.scale(1.0D / awayLength);
                from = contact;
            } else {
                from = contact.add(unit.scale(resolved.distance));
                travelled += resolved.distance;
                reachLeft -= resolved.distance;
            }
            from = from.add(unit.scale(CROSSING_EPSILON));
            travelled += CROSSING_EPSILON;
            reachLeft -= CROSSING_EPSILON;
            if (reachLeft <= 0.0D) {
                path.add(from);
                return new Emission(from, travelled, hitShield, true, power, path);
            }
        }

        // The segment budget, spent. A bound on WORK, not a law about beams: what stops one is its
        // reach and its power, and this only refuses to spend an unbounded number of walks on a tick
        // where two mirrors face each other. The beam ends where the budget ran out and says so.
        path.add(from);
        return new Emission(from, travelled, hitShield, hitStructure, power, path);
    }

    /** A beam that ended where it began: a two-point path and nothing met. */
    private static Emission ended(Vec3d muzzle, Vec3d at, double distance, int residual) {
        List<Vec3d> path = new ArrayList<Vec3d>(2);
        path.add(muzzle);
        path.add(at);
        return new Emission(at, distance, false, false, residual, path);
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

    /**
     * How many times one tick of beam may be handed on before the work is cut off.
     *
     * <p>A beam continues through whatever it gets past: a shell it overpowers, a film it melts, a
     * mirror that turns it. Each of those costs a crossing search and a damage walk, and two mirrors
     * facing each other would trade one beam between them until the reach ran out in epsilon-sized
     * steps. This bounds the WORK; what bounds the BEAM is still its reach and its power.</p>
     */
    private static final int MAX_BEAM_SEGMENTS = 8;

    /** How far past a crossing the line resumes, so a dead shell is not found again at distance zero. */
    private static final double CROSSING_EPSILON = 1.0E-4D;
}
