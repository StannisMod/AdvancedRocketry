package zmaster587.advancedRocketry.damage;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Damage stages of blocks that cannot hold their own, stored per world.
 *
 * <p>A tile entity able to carry a wear stage keeps it, as it always has. Everything else — plain
 * hull, plating, a wall — has nowhere to put a stage, and giving every damaged block a tile entity is
 * not available: vanilla stores a tile only when the block itself declares one, so an injected tile is
 * dropped on the floor. Hence this map.</p>
 *
 * <h3>Shape</h3>
 * <p>Keyed by the packed {@link BlockPos} long, because the access pattern that matters is a turret
 * burst: many cheap reads against blocks that are mostly pristine. A miss must therefore cost nothing,
 * which is why nothing here builds an object to answer "no damage".</p>
 *
 * <h3>Provenance</h3>
 * <p>When a block is destroyed its original state is recorded, so a repair can put back what was
 * there rather than a guess. It is stored as a registry name plus metadata rather than a numeric
 * state id: ids are an install-local encoding and a save that outlives one registry order would
 * otherwise rebuild a hull out of whatever now occupies that number.</p>
 *
 * <h3>This store is per WORLD, and a structure can leave its world</h3>
 * <p>Entries are keyed by position in the world the blocks currently occupy, so they are stable for
 * as long as the blocks are: a ship moves by transform, not by moving its blocks. A structure that is
 * relocated IS re-pasted at fresh coordinates, and there the entries are carried by
 * {@link DamageLayer} — harvested into the capture, expressed as offsets, and replayed at the far
 * end. No block owns the map, so no block can be broken to reset it.</p>
 */
public class BlockDamageSavedData extends WorldSavedData {

    public static final String DATA_NAME = "advancedRocketryBlockDamage";

    private final Map<Long, Entry> entries = new HashMap<>();

    public BlockDamageSavedData() {
        super(DATA_NAME);
    }

    public BlockDamageSavedData(String name) {
        super(name);
    }

    /** The damage map of THIS world (not a global one — a position means nothing without its world). */
    public static BlockDamageSavedData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        BlockDamageSavedData data =
                (BlockDamageSavedData) storage.getOrLoadData(BlockDamageSavedData.class, DATA_NAME);
        if (data == null) {
            data = new BlockDamageSavedData();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /** Current stage at {@code pos}, or 0 when this position has never been damaged. */
    public int getStage(BlockPos pos) {
        Entry entry = entries.get(pos.toLong());
        return entry == null ? 0 : entry.stage;
    }

    /** Record a new stage. A stage of 0 clears the entry rather than storing "undamaged". */
    public void setStage(BlockPos pos, int stage) {
        long key = pos.toLong();
        if (stage <= 0) {
            if (entries.remove(key) != null) {
                markDirty();
            }
            return;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry();
            entries.put(key, entry);
        }
        entry.stage = stage;
        markDirty();
    }

    /**
     * Record what stood at {@code pos} before it was destroyed. Called with the state as it was, at
     * the moment it stops being readable from the world.
     */
    public void recordDestroyed(BlockPos pos, Block block, int meta) {
        if (block == null || block.getRegistryName() == null) {
            return;
        }
        long key = pos.toLong();
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry();
            entries.put(key, entry);
        }
        entry.originalBlock = block.getRegistryName().toString();
        entry.originalMeta = meta;
        markDirty();
    }

    /** Registry name of what was destroyed here, or null if nothing was. */
    public String getDestroyedBlockName(BlockPos pos) {
        Entry entry = entries.get(pos.toLong());
        return entry == null ? null : entry.originalBlock;
    }

    /** Metadata of what was destroyed here; meaningless unless {@link #getDestroyedBlockName} is set. */
    public int getDestroyedMeta(BlockPos pos) {
        Entry entry = entries.get(pos.toLong());
        return entry == null ? 0 : entry.originalMeta;
    }

    /** Forget this position entirely — what a completed repair does. */
    public void clear(BlockPos pos) {
        if (entries.remove(pos.toLong()) != null) {
            markDirty();
        }
    }

    /**
     * Give {@code to} exactly what {@code from} had, and leave {@code from} with nothing — for a block
     * that was relocated rather than repaired or rebuilt.
     *
     * <p>"Exactly what it had" includes having had NOTHING: an undamaged block arriving at a position
     * some earlier structure left a record at must read as undamaged, so the absent case clears the
     * destination instead of returning early. Neither argument is retained, so a caller may pass the
     * mutable cursor it is iterating with.</p>
     */
    public void move(BlockPos from, BlockPos to) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        Entry entry = entries.remove(from.toLong());
        if (entry == null) {
            clear(to);
            return;
        }
        entries.put(to.toLong(), entry);
        markDirty();
    }

    /** How many positions this world currently holds damage for (diagnostics and tests). */
    public int size() {
        return entries.size();
    }

    /**
     * Every damaged position inside the inclusive box. Walks the entries rather than the volume: a
     * capture box is tens of thousands of positions and almost none of them are damaged, so the cost
     * belongs to what is recorded, not to how big the structure is.
     */
    public List<BlockPos> positionsIn(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        List<BlockPos> found = new ArrayList<>();
        for (Long key : entries.keySet()) {
            BlockPos pos = BlockPos.fromLong(key);
            if (pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                found.add(pos);
            }
        }
        return found;
    }

    /**
     * Forget every position inside the inclusive box — what a relocation's CUT does to the region it
     * empties. Without it the vacated coordinates keep their damage, and the next structure pasted
     * over them inherits somebody else's holes.
     */
    public void clearBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (BlockPos pos : positionsIn(minX, minY, minZ, maxX, maxY, maxZ)) {
            clear(pos);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        entries.clear();
        NBTTagList list = nbt.getTagList("entries", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            Entry entry = new Entry();
            entry.stage = tag.getInteger("stage");
            if (tag.hasKey("block")) {
                entry.originalBlock = tag.getString("block");
                entry.originalMeta = tag.getInteger("meta");
            }
            entries.put(tag.getLong("pos"), entry);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Long, Entry> mapEntry : entries.entrySet()) {
            Entry entry = mapEntry.getValue();
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("pos", mapEntry.getKey());
            tag.setInteger("stage", entry.stage);
            if (entry.originalBlock != null) {
                tag.setString("block", entry.originalBlock);
                tag.setInteger("meta", entry.originalMeta);
            }
            list.appendTag(tag);
        }
        nbt.setTag("entries", list);
        return nbt;
    }

    /** Resolve a recorded provenance name back to a block, or null if that block is no longer present. */
    public static Block blockFromName(String registryName) {
        return registryName == null ? null : Block.REGISTRY.getObject(new ResourceLocation(registryName));
    }

    private static final class Entry {
        private int stage;
        private String originalBlock;
        private int originalMeta;
    }
}
