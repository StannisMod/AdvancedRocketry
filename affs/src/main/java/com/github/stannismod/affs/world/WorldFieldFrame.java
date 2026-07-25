package com.github.stannismod.affs.world;

import net.minecraft.util.math.Vec3d;

/**
 * The identity frame — a standalone shield on a planet, asteroid or station. Field coordinates ARE
 * world coordinates and the shell is static, so every conversion is a no-op and the surface velocity is
 * zero. This is AFFS's original world-frame behaviour, unchanged; a stateless singleton.
 */
public final class WorldFieldFrame implements FieldFrame {

    public static final WorldFieldFrame INSTANCE = new WorldFieldFrame();

    private static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    private WorldFieldFrame() {
    }

    @Override
    public Vec3d fieldToWorld(double x, double y, double z) {
        return new Vec3d(x, y, z);
    }

    @Override
    public Vec3d surfaceVelocityAt(double worldX, double worldY, double worldZ) {
        return ZERO;
    }

    @Override
    public boolean isReady() {
        return true;
    }
}
