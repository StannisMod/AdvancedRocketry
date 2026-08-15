package zmaster587.advancedRocketry.damage;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.ArrayList;
import java.util.List;

/**
 * The damage a captured structure carries with it: the stages and destruction provenance of its
 * plain blocks, expressed as offsets from the capture box rather than as world coordinates.
 *
 * <h3>Why this exists at all</h3>
 * <p>A block's stage lives in one of two homes — a tile entity that can hold its own wear, or the
 * per-world {@link BlockDamageSavedData} for everything else. The tile half already travels: a
 * relocation copies tile NBT verbatim. The other half does not, because it is keyed by position in
 * the world the blocks occupy, and a relocated structure is re-pasted at fresh coordinates. Without
 * this layer a ship that jumps arrives pristine — a free repair for the price of a jump.</p>
 *
 * <h3>Why the damage is NOT hung on a block</h3>
 * <p>Handing the whole map to one tile's NBT (the flight computer, say) would make it travel for
 * free, and would also make a single breakable block the custodian of every other block's state:
 * mine that block, put it back, and the hull is whole again. Damage is a property of the structure,
 * so it moves through the channel the structure itself moves through and no block owns it.</p>
 *
 * <h3>Frame</h3>
 * <p>Offsets are relative to the minimum corner of the box that was captured — the same origin the
 * block array and the tile coordinates use — so the layer survives being pasted anywhere, in any
 * world, exactly as the blocks do. It deliberately carries destroyed positions too (air in the
 * snapshot, provenance in the entry), or a rebuild at the far end would have nothing to put back.</p>
 */
public final class DamageLayer {

    private static final String NBT_LIST = "damageLayer";

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Everything {@code world} records inside the SELECTION box, as offsets from the ORIGIN.
     *
     * <p>The two are given separately because they are genuinely different boxes, and conflating them
     * silently drops damage. A capture's origin is its tight block bounds — that is where the blocks
     * will be laid down again — but those bounds are computed from blocks that still EXIST, and the
     * interesting records are exactly the positions whose block does not. Shoot the outermost column
     * of a hull away and the tight bounds shrink inside it, leaving that column's records outside a
     * box drawn from them, while the cut that follows still clears the wider region. Selection must
     * therefore cover everything the caller is about to empty; an offset outside the block volume is
     * legitimate, and negative components are expected.</p>
     */
    public static DamageLayer harvest(World world, int minX, int minY, int minZ,
                                      int maxX, int maxY, int maxZ,
                                      int originX, int originY, int originZ) {
        if (world == null || world.isRemote) {
            return new DamageLayer();
        }
        return harvest(BlockDamageSavedData.get(world), minX, minY, minZ, maxX, maxY, maxZ,
                originX, originY, originZ);
    }

    /** The same, against the map itself — the world is only how the map is found. */
    public static DamageLayer harvest(BlockDamageSavedData data, int minX, int minY, int minZ,
                                      int maxX, int maxY, int maxZ,
                                      int originX, int originY, int originZ) {
        DamageLayer layer = new DamageLayer();
        for (BlockPos pos : data.positionsIn(minX, minY, minZ, maxX, maxY, maxZ)) {
            Entry entry = new Entry();
            entry.offset = new BlockPos(pos.getX() - originX, pos.getY() - originY, pos.getZ() - originZ);
            entry.stage = data.getStage(pos);
            entry.originalBlock = data.getDestroyedBlockName(pos);
            entry.originalMeta = data.getDestroyedMeta(pos);
            layer.entries.add(entry);
        }
        return layer;
    }

    /** True when the captured structure was pristine — the common case, and the cheap one. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** How many damaged positions this layer carries (diagnostics and tests). */
    public int size() {
        return entries.size();
    }

    /**
     * Write these entries into {@code world}'s damage map, with the layer's origin placed at
     * {@code (x,y,z)}. Callers paste blocks first: a position this layer does not mention keeps
     * whatever the destination already said about it, which is why a paste also clears the
     * positions it overwrites.
     */
    public void applyAt(World world, int x, int y, int z) {
        if (world == null || world.isRemote || entries.isEmpty()) {
            return;
        }
        applyTo(BlockDamageSavedData.get(world), x, y, z);
    }

    /** The same, against the map itself. */
    public void applyTo(BlockDamageSavedData data, int x, int y, int z) {
        for (Entry entry : entries) {
            BlockPos pos = new BlockPos(x + entry.offset.getX(),
                    y + entry.offset.getY(),
                    z + entry.offset.getZ());
            data.setStage(pos, entry.stage);
            if (entry.originalBlock != null) {
                data.recordDestroyed(pos, BlockDamageSavedData.blockFromName(entry.originalBlock),
                        entry.originalMeta);
            }
        }
    }

    /** Absent key means "this structure was captured undamaged", not a malformed tag. */
    public void writeToNBT(NBTTagCompound nbt) {
        if (entries.isEmpty()) {
            return;
        }
        NBTTagList list = new NBTTagList();
        for (Entry entry : entries) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("off", entry.offset.toLong());
            tag.setInteger("stage", entry.stage);
            if (entry.originalBlock != null) {
                tag.setString("block", entry.originalBlock);
                tag.setInteger("meta", entry.originalMeta);
            }
            list.appendTag(tag);
        }
        nbt.setTag(NBT_LIST, list);
    }

    /** The inverse of {@link #writeToNBT}; an empty layer when the tag carries none. */
    public static DamageLayer readFromNBT(NBTTagCompound nbt) {
        DamageLayer layer = new DamageLayer();
        if (nbt == null || !nbt.hasKey(NBT_LIST)) {
            return layer;
        }
        NBTTagList list = nbt.getTagList(NBT_LIST, NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            Entry entry = new Entry();
            entry.offset = BlockPos.fromLong(tag.getLong("off"));
            entry.stage = tag.getInteger("stage");
            if (tag.hasKey("block")) {
                entry.originalBlock = tag.getString("block");
                entry.originalMeta = tag.getInteger("meta");
            }
            layer.entries.add(entry);
        }
        return layer;
    }

    private static final class Entry {
        private BlockPos offset;
        private int stage;
        private String originalBlock;
        private int originalMeta;
    }
}
