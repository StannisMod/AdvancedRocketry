package zmaster587.advancedRocketry.api.weapon;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A block that is worth something when it is part of a gun.
 *
 * <h3>Implemented by the BLOCK, deliberately</h3>
 * <p>Parts have no state of their own — a barrel section is a barrel section wherever it is — so
 * asking the block rather than a tile entity means a hundred-block battery costs a hundred block
 * lookups and no tile entities at all. It also means an addon adds a gun part by implementing one
 * interface on a plain block, with nothing to register.</p>
 *
 * <h3>What a part may say</h3>
 * <p>Only what it contributes. A part is never asked whether the gun is complete, never told what
 * else is in the build, and never given the chance to veto: completeness is
 * {@link GunSpec#isOperable()}, decided once, on the sum. That is what keeps a part addable without
 * the parts already there having to agree.</p>
 */
public interface IGunPart {

    /**
     * Add this part's contribution to the gun being assembled.
     *
     * @param builder the accumulating spec; a part adds, and never resets what others contributed
     * @param world   the world the assembly is being walked in
     * @param pos     where this part sits — a part whose contribution depends on its own placement
     *                (a muzzle brake that only counts at the end of a barrel) reads it here
     * @param state   the part's own block state, so a part with variants need not look itself up
     */
    void contributeTo(GunSpec.Builder builder, World world, BlockPos pos, IBlockState state);

    /**
     * Whether the assembly walk may continue THROUGH this part to its neighbours.
     *
     * <p>Almost every part conducts: a gun is a connected run of parts and the walk has to reach
     * the far end of the barrel. A part that answers false is a terminator — an end cap, a breech —
     * and is counted itself without the walk spilling past it.</p>
     */
    default boolean conductsAssembly() {
        return true;
    }
}
