package com.github.stannismod.affs.gui;

import net.minecraft.nbt.NBTTagCompound;

import java.util.Objects;

public final class NetworkMapMarker {

    public static final byte KIND_OTHER = 0;
    public static final byte KIND_CABLE = 1;
    public static final byte KIND_GENERATOR = 2;
    public static final byte KIND_SOURCE = 3;
    public static final byte KIND_SINK = 4;
    public static final byte KIND_CONSOLE = 5;
    public static final byte KIND_INJECTOR = 6;
    public static final byte KIND_FRAME = 7;
    public static final byte KIND_FIELD = 8;
    public static final byte KIND_CONTOUR = 9;

    private final int x;
    private final int y;
    private final int z;
    private final byte kind;
    private final boolean area;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public NetworkMapMarker(int x, int y, int z, byte kind) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.kind = kind;
        this.area = false;
        this.minX = x;
        this.minY = y;
        this.minZ = z;
        this.maxX = x;
        this.maxY = y;
        this.maxZ = z;
    }

    private NetworkMapMarker(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, byte kind, int anchorX, int anchorY, int anchorZ) {
        this.x = anchorX;
        this.y = anchorY;
        this.z = anchorZ;
        this.kind = kind;
        this.area = true;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static NetworkMapMarker createArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, byte kind, int anchorX, int anchorY, int anchorZ) {
        return new NetworkMapMarker(minX, minY, minZ, maxX, maxY, maxZ, kind, anchorX, anchorY, anchorZ);
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public byte getKind() {
        return kind;
    }

    public boolean isArea() {
        return area;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public int getPriority() {
        switch (kind) {
            case KIND_CONTOUR:
                return 0;
            case KIND_FIELD:
                return 1;
            case KIND_OTHER:
                return 2;
            case KIND_CABLE:
                return 3;
            case KIND_SOURCE:
            case KIND_SINK:
                return 4;
            case KIND_GENERATOR:
            case KIND_CONSOLE:
                return 5;
            case KIND_INJECTOR:
                return 6;
            case KIND_FRAME:
                return 7;
            default:
                return 0;
        }
    }

    public boolean matchesPosition(NetworkMapMarker other) {
        if (other == null) {
            return false;
        }
        if (area != other.area) {
            return false;
        }
        if (area) {
            return minX == other.minX
                    && minY == other.minY
                    && minZ == other.minZ
                    && maxX == other.maxX
                    && maxY == other.maxY
                    && maxZ == other.maxZ
                    && kind == other.kind;
        }
        return x == other.x && y == other.y && z == other.z;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setByte("k", kind);
        tag.setBoolean("a", area);
        tag.setInteger("minX", minX);
        tag.setInteger("minY", minY);
        tag.setInteger("minZ", minZ);
        tag.setInteger("maxX", maxX);
        tag.setInteger("maxY", maxY);
        tag.setInteger("maxZ", maxZ);
        return tag;
    }

    public static NetworkMapMarker readFromNBT(NBTTagCompound tag) {
        if (tag == null) {
            return new NetworkMapMarker(0, 0, 0, KIND_OTHER);
        }
        if (tag.getBoolean("a")) {
            return new NetworkMapMarker(
                    tag.getInteger("minX"),
                    tag.getInteger("minY"),
                    tag.getInteger("minZ"),
                    tag.getInteger("maxX"),
                    tag.getInteger("maxY"),
                    tag.getInteger("maxZ"),
                    tag.getByte("k"),
                    tag.getInteger("x"),
                    tag.getInteger("y"),
                    tag.getInteger("z")
            );
        }
        return new NetworkMapMarker(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"), tag.getByte("k"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NetworkMapMarker)) {
            return false;
        }
        NetworkMapMarker that = (NetworkMapMarker) o;
        return x == that.x
                && y == that.y
                && z == that.z
                && kind == that.kind
                && area == that.area
                && minX == that.minX
                && minY == that.minY
                && minZ == that.minZ
                && maxX == that.maxX
                && maxY == that.maxY
                && maxZ == that.maxZ;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, kind, area, minX, minY, minZ, maxX, maxY, maxZ);
    }
}
