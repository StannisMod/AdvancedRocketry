package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.util.WeightEngine;

/**
 * The structural mass of one block, in kilograms, for the physics engine to accumulate.
 *
 * <h2>Why this exists at all, and why it applies to EVERY craft</h2>
 *
 * <p>The engine decides a block's mass in exactly one place, from a flat table keyed on the block
 * alone: a full fuel tank masses what an empty one does, and a blockstate carries no information.
 * AR already owns a per-block table denominated in kilograms that the rocket tier has used for years,
 * and it is strictly better informed. So this replaces that one lookup rather than reimplementing the
 * accumulation around it — the smearing that keeps a tensor invertible and the parallel-axis
 * bookkeeping that follows a moving centre of mass are the engine's, are correct, and stay.</p>
 *
 * <p>The rule applies to every physics object, not only to craft AR assembled. A mass model that
 * answered differently depending on who built the hull would be two mass models, and the point is
 * that there is one. It costs nothing worth keeping: the only force-providing block in the tree
 * computes its force by multiplying BY the craft's mass, so it is invariant under any mass model.</p>
 *
 * <h2>What is NOT answered here</h2>
 *
 * <p>Tile contents — fluids and inventories — cannot be seen from a blockstate, and a blockstate is
 * all this receives. Sampling them belongs to the cadence that runs for craft which have a flight
 * computer, so a foreign hull gets honest structural mass without paying for inventory tracking.
 * Crew likewise: a body is not a block.</p>
 */
public final class ArBlockMass {

    private ArBlockMass() {}

    /**
     * Mass of one block of {@code state}, kilograms.
     *
     * <p>Air and liquids mass nothing. A liquid's mass belongs to the tank that holds it, and counting
     * the liquid block here as well would count it twice.</p>
     *
     * <p>A block with no item form — a technical block, a multiblock filler — cannot be looked up in a
     * table keyed by registry name, so it takes the table's own fallback. That keeps the answer inside
     * ONE model instead of falling back to the engine's, which would reintroduce the second table this
     * class exists to remove.</p>
     */
    public static double of(IBlockState state) {
        if (state == null || state.getBlock() == null
                || state.getBlock() == Blocks.AIR
                || state.getBlock() instanceof BlockLiquid) {
            return 0.0;
        }
        ItemStack asItem = new ItemStack(state.getBlock());
        if (asItem.isEmpty()) {
            return WeightEngine.INSTANCE.fallbackMass();
        }
        return WeightEngine.INSTANCE.getWeight(asItem);
    }
}
