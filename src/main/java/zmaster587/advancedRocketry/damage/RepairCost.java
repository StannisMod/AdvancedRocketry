package zmaster587.advancedRocketry.damage;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

import zmaster587.advancedRocketry.api.ARConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * What one stage of repair costs, in materials, and whether a player is carrying it.
 *
 * <h3>The price is the block's own recipe, scaled</h3>
 * <p>A repair is a partial rebuild, so it is priced out of what building the thing costs: the
 * ingredients of the block's crafting recipe times a per-stage fraction. That keeps repair inside the
 * material economy the ship already runs on rather than inventing a currency for it, and it scales
 * with the block automatically — a hull plate is cheap to weld and a machine is not, without anybody
 * writing a second table.</p>
 *
 * <h3>What it deliberately does NOT promise</h3>
 * <p>That welding is always the cheap option. For a plain, cheaply-crafted block, breaking it and
 * placing a fresh one is a repair too, and often a cheaper one — that is a legitimate outcome, not a
 * balance failure. Welding buys something replacement cannot: the block, and everything its tile
 * entity is holding, stays where it is. Replacing a machine to fix a crack empties it.</p>
 *
 * <h3>Blocks with no recipe</h3>
 * <p>Stone, ore, and anything else that is gathered rather than crafted has no ingredient list to
 * price against, and this refuses them rather than inventing a cost. The caller reports that as its
 * own outcome; it is not a silent no-op.</p>
 */
public final class RepairCost {

    private RepairCost() {
    }

    /**
     * The materials one stage of repair at {@code pos} costs, or {@code null} when this block cannot
     * be priced — no crafting recipe, or nothing there to repair.
     *
     * <p>Ingredient counts are rounded UP, so the cheapest possible recipe still costs one item per
     * stage: a repair is never free. Where several recipes make the same block the first registered
     * one wins, which is arbitrary but stable; a block whose recipes differ wildly in cost would need
     * a rule of its own, and none does today.</p>
     */
    public static List<ItemStack> perStage(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        IBlockState state = world.getBlockState(pos);
        ItemStack asItem = state.getBlock().getItem(world, pos, state);
        if (asItem.isEmpty()) {
            return null;
        }
        IRecipe recipe = recipeFor(asItem);
        if (recipe == null) {
            return null;
        }
        int stages = Math.max(1, DamageState.getMaxStage(world, pos));
        double fraction = ARConfiguration.getCurrentConfig().repairCostPerStageFraction / stages;

        List<ItemStack> cost = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] variants = ingredient.getMatchingStacks();
            if (variants.length == 0) {
                continue;
            }
            int needed = (int) Math.ceil(variants[0].getCount() * fraction);
            if (needed <= 0) {
                continue;
            }
            ItemStack charge = variants[0].copy();
            charge.setCount(needed);
            cost.add(charge);
        }
        return cost.isEmpty() ? null : cost;
    }

    /**
     * Take {@code cost} out of the player's inventory, or answer false having taken nothing.
     *
     * <p>Simulated first by the caller and then taken, rather than taken optimistically and refunded:
     * a partial charge for a repair that then could not happen is the shape that quietly eats
     * materials. Creative players are charged nothing, as everywhere else.</p>
     */
    public static boolean consume(EntityPlayer player, List<ItemStack> cost, boolean simulate) {
        if (player == null || cost == null) {
            return false;
        }
        if (player.capabilities.isCreativeMode) {
            return true;
        }
        InventoryPlayer inventory = player.inventory;
        // Counted against a scratch copy so a simulate never touches the real inventory and a real
        // take can still be abandoned half-way without having moved anything.
        NonNullList<ItemStack> scratch = NonNullList.withSize(inventory.getSizeInventory(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            scratch.set(i, inventory.getStackInSlot(i).copy());
        }

        for (ItemStack wanted : cost) {
            int remaining = wanted.getCount();
            for (int i = 0; i < scratch.size() && remaining > 0; i++) {
                ItemStack inSlot = scratch.get(i);
                if (inSlot.isEmpty() || !OreDictionary.itemMatches(wanted, inSlot, false)) {
                    continue;
                }
                int take = Math.min(remaining, inSlot.getCount());
                inSlot.shrink(take);
                remaining -= take;
            }
            if (remaining > 0) {
                return false;
            }
        }
        if (!simulate) {
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                inventory.setInventorySlotContents(i, scratch.get(i));
            }
        }
        return true;
    }

    /** The first registered crafting recipe whose output is this item, or null if nothing crafts it. */
    private static IRecipe recipeFor(ItemStack output) {
        for (IRecipe recipe : CraftingManager.REGISTRY) {
            ItemStack result = recipe.getRecipeOutput();
            if (!result.isEmpty() && OreDictionary.itemMatches(result, output, false)) {
                return recipe;
            }
        }
        return null;
    }
}
