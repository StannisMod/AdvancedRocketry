package zmaster587.advancedRocketry.navigation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * The addresses one memory crystal holds — the tier-2 ship's entire galactic knowledge, in an item.
 *
 * <p><b>Capacity is unlimited by design.</b> A crystal is not a flash drive: its recording density is
 * enormous, so the copy and erase operations exist for duplication and exchange between players and
 * ships, never for capacity management. (The platform's item-NBT wire limit is the only ceiling, and it
 * sits tens of thousands of entries above realistic play.)</p>
 *
 * <p><b>One entry per BODY.</b> The store is keyed by {@link CrystalEntry#identityKey()} — the body's
 * dimension where there is one, the coordinate otherwise — so copying a crystal into another can never
 * duplicate a destination; where both sides know one, the records are merged by FRESHNESS
 * ({@link CrystalEntry#mergeWith}). Keying by coordinate instead would give a planet one entry per
 * observation, because a planet moves. Copy is <b>add-only</b>: it never removes an address from either
 * side, and it never replaces a newer observation with an older one.</p>
 *
 * <p>Insertion order is preserved so the nav GUI lists addresses in the order they were learned.</p>
 */
public final class CrystalMemory {

    /** The item-NBT key the whole address list lives under. Same-version wire/save contract. */
    public static final String NBT_KEY = "navAddresses";

    private final Map<String, CrystalEntry> entries = new LinkedHashMap<>();

    /** An empty crystal — a blank, never a broken one. */
    public CrystalMemory() {
    }

    /**
     * Record {@code entry}, merging by freshness against any record this crystal already holds for the
     * same body. Returns {@code true} when the crystal changed (a new body, or a fresher record of a
     * known one) — a caller writing back to an item can skip the write when nothing moved.
     */
    public boolean record(CrystalEntry entry) {
        if (entry == null) {
            return false;
        }
        String key = entry.identityKey();
        CrystalEntry known = entries.get(key);
        if (known == null) {
            entries.put(key, entry);
            return true;
        }
        CrystalEntry merged = known.mergeWith(entry);
        if (merged.equals(known)) {
            return false;
        }
        entries.put(key, merged);
        return true;
    }

    /**
     * Copy every address of {@code source} into this crystal — ADD-ONLY: this crystal keeps everything
     * it already knew, gains what it did not, and keeps the fresher record wherever both knew the same
     * address. {@code source} is never modified. Returns the number of addresses this crystal changed.
     */
    public int copyFrom(CrystalMemory source) {
        if (source == null || source == this) {
            return 0;
        }
        int changed = 0;
        for (CrystalEntry e : source.entries.values()) {
            if (record(e)) {
                changed++;
            }
        }
        return changed;
    }

    /** Forget one address, by the coordinate it was last observed at. Returns {@code true} if known. */
    public boolean erase(GalacticCoord coord) {
        CrystalEntry found = get(coord);
        return found != null && entries.remove(found.identityKey()) != null;
    }

    /** Forget every address — a blanked crystal, ready to be written again. */
    public void eraseAll() {
        entries.clear();
    }

    /** The record whose OBSERVED coordinate is {@code coord}, or {@code null}. */
    public CrystalEntry get(GalacticCoord coord) {
        if (coord == null) {
            return null;
        }
        for (CrystalEntry e : entries.values()) {
            if (coord.equals(e.coord())) {
                return e;
            }
        }
        return null;
    }

    /** The record for the body in dimension {@code dimId}, or {@code null}. */
    public CrystalEntry forBody(int dimId) {
        return entries.get(CrystalEntry.bodyIdentityKey(dimId));
    }

    public boolean knows(GalacticCoord coord) {
        return get(coord) != null;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Every address, in the order it was learned. Unmodifiable. */
    public List<CrystalEntry> list() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    /** Write the address list into {@code nbt} under {@link #NBT_KEY}. */
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (CrystalEntry e : entries.values()) {
            list.appendTag(e.writeToNBT());
        }
        nbt.setTag(NBT_KEY, list);
    }

    /**
     * Read a crystal written by {@link #writeToNBT}. A missing tag reads as an empty crystal (a freshly
     * crafted one has no NBT at all), and an entry that cannot be read is skipped rather than failing
     * the whole crystal — one corrupt address must not cost a pilot every other one.
     */
    public static CrystalMemory readFromNBT(NBTTagCompound nbt) {
        CrystalMemory memory = new CrystalMemory();
        if (nbt == null || !nbt.hasKey(NBT_KEY)) {
            return memory;
        }
        NBTTagList list = nbt.getTagList(NBT_KEY, 10); // 10 = NBTTagCompound
        for (int i = 0; i < list.tagCount(); i++) {
            CrystalEntry entry = CrystalEntry.readFromNBT(list.getCompoundTagAt(i));
            if (entry != null) {
                memory.record(entry);
            }
        }
        return memory;
    }

    /** A crystal holding exactly {@code seed} — the shape a pre-seeded starter crystal is built with. */
    public static CrystalMemory of(Collection<CrystalEntry> seed) {
        CrystalMemory memory = new CrystalMemory();
        if (seed != null) {
            for (CrystalEntry e : seed) {
                memory.record(e);
            }
        }
        return memory;
    }
}
