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
import zmaster587.advancedRocketry.damage.ShipDamageService;
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
     * Resolve one shot meeting one block.
     *
     * @param hit the crossing, carrying the block's own frame, the entry face in that frame and the
     *            world point
     * @param worldVelocity the shot's velocity in WORLD terms
     */
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

    public static Resolution resolve(World world, Shot shot, StructureCrossing.Hit hit,
                                     Vec3d worldVelocity, double reachBlocks, boolean resumingBore) {
        if (world == null || shot == null || hit == null) {
            return new Resolution(ContactResult.stopped(), 0.0D);
        }

        Contact contact = new Contact(hit.block, hit.point, hit.entryFace,
                inBlockFrame(world, hit, worldVelocity), shot.getKind(), shot.getImpactEnergy(),
                shot.getRadius(), 1.0D, hit.shipId);

        IContactResponder responder = responderAt(world, hit.block);
        if (responder != null) {
            ContactResult answer = responder.onContact(contact);
            if (answer != null) {
                // A block that answered for itself did not walk anything, so the body is advanced past
                // the block it was answered by — otherwise the next test finds the same block, asks
                // again, and a round argues with one plate until the tick's crossing budget runs out.
                return new Resolution(answer, answer.isStopped() ? 0.0D : 1.0D);
            }
        }
        return defaultLaw(world, shot, contact, reachBlocks, resumingBore);
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
    private static Resolution defaultLaw(World world, Shot shot, Contact contact, double reachBlocks,
                                         boolean resumingBore) {
        ImpactRequest request = resumingBore
                ? ImpactRequest.resuming(shot.nextImpactId(), contact.getPoint(),
                        directionOf(contact, shot), contact.getEnergy(), contact.getKind(),
                        reachBlocks, areaOf(contact.getRadius()))
                : ImpactRequest.penetrating(shot.nextImpactId(), contact.getPoint(),
                        directionOf(contact, shot), contact.getEnergy(), contact.getKind(),
                        reachBlocks, areaOf(contact.getRadius()));
        DamageReport report = ShipDamageService.apply(world, request);

        int residual = report.getBudgetLeft();
        if (residual <= 0) {
            return new Resolution(ContactResult.stopped(), report.getDistanceWalked());
        }
        // It got through what it met, or as far as this tick's travel allowed. Either way it is still
        // a shot, and the substrate advances it by what the walk says it covered.
        return new Resolution(ContactResult.passedThrough(residual), report.getDistanceWalked());
    }

    /**
     * The world-frame direction the impact is declared along. Taken from the shot rather than from the
     * contact's own velocity, because the contact carries a BLOCK-frame velocity and the damage
     * service works in world terms — mixing the two is the frame bug this separation exists to make
     * impossible.
     */
    private static Vec3d directionOf(Contact contact, Shot shot) {
        Vec3d v = shot.getVelocity();
        double speed = v.lengthVector();
        return speed <= 1.0E-9D ? new Vec3d(0.0D, -1.0D, 0.0D) : v.scale(1.0D / speed);
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
