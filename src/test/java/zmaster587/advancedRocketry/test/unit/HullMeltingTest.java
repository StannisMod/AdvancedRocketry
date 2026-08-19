package zmaster587.advancedRocketry.test.unit;

import org.junit.BeforeClass;
import org.junit.Test;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.subsystem.heat.HullMelting;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The environment's half of the melting rung: how hot the outside alone can drive a block.
 *
 * <p>The incident flux is quoted on the same curve a radiator sheds on, so turning it into a
 * temperature is that curve read backwards - and the only thing worth pinning is that it IS the same
 * curve. A separate model here would let a ship parked in a star melt at one temperature and radiate
 * as if it were at another.</p>
 */
public class HullMeltingTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void theEquilibriumTemperatureIsTheRadiationCurveReadBackwards() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.shipHeatRadiatorReferenceKelvin = 500;
        config.shipHeatRadiatorCellPower = 6000;

        // What a cell radiates AT the reference is the flux that would hold it there, so feeding that
        // flux back must return the reference itself.
        double atReference = HullMelting.equilibriumKelvin(6000 / 20);

        assertEquals("a surface in exactly the flux it radiates sits at that temperature",
                500.0D, atReference, 1.0D);
    }

    @Test
    public void moreFluxIsAHotterSurfaceButOnlyToTheFourthRoot() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.shipHeatRadiatorReferenceKelvin = 500;
        config.shipHeatRadiatorCellPower = 6000;

        double single = HullMelting.equilibriumKelvin(300);
        double sixteenfold = HullMelting.equilibriumKelvin(300 * 16);

        assertTrue("premise: a surface under any flux at all has a temperature", single > 0);
        assertEquals("sixteen times the flux is twice the temperature - the law is quartic, so a star"
                + " that is far brighter is not proportionally hotter on your hull",
                2.0D, sixteenfold / single, 0.01D);
    }

    @Test
    public void nothingArrivingIsNoTemperatureAtAll() {
        assertEquals("an unlit surface is not driven anywhere by the environment", 0.0D,
                HullMelting.equilibriumKelvin(0), 0.0D);
    }
}
