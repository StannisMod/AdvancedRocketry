package zmaster587.advancedRocketry.projectile;

import com.github.stannismod.affs.world.shield.ShieldStrikeService;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Which layer a straight segment meets FIRST — the field or a structure — and how far along.
 *
 * <h3>Why this is a class and not four lines inside a step</h3>
 * <p>It was four lines inside {@link ShotSubstrate}, and it stayed correct only while exactly one
 * thing in the game asked the question. A held beam asks it too, and a second copy of "which layer
 * wins" is a second answer: a weapon that resolved the ordering one way while the substrate resolved
 * it the other would fire through its own shield, or into a shell it had decided was not there, for
 * reasons no reproduction would find.</p>
 *
 * <h3>Ordering is geometric, never a pipeline</h3>
 * <p>"Shield first, then hull" is a rule that is wrong whenever the geometry says otherwise: a body
 * emitted from INSIDE a shell meets the hull with no shield in between, and one crossing a friendly
 * bubble on its way elsewhere should not be billed to it. So both layers are asked where they would be
 * crossed, in blocks along this segment, and the smaller distance wins. The field answers {@code -1}
 * for a ray that starts inside a shell, which is that same statement in its own vocabulary.</p>
 */
public final class LayerCrossing {

    /** What the segment met first. Exactly one of {@link #isField} / {@link #isStructure} is true. */
    public static final class First {
        /** How far along the segment, in blocks; {@code -1} when nothing was met. */
        public final double distance;
        /** The structure crossing, or null when the field won or nothing was met. */
        public final StructureCrossing.Hit structure;
        private final boolean field;

        private First(double distance, StructureCrossing.Hit structure, boolean field) {
            this.distance = distance;
            this.structure = structure;
            this.field = field;
        }

        public boolean isField() {
            return field;
        }

        public boolean isStructure() {
            return structure != null && !field;
        }

        /** Nothing stands between the two ends of this segment. */
        public boolean isNothing() {
            return !field && structure == null;
        }
    }

    private static final First NOTHING = new First(-1.0D, null, false);

    private LayerCrossing() {
    }

    /**
     * Ask both layers about the segment {@code from -> to}.
     *
     * @param radius     the body's own width; below half a block the sweep IS the ray
     * @param onlyHullId when non-null, the structure question is narrowed to that one hull — a body
     *                   already inside a hull's material is inside that hull and nothing else, so
     *                   asking the world frame and every other ship is work whose answer is known
     */
    public static First along(World world, Vec3d from, Vec3d to, double radius, String onlyHullId) {
        if (world == null || from == null || to == null) {
            return NOTHING;
        }
        Vec3d span = to.subtract(from);
        double reach = span.lengthVector();
        if (reach <= 0.0D) {
            return NOTHING;
        }
        Vec3d direction = span.scale(1.0D / reach);

        double fieldDistance = ShieldStrikeService.nearestShellCrossing(world, from, direction, reach);
        StructureCrossing.Hit structure = StructureCrossing.firstAlong(world, from, to, onlyHullId,
                radius);
        double structureDistance = structure == null ? -1.0D : structure.distance;

        boolean fieldFirst = fieldDistance >= 0.0D
                && (structureDistance < 0.0D || fieldDistance <= structureDistance);
        if (fieldFirst) {
            return new First(fieldDistance, structure, true);
        }
        if (structureDistance >= 0.0D) {
            return new First(structureDistance, structure, false);
        }
        return NOTHING;
    }
}
