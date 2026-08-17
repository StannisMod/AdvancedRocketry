package zmaster587.advancedRocketry.weapon;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.weapon.GunSpec;
import zmaster587.advancedRocketry.api.weapon.IGunPart;
import zmaster587.advancedRocketry.tile.weapon.TileTurret;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Walks what was built around a gun's controller and answers what it adds up to.
 *
 * <h3>Connectivity, not a template</h3>
 * <p>A gun is the connected run of parts touching its controller — there is no fixed shape to
 * match. That is what makes the contract addable-to: a part shipped by somebody else joins a gun by
 * being placed against one, and the walk that finds it was not written knowing it exists. It also
 * means "bigger gun" is a thing a player builds rather than a tier they unlock.</p>
 *
 * <h3>Bounded on purpose</h3>
 * <p>The walk stops at {@link #MAX_PARTS}. Without a bound, a player who paves a hull in barrel
 * sections gets one gun with a five-figure part count and a rebuild that walks it every time a
 * block changes. The cap is a bound on WORK, and a build that reaches it is still a working gun —
 * it is simply not credited for parts past the limit.</p>
 */
public final class GunAssembly {

    /** The most parts one gun may be built from. */
    public static final int MAX_PARTS = 256;

    private final GunSpec spec;
    private final int reach;

    private GunAssembly(GunSpec spec, int reach) {
        this.spec = spec;
        this.reach = reach;
    }

    /** What this build is worth. Never null; an unbuilt controller answers a spec that is not operable. */
    public GunSpec getSpec() {
        return spec;
    }

    /**
     * How far the furthest part sits from the controller, in blocks along the axes. The muzzle is
     * put past this so a round is not born inside the gun that fired it — a shot spawned in its own
     * barrel would resolve a structure crossing on its first tick and destroy the weapon.
     */
    public int getReach() {
        return reach;
    }

    /**
     * Walk the assembly rooted at {@code origin} — the controller's own position, which is NOT
     * counted as a part.
     */
    public static GunAssembly scan(World world, BlockPos origin) {
        GunSpec.Builder builder = new GunSpec.Builder();
        if (world == null || origin == null) {
            return new GunAssembly(builder.build(), 0);
        }

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        visited.add(origin);
        for (EnumFacing facing : EnumFacing.VALUES) {
            frontier.add(origin.offset(facing));
        }

        int counted = 0;
        int reach = 0;
        while (!frontier.isEmpty() && counted < MAX_PARTS) {
            BlockPos pos = frontier.poll();
            if (!visited.add(pos)) {
                continue;
            }
            // An unloaded chunk is not "no part here" — it is an unknown, and generating chunks to
            // answer a per-tick question would be a far worse bargain than under-counting a gun
            // whose far end nobody is near.
            if (!world.isBlockLoaded(pos)) {
                continue;
            }
            IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (!(block instanceof IGunPart)) {
                continue;
            }
            IGunPart part = (IGunPart) block;
            part.contributeTo(builder, world, pos, state);
            builder.countPart();
            counted++;
            reach = Math.max(reach, axisDistance(origin, pos));
            if (!part.conductsAssembly()) {
                continue;
            }
            for (EnumFacing facing : EnumFacing.VALUES) {
                BlockPos next = pos.offset(facing);
                if (!visited.contains(next)) {
                    frontier.add(next);
                }
            }
        }
        return new GunAssembly(builder.build(), reach);
    }

    /**
     * A part entered or left the world at {@code changed}: mark every controller that could have been
     * counting it.
     *
     * <p>The search is the assembly walk run backwards — out through the parts still standing, looking
     * for controllers beside any of them. It has to be a walk rather than a look at the six
     * neighbours, because a barrel section added at the far end of a ten-block barrel is nowhere near
     * the controller whose numbers it changes.</p>
     *
     * <p>Note the asymmetry with a break: by the time {@code breakBlock} runs, the part is already
     * gone from the world, so the walk starts from its neighbours and the run it used to join is
     * whatever is still connected. A part whose removal SPLITS a gun in two therefore marks only the
     * halves still reachable — which is the right answer, because the other half's controller no
     * longer had it either.</p>
     */
    public static void markControllersDirty(World world, BlockPos changed) {
        if (world == null || world.isRemote || changed == null) {
            return;
        }
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> marked = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        visited.add(changed);
        frontier.add(changed);

        int walked = 0;
        while (!frontier.isEmpty() && walked < MAX_PARTS) {
            BlockPos pos = frontier.poll();
            for (EnumFacing facing : EnumFacing.VALUES) {
                BlockPos next = pos.offset(facing);
                if (!world.isBlockLoaded(next)) {
                    continue;
                }
                TileEntity tile = world.getTileEntity(next);
                if (tile instanceof TileTurret && marked.add(next)) {
                    ((TileTurret) tile).markAssemblyDirty();
                    continue;
                }
                if (!visited.add(next)) {
                    continue;
                }
                Block block = world.getBlockState(next).getBlock();
                if (block instanceof IGunPart && ((IGunPart) block).conductsAssembly()) {
                    frontier.add(next);
                    walked++;
                }
            }
        }
    }

    private static int axisDistance(BlockPos from, BlockPos to) {
        return Math.max(Math.abs(to.getX() - from.getX()),
                Math.max(Math.abs(to.getY() - from.getY()), Math.abs(to.getZ() - from.getZ())));
    }
}
