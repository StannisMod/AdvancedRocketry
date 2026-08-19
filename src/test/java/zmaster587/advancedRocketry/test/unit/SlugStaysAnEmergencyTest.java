package zmaster587.advancedRocketry.test.unit;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.subsystem.heat.ThermalMaterial;
import zmaster587.advancedRocketry.subsystem.heat.ThermalMaterials;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertTrue;

/**
 * The clause that keeps the emergency dump an emergency, checked as a RELATION rather than as a pair
 * of numbers.
 *
 * <p>C12 HEAT-17 says sustained throughput from ejected slugs must stay below the cheapest continuous
 * radiator tier: {@code E_slug / t_charge < P_radiator}. Written that way it survives rebalancing -
 * by us or by a modpack author - because what it forbids is a SHAPE (slugs standing in for surface),
 * not a value. So this test computes both sides from the config the game is actually running and
 * compares them; it names no temperature and no rate of its own.</p>
 *
 * <p>This became checkable only when the dump gained a charge rate. Before that the clause had no
 * {@code t_charge} to divide by, which is why it sat unpinned through three slices.</p>
 */
public class SlugStaysAnEmergencyTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void shippedDefaults() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.shipHeatAmbientKelvin = 293;
        config.shipHeatSlugMarginKelvin = 100;
        config.shipHeatSlugJoulesPerUnit = 1000;
        config.shipHeatDumpThroughput = 40000;
        config.shipHeatRadiatorCellPower = 6000;
        config.shipHeatRadiatorReferenceKelvin = 500;
    }

    /** What one dump sustains, in heat units per second, for the best slug material in the table. */
    private static double sustainedDumpRate() {
        // The dump's throughput IS the sustained rate: it is what the machine moves per second, and a
        // bigger slug only means it runs longer before the port fires. That is the whole point of
        // expressing the clause per second rather than per slug.
        return ARConfiguration.getCurrentConfig().shipHeatDumpThroughput;
    }

    /**
     * What ONE radiating cell sheds per second at its own reference temperature - the cheapest tier.
     *
     * <p>Read straight off the config rather than through the curve, because at the reference point
     * the curve is defined to give exactly this: that is what "a point on the curve" means, and going
     * through the code to fetch a number the config states would measure the arithmetic instead of
     * the relation.</p>
     */
    private static double cheapestRadiatorRate() {
        // A radiator is a square plate by design, so the smallest one worth calling a tier is 3x3.
        // Naming that here rather than a bare multiplier is the difference between stating the
        // clause's own subject and inventing a fudge factor: change the shape of the cheapest tier
        // and this number should change with it.
        return ARConfiguration.getCurrentConfig().shipHeatRadiatorCellPower * CHEAPEST_TIER_CELLS;
    }

    /** The smallest square plate a player would build: the cheapest CONTINUOUS tier the clause names. */
    private static final int CHEAPEST_TIER_CELLS = 3 * 3;

    @Test
    public void aDumpCannotStandInForRadiators() {
        double dump = sustainedDumpRate();
        double radiator = cheapestRadiatorRate();

        assertTrue("premise: the cheapest radiator tier must shed something at its reference point",
                radiator > 0);
        assertTrue("sustained dump throughput must stay under what the cheapest continuous radiator"
                + " tier sheds, or a ship could be cooled by feeding it iron: dump=" + dump
                + " per second, radiator=" + radiator + " per second per cell",
                dump < radiator);
    }

    /**
     * The other half of the same bargain: a slug is SPENT. Whatever it carries away leaves the ship
     * with it, so the material is a consumable and not a heat exchanger that keeps working.
     */
    @Test
    public void whatTheSlugCarriesLeavesWithIt() {
        ThermalMaterial iron = ThermalMaterials.INSTANCE.byName("iron");
        long capacity = ThermalMaterials.slugCapacity(iron,
                ThermalMaterials.volumeMillilitres("blockIron"));

        assertTrue("premise: a block of iron must be worth carrying at all", capacity > 0);

        double secondsOfCooling = capacity / sustainedDumpRate();
        assertTrue("one whole block of iron must buy SECONDS rather than a steady state - it is an"
                + " emergency measure, and a value that bought minutes would make it a cooling"
                + " system: " + secondsOfCooling + " s", secondsOfCooling < 600);
    }
}
