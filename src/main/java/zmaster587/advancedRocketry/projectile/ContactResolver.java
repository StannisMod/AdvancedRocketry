package zmaster587.advancedRocketry.projectile;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.damage.Contact;
import zmaster587.advancedRocketry.api.damage.ContactResult;
import zmaster587.advancedRocketry.api.damage.IContactResponder;
import zmaster587.advancedRocketry.api.damage.DamageReport;
import zmaster587.advancedRocketry.api.damage.ImpactRequest;
import zmaster587.advancedRocketry.api.damage.TravellingBody;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.damage.ImpactKind;
import zmaster587.advancedRocketry.damage.ShipDamageService;
import zmaster587.advancedRocketry.util.WeightEngine;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Asks the block a shot just met what happens, and answers for it when it has nothing to say.
 *
 * <h3>Why this lives in the shot's layer and not in the damage engine</h3>
 * <p>A contact is a fact about a travelling body — how fast, how wide, at what angle — and the damage
 * engine is deliberately ignorant of all three: it is also driven by explosions and collisions, which
 * have none of them. So the body's own layer resolves the meeting, and only what the answer says was
 * absorbed goes to the engine, through the door that already existed.</p>
 *
 * <h3>The default law reproduces what the game did before there was a contract</h3>
 * <p>A block that says nothing gets the behaviour it always had: the impact is declared against
 * structure with the shot's full energy and the shot ends there. That is the property this layer can
 * be held to before any armour exists — if the plain-hull tests move, the seam was built wrong,
 * whatever the armour does later.</p>
 */
public final class ContactResolver {

    private ContactResolver() {
    }

    /**
     * What happened, AND how far along its own direction the body got while it happened. The distance
     * is not part of {@link ContactResult} on purpose: a block answering a contact says what becomes
     * of the body, not how the substrate should move it, and giving armour a way to state a distance
     * would be giving it a way to teleport a round.
     */
    public static final class Resolution {
        public final ContactResult result;
        public final double distance;

        Resolution(ContactResult result, double distance) {
            this.result = result;
            this.distance = Math.max(0.0D, distance);
        }
    }

    /**
     * Resolve one travelling body meeting one block.
     *
     * <p>It takes the body's FACTS rather than whatever record is carrying them, so that armour serves
     * every weapon family: a shell out of a gun, a bolt, and a beam somebody is holding on a hull are
     * three different things to own and one thing to answer.</p>
     *
     * @param body the body's own facts — velocity, kind, what it is still worth, how wide it is, and
     *             the identity this meeting is remembered by
     * @param hit  the crossing: the block's own frame, the entry face in that frame, the world point
     */
    public static Resolution resolve(World world, TravellingBody body, StructureCrossing.Hit hit,
                                     double reachBlocks, boolean resumingBore) {
        if (world == null || body == null || hit == null) {
            return new Resolution(ContactResult.stopped(), 0.0D);
        }

        Contact contact = new Contact(hit.block, hit.point, hit.entryFace,
                inBlockFrame(world, hit, body.getVelocity()), body.getKind(), body.getEnergy(),
                body.getRadius(), 1.0D, hit.shipId);

        IContactResponder responder = responderAt(world, hit.block);
        if (responder != null) {
            ContactResult answer = responder.onContact(world, contact);
            if (answer != null) {
                // A block that answered for itself did not walk anything, so the body is advanced past
                // the block it was answered by — otherwise the next test finds the same block, asks
                // again, and a round argues with one plate until the tick's crossing budget runs out.
                return new Resolution(answer, answer.isStopped() ? 0.0D : 1.0D);
            }
        }
        ContactResult skipped = ricochet(world, contact, body);
        if (skipped != null) {
            // A graze that skipped off did not walk into anything, so the body is moved past the block
            // it bounced from, exactly as a block that answered for itself would have left it.
            return new Resolution(skipped, 1.0D);
        }
        return defaultLaw(world, body, contact, reachBlocks, resumingBore);
    }

    /**
     * The default ricochet: a solid round that meets METAL at a shallow enough angle skips off it.
     *
     * <p>Three narrowings, and each one is what keeps this from being a surprise. Only a body with
     * MASS bounces — a beam has nothing to reflect and its energy is absorbed. Only METAL bounces, so
     * a player meets skipping rounds off a steel hull and never off a plank wall. And only a shallow
     * enough hit bounces, on an angle threshold that preserves the one ordering worth preserving: a
     * squarer hit never bounces where a shallower one did not.</p>
     *
     * <p>Answers null when the body digs in — which is every case except a glancing hit on metal, and
     * therefore the case the whole game is still made of.</p>
     */
    private static ContactResult ricochet(World world, Contact contact, TravellingBody body) {
        if (!carriesMass(contact.getKind()) || contact.getEntryFace() == null) {
            return null;
        }
        double threshold = ARConfiguration.getCurrentConfig().ricochetIncidenceDegrees;
        if (threshold >= 90.0D || contact.getIncidenceDegrees() < threshold) {
            return null;
        }
        if (!WeightEngine.INSTANCE.isMetal(world, contact.getPos())) {
            return null;
        }
        Vec3d bounced = mirrored(contact);
        if (bounced == null) {
            return null;
        }
        Vec3d worldVelocity = toWorldFrame(world, contact.getShipId(), bounced);
        return worldVelocity == null ? null : ContactResult.deflected(worldVelocity, body.getEnergy());
    }

    /** Which kinds are a lump of something travelling, as opposed to energy arriving. */
    private static boolean carriesMass(ImpactKind kind) {
        return kind == ImpactKind.KINETIC || kind == ImpactKind.EXPLOSIVE;
    }

    /**
     * The body's velocity mirrored in the face it grazed, in the BLOCK's own frame — which is the only
     * frame in which the normal and the velocity are the same kind of thing. Restitution takes its
     * cut here, so a bounce costs a round something and two facing plates cannot keep one forever.
     */
    /**
     * The body's velocity mirrored in the face it met, in WORLD terms — what a block answering with a
     * deflection has to hand back. Exposed because a block that computed this itself would be doing
     * the frame conversion a second time, and the second time is where a subspace normal meets a
     * world velocity and nobody notices. Answers null when the ship cannot be asked, which the caller
     * should read as "let it through" rather than as a zero velocity.
     */
    public static Vec3d mirroredWorldVelocity(World world, Contact contact, double restitution) {
        Vec3d local = mirrored(contact, restitution);
        return local == null ? null : toWorldFrame(world, contact.getShipId(), local);
    }

    private static Vec3d mirrored(Contact contact) {
        return mirrored(contact, ARConfiguration.getCurrentConfig().ricochetRestitution);
    }

    private static Vec3d mirrored(Contact contact, double restitution) {
        Vec3d normal = contact.getNormal();
        Vec3d velocity = contact.getVelocity();
        if (normal == null || velocity == null) {
            return null;
        }
        double along = velocity.x * normal.x + velocity.y * normal.y + velocity.z * normal.z;
        Vec3d reflected = velocity.subtract(normal.scale(2.0D * along));
        return reflected.scale(Math.max(0.0D, restitution));
    }

    /** Back out of the block's frame, because what flies away flies away through the world. */
    private static Vec3d toWorldFrame(World world, String shipId, Vec3d blockFrame) {
        if (shipId == null) {
            return blockFrame;
        }
        double[] rotated = VSIntegration.rotateToWorldFrameFor(world, shipId, blockFrame.x,
                blockFrame.y, blockFrame.z);
        // A ship that stopped answering between the crossing and here cannot be asked where "away"
        // points. Answering null lets the body dig in instead, which is the recoverable mistake.
        return rotated == null ? null : new Vec3d(rotated[0], rotated[1], rotated[2]);
    }

    /** How far a body of this radius reaches across, in square blocks. */
    public static double areaOf(double radius) {
        double r = Math.max(radius, 0.0D);
        return r <= 0.0D ? ImpactRequest.REFERENCE_AREA : Math.PI * r * r;
    }

    /**
     * What an ordinary block does: resist with a pressure, and let through whatever the body still has
     * after paying for the depth it managed.
     *
     * <p><b>Penetration takes time.</b> The impact is granted only the path the body actually
     * travelled this tick, so boring through a hull is a thing that happens over several ticks rather
     * than an event resolved in the tick it began. What comes back is the budget the walk could not
     * spend, and that is what the body carries on with: a round that ran out inside the armour is
     * stopped, and one that still has something left keeps going.</p>
     *
     * <p>The body's cross-section rides along, because the material resists with a pressure: the same
     * energy behind a wider face buys less depth. At the reference cross-section the price is what it
     * always was.</p>
     */
    private static Resolution defaultLaw(World world, TravellingBody body, Contact contact,
                                         double reachBlocks, boolean resumingBore) {
        ImpactRequest request = resumingBore
                ? ImpactRequest.resuming(body.getImpactId(), contact.getPoint(),
                        body.getDirection(), contact.getEnergy(), contact.getKind(),
                        reachBlocks, areaOf(contact.getRadius()))
                : ImpactRequest.penetrating(body.getImpactId(), contact.getPoint(),
                        body.getDirection(), contact.getEnergy(), contact.getKind(),
                        reachBlocks, areaOf(contact.getRadius()));
        DamageReport report = ShipDamageService.apply(world, request);

        if (ShotCrossingTrace.enabled()) {
            ShotCrossingTrace.impact(body.getImpactId(), contact.getPos(), contact.getEnergy(),
                    reachBlocks, resumingBore, report.getOutcome().name(),
                    report.getStopReason() == null ? null : report.getStopReason().name(),
                    report.getBudgetSpent(), report.getBudgetLeft(), report.getDistanceWalked(),
                    report.getBlocksStaged(), report.getBlocksDestroyed(),
                    ShipDamageService.rememberedTickOf(world, body.getImpactId()),
                    world.getTotalWorldTime());
        }

        int residual = report.getBudgetLeft();
        if (residual <= 0) {
            return new Resolution(ContactResult.stopped(), report.getDistanceWalked());
        }
        // It got through what it met, or as far as this tick's travel allowed. Either way it is still
        // a shot, and the substrate advances it by what the walk says it covered.
        return new Resolution(ContactResult.passedThrough(residual), report.getDistanceWalked());
    }

    /**
     * The shot's velocity expressed in the frame the block lives in — itself off a ship, rotated into
     * subspace on one, through the port's own vector rotation rather than a difference of two mapped
     * points (which is the same thing when the transform is rigid, and one more place to drift).
     */
    private static Vec3d inBlockFrame(World world, StructureCrossing.Hit hit, Vec3d worldVelocity) {
        if (worldVelocity == null) {
            return null;
        }
        if (hit.shipId == null) {
            return worldVelocity;
        }
        double[] rotated = VSIntegration.rotateToShipFrameFor(world, hit.shipId, worldVelocity.x,
                worldVelocity.y, worldVelocity.z);
        if (rotated == null) {
            // The ship stopped answering between the crossing and here. A world-frame velocity against
            // a subspace face would be a plausible-looking angle about nothing, so answer with no
            // velocity at all: the incidence reads square-on, which is the reading that never bounces.
            return null;
        }
        return new Vec3d(rotated[0], rotated[1], rotated[2]);
    }

    /** The block's own answer, then its tile's; null when neither has one. */
    private static IContactResponder responderAt(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        if (block instanceof IContactResponder) {
            return (IContactResponder) block;
        }
        TileEntity tile = world.getTileEntity(pos);
        return tile instanceof IContactResponder ? (IContactResponder) tile : null;
    }
}
