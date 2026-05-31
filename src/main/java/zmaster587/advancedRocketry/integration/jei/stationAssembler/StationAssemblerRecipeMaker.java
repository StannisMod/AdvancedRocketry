package zmaster587.advancedRocketry.integration.jei.stationAssembler;

import mezz.jei.api.IJeiHelpers;
import java.util.Collections;
import java.util.List;

/** Single illustrative entry: the Station Assembler has no craft-list; it’s a process gate. */
public class StationAssemblerRecipeMaker {
    public static List<StationAssemblerWrapper> getRecipes(IJeiHelpers helpers) {
        return java.util.Collections.singletonList(new StationAssemblerWrapper());
    }

    // Keep this to match existing pattern in your makers
    public static List<StationAssemblerWrapper> getMachineRecipes(IJeiHelpers helpers, Class<?> ignored) {
        return getRecipes(helpers);
    }
}
