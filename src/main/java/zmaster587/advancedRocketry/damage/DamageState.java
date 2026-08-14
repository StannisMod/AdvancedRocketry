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
