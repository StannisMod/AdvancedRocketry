package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import org.junit.Test;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.util.WeightEngine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * MC-free unit coverage for {@link WeightEngine}: the parts that don't need a
 * block/item registry — fluid weight arithmetic, the JSON table round-trip, and
 * default seeding. Block/material resolution (which needs real ItemStacks) is
 * covered by the server-tier {@code WeightSystemTest}.
 */
public class WeightEngineUnitTest {

    private static Fluid testFluid() {
        ResourceLocation tex = new ResourceLocation("advancedrocketry", "blocks/unit_fluid");
        return new Fluid("ar_unit_fluid", tex, tex);
    }

    @Test
    public void fluidWeightIsPositiveAndLinearInAmount() {
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
        double prevScale = ARConfiguration.getCurrentConfig().fuelMassScale;
        try {
            ARConfiguration.getCurrentConfig().fuelMassScale = 1.0;
            // An unknown fluid still weighs something (the fallback per-mB rate)
            // and the weight is linear in the amount. The exact kN/mB constant is
            // an implementation default.
            float base = we.getWeight(testFluid(), 1000f);
            assertTrue("fallback fluid weight must be positive: " + base, base > 0);
            assertEquals("fluid weight must be linear in the amount",
                    2 * base, we.getWeight(testFluid(), 2000f), 1e-4);
        } finally {
            ARConfiguration.getCurrentConfig().fuelMassScale = prevScale;
        }
    }

    @Test
    public void fuelMassScaleMultipliesFluidWeight() {
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
        double prevScale = ARConfiguration.getCurrentConfig().fuelMassScale;
        try {
            ARConfiguration.getCurrentConfig().fuelMassScale = 1.0;
            float base = we.getWeight(testFluid(), 1000f);

            ARConfiguration.getCurrentConfig().fuelMassScale = 2.5;
            assertEquals("fluid weight must scale by fuelMassScale",
                    2.5f * base, we.getWeight(testFluid(), 1000f), 1e-4);
        } finally {
            ARConfiguration.getCurrentConfig().fuelMassScale = prevScale;
        }
    }

    @Test
    public void seedDefaultsPopulatesMaterialTable() {
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
        assertTrue("default material table must be populated", we.materialCount() > 10);
    }

    @Test
    public void individualOverrideSurvivesSaveLoadRoundTrip() {
        WeightEngine we = WeightEngine.INSTANCE;
        try {
            we.resetTables();
            assertNull("clean slate must not know the test key", we.rawIndividual("ar:roundtrip_probe"));

            we.setIndividual("ar:roundtrip_probe", 42.0);
            we.save();

            // Wipe in-memory state, then reload from the file just written.
            we.resetTables();
            assertNull("resetTables must drop the in-memory override", we.rawIndividual("ar:roundtrip_probe"));

            we.load();
            assertEquals("override must persist across save/load",
                    Double.valueOf(42.0), we.rawIndividual("ar:roundtrip_probe"));
        } finally {
            // Leave no residue in the on-disk config for other tests.
            we.resetTables();
            we.save();
        }
    }
}
