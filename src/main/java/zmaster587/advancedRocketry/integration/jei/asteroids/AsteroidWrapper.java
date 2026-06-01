package zmaster587.advancedRocketry.integration.jei.asteroids;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;
import zmaster587.advancedRocketry.item.ItemAsteroidChip;
import zmaster587.advancedRocketry.util.Asteroid;

import java.util.*;

public class AsteroidWrapper implements IRecipeWrapper {

    // Static grid: 6x2
    public static final int COLS = 6;
    public static final int ROWS = 2;
    public static final int PAGE_SIZE = COLS * ROWS; // 12

    private final String asteroidKey;
    private final Asteroid asteroid;

    private final int pageIndex;   // 0-based
    private final int pageCount;   // >= 1

    private final ItemStack observatory;
    private final ItemStack chip;

    private final List<ItemStack> outputsVisible; // <= 12 (already sliced)

    public AsteroidWrapper(String asteroidKey, Asteroid asteroid, int pageIndex, int pageCount, List<ItemStack> outputsVisible) {
        this.asteroidKey = asteroidKey;
        this.asteroid = asteroid;
        this.pageIndex = Math.max(0, pageIndex);
        this.pageCount = Math.max(1, pageCount);

        this.observatory = new ItemStack(AdvancedRocketryBlocks.blockObservatory);
        this.chip = makeDisplayChip(asteroidKey);

        // Detach from subList backing
        this.outputsVisible = (outputsVisible == null) ? Collections.emptyList() : new ArrayList<>(outputsVisible);
    }

    public boolean isValid() {
        return asteroid != null;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public String getDisplayName() {
        try {
            String n = asteroid.getName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {}
        return (asteroidKey != null && !asteroidKey.isEmpty()) ? asteroidKey : "Asteroid";
    }

    public String getHeaderText() {
        String name = getDisplayName();
        if (pageCount > 1) {
            name += " (" + (pageIndex + 1) + "/" + pageCount + ")";
        }
        return name;
    }

    @Override
    public void getIngredients(IIngredients ing) {
        List<List<ItemStack>> inputs = new ArrayList<>(2);
        inputs.add(Collections.singletonList(observatory));
        inputs.add(Collections.singletonList(chip));
        ing.setInputLists(mezz.jei.api.ingredients.VanillaTypes.ITEM, inputs);

        ing.setOutputLists(mezz.jei.api.ingredients.VanillaTypes.ITEM,
                Collections.singletonList(outputsVisible)
        );
    }

    @Override
    public void drawInfo(Minecraft mc, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        if (mc == null || mc.fontRenderer == null) return;

        // Draw header per-recipe (safe; wrapper is per recipe instance)
        String header = getHeaderText();
        if (header != null && !header.isEmpty()) {
            GlStateManager.color(1f, 1f, 1f, 1f);
            mc.fontRenderer.drawString(header, 6, 2, 0x404040);
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }

    private static ItemStack makeDisplayChip(String type) {
        ItemStack stack = new ItemStack(AdvancedRocketryItems.itemAsteroidChip);
        if (stack.getItem() instanceof ItemAsteroidChip) {
            ItemAsteroidChip chip = (ItemAsteroidChip) stack.getItem();
            chip.setUUID(stack, 0L);
            chip.setType(stack, type != null ? type : "");
            chip.setMaxData(stack, 1000);
        }
        return stack;
    }

    // Option A: deterministic “sane correct view”
    public static List<ItemStack> collectOutputsFromConfig(Asteroid asteroid) {
        if (asteroid == null || asteroid.itemStacks == null) return Collections.emptyList();

        LinkedHashMap<String, ItemStack> seen = new LinkedHashMap<>();

        for (ItemStack s : asteroid.itemStacks) {
            if (s == null || s.isEmpty()) continue;
            if (s.getItem() == null || s.getItem().getRegistryName() == null) continue;

            ItemStack one = s.copy();
            one.setCount(1);

            String key = String.valueOf(one.getItem().getRegistryName()) + "@" + one.getMetadata();
            if (!seen.containsKey(key)) {
                seen.put(key, one);
            }
        }
        return new ArrayList<>(seen.values());
    }
}
