package com.github.stannismod.affs.world;

import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * The "responsible area" method (D134-3): partition the composite field surface into per-emitter
 * <em>zones</em> so that <b>each surface point belongs to its nearest emitter</b> — a Voronoi partition
 * over the emitter centres. A zone's regeneration rate is its emitter's throughput and its reserve is
 * that emitter's coil, so knowing which emitter owns a point is the substrate the zoned balancing
 * (per-zone drop/regen, and later the redistribution control surface) stands on.
 *
 * <p>Pure geometry: no world access, no side effects. Membership is decided by centre distance, which
 * is the standard Voronoi cell test; the composite surface itself is a smooth-union of the same centres
 * ({@link FieldSurfaceMath}), so the nearest-centre owner is a faithful reading of "whose part of the
 * shell is this."</p>
 */
public final class FieldZoneMath {

    private FieldZoneMath() {
    }

    /**
     * Index of the emitter whose centre is nearest {@code point}, or {@code -1} if the list is empty.
     * Ties resolve to the earliest emitter in the list (deterministic).
     */
    public static int nearestEmitterIndex(List<? extends FieldSource> emitters, Vec3d point) {
        if (emitters == null || emitters.isEmpty() || point == null) {
            return -1;
        }
        int best = -1;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (int i = 0; i < emitters.size(); i++) {
            FieldSource emitter = emitters.get(i);
            if (emitter == null) {
                continue;
            }
            double distSq = centerDistanceSq(emitter, point);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }

    /**
     * The emitter that owns the zone containing {@code point} (its nearest emitter), or {@code null} if
     * the list is empty. This is the "which emitter is responsible for this surface point" query.
     */
    public static FieldSource nearestEmitter(List<? extends FieldSource> emitters, Vec3d point) {
        int index = nearestEmitterIndex(emitters, point);
        return index < 0 ? null : emitters.get(index);
    }

    private static double centerDistanceSq(FieldSource emitter, Vec3d point) {
        double cx = emitter.getPos().getX() + 0.5D;
        double cy = emitter.getPos().getY() + 0.5D;
        double cz = emitter.getPos().getZ() + 0.5D;
        double dx = point.x - cx;
        double dy = point.y - cy;
        double dz = point.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }
}
