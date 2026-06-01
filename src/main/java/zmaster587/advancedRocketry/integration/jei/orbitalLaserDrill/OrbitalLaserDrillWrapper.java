package zmaster587.advancedRocketry.integration.jei.orbitalLaserDrill;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.dimension.DimensionProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class OrbitalLaserDrillWrapper implements IRecipeWrapper {

    public static final int GRID = 6;
    public static final int PAGE_SIZE = GRID * GRID; // 36

    private final ItemStack machine;
    private final List<ItemStack> outputsPage;

    private final int pageIndex; // 0-based
    private final int pageCount; // >= 1

    public OrbitalLaserDrillWrapper(int pageIndex, int pageCount, List<ItemStack> outputsPage) {
        this.machine = new ItemStack(AdvancedRocketryBlocks.blockSpaceLaser);
        this.pageIndex = pageIndex;
        this.pageCount = Math.max(1, pageCount);
        this.outputsPage = outputsPage == null ? Collections.emptyList() : new ArrayList<>(outputsPage);
    }

    public String getHeaderText() {
        String name = machine.getDisplayName();
        if (pageCount > 1) {
            name += " (" + (pageIndex + 1) + "/" + pageCount + ")";
        }
        return name;
    }

    @Override
    public void getIngredients(IIngredients ing) {
        // Input: machine block (discoverability)
        List<List<ItemStack>> inputs = new ArrayList<>(1);
        inputs.add(Collections.singletonList(machine));
        ing.setInputLists(mezz.jei.api.ingredients.VanillaTypes.ITEM, inputs);

        // Output: one list shown in the grid
        ing.setOutputLists(mezz.jei.api.ingredients.VanillaTypes.ITEM,
                Collections.singletonList(outputsPage)
        );
    }

    // ---- shared builder used by the RecipeMaker ----

    public static List<ItemStack> buildVoidDrillActivationList(DimensionProperties dimPropsOrNull) {
        List<ItemStack> ores = new ArrayList<>();
        HashSet<String> seenKeys = new HashSet<>();

        // 1) Global list from config
        List<String> configOres = ARConfiguration.getCurrentConfig().standardLaserDrillOres;
        if (configOres != null) {
            for (String oreDictName : configOres) {
                ItemStack stack = parseConfigEntryToStack(oreDictName);
                if (!stack.isEmpty()) {
                    String key = key(stack);
                    if (seenKeys.add(key)) ores.add(stack);
                }
            }
        }

        // 2) Dimension-specific additions (matches VoidDrill.activate behavior)
        if (dimPropsOrNull != null && dimPropsOrNull.laserDrillOres != null) {
            for (ItemStack s : dimPropsOrNull.laserDrillOres) {
                if (s == null || s.isEmpty()) continue;
                ItemStack copy = s.copy();
                String key = key(copy);
                if (seenKeys.add(key)) ores.add(copy);
            }
        }

        return ores;
    }

    private static ItemStack parseConfigEntryToStack(String oreDictName) {
        if (oreDictName == null || oreDictName.isEmpty()) return ItemStack.EMPTY;

        String[] args = oreDictName.split(":");

        // OreDict first: "oreIron:2"
        List<ItemStack> globalOres = OreDictionary.getOres(args[0]);
        if (globalOres != null && !globalOres.isEmpty()) {
            int amt = 1;
            if (args.length > 1) {
                try { amt = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            ItemStack base = globalOres.get(0);
            return new ItemStack(base.getItem(), amt, base.getItemDamage());
        }

        // Fallback: "modid:blockname[:meta[:size]]"
        String name;
        try {
            name = args[0] + ":" + args[1];
        } catch (IndexOutOfBoundsException e) {
            return ItemStack.EMPTY;
        }

        int meta = 0;
        int size = 1;

        if (args.length > 2) {
            try { meta = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
        }
        if (args.length > 3) {
            try { size = Integer.parseInt(args[3]); } catch (NumberFormatException ignored) {}
        }

        Block block = Block.getBlockFromName(name);
        if (block != null && block != Blocks.AIR) {
            return new ItemStack(block, size, meta);
        }

        Item item = Item.getByNameOrId(name);
        if (item != null) {
            return new ItemStack(item, size, meta);
        }

        return ItemStack.EMPTY;
    }

    private static String key(ItemStack s) {
        return s.getItem().getRegistryName() + "@" + s.getItemDamage() + "x" + s.getCount();
    }
}
