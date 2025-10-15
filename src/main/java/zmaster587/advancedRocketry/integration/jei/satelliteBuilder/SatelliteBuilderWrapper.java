package zmaster587.advancedRocketry.integration.jei.satelliteBuilder;

import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.ITooltipCallback;
import mezz.jei.api.ingredients.IIngredients;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;
import zmaster587.advancedRocketry.integration.jei.MachineRecipe;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.interfaces.IRecipe;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SatelliteBuilderWrapper extends MachineRecipe {
    public final boolean isCopyRecipe;
    private final IRecipe baseRecipe;                         // keep a handle
    private final List<ItemStack> outputVariants;             // for JEI cycling (copy recipe)
    private static Set<String> COPY_STRIP_STRINGS;

    public SatelliteBuilderWrapper(IRecipe rec, boolean isCopyRecipe) {
        this(rec, isCopyRecipe, null);
    }

    public SatelliteBuilderWrapper(IRecipe rec, boolean isCopyRecipe, List<ItemStack> outputVariants) {
        super(rec);
        this.isCopyRecipe = isCopyRecipe;
        this.baseRecipe = rec;
        this.outputVariants = outputVariants;
    }

    @Override
    public void getIngredients(mezz.jei.api.ingredients.IIngredients ingredients) {
        super.getIngredients(ingredients); // inputs already mapped

        if (isCopyRecipe) {
            // ONE output slot cycling MANY variants
            List<ItemStack> variants = (outputVariants != null && !outputVariants.isEmpty())
                    ? outputVariants
                    : baseRecipe.getOutput(); // fallback if you didn’t pass variants

            if (variants != null && !variants.isEmpty()) {
                ingredients.setOutputLists(ItemStack.class,
                        java.util.Collections.singletonList(variants));
            }
        } else {
            // Assembly: single concrete output (first item)
            List<ItemStack> outs = baseRecipe.getOutput();
            if (outs != null && !outs.isEmpty()) {
                ingredients.setOutput(ItemStack.class, outs.get(0));
            }
        }
    }



    public java.util.List<ItemStack> getOutputVariants() { return outputVariants; }

    public void registerTooltipCallbacks(IGuiItemStackGroup stacks) {
        final boolean isCopy = this.isCopyRecipe;

        stacks.addTooltipCallback(new ITooltipCallback<ItemStack>() {
            @Override
            public void onTooltip(int slotIndex, boolean input, ItemStack stack, List<String> tooltip) {
                if (stack.isEmpty()) return;

                if (!isCopy) {
                    // === ASSEMBLY RECIPE === (only touch output slot)
                    if (slotIndex != 7) return;

                    if (stack.getItem() == AdvancedRocketryItems.itemSatellite) {
                        // Remove "empty chassis" (whatever the localization)
                        String libEmpty = LibVulpes.proxy.getLocalizedString("msg.itemsatellite.empty");
                        tooltip.removeIf(line -> {
                            String stripped = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(line);
                            if (stripped == null) return false;
                            String s = stripped.trim();
                            return s.equalsIgnoreCase(libEmpty) || s.equalsIgnoreCase("unprogrammed");
                        });

                        // Add clean preview label if not already present
                        String label = I18n.format("jei.sb.satellitepreview");
                        addIfMissing(tooltip, label);
                    }
                    return;
                }

                // === CHIP-COPY RECIPE ===
                if (slotIndex == 7 || slotIndex == 8) {
                    ensureCopyStripStringsBuilt();

                    // Strip any "unprogrammed"/blank-style lines (locale + color safe)
                    tooltip.removeIf(line -> {
                        String stripped = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(line);
                        return stripped != null && COPY_STRIP_STRINGS.contains(stripped.trim().toLowerCase());
                    });

                    // Add concise labels
                    final String key = (slotIndex == 7) ? "jei.sb.copy.output" : "jei.sb.copy.source";
                    String label = I18n.format(key);
                    addIfMissing(tooltip, label);
                }
            }
        });
    }

    private static void addIfMissing(List<String> tooltip, String label) {
        for (String l : tooltip) {
            String stripped = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(l);
            if (stripped != null && stripped.equalsIgnoreCase(label)) return;
        }
        tooltip.add(label);
    }

    private static void ensureCopyStripStringsBuilt() {
        if (COPY_STRIP_STRINGS != null) return;
        COPY_STRIP_STRINGS = new HashSet<>();
        String[] keys = {
                "msg.itemchip.unprogrammed",
                "msg.satelliteidchip.unprogrammed",
                "msg.planetidchip.unprogrammed",
                "msg.stationchip.unprogrammed",
                "msg.orescanner.unprogrammed",
                "msg.itemsatellite.empty"
        };
        for (String k : keys) {
            String v1 = I18n.format(k);
            if (v1 != null) COPY_STRIP_STRINGS.add(v1.trim().toLowerCase());
            String v2 = LibVulpes.proxy.getLocalizedString(k);
            if (v2 != null) COPY_STRIP_STRINGS.add(v2.trim().toLowerCase());
        }
        COPY_STRIP_STRINGS.add("unprogrammed");
    }

    @Override
    public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        FontRenderer fr = minecraft.fontRenderer;
        String text = I18n.format(isCopyRecipe ? "jei.sb.copychiphint" : "jei.sb.assemblyhint");
        int tw = fr.getStringWidth(text);
        fr.drawString(text, (recipeWidth - tw) / 2, recipeHeight - fr.FONT_HEIGHT - 4, 0x000000);
    }
}
