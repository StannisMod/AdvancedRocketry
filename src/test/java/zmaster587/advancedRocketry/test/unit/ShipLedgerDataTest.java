package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.ShipLedgerData;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure persistence contract of {@link ShipLedgerData} (the durable backing for the ship ledger): a
 * settled ship's galactic position + slot binding survives a write&rarr;read NBT round-trip, in-transit
 * ships are NOT persisted (they need a transit record + block snapshot, a later phase), and a loaded
 * store repopulates a live {@link ShipLedger}. No MC world — the WSD's own {@code write/readFromNBT} +
 * {@code saveFrom}/{@code loadInto} operate on in-memory maps.
 */
public class ShipLedgerDataTest {

    private static GalacticCoord coord(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    @Test
    public void settledEntriesSurviveWriteReadRoundTrip() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        GalacticCoord coordA = coord(3, 0, -1, 100, 0, 50);
        GalacticCoord coordB = coord(-7, 2, 0, 0, -40, 0);

        ShipLedger live = new ShipLedger();
        live.settle(a, coordA, 5);
        live.settle(b, coordB, 7);
        live.beginTransit(UUID.randomUUID(), coord(9, 0, 0, 0, 0, 0)); // in-transit: must be skipped

        ShipLedgerData src = new ShipLedgerData();
        src.saveFrom(live);
        assertEquals("only the two settled ships are persisted", 2, src.snapshot().size());

        NBTTagCompound nbt = src.writeToNBT(new NBTTagCompound());

        ShipLedgerData dst = new ShipLedgerData();
        dst.readFromNBT(nbt);

        Map<UUID, ShipLedger.Entry> read = dst.snapshot();
        assertEquals("both settled ships survive the round-trip", 2, read.size());

        assertNotNull("ship A present", read.get(a));
        assertEquals("A coord survives", coordA, read.get(a).coord);
        assertEquals("A slot dim survives", 5, read.get(a).slotDim);
        assertEquals("A restored as SETTLED", ShipLedger.State.SETTLED, read.get(a).state);

        assertNotNull("ship B present", read.get(b));
        assertEquals("B coord survives", coordB, read.get(b).coord);
        assertEquals("B slot dim survives", 7, read.get(b).slotDim);
    }

    @Test
    public void inTransitEntriesAreNotPersisted() {
        ShipLedger live = new ShipLedger();
        live.beginTransit(UUID.randomUUID(), coord(1, 0, 0, 0, 0, 0));

        ShipLedgerData data = new ShipLedgerData();
        data.saveFrom(live);
        assertTrue("an in-transit ship contributes nothing to the store", data.snapshot().isEmpty());

        NBTTagCompound nbt = data.writeToNBT(new NBTTagCompound());
        ShipLedgerData dst = new ShipLedgerData();
        dst.readFromNBT(nbt);
        assertTrue("nothing to restore", dst.snapshot().isEmpty());
    }

    @Test
    public void loadIntoRepopulatesALiveLedger() {
        UUID a = UUID.randomUUID();
        GalacticCoord coordA = coord(2, -3, 4, 10, 20, -30);

        ShipLedger source = new ShipLedger();
        source.settle(a, coordA, 11);

        ShipLedgerData data = new ShipLedgerData();
        data.saveFrom(source);
        // Round-trip through NBT so we exercise the on-disk shape, not just the in-memory copy.
        ShipLedgerData restored = new ShipLedgerData();
        restored.readFromNBT(data.writeToNBT(new NBTTagCompound()));

        ShipLedger live = new ShipLedger();
        assertEquals("starts empty", 0, live.size());
        restored.loadInto(live);

        assertEquals("one settled ship restored into the live ledger", 1, live.size());
        ShipLedger.Entry en = live.get(a);
        assertNotNull("ship A restored", en);
        assertEquals(coordA, en.coord);
        assertEquals(11, en.slotDim);
        assertEquals(ShipLedger.State.SETTLED, en.state);
        assertFalse("live ledger snapshot is a copy", live.snapshot() == restored.snapshot());
    }
}
