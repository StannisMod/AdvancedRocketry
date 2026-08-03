package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.ShipLedgerData;
import zmaster587.advancedRocketry.space.TransitRecord;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure persistence contract of {@link ShipLedgerData} (the durable backing for the ship ledger): a
 * settled ship's galactic position survives a write&rarr;read NBT round-trip, a ship in flight is
 * carried by its transit record instead of by the settled list, a write that would store a ship in
 * NEITHER place is refused outright, and a loaded store repopulates a live {@link ShipLedger}. No MC
 * world — the WSD's own {@code write/readFromNBT} + {@code replaceAll}/{@code loadInto} operate on
 * in-memory maps.
 */
public class ShipLedgerDataTest {

    private static GalacticCoord coord(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    /** The in-flight record that carries {@code ship} — what makes it legal to leave the settled list. */
    private static TransitRecord flying(UUID ship, GalacticCoord target) {
        NBTTagCompound blocks = new NBTTagCompound();
        blocks.setInteger("blocks", 1);
        return new TransitRecord(ship.toString(), coord(0, 0, 0, 0, 0, 0), target, 10L, 0L, 0L, 0L, 1L,
                java.util.Collections.<UUID>emptyList(), blocks);
    }

    /** The whole store replaced from a live ledger with nothing in flight and no visits recorded. */
    private static List<UUID> store(ShipLedgerData data, ShipLedger live) {
        return data.replaceAll(live.snapshot(), java.util.Collections.<TransitRecord>emptyList(),
                java.util.Collections.<String, Long>emptyMap());
    }

    @Test
    public void settledEntriesSurviveWriteReadRoundTrip() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID flyer = UUID.randomUUID();
        GalacticCoord coordA = coord(3, 0, -1, 100, 0, 50);
        GalacticCoord coordB = coord(-7, 2, 0, 0, -40, 0);
        GalacticCoord flyingTo = coord(9, 0, 0, 0, 0, 0);

        ShipLedger live = new ShipLedger();
        live.settle(a, coordA);
        live.settle(b, coordB);
        live.beginTransit(flyer, flyingTo); // in flight: belongs in the transit list, not this one

        ShipLedgerData src = new ShipLedgerData();
        assertTrue("nothing is dropped: the flying ship is carried by its record",
                src.replaceAll(live.snapshot(),
                        java.util.Collections.singletonList(flying(flyer, flyingTo)),
                        java.util.Collections.<String, Long>emptyMap()).isEmpty());
        assertEquals("only the two settled ships are in the settled list", 2, src.snapshot().size());

        NBTTagCompound nbt = src.writeToNBT(new NBTTagCompound());

        ShipLedgerData dst = new ShipLedgerData();
        dst.readFromNBT(nbt);

        Map<UUID, ShipLedger.Entry> read = dst.snapshot();
        assertEquals("both settled ships survive the round-trip", 2, read.size());

        assertNotNull("ship A present", read.get(a));
        assertEquals("A coord survives", coordA, read.get(a).coord);
        assertEquals("A restored as SETTLED", ShipLedger.State.SETTLED, read.get(a).state);

        assertNotNull("ship B present", read.get(b));
        assertEquals("B coord survives", coordB, read.get(b).coord);
    }

    @Test
    public void theStoreWritesNoSlotDimension() {
        // A slot dim is minted per boot and re-used as cells come and go, so an id written here names
        // a different cell — or no world at all — on the next start. Persisting one is persisting an
        // answer that expires with the process, and the reader has no way to tell an expired one from
        // a live one. The coordinate is what survives a restart; the dimension is re-derived from it.
        UUID a = UUID.randomUUID();
        ShipLedger live = new ShipLedger();
        live.settle(a, coord(3, 0, -1, 100, 0, 50));

        ShipLedgerData data = new ShipLedgerData();
        store(data, live);
        NBTTagCompound nbt = data.writeToNBT(new NBTTagCompound());

        NBTTagCompound ship = nbt.getTagList("ships", 10).getCompoundTagAt(0);
        assertEquals("sanity: the one settled ship was written", 1,
                nbt.getTagList("ships", 10).tagCount());
        assertTrue("its galactic coordinate IS written — that is what survives a restart",
                ship.hasKey("galacticCoord"));
        assertFalse("no per-boot slot dimension may be written alongside it", ship.hasKey("slotDim"));
    }

    @Test
    public void anInFlightShipIsCarriedByItsTransitRecordAndNotByTheSettledList() {
        UUID flyer = UUID.randomUUID();
        GalacticCoord flyingTo = coord(1, 0, 0, 0, 0, 0);
        ShipLedger live = new ShipLedger();
        live.beginTransit(flyer, flyingTo);

        ShipLedgerData data = new ShipLedgerData();
        assertTrue("the write is applied", data.replaceAll(live.snapshot(),
                java.util.Collections.singletonList(flying(flyer, flyingTo)),
                java.util.Collections.<String, Long>emptyMap()).isEmpty());
        assertTrue("a ship in flight is not in the settled list", data.snapshot().isEmpty());

        NBTTagCompound nbt = data.writeToNBT(new NBTTagCompound());
        ShipLedgerData dst = new ShipLedgerData();
        dst.readFromNBT(nbt);
        assertTrue("and it is still not in the settled list after the round-trip",
                dst.snapshot().isEmpty());
        assertEquals("it comes back as the in-flight jump it is", 1, dst.loadTransits().size());
        assertEquals(flyer.toString(), dst.loadTransits().get(0).shipId);
    }

    /**
     * The contract that a lost fleet was made of: a ship the ledger no longer calls settled, and that
     * no in-flight jump carries either, would be stored in NEITHER list — so this write is refused and
     * the store is left alone.
     *
     * <p>This is exactly the state a save point reaches when the transit half of the gather fails: the
     * live ledger says the ship is flying, the transit records say nothing, and the old code obliged by
     * writing an empty fleet over a good one. There is no ordering of a clear-then-refill that survives
     * it, which is why the store no longer offers one.</p>
     */
    @Test
    public void aWriteThatWouldStoreAFlyingShipNowhereIsRefused() {
        UUID settled = UUID.randomUUID();
        UUID flyer = UUID.randomUUID();
        GalacticCoord home = coord(4, 0, 0, 0, 0, 0);

        ShipLedgerData data = new ShipLedgerData();
        ShipLedger before = new ShipLedger();
        before.settle(settled, home);
        before.settle(flyer, home);
        assertTrue("arrangement: a good fleet is stored first", store(data, before).isEmpty());
        NBTTagCompound good = data.writeToNBT(new NBTTagCompound());

        ShipLedger during = new ShipLedger();
        during.settle(settled, home);
        during.beginTransit(flyer, coord(5, 0, 0, 0, 0, 0)); // flying, and nothing carries it

        List<UUID> dropped = store(data, during);
        assertEquals("the write names the ship it would have lost", 1, dropped.size());
        assertEquals(flyer, dropped.get(0));
        assertEquals("and it changed nothing: both ships are still stored", 2, data.snapshot().size());
        assertEquals("byte for byte, the store is what it was before the refused write",
                good.toString(), data.writeToNBT(new NBTTagCompound()).toString());
    }

    @Test
    public void loadIntoRepopulatesALiveLedger() {
        UUID a = UUID.randomUUID();
        GalacticCoord coordA = coord(2, -3, 4, 10, 20, -30);

        ShipLedger source = new ShipLedger();
        source.settle(a, coordA);

        ShipLedgerData data = new ShipLedgerData();
        store(data, source);
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
        assertEquals(ShipLedger.State.SETTLED, en.state);
        assertFalse("live ledger snapshot is a copy", live.snapshot() == restored.snapshot());
    }

    @Test
    public void transitRecordsSurviveWriteReadRoundTrip() {
        GalacticCoord org = coord(1, 0, 0, 500, 0, 0);
        GalacticCoord tgt = coord(2, 0, 0, 0, 0, 0);
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setInteger("blocks", 27); // stands in for the StorageChunk NBT (its own round-trip = TransitRecordTest)
        UUID crew = UUID.randomUUID();
        TransitRecord rec = new TransitRecord(UUID.randomUUID().toString(), org, tgt, 3_500_000L,
                1_250_000L, 4242L, 100L, 9L, java.util.Collections.singletonList(crew), snapshot);

        ShipLedgerData src = new ShipLedgerData();
        src.replaceAll(java.util.Collections.<UUID, ShipLedger.Entry>emptyMap(),
                java.util.Collections.singletonList(rec),
                java.util.Collections.<String, Long>emptyMap());

        // Round-trip through the store's own NBT (the same write/read that hits disk on a world save).
        ShipLedgerData dst = new ShipLedgerData();
        dst.readFromNBT(src.writeToNBT(new NBTTagCompound()));

        List<TransitRecord> read = dst.loadTransits();
        assertEquals("the in-flight transit survives the store's 'transits' NBT round-trip", 1, read.size());
        TransitRecord r = read.get(0);
        assertEquals("shipId survives", rec.shipId, r.shipId);
        assertEquals("origin survives", org, r.origin);
        assertEquals("the flight's priced distance survives", 3_500_000L, r.distanceBlocks);
        assertEquals("how far it had got survives", 1_250_000L, r.travelledBlocks);
        assertEquals("target survives", tgt, r.target);
        assertEquals("arrivalTick survives", 4242L, r.arrivalTick);
        assertEquals("speed survives", 9L, r.speed);
        assertEquals("crew survives", 1, r.crew.size());
        assertEquals(crew, r.crew.get(0));
        assertNotNull("the block snapshot survives (so the persisted transit can rematerialize)", r.snapshot);
        assertEquals("snapshot payload survives", 27, r.snapshot.getInteger("blocks"));
    }
}
