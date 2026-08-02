package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.TransitRecord;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure NBT round-trip of {@link TransitRecord} — the durable snapshot of one in-flight ship. The logical
 * flight state (position/target/ticks/speed), the aboard crew UUIDs, and the optional packed-ship snapshot
 * must survive a write&rarr;read unchanged, so a hyperspace jump reconstructs across a restart.
 */
public class TransitRecordTest {

    private static GalacticCoord coord(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    @Test
    public void roundTripPreservesLogicalStateAndCrew() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        GalacticCoord org = coord(3, 0, -1, 250, 0, -60);
        GalacticCoord tgt = coord(9, 2, 0, 0, 0, 0);
        TransitRecord r = new TransitRecord("ship-A", org, tgt, 24_000L, 9_000L, 5000L, 4200L, 137L,
                Arrays.asList(u1, u2), null);

        TransitRecord back = TransitRecord.readFromNBT(r.writeToNBT());

        assertEquals("ship-A", back.shipId);
        // The mid-flight state is (origin name, target name, progress) and never a raw absolute:
        // an absolute means something different every tick it is read back (C15 ADDR-12).
        assertEquals(org, back.origin);
        assertEquals(tgt, back.target);
        assertEquals("the flight's priced distance survives", 24_000L, back.distanceBlocks);
        assertEquals("how far it had got survives", 9_000L, back.travelledBlocks);
        assertEquals(5000L, back.arrivalTick);
        assertEquals(4200L, back.lastTicked);
        assertEquals(137L, back.speed);
        List<UUID> crew = back.crew;
        assertEquals("both crew survive", 2, crew.size());
        assertTrue(crew.contains(u1));
        assertTrue(crew.contains(u2));
        assertNull("no snapshot -> stays null", back.snapshot);
    }

    @Test
    public void packedSnapshotSurvivesWhenPresent() {
        NBTTagCompound snap = new NBTTagCompound();
        snap.setInteger("marker", 42); // stand-in for the StorageChunk payload
        TransitRecord r = new TransitRecord("s", GalacticCoord.ORIGIN, coord(1, 0, 0, 0, 0, 0),
                4_000_000L, 0L, 10L, 0L, 5L, null, snap);

        TransitRecord back = TransitRecord.readFromNBT(r.writeToNBT());

        assertNotNull("the packed ship snapshot survives", back.snapshot);
        assertEquals(42, back.snapshot.getInteger("marker"));
        assertTrue("unmanned record has empty crew", back.crew.isEmpty());
    }
}
