package com.github.stannismod.affs.world.contour;

import com.github.stannismod.affs.te.TileEntityContourInjector;
import net.minecraft.block.Block;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.*;

public final class ContourFrameGeometry {

    public enum Axis {
        X_FIXED,
        Y_FIXED,
        Z_FIXED
    }

    private final Axis axis;
    private final int fixedCoordinate;
    private final int minA;
    private final int maxA;
    private final int minB;
    private final int maxB;
    private final List<BlockPos> framePositions;
    private final List<BlockPos> interiorPositions;
    private final int injectorCount;

    private ContourFrameGeometry(Axis axis, int fixedCoordinate, int minA, int maxA, int minB, int maxB, List<BlockPos> framePositions, List<BlockPos> interiorPositions, int injectorCount) {
        this.axis = axis;
        this.fixedCoordinate = fixedCoordinate;
        this.minA = minA;
        this.maxA = maxA;
        this.minB = minB;
        this.maxB = maxB;
        this.framePositions = framePositions;
        this.interiorPositions = interiorPositions;
        this.injectorCount = injectorCount;
    }

    @Nullable
    public static ContourFrameGeometry find(World world, BlockPos injectorPos, Block frameBlock, int scanRadius) {
        if (world == null || injectorPos == null || frameBlock == null || scanRadius <= 0) {
            return null;
        }

        Set<BlockPos> candidates = new HashSet<>();
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    BlockPos cursor = injectorPos.add(dx, dy, dz);
                    if (!world.isBlockLoaded(cursor)) {
                        continue;
                    }
                    Block block = world.getBlockState(cursor).getBlock();
                    boolean injectorHere = cursor.equals(injectorPos) && world.getTileEntity(cursor) instanceof TileEntityContourInjector;
                    if (block == frameBlock || injectorHere) {
                        candidates.add(new BlockPos(cursor.getX(), cursor.getY(), cursor.getZ()));
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Set<BlockPos> visited = new HashSet<>();
        ContourFrameGeometry best = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (BlockPos seed : candidates) {
            if (!visited.add(seed)) {
                continue;
            }

            Set<BlockPos> component = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                component.add(current);
                for (EnumFacing facing : EnumFacing.VALUES) {
                    BlockPos next = current.offset(facing);
                    if (candidates.contains(next) && visited.add(next)) {
                        queue.add(next);
                    }
                }
            }

            ContourFrameGeometry geometry = analyzeComponent(world, injectorPos, frameBlock, component);
            if (geometry == null) {
                continue;
            }

            double distanceSq = distanceSqToComponent(injectorPos, geometry);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = geometry;
            }
        }

        return best;
    }

    private static double distanceSqToComponent(BlockPos injectorPos, ContourFrameGeometry geometry) {
        double cx = injectorPos.getX() + 0.5D;
        double cy = injectorPos.getY() + 0.5D;
        double cz = injectorPos.getZ() + 0.5D;
        double dx = clampDistance(cx, geometry.getMinX(), geometry.getMaxX());
        double dy = clampDistance(cy, geometry.getMinY(), geometry.getMaxY());
        double dz = clampDistance(cz, geometry.getMinZ(), geometry.getMaxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clampDistance(double value, int min, int max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
    }

    @Nullable
    private static ContourFrameGeometry analyzeComponent(World world, BlockPos injectorPos, Block frameBlock, Set<BlockPos> component) {
        if (component == null || component.size() < 4) {
            return null;
        }

        Axis axis = detectAxis(component);
        if (axis == null) {
            return null;
        }

        int fixed = axis == Axis.X_FIXED ? component.iterator().next().getX()
                : axis == Axis.Y_FIXED ? component.iterator().next().getY()
                : component.iterator().next().getZ();

        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minB = Integer.MAX_VALUE;
        int maxB = Integer.MIN_VALUE;
        for (BlockPos pos : component) {
            int a = axis == Axis.X_FIXED ? pos.getY() : pos.getX();
            int b = axis == Axis.Z_FIXED ? pos.getY() : pos.getZ();
            if (axis == Axis.X_FIXED) {
                b = pos.getZ();
            } else if (axis == Axis.Y_FIXED) {
                a = pos.getX();
                b = pos.getZ();
            } else {
                a = pos.getX();
                b = pos.getY();
            }
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }

        if (maxA - minA < 2 || maxB - minB < 2) {
            return null;
        }

        List<BlockPos> framePositions = new ArrayList<>();
        List<BlockPos> interiorPositions = new ArrayList<>();
        int injectorCount = 0;

        for (int a = minA; a <= maxA; a++) {
            for (int b = minB; b <= maxB; b++) {
                boolean perimeter = a == minA || a == maxA || b == minB || b == maxB;
                BlockPos pos = toWorldPos(axis, fixed, a, b);
                if (!world.isBlockLoaded(pos)) {
                    return null;
                }
                Block block = world.getBlockState(pos).getBlock();
                if (perimeter) {
                    boolean injectorHere = pos.equals(injectorPos) && world.getTileEntity(pos) instanceof TileEntityContourInjector;
                    if (block != frameBlock && !injectorHere) {
                        return null;
                    }
                    if (injectorHere) {
                        injectorCount++;
                    } else {
                        framePositions.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
                    }
                } else {
                    if (block != net.minecraft.init.Blocks.AIR) {
                        return null;
                    }
                    interiorPositions.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
                }
            }
        }

        if (injectorCount != 1) {
            return null;
        }

        if (framePositions.size() + injectorCount != component.size()) {
            return null;
        }

        if (interiorPositions.isEmpty()) {
            return null;
        }

        return new ContourFrameGeometry(axis, fixed, minA, maxA, minB, maxB, framePositions, interiorPositions, injectorCount);
    }

    @Nullable
    private static Axis detectAxis(Set<BlockPos> component) {
        boolean sameX = true;
        boolean sameY = true;
        boolean sameZ = true;
        BlockPos first = component.iterator().next();
        int x = first.getX();
        int y = first.getY();
        int z = first.getZ();
        for (BlockPos pos : component) {
            sameX &= pos.getX() == x;
            sameY &= pos.getY() == y;
            sameZ &= pos.getZ() == z;
        }
        if (sameX) {
            return Axis.X_FIXED;
        }
        if (sameY) {
            return Axis.Y_FIXED;
        }
        if (sameZ) {
            return Axis.Z_FIXED;
        }
        return null;
    }

    private static BlockPos toWorldPos(Axis axis, int fixed, int a, int b) {
        switch (axis) {
            case X_FIXED:
                return new BlockPos(fixed, a, b);
            case Y_FIXED:
                return new BlockPos(a, fixed, b);
            case Z_FIXED:
            default:
                return new BlockPos(a, b, fixed);
        }
    }

    public Axis getAxis() {
        return axis;
    }

    public int getFixedCoordinate() {
        return fixedCoordinate;
    }

    public int getMinA() {
        return minA;
    }

    public int getMaxA() {
        return maxA;
    }

    public int getMinB() {
        return minB;
    }

    public int getMaxB() {
        return maxB;
    }

    public int getFrameCount() {
        return framePositions.size();
    }

    public int getInteriorCount() {
        return interiorPositions.size();
    }

    public int getInjectorCount() {
        return injectorCount;
    }

    public List<BlockPos> getFramePositions() {
        return framePositions;
    }

    public List<BlockPos> getInteriorPositions() {
        return interiorPositions;
    }

    public AxisAlignedBB getFieldBox() {
        switch (axis) {
            case X_FIXED:
                return new AxisAlignedBB(fixedCoordinate, minA + 1.0D, minB + 1.0D, fixedCoordinate + 1.0D, maxA, maxB);
            case Y_FIXED:
                return new AxisAlignedBB(minA + 1.0D, fixedCoordinate, minB + 1.0D, maxA, fixedCoordinate + 1.0D, maxB);
            case Z_FIXED:
            default:
                return new AxisAlignedBB(minA + 1.0D, minB + 1.0D, fixedCoordinate, maxA, maxB, fixedCoordinate + 1.0D);
        }
    }

    public boolean intersects(AxisAlignedBB box) {
        return box != null && getFieldBox().intersects(box);
    }

    public boolean containsInterior(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        switch (axis) {
            case X_FIXED:
                return pos.getX() == fixedCoordinate && pos.getY() > minA && pos.getY() < maxA && pos.getZ() > minB && pos.getZ() < maxB;
            case Y_FIXED:
                return pos.getY() == fixedCoordinate && pos.getX() > minA && pos.getX() < maxA && pos.getZ() > minB && pos.getZ() < maxB;
            case Z_FIXED:
            default:
                return pos.getZ() == fixedCoordinate && pos.getX() > minA && pos.getX() < maxA && pos.getY() > minB && pos.getY() < maxB;
        }
    }

    public int getMinX() {
        switch (axis) {
            case X_FIXED:
                return fixedCoordinate;
            case Y_FIXED:
            case Z_FIXED:
            default:
                return minA;
        }
    }

    public int getMaxX() {
        switch (axis) {
            case X_FIXED:
                return fixedCoordinate;
            case Y_FIXED:
            case Z_FIXED:
            default:
                return maxA;
        }
    }

    public int getMinY() {
        switch (axis) {
            case X_FIXED:
                return minA;
            case Y_FIXED:
                return fixedCoordinate;
            case Z_FIXED:
            default:
                return minB;
        }
    }

    public int getMaxY() {
        switch (axis) {
            case X_FIXED:
                return maxA;
            case Y_FIXED:
                return fixedCoordinate;
            case Z_FIXED:
            default:
                return maxB;
        }
    }

    public int getMinZ() {
        switch (axis) {
            case X_FIXED:
                return minB;
            case Y_FIXED:
                return minA;
            case Z_FIXED:
            default:
                return fixedCoordinate;
        }
    }

    public int getMaxZ() {
        switch (axis) {
            case X_FIXED:
                return maxB;
            case Y_FIXED:
                return maxA;
            case Z_FIXED:
            default:
                return fixedCoordinate;
        }
    }

    public String getBoundsString() {
        return "[" + getMinX() + ", " + getMinY() + ", " + getMinZ() + "] -> [" + getMaxX() + ", " + getMaxY() + ", " + getMaxZ() + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContourFrameGeometry)) {
            return false;
        }
        ContourFrameGeometry that = (ContourFrameGeometry) o;
        return fixedCoordinate == that.fixedCoordinate
                && minA == that.minA
                && maxA == that.maxA
                && minB == that.minB
                && maxB == that.maxB
                && injectorCount == that.injectorCount
                && axis == that.axis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(axis, fixedCoordinate, minA, maxA, minB, maxB, injectorCount);
    }
}
