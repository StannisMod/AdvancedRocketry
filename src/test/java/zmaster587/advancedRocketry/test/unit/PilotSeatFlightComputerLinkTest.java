package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.Test;

import zmaster587.advancedRocketry.tile.TilePilotSeat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract for how a {@link TilePilotSeat} remembers its Advanced Flight Computer.
 *
 * <p>The seat and computer are linked on the launch pad, then the physics mod relocates the
 * whole craft into a ship — a rigid translation that changes both blocks' absolute positions
 * but NOT the offset between them. So the seat must store the computer as a RELATIVE offset,
 * not an absolute position; at runtime it recovers the computer at {@code seatPos + offset}.
 * These tests pin that relative-offset contract (the thing that makes the link survive
 * relocation) and its NBT persistence — no world, no physics mod, no client needed.</p>
 */
public class PilotSeatFlightComputerLinkTest {

    @Test
    public void freshSeatIsUnlinkedAndResolvesNoComputer() {
        TilePilotSeat seat = new TilePilotSeat();
        seat.setPos(new BlockPos(10, 64, 10));
        assertFalse("a freshly-placed pilot seat is not linked", seat.isLinked());
        assertNull("an unlinked seat resolves no flight computer position", seat.getFlightComputerPos());
    }

    @Test
    public void linkStoresComputerAsRelativeOffset() {
        TilePilotSeat seat = new TilePilotSeat();
        seat.setPos(new BlockPos(10, 64, 10));
        BlockPos afc = new BlockPos(10, 66, 7); // +2 up, -3 north of the seat
        seat.linkToFlightComputer(afc);

        assertTrue("linking marks the seat linked", seat.isLinked());
        assertEquals("a linked seat resolves the computer's position",
                afc, seat.getFlightComputerPos());
    }

    @Test
    public void resolvedComputerPositionTracksTheSeatUnderRigidTranslation() {
        // Link on the "pad", then simulate the physics-mod relocation as a rigid translation of
        // BOTH blocks by the same delta. The stored offset is relative, so re-placing the seat
        // at its post-relocation position must resolve the computer at ITS post-relocation
        // position — the property that lets the link survive the move.
        BlockPos padSeat = new BlockPos(100, 64, 100);
        BlockPos padAfc = new BlockPos(103, 65, 98);
        TilePilotSeat seat = new TilePilotSeat();
        seat.setPos(padSeat);
        seat.linkToFlightComputer(padAfc);

        BlockPos delta = new BlockPos(-2048, 0, 4096); // arbitrary rigid move into ship subspace
        seat.setPos(padSeat.add(delta));

        assertEquals("the resolved computer position tracks the seat under a rigid translation",
                padAfc.add(delta), seat.getFlightComputerPos());
    }

    @Test
    public void linkSurvivesNbtRoundTrip() {
        TilePilotSeat seat = new TilePilotSeat();
        seat.setPos(new BlockPos(10, 64, 10));
        seat.linkToFlightComputer(new BlockPos(10, 66, 7));

        // Persist only the fields this tile writes (the parent TileEntity.writeToNBT needs a
        // registered tile mapping unavailable in a pure unit test); read them back into a fresh
        // tile whose position comes from the vanilla x/y/z keys.
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("x", 10);
        nbt.setInteger("y", 64);
        nbt.setInteger("z", 10);
        seat.writeLinkNbt(nbt);

        TilePilotSeat reloaded = new TilePilotSeat();
        reloaded.readFromNBT(nbt);

        assertTrue("linked state survives NBT", reloaded.isLinked());
        assertEquals("the relative offset survives NBT",
                new BlockPos(10, 66, 7), reloaded.getFlightComputerPos());
    }
}
