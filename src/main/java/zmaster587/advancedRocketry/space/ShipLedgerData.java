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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable backing for the wave-1 {@link ShipLedger}: persists, per ship UUID, WHERE a settled tier-2
 * ship is (its {@link GalacticCoord} + the cell + the slot dim it was bound to). Slot dim ids are
 * transient (minted in registration order each start, they shift when planets are added/removed), so
 * only the {@code GalacticCoord} is meaningful across a restart — the login restore re-materializes
 * the cell and ignores MC's stale slot-dim placement.
 *
 * <p>Hosting + accessor mirror {@code UniverseRegistry} exactly: a {@link WorldSavedData} on the
 * overworld global {@link MapStorage}, server-side only, persisted by MC whenever it is {@code
 * markDirty()}-ed. This first increment persists SETTLED entries only; in-transit ships need the
 * transit record + block snapshot (a later phase) and are skipped.</p>
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
     * Replace the persisted snapshot from the live ledger (SETTLED entries only) and mark dirty so MC
     * writes it on the next world save. Called at save points; a no-op-safe full re-snapshot.
     */
    public void saveFrom(ShipLedger live) {
        entries.clear();
        for (Map.Entry<UUID, ShipLedger.Entry> e : live.snapshot().entrySet()) {
            if (e.getValue().state == ShipLedger.State.SETTLED) {
                entries.put(e.getKey(), e.getValue());
            }
        }
        markDirty();
    }

    /**
     * Populate the live ledger with the persisted SETTLED entries — called once, after the worlds are
     * up (server-started), so the server's knowledge of every settled ship survives a restart.
     */
    public void loadInto(ShipLedger live) {
        for (Map.Entry<UUID, ShipLedger.Entry> e : entries.entrySet()) {
            ShipLedger.Entry en = e.getValue();
            live.settle(e.getKey(), en.coord, en.slotDim);
        }
    }

    /** Replace the persisted transit records (called at the same save point as {@link #saveFrom}). */
    public void saveTransits(List<TransitRecord> records) {
        transits.clear();
        if (records != null) {
            transits.addAll(records);
        }
        markDirty();
    }

    /** The persisted in-flight transit records (a copy). Empty until a jump is in flight at a save point. */
    public List<TransitRecord> loadTransits() {
        return new ArrayList<>(transits);
    }

    /**
     * Replace the persisted per-cell last-visit times (same save point as {@link #saveFrom}). Without
     * these every cell looks freshly visited after a restart and age-based GC never fires.
     */
    public void saveVisits(Map<String, Long> visits) {
        cellVisits.clear();
        if (visits != null) {
            cellVisits.putAll(visits);
        }
        markDirty();
    }

    /** The persisted per-cell last-visit times (a copy). */
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
            int slotDim = c.getInteger("slotDim");
            entries.put(id, new ShipLedger.Entry(coord, ShipLedger.State.SETTLED, slotDim));
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
            c.setInteger("slotDim", e.getValue().slotDim);
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
