package zmaster587.advancedRocketry.integration.jei.orbitalLaserDrill;

import mezz.jei.api.IJeiHelpers;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OrbitalLaserDrillRecipeMaker {

    public static List<OrbitalLaserDrillWrapper> getRecipes(IJeiHelpers helpers) {

        // Only show this page if VoidDrill is active
        if (ARConfiguration.getCurrentConfig().laserDrillPlanet) {
            return Collections.emptyList();
        }

        World w = Minecraft.getMinecraft() != null ? Minecraft.getMinecraft().world : null;
        DimensionProperties props = null;

        if (w != null && w.provider != null) {
            props = DimensionManager.getInstance().getDimensionProperties(w.provider.getDimension());
        }

        List<ItemStack> all = OrbitalLaserDrillWrapper.buildVoidDrillActivationList(props);
        int total = all.size();

        int pages = Math.max(1, (total + OrbitalLaserDrillWrapper.PAGE_SIZE - 1) / OrbitalLaserDrillWrapper.PAGE_SIZE);

        List<OrbitalLaserDrillWrapper> out = new ArrayList<>(pages);

        for (int page = 0; page < pages; page++) {
            int from = page * OrbitalLaserDrillWrapper.PAGE_SIZE;
            int to = Math.min(total, from + OrbitalLaserDrillWrapper.PAGE_SIZE);

            List<ItemStack> slice = (from < to) ? all.subList(from, to) : Collections.emptyList();
            out.add(new OrbitalLaserDrillWrapper(page, pages, slice));
        }

        return out;
    }

    public static List<OrbitalLaserDrillWrapper> getMachineRecipes(IJeiHelpers helpers, Class<?> ignored) {
        return getRecipes(helpers);
    }
}
