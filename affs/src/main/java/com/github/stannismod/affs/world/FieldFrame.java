package com.github.stannismod.affs.world;

import net.minecraft.util.math.Vec3d;

/**
 * The frame seam (§4.3): a shield network is <em>entirely</em> standalone (world-frame) or
 * <em>entirely</em> on one Valkyrien Skies ship (subspace-frame), never mixed. This interface hides that
 * choice from the field geometry so the same SDF / collision / deflection code runs in both cases.
 *
 * <p><b>Why the seam is small.</b> The field is a smooth-union of <em>spheres</em> centred at emitter
 * positions, and a sphere is rotation-invariant: a sphere at field-frame centre {@code C} with radius
 * {@code r} is, in world space, a sphere at {@code fieldToWorld(C)} with the <em>same</em> {@code r}.
 * So the field never has to be un-rotated — everything stays world-frame once each emitter's centre is
 * mapped out to world. The only genuinely frame-dependent quantities are (1) that world centre and
 * (2) the shell's own velocity, which the impact/deflection math must subtract so a cruising ship does
 * not bill its own crew (§4.3 point 3, the one place a naive port is silently wrong).</p>
 *
 * <p>Standalone is the identity frame ({@link WorldFieldFrame}); the ship frame ({@link ShipFieldFrame})
 * delegates to {@code VSIntegration}. A ship frame that cannot resolve (ship unloaded) reports
 * {@link #isReady()} = false and its emitter degrades to off (Q4 fail-open) rather than projecting a
 * shell at the wrong place.</p>
 */
public interface FieldFrame {

    /**
     * Map a field-frame point (an emitter's subspace centre) to its current world position. On a ship
     * this tracks the hull as it flies/turns. Returns {@code null} when the frame cannot resolve (ship
     * not loaded) — callers treat that as "field off".
     */
    Vec3d fieldToWorld(double x, double y, double z);

    /**
     * World-frame velocity of the shell surface at a world point (blocks per tick). Zero for a
     * standalone field (the surface is static); on a ship it is the hull's motion at that point, which
     * the impact cost and the deflection must be taken <em>relative to</em>.
     */
    Vec3d surfaceVelocityAt(double worldX, double worldY, double worldZ);

    /**
     * True when the frame's conversions resolve. Always true standalone; on a ship, false when the ship
     * is not loaded on this side — the emitter then contributes no shell (fail-open).
     */
    boolean isReady();
}
