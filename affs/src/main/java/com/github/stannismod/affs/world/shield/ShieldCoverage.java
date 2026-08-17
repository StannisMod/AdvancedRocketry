package com.github.stannismod.affs.world.shield;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.world.FieldFrame;
import com.github.stannismod.affs.world.FieldFrames;
import com.github.stannismod.affs.world.FieldSurfaceMath;

import java.util.List;

/**
 * "Is this block under the field right now?" — the one question a subsystem that is not the shield
 * needs to ask of it.
 * <p>
 * It exists so that a caller elsewhere in the mod does not have to know either half of the answer: that
 * the field is an SDF smooth-union of emitter spheres, and that an emitter's centre is a WORLD point
 * while a block on a ship is addressed in that ship's subspace. Both live here, and a caller that
 * copied them would be one refactor away from asking the shell about a position it never occupied.
 * <p>
 * <b>Asking costs the shield nothing.</b> This is a geometry read, not a {@link ShieldStrike}: nothing
 * is absorbed, no shield energy is spent, and the field is not weakened by being stood under. A caller
 * that needs the shield to PAY for what it stopped declares a strike instead.
 */
public final class ShieldCoverage {

    private ShieldCoverage() {
    }

    /**
     * Whether {@code pos} sits inside the composite field of every generator that is up in its world.
     * <p>
     * Resolves the active generators itself, which is the convenient form for a one-off question. A
     * caller asking about many positions in the same tick should resolve the list once with
     * {@link #activeGenerators(World)} and use the overload.
     */
    public static boolean isCovered(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return isCovered(activeGenerators(world), world, pos);
    }

    /** The generators that are up in {@code world} — resolve once, ask about many positions. */
    public static List<TileEntityFieldGenerator> activeGenerators(World world) {
        return FieldSurfaceMath.getActiveGenerators(world);
    }

    /**
     * Whether {@code pos} sits inside the composite field of {@code generators}.
     * <p>
     * {@code pos} is in whatever frame its own block lives in — subspace on a ship, world otherwise —
     * and is converted before it meets the shell, because an emitter's sphere is centred in world
     * coordinates whichever it is mounted on.
     */
    public static boolean isCovered(List<TileEntityFieldGenerator> generators, World world, BlockPos pos) {
        if (generators == null || generators.isEmpty() || world == null || pos == null) {
            return false;
        }
        FieldFrame frame = FieldFrames.forBlock(world, pos);
        if (!frame.isReady()) {
            // A ship whose transform is not resolvable on this side cannot be placed against the shell.
            // Answering "not covered" is the fail-open half of the same choice the emitter list makes.
            return false;
        }
        Vec3d point = frame.fieldToWorld(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        // The HULL distance, not the SHELL distance. The shell one is a membrane test — it answers
        // "within half a block of the surface" — so a block safely in the middle of the bubble reads
        // as outside it. What a caller asking about coverage means is the enclosed volume.
        return FieldSurfaceMath.compositeHullDistance(generators, point) <= 0.0D;
    }
}
