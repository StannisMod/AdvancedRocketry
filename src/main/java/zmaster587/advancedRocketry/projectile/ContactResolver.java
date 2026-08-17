package zmaster587.advancedRocketry.projectile;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.damage.Contact;
import zmaster587.advancedRocketry.api.damage.ContactResult;
import zmaster587.advancedRocketry.api.damage.IContactResponder;
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
    public static ContactResult resolve(World world, Shot shot, StructureCrossing.Hit hit,
                                        Vec3d worldVelocity) {
        if (world == null || shot == null || hit == null) {
            return ContactResult.stopped();
        }

        Contact contact = new Contact(hit.block, hit.point, hit.entryFace,
                inBlockFrame(world, hit, worldVelocity), shot.getKind(), shot.getImpactEnergy(),
                shot.getRadius(), 1.0D, hit.shipId);

        IContactResponder responder = responderAt(world, hit.block);
        if (responder != null) {
            ContactResult answer = responder.onContact(contact);
            if (answer != null) {
                return answer;
            }
        }
        return defaultLaw(world, shot, contact);
    }

    /**
     * What an ordinary block does: absorb the impact through the damage service and stop the body.
     *
     * <p>The whole energy is declared, exactly as before this seam existed — a shot does not yet
     * survive a hull, and making it survive is a separate decision with its own consequences (the
     * deceleration law, a speed floor, an identity per tick). Wiring it here would have smuggled that
     * change in under a refactor.</p>
     */
    private static ContactResult defaultLaw(World world, Shot shot, Contact contact) {
        ShipDamageService.apply(world, ImpactRequest.penetrating(shot.nextImpactId(),
                contact.getPoint(), directionOf(contact, shot), contact.getEnergy(),
                contact.getKind()));
        return ContactResult.stopped();
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
     * subspace on one. Done by mapping two world points a velocity apart and subtracting: a ship's
     * transform is rigid, so the difference of two mapped points IS the mapped vector, and it needs no
     * port surface beyond the one the crossing already uses.
     */
    private static Vec3d inBlockFrame(World world, StructureCrossing.Hit hit, Vec3d worldVelocity) {
        if (worldVelocity == null) {
            return null;
        }
        if (hit.shipId == null) {
            return worldVelocity;
        }
        double[] base = VSIntegration.toShipFrameFor(world, hit.shipId, hit.point.x, hit.point.y,
                hit.point.z);
        double[] tip = VSIntegration.toShipFrameFor(world, hit.shipId, hit.point.x + worldVelocity.x,
                hit.point.y + worldVelocity.y, hit.point.z + worldVelocity.z);
        if (base == null || tip == null) {
            // The ship stopped answering between the crossing and here. A world-frame velocity against
            // a subspace face would be a plausible-looking angle about nothing, so answer with no
            // velocity at all: the incidence reads square-on, which is the reading that never bounces.
            return null;
        }
        return new Vec3d(tip[0] - base[0], tip[1] - base[1], tip[2] - base[2]);
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
