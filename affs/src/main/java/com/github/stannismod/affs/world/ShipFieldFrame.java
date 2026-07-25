package com.github.stannismod.affs.world;

import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * The Valkyrien Skies ship frame (§4.3). The emitters' subspace centres are mapped to their
 * live world positions through the ship's transform, so the shell rides the flying hull, and the
 * shell's own motion is exposed so impacts can be taken relative to it (a cruising ship must not bill
 * its own crew). Delegates entirely to {@link VSIntegration}'s anchored (by-ship-id) seam — one ship id
 * for the whole network, resolved once at rebuild.
 *
 * <p>All conversions return {@code null} when VS is absent or the ship is not loaded on this side;
 * {@link #isReady()} reflects that so the emitter degrades to off rather than shielding the wrong place.</p>
 */
public final class ShipFieldFrame implements FieldFrame {

    private static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    private final World world;
    private final String shipId;

    public ShipFieldFrame(World world, String shipId) {
        this.world = world;
        this.shipId = shipId;
    }

    public String getShipId() {
        return shipId;
    }

    @Override
    public Vec3d fieldToWorld(double x, double y, double z) {
        double[] w = VSIntegration.toWorldFrameFor(world, shipId, x, y, z);
        return w == null ? null : new Vec3d(w[0], w[1], w[2]);
    }

    @Override
    public Vec3d surfaceVelocityAt(double worldX, double worldY, double worldZ) {
        double[] v = VSIntegration.shipVelocityAtPointFor(world, shipId, worldX, worldY, worldZ);
        return v == null ? ZERO : new Vec3d(v[0], v[1], v[2]);
    }

    @Override
    public boolean isReady() {
        // The ship resolves iff a point maps to world — same gate every conversion uses.
        return VSIntegration.toWorldFrameFor(world, shipId, 0.0D, 0.0D, 0.0D) != null;
    }
}
