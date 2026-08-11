package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import org.junit.Test;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>A crossing that takes a ship's blocks away leaves nothing still bound to them.</b>
 *
 * <p>A seat dummy is an entity; the cut removes blocks. So the chairs outlive the ship unless
 * something retires them, and a rider left mounted on a chair whose ship no longer exists is an
 * inconsistent state that needs no measurement to be a defect — he sits in empty space, and the
 * dead chair goes on clearing the flight computer's pilot input every tick.</p>
 *
 * <p>What is pinned here is the MATCH, because that is where the whole thing can silently do
 * nothing: the decision is about where the SEAT is, not where the dummy is. A dummy rides at its
 * ship's world position, which for a managed ship is megablocks away from the subspace shipyard the
 * cut covers — so a positional query over the cut box matches none of them, retires nothing, and
 * looks exactly like a crossing with no riders.</p>
 *
 * <p>The boundary is "nothing comes back", not "a cut happened": the same cut also serves rocket and
 * station assembly, where the blocks ARE re-pasted and the pilot must stay seated. That is why the
 * retirement lives at the crossing and not in the cut.</p>
 */
public class CutLeavesNothingBoundTest {

    /** A shipyard box, of the shape a crossing actually cuts: far from the origin, tightly fitted. */
    private static AxisAlignedBB shipyard() {
        return new AxisAlignedBB(5120000, 128, 51200, 5120009, 134, 51209);
    }

    @Test
    public void aSeatInsideTheCutIsRetiredWithTheBlocksItBelongsTo() {
        AxisAlignedBB cut = shipyard();
        assertTrue("a seat in the middle of the cut ship goes with it",
                VSIntegration.boundToCutBlocks(new BlockPos(5120004, 130, 51204), cut));
        // The corners, because a fitted box is exactly as wide as the ship and a seat is allowed to
        // be the outermost block of it. Matching on the seat's CENTRE is what makes the low corner
        // inside rather than on the boundary.
        assertTrue("the lowest corner block of the hull is still part of the hull",
                VSIntegration.boundToCutBlocks(new BlockPos(5120000, 128, 51200), cut));
        assertTrue("and so is the highest one",
                VSIntegration.boundToCutBlocks(new BlockPos(5120008, 133, 51208), cut));
    }

    @Test
    public void aSeatOutsideTheCutIsLeftAlone() {
        AxisAlignedBB cut = shipyard();
        // The control, and it is the load-bearing half: a crossing must not reach out and unseat
        // somebody on a NEIGHBOURING ship. Hyperspace lanes are 2048 apart and shipyards are packed
        // far closer than that, so "one block outside" is the realistic distance, not a contrivance.
        assertFalse("a seat one block beyond the cut belongs to another ship",
                VSIntegration.boundToCutBlocks(new BlockPos(5120010, 130, 51204), cut));
        assertFalse("and so does one below it",
                VSIntegration.boundToCutBlocks(new BlockPos(5120004, 127, 51204), cut));
        assertFalse("and one on the far side of the yard",
                VSIntegration.boundToCutBlocks(new BlockPos(5120004, 130, 51300), cut));
    }

    @Test
    public void aDummyBoundToNothingIsNeverRetired() {
        // A dummy with no seat is bound to nothing, so nothing a cut removes can be what it is bound
        // to. It must not be swept in as a null-shaped wildcard: the sweep runs on every crossing.
        assertFalse(VSIntegration.boundToCutBlocks(null, shipyard()));
        assertFalse(VSIntegration.boundToCutBlocks(new BlockPos(5120004, 130, 51204), null));
    }

    @Test
    public void theMatchIsOnTheSeatAndNotOnWhereTheDummyRides() {
        // The failure this exists to stop, stated as a measurement. A managed ship's dummy rides at
        // the ship's WORLD pose — a few hundred blocks from spawn — while its seat lives in a
        // subspace shipyard millions of blocks away. Ask the question about the dummy's own position
        // and the answer is "no dummies here" for every crossing that ever runs.
        BlockPos seatInYard = new BlockPos(5120004, 130, 51204);
        BlockPos whereTheDummyRides = new BlockPos(120, 70, 340);
        AxisAlignedBB cut = shipyard();

        assertTrue("the seat is what the cut is taking", VSIntegration.boundToCutBlocks(seatInYard, cut));
        assertFalse("the dummy's own position is nowhere near it, which is exactly why asking about"
                + " the dummy retires nothing", VSIntegration.boundToCutBlocks(whereTheDummyRides, cut));
    }
}
