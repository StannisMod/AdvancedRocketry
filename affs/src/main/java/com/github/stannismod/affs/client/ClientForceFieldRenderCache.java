package com.github.stannismod.affs.client;

import com.github.stannismod.affs.world.FieldFrame;
import com.github.stannismod.affs.world.FieldFrames;
import com.github.stannismod.affs.world.FieldSource;
import com.github.stannismod.affs.world.FieldSurfaceMath;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientForceFieldRenderCache {

    private static final Map<Integer, RenderState> STATES_BY_DIMENSION = new ConcurrentHashMap<>();

    private ClientForceFieldRenderCache() {
    }

    public static void replaceSnapshot(int dimension, List<? extends FieldSource> sources) {
        STATES_BY_DIMENSION.put(dimension, new RenderState(sources));
    }

    public static void clearAll() {
        STATES_BY_DIMENSION.clear();
    }

    public static RenderMesh getMesh(World world) {
        if (world == null) {
            return RenderMesh.EMPTY;
        }

        RenderState state = STATES_BY_DIMENSION.get(world.provider.getDimension());
        if (state == null) {
            return RenderMesh.EMPTY;
        }
        return state.getMesh(world);
    }

    private static final class RenderState {
        private final List<FieldSource> sources;
        private volatile RenderMesh mesh;
        private volatile boolean dirty = true;

        private RenderState(List<? extends FieldSource> sources) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }

        private RenderMesh getMesh(World world) {
            // Resolve each source's frame here (the client world is only available at draw time), so a
            // source on a VS ship reports its hull-transformed world centre and the mesh is built there.
            List<FieldSource> resolved = new ArrayList<>(sources.size());
            boolean anyShip = false;
            for (FieldSource s : sources) {
                FieldFrame frame = FieldFrames.forBlock(world, s.getPos());
                if (!(frame instanceof com.github.stannismod.affs.world.WorldFieldFrame)) {
                    anyShip = true;
                }
                resolved.add(new FramedSource(s, frame));
            }

            // A standalone field is static, so its mesh is cached until the snapshot changes. A ship's
            // field moves every frame, so it must be rebuilt each draw (never cached).
            if (!anyShip) {
                RenderMesh cached = mesh;
                if (!dirty && cached != null) {
                    return cached;
                }
                RenderMesh rebuilt = buildMesh(resolved);
                mesh = rebuilt;
                dirty = false;
                return rebuilt;
            }
            return buildMesh(resolved);
        }
    }

    /** A client-side source that reports its world centre through a resolved frame (identity standalone,
     *  ship-transformed on a VS hull), so the render mesh follows the flying hull. */
    private static final class FramedSource implements FieldSource {
        private final FieldSource delegate;
        private final FieldFrame frame;

        private FramedSource(FieldSource delegate, FieldFrame frame) {
            this.delegate = delegate;
            this.frame = frame;
        }

        @Override
        public net.minecraft.util.math.BlockPos getPos() {
            return delegate.getPos();
        }

        @Override
        public int getRadius() {
            return delegate.getRadius();
        }

        @Override
        public Vec3d getWorldCenter() {
            Vec3d c = frame.fieldToWorld(getPos().getX() + 0.5D, getPos().getY() + 0.5D, getPos().getZ() + 0.5D);
            return c != null ? c : new Vec3d(getPos().getX() + 0.5D, getPos().getY() + 0.5D, getPos().getZ() + 0.5D);
        }
    }

    private static RenderMesh buildMesh(List<FieldSource> sources) {
        if (sources.isEmpty()) {
            return RenderMesh.EMPTY;
        }

        SetBuilder builder = new SetBuilder();
        for (FieldSource source : sources) {
            // Lay the marching-cubes grid around the field's WORLD centre (identity standalone,
            // hull-transformed on a ship), on the half-unit lattice the sampler expects, so the grid and
            // the SDF — which also reads getWorldCenter — stay consistent in both frames.
            Vec3d wc = source.getWorldCenter();
            int cx = (int) Math.floor(wc.x * 2.0D);
            int cy = (int) Math.floor(wc.y * 2.0D);
            int cz = (int) Math.floor(wc.z * 2.0D);
            int radius = source.getRadius() + 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        builder.add(new GridCell(cx + dx * 2, cy + dy * 2, cz + dz * 2));
                    }
                }
            }
        }

        List<Triangle> triangles = new ArrayList<>();
        for (GridCell cell : builder.values()) {
            addCellTriangles(triangles, sources, cell);
        }
        return new RenderMesh(Collections.unmodifiableList(triangles));
    }

    private static void addCellTriangles(List<Triangle> triangles, List<FieldSource> sources, GridCell cell) {
        double x0 = cell.x * 0.5D;
        double y0 = cell.y * 0.5D;
        double z0 = cell.z * 0.5D;
        double x1 = x0 + 0.5D;
        double y1 = y0 + 0.5D;
        double z1 = z0 + 0.5D;

        Vec3d[] positions = new Vec3d[] {
            new Vec3d(x0, y0, z0),
            new Vec3d(x1, y0, z0),
            new Vec3d(x1, y1, z0),
            new Vec3d(x0, y1, z0),
            new Vec3d(x0, y0, z1),
            new Vec3d(x1, y0, z1),
            new Vec3d(x1, y1, z1),
            new Vec3d(x0, y1, z1)
        };

        double[] values = new double[8];
        boolean hasInside = false;
        boolean hasOutside = false;
        for (int i = 0; i < 8; i++) {
            values[i] = FieldSurfaceMath.compositeHullDistance(sources, positions[i]);
            if (values[i] <= 0.0D) {
                hasInside = true;
            } else {
                hasOutside = true;
            }
        }

        if (!hasInside || !hasOutside) {
            return;
        }

        int[][] tets = {
            {0, 5, 1, 6},
            {0, 1, 2, 6},
            {0, 2, 3, 6},
            {0, 3, 7, 6},
            {0, 7, 4, 6},
            {0, 4, 5, 6}
        };

        for (int[] tet : tets) {
            emitTetra(triangles, positions, values, tet);
        }
    }

    private static void emitTetra(List<Triangle> triangles, Vec3d[] positions, double[] values, int[] tet) {
        Vec3d[] verts = new Vec3d[4];
        double[] vals = new double[4];
        for (int i = 0; i < 4; i++) {
            verts[i] = positions[tet[i]];
            vals[i] = values[tet[i]];
        }

        int[] inside = new int[4];
        int[] outside = new int[4];
        int insideCount = 0;
        int outsideCount = 0;

        for (int i = 0; i < 4; i++) {
            if (vals[i] <= 0.0D) {
                inside[insideCount++] = i;
            } else {
                outside[outsideCount++] = i;
            }
        }

        if (insideCount == 0 || insideCount == 4) {
            return;
        }

        if (insideCount == 1 || insideCount == 3) {
            int pivot = insideCount == 1 ? inside[0] : outside[0];
            int a = insideCount == 1 ? outside[0] : inside[0];
            int b = insideCount == 1 ? outside[1] : inside[1];
            int c = insideCount == 1 ? outside[2] : inside[2];

            Vec3d p0 = interpolate(verts[pivot], vals[pivot], verts[a], vals[a]);
            Vec3d p1 = interpolate(verts[pivot], vals[pivot], verts[b], vals[b]);
            Vec3d p2 = interpolate(verts[pivot], vals[pivot], verts[c], vals[c]);

            if (insideCount == 1) {
                triangles.add(new Triangle(p0, p1, p2));
            } else {
                triangles.add(new Triangle(p0, p2, p1));
            }
            return;
        }

        if (insideCount == 2) {
            int in0 = inside[0];
            int in1 = inside[1];
            int out0 = outside[0];
            int out1 = outside[1];

            Vec3d p0 = interpolate(verts[in0], vals[in0], verts[out0], vals[out0]);
            Vec3d p1 = interpolate(verts[in0], vals[in0], verts[out1], vals[out1]);
            Vec3d p2 = interpolate(verts[in1], vals[in1], verts[out0], vals[out0]);
            Vec3d p3 = interpolate(verts[in1], vals[in1], verts[out1], vals[out1]);

            triangles.add(new Triangle(p0, p1, p2));
            triangles.add(new Triangle(p2, p1, p3));
        }
    }

    private static Vec3d interpolate(Vec3d a, double va, Vec3d b, double vb) {
        double t = va / (va - vb);
        return new Vec3d(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        );
    }

    public static final class RenderMesh {
        public static final RenderMesh EMPTY = new RenderMesh(Collections.emptyList());
        private final List<Triangle> triangles;

        private RenderMesh(List<Triangle> triangles) {
            this.triangles = triangles;
        }

        public List<Triangle> getTriangles() {
            return triangles;
        }
    }

    public static final class Triangle {
        public final Vec3d a;
        public final Vec3d b;
        public final Vec3d c;

        private Triangle(Vec3d a, Vec3d b, Vec3d c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    private static final class GridCell {
        private final int x;
        private final int y;
        private final int z;

        private GridCell(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof GridCell)) {
                return false;
            }
            GridCell other = (GridCell) obj;
            return x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(x);
            result = 31 * result + Integer.hashCode(y);
            result = 31 * result + Integer.hashCode(z);
            return result;
        }
    }

    private static final class SetBuilder {
        private final java.util.HashSet<GridCell> cells = new java.util.HashSet<>();

        private void add(GridCell cell) {
            cells.add(cell);
        }

        private java.util.Set<GridCell> values() {
            return cells;
        }
    }
}
