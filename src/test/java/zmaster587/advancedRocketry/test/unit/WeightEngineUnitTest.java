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
    public void fluidWeightIsFallbackRatePerMb() {
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
        double prevScale = ARConfiguration.getCurrentConfig().fuelMassScale;
        try {
            ARConfiguration.getCurrentConfig().fuelMassScale = 1.0;
            // Default fluidFallback is 0.001 kN/mB → 1000 mB == 1.0 kN.
            assertEquals(1.0f, we.getWeight(testFluid(), 1000f), 1e-4);
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
            ARConfiguration.getCurrentConfig().fuelMassScale = 2.5;
            assertEquals(2.5f, we.getWeight(testFluid(), 1000f), 1e-4);
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
