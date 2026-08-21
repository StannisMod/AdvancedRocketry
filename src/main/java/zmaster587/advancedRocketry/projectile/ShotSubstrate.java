package zmaster587.advancedRocketry.projectile;

import com.github.stannismod.affs.world.shield.ShieldStrike;
import com.github.stannismod.affs.world.shield.ShieldStrikeResult;
import com.github.stannismod.affs.world.shield.ShieldStrikeService;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.damage.ImpactKind;
import zmaster587.advancedRocketry.api.damage.TravellingBody;
import zmaster587.advancedRocketry.api.projectile.ShotEndReason;
import zmaster587.advancedRocketry.api.projectile.ShotSpec;
import zmaster587.advancedRocketry.damage.ImpactKindMapping;

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
                || !ARConfiguration.getCurrentConfig().enableWeapons) {
            return -1L;
        }
        long id = ShotRegistry.get(world).add(spec, ARConfiguration.getCurrentConfig().maxShotsPerWorld);
        if (id >= 0L) {
            ShotReplication.announceSpawn(world, id, spec);
        }
        return id;
    }

    /**
     * What is left of a body's velocity after it has spent energy boring.
     *
     * <p>Kinetic energy goes as the square of speed, so a body that has spent a fraction of its energy
     * keeps the square root of what remains: {@code v' = v·sqrt(E'/E)}. Written as a RATIO rather than
     * from {@code sqrt(2E/m)} on purpose — the ratio needs no mass, and a mass of zero is a legitimate
     * declaration for a body that is not a lump of metal.</p>
     *
     * <p><b>Speed and energy are coupled only for a body with mass.</b> A beam pays for depth like
     * anything else — it is the same pressure over the same area — but it does not decelerate, because
     * a beam that has spent half its energy is DIMMER, not slower: its energy is amplitude and its
     * speed is its own. A mass of zero is how the formula announces that the relationship does not
     * exist for this thing, rather than a physical claim about how fast a massless body travels.</p>
     */
    private static Vec3d slowedByWorkDone(Vec3d velocity, int energyBefore, int energyAfter,
                                          ImpactKind kind) {
        if (!carriesMass(kind) || energyBefore <= 0 || energyAfter >= energyBefore) {
            return velocity;
        }
        double ratio = Math.sqrt(Math.max(0.0D, (double) energyAfter / (double) energyBefore));
        return velocity.scale(ratio);
    }

    /**
     * How wide this shot is for the purpose of MEETING things, capped by config. A body sweeps a
     * cylinder rather than a line, and the work a step does grows with the square of its width, so an
     * absurd calibre is bounded here rather than being a way of making the server do arbitrary work.
     * The declared radius still prices the shot in full: only the geometry is capped.
     */
    private static double bodyRadius(Shot shot) {
        return Math.min(shot.getRadius(), ARConfiguration.getCurrentConfig().shotBodyRadiusCap);
    }

    /** Which kinds are a lump of something travelling, as opposed to energy arriving. */
    private static boolean carriesMass(ImpactKind kind) {
        return kind == ImpactKind.KINETIC || kind == ImpactKind.EXPLOSIVE;
    }

    /**
     * How this shot is declared to a shell: at the rate its own kind is billed at, carrying a body
     * only when there is one to carry.
     *
     * <p>Those are two separate questions and they are answered separately. What a shell CHARGES for
     * comes from the single declared hull-kind to shield-kind mapping, so a beam is billed against
     * the shell's energy resistance rather than as if it were a slug. Whether a BODY travels is what
     * decides a mirror off a fully-paid shell, and a beam has nothing to mirror: its energy arrives
     * and stays there. Declaring a velocity for one would bounce light off a shield.</p>
     */
    private static ShieldStrike strikeFor(Shot shot, Vec3d position, Vec3d direction, double reach,
                                          Vec3d velocity) {
        ImpactKind kind = shot.getKind();
        return new ShieldStrike(position, direction, reach, shot.getImpactEnergy(),
                ImpactKindMapping.toShieldKind(kind), false, carriesMass(kind) ? velocity : null);
    }

    /** Advance every shot in this world by one tick. Driven by {@link ShotSubstrateEvents}. */
    public static void tick(World world) {
        if (world == null || world.isRemote) {
            return;
        }
        if (!ARConfiguration.getCurrentConfig().enableWeapons) {
            endWhatWasStillInTheAir(world);
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
                Vec3d endedAt = ShotFrame.worldPosition(world, shot);
                registry.end(shot.getId(), end, endedAt);
                ShotReplication.announceEnd(world, shot.getId(), endedAt, end);
            }
        }
        registry.markDirty();
    }

    /**
     * Empty this world's registry when the substrate is switched off.
     *
     * <p><b>Off has to mean gone, not paused.</b> The registry is world-saved data, so a round left
     * sitting in it is written back on every save that follows, and switching the flag on again — a
     * month later, on a world that has moved on — resumes it from wherever it was. A config flag
     * that suspends its mechanic instead of ending it is not a way to turn the mechanic off.</p>
     *
     * <p>Ended one by one through the same path everything else uses, so the clients that were told
     * about these rounds are told they are over rather than left drawing them until they age out.</p>
     */
    private static void endWhatWasStillInTheAir(World world) {
        ShotRegistry registry = ShotRegistry.get(world);
        if (registry.count() == 0) {
            return;
        }
        for (Shot shot : registry.snapshot()) {
            Vec3d endedAt = ShotFrame.worldPosition(world, shot);
            registry.end(shot.getId(), ShotEndReason.SUBSTRATE_DISABLED, endedAt);
            ShotReplication.announceEnd(world, shot.getId(), endedAt, ShotEndReason.SUBSTRATE_DISABLED);
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

        // A shot that spent the last tick inside a hull is kept in that hull's frame between ticks, so
        // that a ship which manoeuvred in between carried it along. The tick itself is done in world
        // terms — the shield layer, the damage engine and every other ship are world-frame questions —
        // so it rejoins the world here, at the hull's CURRENT pose, and is handed back at the end.
        String boringHull = shot.getHullId();
        ShotFrame.leaveHull(world, shot);

        Vec3d position = shot.getPosition();
        Vec3d velocity = shot.getVelocity();
        double gravity = shot.getEnvironment().getGravityPerTickSquared();
        if (gravity > 0.0D) {
            // Semi-implicit Euler: the tick's own acceleration is applied before the step, so a shot
            // fired flat starts falling in the tick it is fired rather than the one after.
            velocity = velocity.addVector(0.0D, -gravity, 0.0D);
        }

        // The hull it was still drilling when the tick ran out, if any: that is the frame it is
        // handed back to at the end.
        String endedInsideHull = null;

        double timeLeft = 1.0D;
        for (int crossing = 0; crossing < MAX_CROSSINGS_PER_TICK && timeLeft > 1.0E-6D; crossing++) {
            double speed = velocity.lengthVector();
            if (speed <= 1.0E-9D) {
                break; // going nowhere; it still ages out
            }
            Vec3d direction = velocity.scale(1.0D / speed);
            double reach = speed * timeLeft;
            Vec3d segmentEnd = position.add(velocity.scale(timeLeft));

            // Only on the first crossing of a tick that began inside material is the hull narrowed:
            // there the answer is known to be that hull, and once the round has deflected or come out
            // it is an ordinary body again and asks everything.
            LayerCrossing.First first = LayerCrossing.along(world, position, segmentEnd,
                    bodyRadius(shot), crossing == 0 ? boringHull : null);
            StructureCrossing.Hit structure = first.structure;
            double fieldDistance = first.isField() ? first.distance : -1.0D;
            double structureDistance = structure == null ? -1.0D : structure.distance;

            if (ShotCrossingTrace.enabled()) {
                // The two distances as this step saw them, before anything is decided from them. A
                // round that crossed a wall unmarked and one whose impact was refused are the same
                // picture from outside; they differ here, and only here.
                ShotCrossingTrace.crossing(shot.getId(), shot.getAge(),
                        crossing == 0 ? boringHull : null, position, segmentEnd, bodyRadius(shot),
                        fieldDistance, structureDistance,
                        structure == null ? null : structure.block.getX() + "," + structure.block.getY()
                                + "," + structure.block.getZ() + " "
                                + world.getBlockState(structure.block).getBlock().getRegistryName());
            }

            boolean fieldFirst = first.isField();
            boolean structureFirst = first.isStructure();

            if (structureFirst) {
                shot.setPosition(structure.point);
                shot.setVelocity(velocity);

                // The block decides, this loop obeys — the same relationship the shell above already
                // has with the field layer. What it is granted is only the path still left in THIS
                // tick after reaching the surface: boring is a thing that takes time, so a round that
                // meets armour spends the rest of the tick inside it rather than resolving its whole
                // life at the moment of contact.
                int energyBefore = shot.getImpactEnergy();
                double reachInside = Math.max(0.0D, reach - structure.distance);
                // A crossing found at zero distance is a bore this shot began on an earlier tick: it
                // is standing in that block, and it paid for it then.
                boolean resuming = structure.distance <= CROSSING_EPSILON * 2.0D;
                // The seam is handed the BODY's facts, not this shot: the same armour has to answer a
                // bolt and a held beam, and neither of those is a record in this registry. A fresh
                // identity per contact is what keeps a bore across several ticks from being refused as
                // a duplicate of its own first one — and it comes from the WORLD's counter rather than
                // from this shot, so that no amount of boring can walk it into another round's.
                TravellingBody body = new TravellingBody(ShotRegistry.get(world).nextImpactId(),
                        velocity, shot.getKind(),
                        shot.getImpactEnergy(), shot.getRadius());
                ContactResolver.Resolution contact = ContactResolver.resolve(world, body, structure,
                        reachInside, resuming);
                if (contact.result.isStopped()) {
                    // It came to rest where the walk stopped, not where it went in.
                    shot.setPosition(structure.point.add(direction.scale(contact.distance)));
                    shot.setVelocity(new Vec3d(0.0D, 0.0D, 0.0D));
                    return ShotEndReason.STRUCTURE_IMPACT;
                }

                shot.setImpactEnergy(contact.result.getResidualEnergy());

                if (contact.result.isDeflected()) {
                    timeLeft -= (structure.distance + CROSSING_EPSILON) / speed;
                    velocity = contact.result.getDeflectedVelocity();
                    position = structure.point.add(velocity.normalize().scale(CROSSING_EPSILON));
                } else {
                    // It is still going, so it used this tick's travel: it is as deep as its speed
                    // took it, and no deeper. That is the whole of "penetration takes time" — the
                    // depth per tick is the distance per tick, and the next tick starts from here.
                    position = structure.point.add(direction.scale(reachInside));
                    timeLeft = 0.0D;
                    velocity = slowedByWorkDone(velocity, energyBefore, shot.getImpactEnergy(),
                            shot.getKind());
                    endedInsideHull = structure.shipId;
                }

                if (velocity.lengthVector()
                        < ARConfiguration.getCurrentConfig().shotPenetrationSpeedFloor) {
                    // It is still inside something and no longer travelling: it came to rest there.
                    shot.setPosition(position);
                    shot.setVelocity(new Vec3d(0.0D, 0.0D, 0.0D));
                    return ShotEndReason.STRUCTURE_IMPACT;
                }
                shot.setPosition(position);
                shot.setVelocity(velocity);
                continue;
            }
            if (!fieldFirst) {
                position = segmentEnd;
                timeLeft = 0.0D;
                break;
            }

            ShieldStrikeResult result = ShieldStrikeService.resolve(world,
                    strikeFor(shot, position, direction, reach, velocity));
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
        // Still drilling somebody's hull when the tick ended: it belongs to that hull until it is out,
        // so it is stored in the hull's own frame and rides whatever the ship does before the next
        // tick. A round in the world's own blocks needs none of this — the world does not manoeuvre.
        ShotFrame.embedIfInside(world, shot, endedInsideHull);
        return null;
    }

}
