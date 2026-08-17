package zmaster587.advancedRocketry.api.damage;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * One travelling body meeting one block: everything the block needs to decide what it does about it.
 *
 * <h3>Why a block is asked at all</h3>
 * <p>Toughness alone can only make a block expensive. It cannot send a body somewhere else, cannot
 * spend a charge of its own to stop one, and cannot answer a beam differently from a slug — so mirror
 * armour, reactive armour and a ricochet have nowhere to live. A block that is ASKED, and answers with
 * a {@link ContactResult}, has all three.</p>
 *
 * <h3>What is in here and what is deliberately not</h3>
 * <p>The body's own facts (how fast, how wide, how much it still carries, what kind of thing it is)
 * and the geometry of the meeting (where, through which face, at what angle). <b>Not</b> budgets,
 * stages or toughness: those belong to the layer that spends, and a block answering a contact is not
 * spending anything — it is saying what happens.</p>
 *
 * <h3>Frames — the one thing to get right</h3>
 * <p>{@link #getPos()}, {@link #getEntryFace()} and {@link #getVelocity()} are all in the BLOCK's own
 * frame: subspace on a ship, world off one. They travel together on purpose — an incidence angle is
 * an angle between a body and a face, and on a hull that has rotated, a world-frame velocity against
 * a subspace face is not an angle at all, it is two unrelated numbers. {@link #getPoint()} stays
 * WORLD, because that is where the flash goes and what a player saw. {@link #getShipId()} says which
 * case this is rather than leaving it to be inferred from coordinates.</p>
 */
public final class Contact {

    private final BlockPos pos;
    private final Vec3d point;
    private final EnumFacing entryFace;
    private final Vec3d velocity;
    private final ImpactKind kind;
    private final int energy;
    private final double radius;
    private final double share;
    private final String shipId;

    public Contact(BlockPos pos, Vec3d point, EnumFacing entryFace, Vec3d velocity, ImpactKind kind,
                   int energy, double radius, double share, String shipId) {
        this.pos = pos;
        this.point = point;
        this.entryFace = entryFace;
        this.velocity = velocity;
        this.kind = kind;
        this.energy = Math.max(0, energy);
        this.radius = Math.max(0.0D, radius);
        this.share = share <= 0.0D ? 0.0D : (share > 1.0D ? 1.0D : share);
        this.shipId = shipId;
    }

    /** The block met, in ITS OWN frame: a subspace address on a ship, a world one off a ship. */
    public BlockPos getPos() {
        return pos;
    }

    /** Where the body crossed into it, in WORLD coordinates. */
    public Vec3d getPoint() {
        return point;
    }

    /**
     * The face it came in through, as an OUTWARD normal — it points back the way the body came.
     * {@code null} only when the body began its step already inside this block, which is the one case
     * with no face to have crossed.
     */
    public EnumFacing getEntryFace() {
        return entryFace;
    }

    /** The outward surface normal at the contact, or {@code null} when there is no entry face. */
    public Vec3d getNormal() {
        if (entryFace == null) {
            return null;
        }
        return new Vec3d(entryFace.getFrontOffsetX(), entryFace.getFrontOffsetY(),
                entryFace.getFrontOffsetZ());
    }

    /** The body's velocity when it arrived, in the BLOCK's frame (see the class note on frames). */
    public Vec3d getVelocity() {
        return velocity;
    }

    public ImpactKind getKind() {
        return kind;
    }

    /**
     * How much impact energy is on the table AT THIS BLOCK — already the block's share of a body wide
     * enough to meet several at once, never the whole body's remaining energy.
     */
    public int getEnergy() {
        return energy;
    }

    /** The body's cross-section radius, in blocks. Zero for a body treated as a point. */
    public double getRadius() {
        return radius;
    }

    /** What fraction of the body's cross-section this block covers, in {@code (0, 1]}. */
    public double getShare() {
        return share;
    }

    /** The ship whose blocks were met, or {@code null} for the world's own. */
    public String getShipId() {
        return shipId;
    }

    /**
     * The angle between the incoming body and the surface normal, in degrees: {@code 0} for a body
     * arriving square-on and approaching {@code 90} for one merely grazing the face.
     *
     * <p>This is the quantity a ricochet is decided on, so it is computed once here rather than by
     * every block that cares — two implementations of an angle would eventually disagree about which
     * end of the range means "glancing". Answers {@code 0} when there is no face or no motion, which
     * is the reading that never bounces.</p>
     */
    public double getIncidenceDegrees() {
        Vec3d normal = getNormal();
        if (normal == null || velocity == null) {
            return 0.0D;
        }
        double speed = velocity.lengthVector();
        if (speed <= 1.0E-9D) {
            return 0.0D;
        }
        // The body travels INTO the block, so its direction opposes the outward normal; negating one
        // of them puts the two vectors on the same side and makes the dot product the cosine of the
        // angle a reader would name.
        double cos = -(velocity.x * normal.x + velocity.y * normal.y + velocity.z * normal.z) / speed;
        if (cos > 1.0D) {
            cos = 1.0D;
        } else if (cos < -1.0D) {
            cos = -1.0D;
        }
        return Math.toDegrees(Math.acos(cos));
    }
}
