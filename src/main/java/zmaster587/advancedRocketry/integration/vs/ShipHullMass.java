package zmaster587.advancedRocketry.integration.vs;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.util.datastructures.IBlockPosSet;

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
 * <h2>What it does NOT count</h2>
 *
 * <p>Structure only. Fluids, inventories and crew are not visible from a block's state, and they
 * change without any block changing, so they are re-sampled on the flight computer's own cadence
 * rather than here. A hull with no flight computer therefore gets an honest structural mass and pays
 * nothing for inventory tracking it has no way to keep current.</p>
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
        ShipMassFrame local = builder.build();
        if (local.getTotalMass() <= 0.0) {
            // Every block priced at nothing — a hull of air, or a table that answers zero for
            // everything in it. Not a frame worth writing: it would zero a craft the solver is
            // integrating.
            return null;
        }
        return local.translated(ox, oy, oz);
    }
}
