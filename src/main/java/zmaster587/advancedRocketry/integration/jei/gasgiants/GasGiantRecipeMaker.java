package zmaster587.advancedRocketry.integration.jei.gasgiants;

import mezz.jei.api.IJeiHelpers;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class GasGiantRecipeMaker {

    public static List<GasGiantWrapper> getRecipes(IJeiHelpers helpers) {
        List<GasGiantWrapper> out = new ArrayList<>();

        DimensionManager manager = DimensionManager.getInstance();
        if (manager == null) return out;

        Integer[] dimIds = manager.getRegisteredDimensions();
        if (dimIds == null || dimIds.length == 0) return out;

        Arrays.sort(dimIds, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                DimensionProperties pa = manager.getDimensionProperties(a);
                DimensionProperties pb = manager.getDimensionProperties(b);

                String na = pa != null && pa.getName() != null ? pa.getName() : "";
                String nb = pb != null && pb.getName() != null ? pb.getName() : "";

                int byName = String.CASE_INSENSITIVE_ORDER.compare(na, nb);
                if (byName != 0) return byName;
                return Integer.compare(a, b);
            }
        });

        for (Integer dimId : dimIds) {
            if (dimId == null) continue;

            DimensionProperties props = manager.getDimensionProperties(dimId);
            if (props == null || !props.isGasGiant()) continue;

            String planetName = props.getName() == null || props.getName().isEmpty()
                    ? ("DIM " + props.getId())
                    : props.getName();

            Set<String> seenFluidNames = new LinkedHashSet<>();
            List<FluidStack> fluids = new ArrayList<>();

            for (Fluid fluid : props.getHarvestableGasses()) {
                if (fluid == null) continue;

                String fluidName = fluid.getName();
                if (fluidName == null || fluidName.isEmpty()) continue;
                if (!seenFluidNames.add(fluidName)) continue;

                fluids.add(new FluidStack(fluid, 1000));
            }

            if (!fluids.isEmpty()) {
                out.add(new GasGiantWrapper(props.getId(), planetName, fluids));
            }
        }

        out.sort(new Comparator<GasGiantWrapper>() {
            @Override
            public int compare(GasGiantWrapper a, GasGiantWrapper b) {
                int byPlanet = String.CASE_INSENSITIVE_ORDER.compare(a.getPlanetName(), b.getPlanetName());
                if (byPlanet != 0) return byPlanet;
                return Integer.compare(a.getDimId(), b.getDimId());
            }
        });

        return out;
    }
}