package com.github.stannismod.affs.world;

import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public final class FieldSurfaceMath {

    public static final double FIELD_THICKNESS = 1.0D;
    public static final double FIELD_HALF_THICKNESS = FIELD_THICKNESS * 0.5D;
    private static final double SMOOTH_UNION_K = 0.75D;

    private FieldSurfaceMath() {
    }

    public static double shellDistance(FieldSource generator, Vec3d point) {
        double cx = generator.getPos().getX() + 0.5D;
        double cy = generator.getPos().getY() + 0.5D;
        double cz = generator.getPos().getZ() + 0.5D;
        double dx = point.x - cx;
        double dy = point.y - cy;
        double dz = point.z - cz;
        double distanceToCenter = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.abs(distanceToCenter - generator.getRadius()) - FIELD_HALF_THICKNESS;
    }

    public static double compositeShellDistance(World world, Vec3d point) {
        List<TileEntityFieldGenerator> generators = getActiveGenerators(world);
        return compositeShellDistance(generators, point);
    }

    public static double compositeShellDistance(List<? extends FieldSource> generators, Vec3d point) {
        if (generators.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double distance = shellDistance(generators.get(0), point);
        for (int i = 1; i < generators.size(); i++) {
            distance = smoothMin(distance, shellDistance(generators.get(i), point), SMOOTH_UNION_K);
        }
        return distance;
    }

    public static double compositeHullDistance(List<? extends FieldSource> generators, Vec3d point) {
        if (generators.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double distance = solidDistance(generators.get(0), point);
        for (int i = 1; i < generators.size(); i++) {
            distance = smoothMin(distance, solidDistance(generators.get(i), point), SMOOTH_UNION_K);
        }
        return distance;
    }

    public static boolean intersectsCompositeShell(World world, AxisAlignedBB box) {
        return intersectsCompositeShell(getActiveGenerators(world), box);
    }

    public static boolean intersectsCompositeShell(List<? extends FieldSource> generators, AxisAlignedBB box) {
        for (FieldSource generator : generators) {
            if (intersectsShell(generator, box)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isInsideCompositeShell(World world, BlockPos pos) {
        return isInsideCompositeShell(getActiveGenerators(world), pos);
    }

    public static boolean isInsideCompositeShell(List<? extends FieldSource> generators, BlockPos pos) {
        Vec3d point = new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return compositeShellDistance(generators, point) <= 0.0D;
    }

    public static List<TileEntityFieldGenerator> getActiveGenerators(World world) {
        List<TileEntityFieldGenerator> generators = new ArrayList<>();
        if (world == null) {
            return generators;
        }
        int dimension = world.provider.getDimension();
        for (TileEntityFieldGenerator generator : TileEntityFieldGenerator.getActiveGenerators()) {
            if (generator == null || generator.isInvalid()) {
                continue;
            }
            World generatorWorld = generator.getWorld();
            if (generatorWorld == null || generatorWorld.provider.getDimension() != dimension) {
                continue;
            }
            if (!generator.isFieldPowered()) {
                continue;
            }
            generators.add(generator);
        }
        return generators;
    }

    public static AxisAlignedBB influenceBox(TileEntityFieldGenerator generator) {
        return new AxisAlignedBB(generator.getPos()).grow(generator.getRadius() + FIELD_THICKNESS);
    }

    private static double smoothMin(double a, double b, double k) {
        double h = clamp01(0.5D + 0.5D * (b - a) / k);
        return mix(b, a, h) - k * h * (1.0D - h);
    }

    private static double solidDistance(FieldSource source, Vec3d point) {
        double cx = source.getPos().getX() + 0.5D;
        double cy = source.getPos().getY() + 0.5D;
        double cz = source.getPos().getZ() + 0.5D;
        double dx = point.x - cx;
        double dy = point.y - cy;
        double dz = point.z - cz;
        double distanceToCenter = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distanceToCenter - source.getRadius();
    }

    private static boolean intersectsShell(FieldSource source, AxisAlignedBB box) {
        double cx = source.getPos().getX() + 0.5D;
        double cy = source.getPos().getY() + 0.5D;
        double cz = source.getPos().getZ() + 0.5D;

        double minDistanceSq = distanceSqPointToAabb(cx, cy, cz, box);
        double maxDistanceSq = maxDistanceSqPointToAabb(cx, cy, cz, box);
        double innerRadius = Math.max(0.0D, source.getRadius() - FIELD_HALF_THICKNESS);
        double outerRadius = source.getRadius() + FIELD_HALF_THICKNESS;

        double innerRadiusSq = innerRadius * innerRadius;
        double outerRadiusSq = outerRadius * outerRadius;
        return minDistanceSq <= outerRadiusSq && maxDistanceSq >= innerRadiusSq;
    }

    private static double distanceSqPointToAabb(double x, double y, double z, AxisAlignedBB box) {
        double dx = clampAxisDistance(x, box.minX, box.maxX);
        double dy = clampAxisDistance(y, box.minY, box.maxY);
        double dz = clampAxisDistance(z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double maxDistanceSqPointToAabb(double x, double y, double z, AxisAlignedBB box) {
        double dx = farthestAxisDistance(x, box.minX, box.maxX);
        double dy = farthestAxisDistance(y, box.minY, box.maxY);
        double dz = farthestAxisDistance(z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clampAxisDistance(double point, double min, double max) {
        if (point < min) {
            return min - point;
        }
        if (point > max) {
            return point - max;
        }
        return 0.0D;
    }

    private static double farthestAxisDistance(double point, double min, double max) {
        return Math.max(Math.abs(point - min), Math.abs(point - max));
    }

    private static double mix(double a, double b, double t) {
        return a * (1.0D - t) + b * t;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public static Vec3d reflect(Vec3d velocity, Vec3d normal) {
        if (velocity == null || normal == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        Vec3d unitNormal = normalizeOrZero(normal);
        if (vectorLength(unitNormal) <= 1.0E-8D) {
            return velocity;
        }
        double normalVelocity = dotProduct(velocity, unitNormal);
        return subtract(velocity, scale(unitNormal, 2.0D * normalVelocity));
    }

    public static Vec3d normalizeOrZero(Vec3d vector) {
        if (vector == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        double length = vectorLength(vector);
        if (length <= 1.0E-8D) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        return scale(vector, 1.0D / length);
    }

    public static Vec3d sphereOutwardNormal(Vec3d center, Vec3d point, Vec3d fallback) {
        Vec3d normal = normalizeOrZero(point.subtract(center));
        if (vectorLength(normal) > 1.0E-8D) {
            return normal;
        }
        normal = normalizeOrZero(fallback);
        if (vectorLength(normal) > 1.0E-8D) {
            return normal;
        }
        return new Vec3d(1.0D, 0.0D, 0.0D);
    }

    public static Vec3d boxOutwardNormal(AxisAlignedBB box, Vec3d point, Vec3d fallback) {
        if (box == null || point == null) {
            return normalizeOrZero(fallback);
        }

        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = (box.minY + box.maxY) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double dx = point.x - centerX;
        double dy = point.y - centerY;
        double dz = point.z - centerZ;
        double sx = Math.abs(box.maxX - box.minX);
        double sy = Math.abs(box.maxY - box.minY);
        double sz = Math.abs(box.maxZ - box.minZ);

        if (sx <= sy && sx <= sz) {
            return axisNormal(dx, fallback == null ? 0.0D : fallback.x, 0);
        }
        if (sy <= sz) {
            return axisNormal(dy, fallback == null ? 0.0D : fallback.y, 1);
        }
        return axisNormal(dz, fallback == null ? 0.0D : fallback.z, 2);
    }

    private static Vec3d axisNormal(double delta, double fallbackComponent, int axis) {
        double sign = Math.abs(delta) > 1.0E-8D ? Math.signum(delta) : Math.abs(fallbackComponent) > 1.0E-8D ? Math.signum(fallbackComponent) : 1.0D;
        if (axis == 0) {
            return new Vec3d(sign, 0.0D, 0.0D);
        }
        if (axis == 1) {
            return new Vec3d(0.0D, sign, 0.0D);
        }
        return new Vec3d(0.0D, 0.0D, sign);
    }

    public static double inwardNormalSpeedSq(Vec3d velocity, Vec3d outwardNormal) {
        if (velocity == null || outwardNormal == null) {
            return 0.0D;
        }
        Vec3d unitNormal = normalizeOrZero(outwardNormal);
        if (vectorLength(unitNormal) <= 1.0E-8D) {
            return 0.0D;
        }
        double inwardSpeed = -dotProduct(velocity, unitNormal);
        if (inwardSpeed <= 0.0D) {
            return 0.0D;
        }
        return inwardSpeed * inwardSpeed;
    }

    public static double vectorLength(Vec3d vector) {
        if (vector == null) {
            return 0.0D;
        }
        return Math.sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z);
    }

    public static Vec3d scale(Vec3d vector, double factor) {
        if (vector == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        return new Vec3d(vector.x * factor, vector.y * factor, vector.z * factor);
    }

    public static Vec3d subtract(Vec3d left, Vec3d right) {
        if (left == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        if (right == null) {
            return new Vec3d(left.x, left.y, left.z);
        }
        return new Vec3d(left.x - right.x, left.y - right.y, left.z - right.z);
    }

    public static double dotProduct(Vec3d left, Vec3d right) {
        if (left == null || right == null) {
            return 0.0D;
        }
        return left.x * right.x + left.y * right.y + left.z * right.z;
    }

}
