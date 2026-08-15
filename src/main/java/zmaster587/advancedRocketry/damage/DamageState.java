package zmaster587.advancedRocketry.damage;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.capability.CapabilityWear;
import zmaster587.advancedRocketry.api.capability.IPartWear;

/**
 * The one place that answers "how damaged is the block at this position", whichever of the two homes
 * its stage lives in: a tile entity that carries wear, or the per-world damage map for everything
 * else. Damage and wear are the same axis on purpose — a part worn by use and a part hit by a shot are
 * degraded in one counter, so there is one repair and one consequence formula rather than two that
 * disagree.
 *
 * <p>Consumers and rendering read through here and never touch either home directly; that is what
 * keeps the split an implementation detail rather than a thing every call site has to know.</p>
 */
public final class DamageState {

    /** Stage of a block with no tile of its own. Blocks are not infinitely damageable; this is the cap. */
    public static final int DEFAULT_MAX_STAGE = 4;

    private DamageState() {
    }

    /** Current stage at {@code pos}: 0 = pristine, {@link #getMaxStage} = destroyed. */
    public static int getStage(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return 0;
        }
        IPartWear wear = wearAt(world, pos);
        if (wear != null) {
            return wear.getStage();
        }
        return world.isRemote ? 0 : BlockDamageSavedData.get(world).getStage(pos);
    }

    /** The stage at which the block at {@code pos} is destroyed. */
    public static int getMaxStage(World world, BlockPos pos) {
        IPartWear wear = wearAt(world, pos);
        return wear != null ? wear.getMaxStage() : DEFAULT_MAX_STAGE;
    }

    /**
     * Write a stage back to whichever home owns it. Server side only — the client is told about damage
     * through the block's own sync, never by writing a stage of its own.
     */
    public static void setStage(World world, BlockPos pos, int stage) {
        if (world == null || pos == null || world.isRemote) {
            return;
        }
        IPartWear wear = wearAt(world, pos);
        if (wear != null) {
            wear.setStage(stage);
            TileEntity te = world.getTileEntity(pos);
            if (te != null) {
                te.markDirty();
            }
            return;
        }
        BlockDamageSavedData.get(world).setStage(pos, stage);
    }

    /**
     * A block was RELOCATED from {@code from} to {@code to} in the same world: carry its damage with
     * it. The seam every block-moving mechanism owes this map, and the reason it must exist is that
     * the map is keyed by position while the thing it describes is a block — move one without the
     * other and the damage is either lost (a free repair) or inherited by an innocent block.
     *
     * <p>A block that carries its own wear needs nothing here: its stage lives in its tile, and a
     * relocation that does not carry tile state has lost far more than a crack. The call is still
     * correct for it — clearing whatever the destination's map said is exactly right.</p>
     */
    public static void blockMoved(World world, BlockPos from, BlockPos to) {
        if (world == null || world.isRemote || from == null || to == null) {
            return;
        }
        BlockDamageSavedData.get(world).move(from, to);
    }

    /**
     * Carry the records of positions that hold no block — the HOLES a weapon left in a structure —
     * from the region {@code min..max} to the same region {@code offset} away.
     *
     * <p>{@link #blockMoved} covers everything a relocation enumerates, and a relocation enumerates
     * blocks. A destroyed position has none: what it keeps is the note of what used to stand there,
     * which is what lets a repair put the right block back. Without this the note stays on the empty
     * ground the structure left, and a hull that crossed can be patched only with guesses.</p>
     *
     * <p>Call AFTER the per-block moves, so that what is left in the region is holes rather than
     * blocks already carried. A position still holding a block belongs to something that was not
     * relocated — a neighbour standing inside the same box — and is deliberately left alone.</p>
     */
    public static void holesMoved(World world, BlockPos min, BlockPos max, BlockPos offset) {
        if (world == null || world.isRemote || min == null || max == null || offset == null) {
            return;
        }
        BlockDamageSavedData data = BlockDamageSavedData.get(world);
        for (BlockPos pos : data.positionsIn(min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ())) {
            if (world.isAirBlock(pos)) {
                data.move(pos, pos.add(offset));
            }
        }
    }

    /** True when this position is at its terminal stage — the block is gone, not merely cracked. */
    public static boolean isDestroyed(World world, BlockPos pos) {
        return getStage(world, pos) >= getMaxStage(world, pos);
    }

    private static IPartWear wearAt(World world, BlockPos pos) {
        if (world == null || pos == null || !world.isBlockLoaded(pos)) {
            return null;
        }
        return CapabilityWear.get(world.getTileEntity(pos));
    }
}
