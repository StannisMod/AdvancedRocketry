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
 * NEITHER place is refused outright, the subsystem's own clock rides its own monotonic writer rather
 * than the fleet's snapshot, and a loaded store repopulates a live {@link ShipLedger}. No MC world —
 * the WSD's own {@code write/readFromNBT} + {@code replaceAll}/{@code loadInto} operate on in-memory
 * maps.
 */
public class ShipLedgerDataTest {

    /**
     * A space-clock value no fresh boot reaches — ~11.6 days at 20 tps. Deliberately not a small
     * number: reading back a small one would be indistinguishable from reading back a default.
     */
    private static final long CLOCK = 20_000_000L;

    /** How much NEWER the clock offered with a refused write is. Any non-zero value would do. */
    private static final long AGE = 500_000L;

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

    /**
     * The space subsystem's own clock is part of the durable snapshot, so the counter resumes where
     * the last save left it instead of restarting at zero every boot.
     *
     * <p>Everything else in this store is a STAMP — a cell's last visit, a jump's arrival tick — and a
     * stamp only means anything beside the counter it was taken from. Lose the counter and a flight
     * that had two minutes left comes back having arrived before the world began.</p>
     */
    @Test
    public void theSpaceClockIsStoredWithTheStateItDatesAndSurvivesTheRoundTrip() {
        UUID a = UUID.randomUUID();
        GalacticCoord home = coord(3, 0, -1, 100, 0, 50);
        ShipLedger live = new ShipLedger();
        live.settle(a, home);

        ShipLedgerData src = new ShipLedgerData();
        assertTrue("the write is applied", store(src, live).isEmpty());
        src.setClock(CLOCK);

        ShipLedgerData dst = new ShipLedgerData();
        dst.readFromNBT(src.writeToNBT(new NBTTagCompound()));

        assertEquals("the subsystem must resume its own counter where the save left it", CLOCK,
                dst.clock());
        assertEquals("...beside the fleet that counter dates", 1, dst.snapshot().size());
    }

    /**
     * A save with no clock in it reads back as tick zero — the pre-3.0.0 / brand-new-world case.
     *
     * <p>This is the READ side of the schema, not a field default: it goes through the same
     * {@code readFromNBT} a world on disk does, against NBT that genuinely lacks the key. The store
     * has to answer something, and zero is where a new clock starts, so a world that never stored one
     * begins rather than jumping.</p>
     */
    @Test
    public void aSaveWithNoClockInItReadsBackAsTickZero() {
        ShipLedgerData legacy = new ShipLedgerData();
        legacy.setClock(CLOCK); // it must be capable of holding a non-zero clock first
        assertEquals("arrangement: the store holds a clock before the key is taken away", CLOCK,
                legacy.clock());

        NBTTagCompound nbt = legacy.writeToNBT(new NBTTagCompound());
        assertTrue("arrangement: the schema key must be there to remove", nbt.hasKey("spaceClock"));
        nbt.removeTag("spaceClock");

        legacy.readFromNBT(nbt);
        assertEquals("a save written before the subsystem owned a clock has none to give back, and "
                + "zero is where a new clock starts", 0L, legacy.clock());
    }

    /**
     * A REFUSED fleet write must not roll the clock back with it.
     *
     * <p>The tempting design is the opposite — bundle the clock into the same all-or-nothing write,
     * so a pass that keeps an older fleet keeps the older clock. It is wrong, because the fleet is
     * not the only thing this clock dates. A jump capacitor's {@code since} lives in TILE NBT and a
     * memory crystal's {@code observedTick} in ITEM NBT, and Minecraft commits both to disk BEFORE a
     * world-save event ever reaches this store. A clock rolled back under them comes back EARLIER
     * than stamps already written, elapsed time against those stamps goes negative, and the code
     * that consumes it reads that as "no time has passed" and stops accruing — a world of capacitors
     * that never charge, for however long it takes the clock to catch up.</p>
     *
     * <p>So the clock is monotonic and the fleet is atomic. The price is that a refused pass can
     * leave a clock at most one save cycle ahead of a stale fleet: a cell reads a cycle older, a jump
     * lands a cycle sooner. That is the cheaper of the two errors by a wide margin.</p>
     */
    @Test
    public void aRefusedFleetWriteDoesNotRollTheClockBack() {
        UUID settled = UUID.randomUUID();
        UUID flyer = UUID.randomUUID();
        GalacticCoord home = coord(4, 0, 0, 0, 0, 0);

        ShipLedgerData data = new ShipLedgerData();
        ShipLedger before = new ShipLedger();
        before.settle(settled, home);
        before.settle(flyer, home);
        assertTrue("arrangement: a good fleet is stored first", store(data, before).isEmpty());
        data.setClock(CLOCK);

        // The clock keeps being written every pass, because it is not part of the snapshot.
        data.setClock(CLOCK + AGE);

        // The state a failed gather leaves behind: flying, and nothing carrying it.
        ShipLedger during = new ShipLedger();
        during.settle(settled, home);
        during.beginTransit(flyer, coord(5, 0, 0, 0, 0, 0));
        assertEquals("arrangement: the fleet write must actually be refused", 1,
                store(data, during).size());

        assertEquals("a refused FLEET write may not drag the clock backwards with it: stamps outside "
                + "this store are already on disk at the newer tick, and a clock behind them makes "
                + "their elapsed time negative", CLOCK + AGE, data.clock());
        assertEquals("...and the fleet it declined to replace is untouched", 2, data.snapshot().size());
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
