package zmaster587.advancedRocketry.space;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Durable backing for the wave-1 {@link ShipLedger}: persists, per ship UUID, WHERE a settled tier-2
 * ship is — its {@link GalacticCoord}, and nothing else about its whereabouts.
 *
 * <p>The slot dimension is deliberately NOT persisted. Slot ids are minted in registration order each
 * start and re-used as cells come and go, so an id written on one boot names a different cell (or no
 * world at all) on the next. Writing one down would be writing down an answer that expires with the
 * process: the coordinate is what survives, and {@link SpaceManager#slotDimOf} re-derives the
 * dimension from it once the cell is materialized again.</p>
 *
 * <p>Hosting + accessor mirror {@code UniverseRegistry} exactly: a {@link WorldSavedData} on the
 * overworld global {@link MapStorage}, server-side only, persisted by MC whenever it is {@code
 * markDirty()}-ed. A ship is stored in exactly one of two places — {@code ships} while it is settled in
 * a cell, its {@link TransitRecord} (block snapshot included) while it is in flight — and
 * {@link #replaceAll} is the single, all-or-nothing writer that keeps those two halves in step.</p>
 *
 * <p>Server main thread only.</p>
 */
public final class ShipLedgerData extends WorldSavedData {

    /** The {@code .dat} filename — a save-schema constant. */
    public static final String STORAGE_KEY = "advancedrocketry_shipledger";

    private static final int NBT_VERSION = 1;

    /** The persisted snapshot: ship UUID -> its settled ledger entry. */
    private final Map<UUID, ShipLedger.Entry> entries = new HashMap<>();
    /** The persisted in-flight transit records (a jump survives a restart). */
    private final List<TransitRecord> transits = new ArrayList<>();
    /** cell key -> the world time it was last visited; drives age-based store GC across restarts. */
    private final Map<String, Long> cellVisits = new HashMap<>();

    public ShipLedgerData() {
        super(STORAGE_KEY);
    }

    public ShipLedgerData(String name) {
        super(name);
    }

    /** Resolve (or create) the store on the overworld global MapStorage. Null iff no server/world. */
    public static ShipLedgerData get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        WorldServer overworld = DimensionManager.getWorld(0);
        if (overworld == null) {
            overworld = server.getWorld(0);
        }
        return get(overworld);
    }

    public static ShipLedgerData get(World world) {
        if (world == null) {
            return null;
        }
        MapStorage storage = world.getMapStorage();
        if (storage == null) {
            return null;
        }
        WorldSavedData existing = storage.getOrLoadData(ShipLedgerData.class, STORAGE_KEY);
        if (existing instanceof ShipLedgerData) {
            return (ShipLedgerData) existing;
        }
        ShipLedgerData fresh = new ShipLedgerData();
        storage.setData(STORAGE_KEY, fresh);
        return fresh;
    }

    /**
     * Replace the ENTIRE persisted snapshot — settled ships, in-flight transits and per-cell visit
     * times — in one step, and mark it dirty so MC writes it.
     *
     * <p><b>Why one call instead of three setters.</b> A settled ship is stored under {@code ships} and
     * a flying one under {@code transits}, so a save that rebuilt the first half and then failed before
     * the second erased every ship that happened to be in flight: the durable record said the fleet was
     * empty, and the next flush made that permanent. Rebuilding the halves as separate steps is what
     * made that reachable, so separate steps no longer exist — this method mutates nothing until it
     * holds every value it is going to write, and then writes all of them.</p>
     *
     * <p>It also enforces what used to be a convention no code checked: a ship the live ledger does NOT
     * call settled has to be carried by a transit record, or this write would store it nowhere. Such a
     * snapshot is REFUSED — nothing is touched, whatever was persisted before still stands, and the
     * ships that would have been dropped are returned so the caller can name them. An empty return
     * means the snapshot was applied.</p>
     *
     * @param live     the live ledger's whole snapshot (both states; only the settled half is stored)
     * @param inFlight the in-flight transit records, as exported by the transit manager
     * @param visits   cell key -&gt; the world time it was last visited
     * @return the ships this write would have dropped; empty when it was applied
     */
    public List<UUID> replaceAll(Map<UUID, ShipLedger.Entry> live, List<TransitRecord> inFlight,
                                 Map<String, Long> visits) {
        List<TransitRecord> records = new ArrayList<>();
        Set<String> carried = new HashSet<>();
        if (inFlight != null) {
            for (TransitRecord r : inFlight) {
                if (r == null) {
                    continue;
                }
                records.add(r);
                if (r.shipId != null) {
                    carried.add(r.shipId);
                }
            }
        }
        Map<UUID, ShipLedger.Entry> settled = new HashMap<>();
        List<UUID> dropped = new ArrayList<>();
        if (live != null) {
            for (Map.Entry<UUID, ShipLedger.Entry> e : live.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                if (e.getValue().state == ShipLedger.State.SETTLED) {
                    settled.put(e.getKey(), e.getValue());
                } else if (!carried.contains(e.getKey().toString())) {
                    dropped.add(e.getKey());
                }
            }
        }
        if (!dropped.isEmpty()) {
            return dropped;
        }
        entries.clear();
        entries.putAll(settled);
        transits.clear();
        transits.addAll(records);
        cellVisits.clear();
        if (visits != null) {
            cellVisits.putAll(visits);
        }
        markDirty();
        return Collections.emptyList();
    }

    /**
     * Populate the live ledger with the persisted SETTLED entries — called once, after the worlds are
     * up (server-started), so the server's knowledge of every settled ship survives a restart.
     */
    public void loadInto(ShipLedger live) {
        for (Map.Entry<UUID, ShipLedger.Entry> e : entries.entrySet()) {
            ShipLedger.Entry en = e.getValue();
            live.settle(e.getKey(), en.coord);
        }
    }

    /** The persisted in-flight transit records (a copy). Empty until a jump is in flight at a save point. */
    public List<TransitRecord> loadTransits() {
        return new ArrayList<>(transits);
    }

    /**
     * The persisted per-cell last-visit times (a copy). Without these every cell looks freshly visited
     * after a restart and age-based GC never fires.
     */
    public Map<String, Long> loadVisits() {
        return new HashMap<>(cellVisits);
    }

    /** Test/diagnostic view of the persisted snapshot. */
    public Map<UUID, ShipLedger.Entry> snapshot() {
        return new HashMap<>(entries);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        entries.clear();
        NBTTagList list = nbt.getTagList("ships", 10); // 10 = NBTTagCompound
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            UUID id;
            try {
                id = UUID.fromString(c.getString("shipId"));
            } catch (IllegalArgumentException bad) {
                continue; // corrupt id: drop the entry rather than crash the load
            }
            GalacticCoord coord = GalacticCoord.readFromNBT(c); // reads the "galacticCoord" sub-tag
            entries.put(id, new ShipLedger.Entry(coord, ShipLedger.State.SETTLED));
        }
        transits.clear();
        NBTTagList transitList = nbt.getTagList("transits", 10);
        for (int i = 0; i < transitList.tagCount(); i++) {
            transits.add(TransitRecord.readFromNBT(transitList.getCompoundTagAt(i)));
        }
        cellVisits.clear();
        NBTTagList visitList = nbt.getTagList("cellVisits", 10);
        for (int i = 0; i < visitList.tagCount(); i++) {
            NBTTagCompound c = visitList.getCompoundTagAt(i);
            String key = c.getString("cell");
            if (!key.isEmpty()) {
                cellVisits.put(key, c.getLong("visit"));
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("version", NBT_VERSION);
        NBTTagList list = new NBTTagList();
        for (Map.Entry<UUID, ShipLedger.Entry> e : entries.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("shipId", e.getKey().toString());
            e.getValue().coord.writeToNBT(c); // writes the "galacticCoord" sub-tag
            list.appendTag(c);
        }
        nbt.setTag("ships", list);

        NBTTagList transitList = new NBTTagList();
        for (TransitRecord r : transits) {
            transitList.appendTag(r.writeToNBT());
        }
        nbt.setTag("transits", transitList);

        NBTTagList visitList = new NBTTagList();
        for (Map.Entry<String, Long> e : cellVisits.entrySet()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("cell", e.getKey());
            c.setLong("visit", e.getValue());
            visitList.appendTag(c);
        }
        nbt.setTag("cellVisits", visitList);
        return nbt;
    }
}
