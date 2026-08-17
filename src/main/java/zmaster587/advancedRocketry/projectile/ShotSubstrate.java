package zmaster587.advancedRocketry.projectile;

import com.github.stannismod.affs.world.shield.ShieldStrike;
import com.github.stannismod.affs.world.shield.ShieldStrikeResult;
import com.github.stannismod.affs.world.shield.ShieldStrikeService;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.damage.ContactResult;
import zmaster587.advancedRocketry.api.projectile.ShotEndReason;
import zmaster587.advancedRocketry.api.projectile.ShotSpec;

import java.util.List;

/**
 * What a shot does between muzzle and impact. Fire one with {@link #launch}; everything after that
 * happens in this world's own tick, whether or not anybody is watching.
 *
 * <h3>The layer this owns, and the two it does not</h3>
 * <ul>
 *   <li><b>Here</b>: what a shot IS while it travels — a record, its integration, and which layer it
 *       meets FIRST. That last one is this class's real content.</li>
 *   <li><b>The field layer</b> (the shield's strike seam) owns what a shell does to a body that
 *       reaches it: how much it absorbs, and where a mirrored body goes. This class hands a strike
 *       over and reads the answer; it never computes a deflection or spends shield energy itself.</li>
 *   <li><b>The structure layer</b> owns what an impact does to blocks, and the BLOCK it met owns
 *       what happens to the body: this class asks (through {@link ContactResolver}) and obeys the
 *       answer. It still names no stage and no ship, and it never decides a deflection itself.</li>
 * </ul>
 *
 * <h3>Ordering is geometric, not a pipeline</h3>
 * <p>Shield first, then hull, would be a rule that is wrong whenever the geometry says otherwise — a
 * shot fired from <em>inside</em> a shell meets the hull with no shield in between, and a shot that
 * passes a friendly bubble on the way to its target should not be billed to it. So every layer is
 * asked where it would be crossed, in blocks along this tick's segment, and the smallest distance
 * wins. The field layer answers {@code -1} for a ray that starts inside a shell, which is the same
 * statement in its own vocabulary.</p>
 *
 * <h3>Server only</h3>
 * <p>A client that simulated shots would be describing a different flight from the one about to hit
 * somebody. It is told about shots; it never steps one.</p>
 */
public final class ShotSubstrate {

    /**
     * How many crossings one shot may resolve within a single tick. A reflected shot resumes inside
     * the same tick with what is left of its step, and two shells facing each other would otherwise
     * bounce it forever inside one tick. At the cap the shot simply stops advancing this tick and
     * carries on next one — it is a bound on work, not on how many times a shot may bounce.
     */
    private static final int MAX_CROSSINGS_PER_TICK = 4;

    /**
     * How far past a crossing the shot is nudged before the next test. A body left exactly on a
     * surface is at the mercy of the last bit of a double: the same crossing can be found again, at
     * distance zero, and the tick makes no progress.
     */
    private static final double CROSSING_EPSILON = 1.0E-4D;

    private ShotSubstrate() {
    }

    /**
     * Admit a shot into {@code world} and answer its id, or {@code -1} when it was refused — the
     * substrate is switched off, the world is a client's, or that world is already carrying as many
     * shots as it is allowed. A weapon that fired and got {@code -1} did not fire.
     */
    public static long launch(World world, ShotSpec spec) {
        if (world == null || world.isRemote || spec == null
                || !ARConfiguration.getCurrentConfig().enableProjectileSubstrate) {
            return -1L;
        }
        long id = ShotRegistry.get(world).add(spec, ARConfiguration.getCurrentConfig().maxShotsPerWorld);
        if (id >= 0L) {
            ShotReplication.announceSpawn(world, id, spec);
        }
        return id;
    }

    /** Advance every shot in this world by one tick. Driven by {@link ShotSubstrateEvents}. */
    public static void tick(World world) {
        if (world == null || world.isRemote
                || !ARConfiguration.getCurrentConfig().enableProjectileSubstrate) {
            return;
        }
        ShotRegistry registry = ShotRegistry.get(world);
        if (registry.count() == 0) {
            return;
        }
        List<Shot> shots = registry.snapshot();
        for (Shot shot : shots) {
            ShotEndReason end = step(world, shot);
            if (end != null) {
                // The shot's own position IS where it ended: every terminal branch of the step sets
                // it to the crossing point before returning, so there is one place that decides
                // where a round stopped rather than two that could disagree.
                registry.end(shot.getId(), end, shot.getPosition());
                ShotReplication.announceEnd(world, shot.getId(), shot.getPosition(), end);
            }
        }
        registry.markDirty();
    }

    /**
     * One tick of one shot: why it ended, or null if it is still in the air.
     *
     * <p>Package-visible so a test can step a single shot deterministically instead of waiting on a
     * server tick and then having to explain which tick it was looking at.</p>
     */
    static ShotEndReason step(World world, Shot shot) {
        shot.incrementAge();
        if (shot.getAge() > shot.getLifetimeTicks()) {
            return ShotEndReason.EXPIRED;
        }

        Vec3d position = shot.getPosition();
        Vec3d velocity = shot.getVelocity();
        double gravity = shot.getEnvironment().getGravityPerTickSquared();
        if (gravity > 0.0D) {
            // Semi-implicit Euler: the tick's own acceleration is applied before the step, so a shot
            // fired flat starts falling in the tick it is fired rather than the one after.
            velocity = velocity.addVector(0.0D, -gravity, 0.0D);
        }

        double timeLeft = 1.0D;
        for (int crossing = 0; crossing < MAX_CROSSINGS_PER_TICK && timeLeft > 1.0E-6D; crossing++) {
            double speed = velocity.lengthVector();
            if (speed <= 1.0E-9D) {
                break; // going nowhere; it still ages out
            }
            Vec3d direction = velocity.scale(1.0D / speed);
            double reach = speed * timeLeft;
            Vec3d segmentEnd = position.add(velocity.scale(timeLeft));

            double fieldDistance = ShieldStrikeService.nearestShellCrossing(world, position, direction,
                    reach);
            StructureCrossing.Hit structure = StructureCrossing.firstAlong(world, position, segmentEnd);
            double structureDistance = structure == null ? -1.0D : structure.distance;

            boolean fieldFirst = fieldDistance >= 0.0D
                    && (structureDistance < 0.0D || fieldDistance <= structureDistance);
            boolean structureFirst = structureDistance >= 0.0D && !fieldFirst;

            if (structureFirst) {
                shot.setPosition(structure.point);
                shot.setVelocity(velocity);

                // The block decides, this loop obeys — the same relationship the shell above already
                // has with the field layer. Today every ordinary block answers "stopped", which is
                // exactly what happened before there was a contract; armour is what makes the other
                // two answers reachable.
                ContactResult contact = ContactResolver.resolve(world, shot, structure, velocity);
                if (contact.isStopped()) {
                    return ShotEndReason.STRUCTURE_IMPACT;
                }

                double consumed = (structure.distance + CROSSING_EPSILON) / speed;
                timeLeft -= consumed;
                shot.setImpactEnergy(contact.getResidualEnergy());
                if (contact.isDeflected()) {
                    velocity = contact.getDeflectedVelocity();
                    position = structure.point.add(velocity.normalize().scale(CROSSING_EPSILON));
                } else {
                    position = structure.point.add(direction.scale(CROSSING_EPSILON));
                }
                continue;
            }
            if (!fieldFirst) {
                position = segmentEnd;
                timeLeft = 0.0D;
                break;
            }

            ShieldStrikeResult result = ShieldStrikeService.resolve(world,
                    ShieldStrike.kineticBody(position, direction, reach, shot.getImpactEnergy(),
                            velocity));
            if (!result.isIntercepted()) {
                // The shell was crossed but paid nothing — it went down between the two questions.
                // Carry on through where it used to be rather than stopping in mid-air.
                position = position.add(direction.scale(fieldDistance + CROSSING_EPSILON));
                timeLeft -= (fieldDistance + CROSSING_EPSILON) / speed;
                continue;
            }

            double consumed = (fieldDistance + CROSSING_EPSILON) / speed;
            timeLeft -= consumed;
            Vec3d hitPoint = result.getHitPoint() == null
                    ? position.add(direction.scale(fieldDistance)) : result.getHitPoint();

            if (result.isReflected()) {
                Vec3d bounced = result.getReflectedVelocity();
                if (bounced == null
                        || bounced.lengthVector()
                                < ARConfiguration.getCurrentConfig().shotReflectionSpeedFloor) {
                    shot.setPosition(hitPoint);
                    shot.setVelocity(new Vec3d(0.0D, 0.0D, 0.0D));
                    return ShotEndReason.REFLECTED_TOO_SLOW;
                }
                velocity = bounced;
                position = hitPoint.add(bounced.normalize().scale(CROSSING_EPSILON));
                continue;
            }
            if (result.getResidualImpactEnergy() <= 0) {
                shot.setPosition(hitPoint);
                shot.setVelocity(velocity);
                return ShotEndReason.FIELD_ABSORBED;
            }
            // Graceful penetration: the shell spent everything it had and could not cover the cost.
            // The body carries on, worth less.
            shot.setImpactEnergy(result.getResidualImpactEnergy());
            position = hitPoint.add(direction.scale(CROSSING_EPSILON));
        }

        shot.setPosition(position);
        shot.setVelocity(velocity);
        return null;
    }

}
