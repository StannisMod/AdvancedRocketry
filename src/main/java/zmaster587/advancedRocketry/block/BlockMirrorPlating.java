package zmaster587.advancedRocketry.block;

import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import zmaster587.advancedRocketry.damage.StructureDamageEngine;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.damage.Contact;
import zmaster587.advancedRocketry.api.damage.ContactResult;
import zmaster587.advancedRocketry.api.damage.ImpactKind;
import zmaster587.advancedRocketry.projectile.ContactResolver;

/**
 * Mirror plating — glass with a thin film of metal behind it, and what that means when shot at.
 *
 * <h3>It is a coating, not a wall</h3>
 * <p>A mirror is a surface you apply to a hull, so it clings to whichever face it was put on — see
 * {@link BlockPlating}. A ship is mostly SIDES, and armour you can only lay on a deck is armour for
 * one sixth of a ship.</p>
 *
 * <h3>It reflects a FRACTION, and dies by the rest</h3>
 * <p>No mirror returns everything. What it does not return is absorbed by the film behind the glass,
 * and a film is thin: past some amount of absorbed energy the metal melts and the plate stops being an
 * optic at all. The law is the physics, with nothing invented:</p>
 * <pre>
 *   reflected = R x E            -&gt; sent back out along the mirrored direction
 *   absorbed  = (1 - R) x E      -&gt; stays in the film
 *   absorbed &gt; dissipation       -&gt; the plating is GONE, in one hit, and reflects nothing again
 * </pre>
 * <p>Two things follow without another rule being written. A better mirror survives more hits, because
 * it absorbs less of each. And there is no such thing as a half-working mirror: an optic either is one
 * or is not, which is why this does not degrade through stages the way a hull plate does.</p>
 *
 * <h3>It does nothing whatever about a slug</h3>
 * <p>Glass and foil. A solid body goes through it and is not even slowed; the kind carried by the
 * contact is the only thing separating that case from the one above, which is exactly what the contact
 * seam exists to make expressible.</p>
 */
public class BlockMirrorPlating extends BlockPlating {

    private final double reflectance;
    private final int dissipation;

    /**
     * @param reflectance how much of an arriving beam it sends back, in {@code (0,1)} — the tier
     * @param dissipation how much absorbed energy the film sheds before it melts
     */
    public BlockMirrorPlating(double reflectance, int dissipation) {
        super(Material.IRON);
        this.reflectance = Math.max(0.0D, Math.min(0.999D, reflectance));
        this.dissipation = Math.max(1, dissipation);
        setHardness(1.5F);
    }

    /** How much of a beam this tier returns. The one statement of it; nothing copies the number. */
    public double getReflectance() {
        return reflectance;
    }

    /** How much absorbed energy the film sheds before it stops being a mirror. */
    public int getDissipation() {
        return dissipation;
    }

    @Override
    public ContactResult onContact(World world, Contact contact) {
        if (contact == null) {
            return null;
        }
        if (!isRadiant(contact.getKind())) {
            // A mirror is glass and foil, and a solid round does not care that it is shiny — but it
            // does have to get through it. Declining hands the meeting to the default law, which
            // prices the film off the table and the eighth of a voxel it fills and breaks it like any
            // other pane. Answering "passed through" here instead would let a round cross for nothing
            // and leave the plating standing, which made it armour that only its own counter could
            // remove.
            return ContactResult.noOpinion();
        }

        int absorbed = (int) Math.ceil(contact.getEnergy() * (1.0D - reflectance));
        if (absorbed > dissipation) {
            // The film melted. What is left of the beam goes on into whatever was behind the glass,
            // less what the plating managed to shed on its way out of existence.
            burnOut(world, contact.getPos());
            int residual = contact.getEnergy() - dissipation;
            return residual > 0 ? ContactResult.passedThrough(residual) : ContactResult.stopped();
        }

        // Restitution 1: an optic returns what it reflects, and the fraction it does not return is
        // already accounted for above. A mirror is not a wall that a body bounces off inelastically.
        Vec3d away = ContactResolver.mirroredWorldVelocity(world, contact, 1.0D);
        if (away == null) {
            // Nobody can say which way "out" points — the ship stopped answering between the crossing
            // and here. Absorbing is the recoverable mistake; inventing a direction is not.
            return ContactResult.stopped();
        }
        return ContactResult.deflected(away, (int) Math.round(contact.getEnergy() * reflectance));
    }

    private static boolean isRadiant(ImpactKind kind) {
        return kind == ImpactKind.BEAM || kind == ImpactKind.THERMAL;
    }

    /**
     * Where a plate that has burnt out goes. Only itself: a mirror losing its film is not an explosion,
     * and the hull it was protecting is still standing.
     */
    private void burnOut(World world, BlockPos pos) {
        if (world != null && !world.isRemote && pos != null) {
            // Same road as every other block weapon fire takes: ask, and honour a refusal. A film
            // burning out is still a destruction, and a protected one stays.
            StructureDamageEngine.removeIfAllowed(world, pos);
        }
    }

}
