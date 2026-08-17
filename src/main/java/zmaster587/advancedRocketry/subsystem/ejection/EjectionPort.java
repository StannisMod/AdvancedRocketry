package zmaster587.advancedRocketry.subsystem.ejection;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.subsystem.hull.HullClearance;

/**
 * Throwing matter out of a hull, done once for everyone who needs to.
 * <p>
 * Two subsystems eject solid objects overboard — life support sheds the carbon its scrubbers pull
 * out of the air, and the thermal system fires charged heat slugs when rejection cannot win. They
 * are deliberately DIFFERENT machines: charging a slug to just under its melting point needs
 * materials and a duty cycle an ordinary airlock has no business carrying, and the thermal dump is
 * required to be unusable as a steady state while the carbon sink IS one. What they share is this —
 * the act itself, which is fiddlier than it looks aboard a ship.
 * <p>
 * <b>The clearance walk needs no frame conversion, and the ejection does.</b> A ship's blocks live
 * in a stationary subspace and that is exactly where the world stores them, so asking "are the next
 * few blocks along my facing empty?" is ordinary block arithmetic and is correct on a flying ship
 * without touching a transform. An item ENTITY, on the other hand, lives in the world frame, so the
 * spawn crosses the seam and every part of it must be mapped.
 */
public final class EjectionPort {

    /** Clear of the port's mouth before the item is released, so it never spawns inside the hull. */
    private static final double MUZZLE_OFFSET = 0.6D;

    private EjectionPort() {
    }

    /**
     * How far along {@code facing} the first obstruction sits, or 0 when the exit is clear.
     * <p>
     * The walk itself is {@link HullClearance}: a radiator needs the same question answered and is
     * not ejecting anything, so the implementation moved somewhere neutrally named and this stays as
     * the ejection-side name for it.
     */
    public static int obstructionDistance(World world, BlockPos pos, EnumFacing facing, int clearance) {
        return HullClearance.obstructionDistance(world, pos, facing, clearance);
    }

    /**
     * Releases one stack out of the port, in the world frame, carrying the ship's own motion.
     * <p>
     * The carry matters as much as the direction: an item released with the ship's velocity
     * subtracted out simply reappears inside the hull a tick later, because the hull is moving and
     * the item is not. Sampling the velocity at the port's own world point rather than at the
     * ship's centre also gets rotation right — a port far out on a spinning hull is moving faster
     * than the hub it turns around.
     *
     * @return false when the stack was empty or the world refused the spawn; the caller keeps the
     *         stack in that case rather than losing it
     */
    public static boolean eject(World world, BlockPos pos, EnumFacing facing, ItemStack stack) {
        if (world == null || world.isRemote || pos == null || facing == null
                || stack == null || stack.isEmpty()) {
            return false;
        }

        double[] mouth = worldPointOf(world, pos);
        double[] direction = worldDirectionOf(world, pos, facing);

        double x = mouth[0] + 0.5D + direction[0] * MUZZLE_OFFSET;
        double y = mouth[1] + 0.5D + direction[1] * MUZZLE_OFFSET;
        double z = mouth[2] + 0.5D + direction[2] * MUZZLE_OFFSET;

        EntityItem item = new EntityItem(world, x, y, z, stack.copy());
        item.setPickupDelay(20);

        double[] carry = VSIntegration.shipVelocityAtPoint(world, x, y, z);
        double carryX = carry == null ? 0.0D : carry[0];
        double carryY = carry == null ? 0.0D : carry[1];
        double carryZ = carry == null ? 0.0D : carry[2];

        item.motionX = carryX + direction[0] * EJECT_SPEED;
        item.motionY = carryY + direction[1] * EJECT_SPEED;
        item.motionZ = carryZ + direction[2] * EJECT_SPEED;

        return world.spawnEntity(item);
    }

    /** Slow enough to read as "pushed out", fast enough to clear a hull that is turning. */
    private static final double EJECT_SPEED = 0.35D;

    /**
     * Where this block actually IS right now. On a ship the block's own coordinates are a subspace
     * address that has nothing to do with where the hull is flying, so they are mapped; off a ship
     * the two frames coincide and the mapper answers null.
     */
    private static double[] worldPointOf(World world, BlockPos pos) {
        double[] mapped = VSIntegration.getShipWorldPosition(world, pos);
        return mapped != null ? mapped : new double[]{pos.getX(), pos.getY(), pos.getZ()};
    }

    /**
     * Which way "out of this face" points in the world.
     * <p>
     * Taken as the difference between two SUBSPACE points mapped through the same transform, rather
     * than by rotating a vector: it needs no ship identity, it is exact for any hull attitude, and
     * it degrades to the plain facing vector by construction when there is no ship, because then
     * both points map to themselves.
     */
    private static double[] worldDirectionOf(World world, BlockPos pos, EnumFacing facing) {
        double[] here = VSIntegration.getShipWorldPosition(world, pos);
        double[] ahead = VSIntegration.getShipWorldPosition(world, pos.offset(facing));
        if (here != null && ahead != null) {
            double dx = ahead[0] - here[0];
            double dy = ahead[1] - here[1];
            double dz = ahead[2] - here[2];
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length > 1.0E-6D) {
                return new double[]{dx / length, dy / length, dz / length};
            }
        }
        return new double[]{facing.getFrontOffsetX(), facing.getFrontOffsetY(), facing.getFrontOffsetZ()};
    }
}
