package zmaster587.advancedRocketry.damage;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Keeps the damage map honest about the blocks a PLAYER changes under it.
 *
 * <p>Stages of plain blocks are stored by position, and a position outlives the block that was
 * standing there. Nothing else notices a player's pickaxe: the damage engine writes through
 * {@code setBlockState}, which fires neither of these events, and neither does a relocation's cut.
 * So without this handler a record simply stays behind, and it is wrong in both directions —
 * a freshly placed block reads as cracked (or as destroyed, if the record said so), and the crack
 * a player mined out is still counted against the hull he just repaired.</p>
 *
 * <p>Clearing here also decides what hand-repair COSTS: replacing a damaged block is a real repair,
 * paid for with the block itself, rather than an accounting trick that leaves the hull recorded as
 * broken. The machine-driven repair path is a separate mechanic and is not affected.</p>
 *
 * <p>Lowest priority so that a break or place another handler vetoes never clears anything: a
 * cancelled event is not delivered here at all, and running last means the veto has already
 * happened.</p>
 */
public class DamageInvalidationHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBroken(BlockEvent.BreakEvent event) {
        forget(event.getWorld(), event.getPos());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlaced(BlockEvent.PlaceEvent event) {
        forget(event.getWorld(), event.getPos());
    }

    private static void forget(World world, BlockPos pos) {
        if (world == null || pos == null || world.isRemote) {
            return;
        }
        BlockDamageSavedData.get(world).clear(pos);
    }
}
