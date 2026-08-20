package zmaster587.advancedRocketry.integration.vs;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.util.datastructures.IBlockPosSet;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

import zmaster587.advancedRocketry.ship.mass.MassContributor;
import zmaster587.advancedRocketry.ship.mass.ShipMassFrame;
import zmaster587.advancedRocketry.ship.mass.ShipMassFrameBuilder;

/**
 * The authoritative answer to "what does this hull actually weigh": one pass over every block the
 * ship owns, priced from Advanced Rocketry's own table.
 *
 * <h2>Authority, not the working path</h2>
 *
 * <p>The engine keeps a ship's mass, centre of mass and inertia tensor as an ACCUMULATOR that lives
 * from the craft's birth: it is fed block by block when the hull is first relocated, adjusted per
 * block for the rest of the ship's life, and restored from disk on a world load. The hull is never
 * rescanned. That incremental path is cheap and correct as long as nothing is ever missed, and it
 * stays the working path.</p>
 *
 * <p>This class is the other half: a full recompute, run where it is independently justified rather
 * than on a timer — the craft has just been assembled, or pasted somewhere else, or brought back from
 * disk. Its answer is what a disagreement is measured AGAINST, which is the whole reason it exists:
 * a drift between the two is a missing trigger, and without an independent number nothing could
 * notice one.</p>
 *
 * <h2>The frame this answers in</h2>
 *
 * <p>The engine's record is expressed in the ship's own SUBSPACE address space — the shipyard the
 * craft's blocks really live in, which for a real ship starts past five million blocks along X. A
 * frame computed in any other origin would disagree with the record by that whole offset and every
 * comparison would report drift forever, so the answer here is in exactly that space.</p>
 *
 * <p>It is not ACCUMULATED there, though. Second moments about a point five million away are ~10^13
 * per kilogram before the shift back to the centre of mass cancels almost all of it, which spends
 * most of a double's 15 significant digits on a constant. Measured against the tolerances the writer
 * compares with, the direct form is still inside them — but the fix is one subtraction, so the walk
 * happens about a point near the hull and the frame is translated back at the end. The inertia tensor
 * is about the centre of mass and unaffected by where it was measured from.</p>
 *
 * <h2>All three halves, in one pass</h2>
 *
 * <p>Structure, CONTENT (fluids and inventories, which no block state reveals) and CREW (the people
 * the deck is carrying, with what they carry). They are measured together because the engine's record
 * holds ONE mass: the moment the written number includes content, a structure-only recompute compared
 * against it would report the fuel in the tanks as drift, every time. One composition, written and
 * compared, or the safety net cries wolf about the ordinary case.</p>
 */
final class ShipHullMass {

    private ShipHullMass() {}

    /**
     * The structural mass frame of the ship named by {@code shipUuid}, or {@code null} when this
     * world holds no such ship, or holds one that owns no blocks.
     *
     * <p>A blockless ship is a real state and not an error: a craft cut out of this world for a
     * crossing leaves its registration behind for a moment. {@code null} says "there is nothing here
     * to weigh", which is what a caller must act on — writing a zero frame instead would tell the
     * solver the craft has no mass and no inertia, and it inverts that tensor every step.</p>
     *
     * <p>Must run on the server game thread: it reads block states out of the world.</p>
     */
    @Nullable
    static ShipMassFrame frameOf(@Nullable World world, @Nullable UUID shipUuid) {
        if (world == null || shipUuid == null) {
            return null;
        }
        ShipData ship = VSBridge.shipDataByUuid(world, shipUuid);
        if (ship == null) {
            return null;
        }
        IBlockPosSet blocks = ship.getBlockPositions();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        // Somewhere near the hull, and cheap: the claim's own centre is a fixed property of the ship
        // and needs no pass over the blocks to find.
        BlockPos origin = ship.getChunkClaim().getRegionCenter();
        final double ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();

        ShipMassFrameBuilder builder = new ShipMassFrameBuilder();
        // The int-triple form, so a hull of tens of thousands of blocks does not allocate a BlockPos
        // per block on a pass that runs inside a world tick.
        blocks.forEach((x, y, z) -> {
            double mass = ArBlockMass.of(world.getBlockState(new BlockPos(x, y, z)));
            if (mass > 0.0) {
                // +0.5 because a block's mass sits at the centre of its cell, which is where the
                // engine's own per-block hook puts it. Half a block of disagreement per block would
                // be a systematic centre-of-mass offset, not rounding.
                builder.add(MassContributor.ofBlock(x + 0.5 - ox, y + 0.5 - oy, z + 0.5 - oz,
                        mass, MassContributor.Kind.STRUCTURAL));
            }
        });
        addContents(world, blocks, ox, oy, oz, builder);
        addCrew(world, shipUuid, ox, oy, oz, builder);

        ShipMassFrame local = builder.build();
        if (local.getTotalMass() <= 0.0) {
            // Every block priced at nothing — a hull of air, or a table that answers zero for
            // everything in it. Not a frame worth writing: it would zero a craft the solver is
            // integrating.
            return null;
        }
        return local.translated(ox, oy, oz);
    }

    /**
     * How much a person's own body weighs, in kilograms, before anything they carry. `tunable`.
     *
     * <p>What they are CARRYING is priced for real, through the same table a crate of the same items
     * would be priced by — so a crew member who loads up changes the ship's mass and its centre, which
     * is the whole reason crew is a separate half of the frame rather than a rounding error.</p>
     */
    private static final double CREW_BODY_KG = 80.0D;

    /**
     * Everything held INSIDE the hull's machines: fluids in tanks, items in inventories, whatever a
     * tile answers a capability with. None of it is visible from a block's state, which is why the
     * structural pass above cannot see it and why it is re-sampled rather than tracked by deltas — a
     * tank empties without a single block ever changing.
     */
    private static void addContents(World world, IBlockPosSet blocks,
                                    double ox, double oy, double oz, ShipMassFrameBuilder builder) {
        blocks.forEach((x, y, z) -> {
            TileEntity tile = world.getTileEntity(new BlockPos(x, y, z));
            if (tile == null) {
                return;
            }
            double held = zmaster587.advancedRocketry.util.WeightEngine.INSTANCE.getTEWeight(tile);
            if (held > 0.0) {
                builder.add(MassContributor.ofBlock(x + 0.5 - ox, y + 0.5 - oy, z + 0.5 - oz,
                        held, MassContributor.Kind.CONTENT));
            }
        });
    }

    /**
     * The people the ship is carrying, at the point on it where each of them stands.
     *
     * <p>Who counts is not "everyone within the box": it is the crew definition the rest of the tier
     * already uses — someone seated on this ship, or someone the deck has captured. A passer-by who
     * happens to be inside the hull's bounding box is physically present and is deliberately NOT
     * weighed, because the alternative makes a ship's mass jump when a mob wanders through a doorway.</p>
     */
    private static void addCrew(World world, UUID shipUuid,
                                double ox, double oy, double oz, ShipMassFrameBuilder builder) {
        String shipId = String.valueOf(shipUuid);
        // The ship's own stay region, in its subspace — the SAME volume the crossing enumerates by and
        // the hyperspace void judges a crew member by, so "aboard" means one thing across the tier
        // rather than one thing per caller.
        net.minecraft.util.math.AxisAlignedBB stay =
                VSIntegration.subspaceStayRegion(world, shipId, 1.0D);
        if (stay == null) {
            return; // no loaded ship to be aboard of; carrying nobody is the honest answer
        }
        for (Entity body : world.loadedEntityList) {
            if (body.isDead || !isCarried(body)) {
                continue;
            }
            double[] local = VSIntegration.toShipFrameFor(world, shipId, body.posX, body.posY, body.posZ);
            if (local == null
                    || !stay.contains(new net.minecraft.util.math.Vec3d(local[0], local[1], local[2]))) {
                continue;
            }
            builder.add(MassContributor.ofBlock(local[0] - ox, local[1] - oy, local[2] - oz,
                    CREW_BODY_KG + carriedMass(body), MassContributor.Kind.CREW));
        }
    }

    /**
     * Whether the ship is CARRYING this body rather than merely containing it: someone sitting on it,
     * or someone whose movement the deck has taken over. A mob that wandered in through a doorway is
     * inside the hull and is deliberately not weighed — otherwise a ship's mass and its centre would
     * jump every time something walked past, and the flight model would chase it.
     */
    private static boolean isCarried(Entity body) {
        if (body.isRiding()) {
            return true;
        }
        return body instanceof net.minecraft.entity.EntityLivingBase
                && ShipFrameTravel.handles((net.minecraft.entity.EntityLivingBase) body);
    }

    /** What this person is carrying, priced by the same table their cargo would be priced by. */
    private static double carriedMass(Entity body) {
        if (!(body instanceof EntityPlayer)) {
            return 0.0D;
        }
        EntityPlayer player = (EntityPlayer) body;
        double carried = 0.0D;
        for (net.minecraft.item.ItemStack stack : player.inventory.mainInventory) {
            carried += zmaster587.advancedRocketry.util.WeightEngine.INSTANCE.getWeight(stack);
        }
        for (net.minecraft.item.ItemStack stack : player.inventory.armorInventory) {
            carried += zmaster587.advancedRocketry.util.WeightEngine.INSTANCE.getWeight(stack);
        }
        for (net.minecraft.item.ItemStack stack : player.inventory.offHandInventory) {
            carried += zmaster587.advancedRocketry.util.WeightEngine.INSTANCE.getWeight(stack);
        }
        return carried;
    }
}
