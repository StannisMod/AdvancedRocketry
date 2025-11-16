package zmaster587.advancedRocketry.integration.jei.co2scrubber;

import mezz.jei.api.IJeiHelpers;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;

import java.util.ArrayList;
import java.util.List;

public class Co2ScrubberRecipeMaker {
    public static List<Co2ScrubberWrapper> getRecipes(IJeiHelpers helpers) {
        List<Co2ScrubberWrapper> list = new ArrayList<>();

        ItemStack cart = new ItemStack(AdvancedRocketryItems.itemCarbonScrubberCartridge, 1, net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE);
        list.add(new Co2ScrubberWrapper(cart));

        return list;
    }
}
