package zmaster587.advancedRocketry.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.subsystem.heat.ThermalMaterial;
import zmaster587.advancedRocketry.subsystem.heat.ThermalMaterials;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a material is worth thermally, and why it is worth that.
 *
 * <p>The contract is that a slug's capacity is DERIVED from the substance it is made of - never
 * authored beside an item - so what these pin is the derivation and its consequences, never a
 * number. Every expected value below is computed from the same three physical properties the table
 * stores, so a test cannot agree with production by restating what production says.</p>
 */
public class ThermalMaterialsTest {

    private int prevMargin;
    private int prevJoulesPerUnit;
    private int prevAmbient;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void fixTheScale() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        prevMargin = config.shipHeatSlugMarginKelvin;
        prevJoulesPerUnit = config.shipHeatSlugJoulesPerUnit;
        prevAmbient = config.shipHeatAmbientKelvin;
        config.shipHeatSlugMarginKelvin = 100;
        config.shipHeatSlugJoulesPerUnit = 1000;
        config.shipHeatAmbientKelvin = 293;
    }

    @After
    public void restoreTheScale() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.shipHeatSlugMarginKelvin = prevMargin;
        config.shipHeatSlugJoulesPerUnit = prevJoulesPerUnit;
        config.shipHeatAmbientKelvin = prevAmbient;
    }

    private static ThermalMaterial material(String name) {
        ThermalMaterial found = ThermalMaterials.INSTANCE.byName(name);
        assertNotNull("the shipped table must know " + name, found);
        return found;
    }

    /** The law itself, spelled out here so the test computes it rather than trusting it. */
    private static long expectedJoulesPerCubicMetre(ThermalMaterial m, int ambient, int margin) {
        return (long) m.densityKgPerCubicMetre() * m.specificHeatJoulesPerKgKelvin()
                * (m.ceilingKelvin() - margin - ambient);
    }

    @Test
    public void capacityIsDensityTimesSpecificHeatTimesTheUsableSpan() {
        ThermalMaterial iron = material("iron");

        assertEquals("the energy a lump holds is rho * c * dT and nothing else",
                expectedJoulesPerCubicMetre(iron, 293, 100), iron.joulesPerCubicMetre(293, 100));
    }

    @Test
    public void theMarginIsSubtractedFromTheSpanRatherThanIgnored() {
        ThermalMaterial iron = material("iron");

        long withoutMargin = iron.joulesPerCubicMetre(293, 0);
        long withMargin = iron.joulesPerCubicMetre(293, 100);

        assertTrue("charging a slug short of its ceiling must cost capacity - that is what buys back"
                + " a solid object: " + withoutMargin + " -> " + withMargin, withMargin < withoutMargin);
        assertEquals("and exactly the margin's worth of it",
                (long) iron.densityKgPerCubicMetre() * iron.specificHeatJoulesPerKgKelvin() * 100,
                withoutMargin - withMargin);
    }

    @Test
    public void aMaterialAlreadyPastItsCeilingCarriesNothing() {
        ThermalMaterial lead = material("lead");

        assertEquals("a slug in a room hotter than the slug melts is not a heat sink", 0L,
                lead.joulesPerCubicMetre(lead.ceilingKelvin() + 1, 0));
    }

    /**
     * The progression's whole lesson, and it must fall out of the physics rather than be arranged:
     * lead is eleven times denser than water and barely better as a slug, because it gives up at
     * 600 K.
     */
    @Test
    public void aHigherCeilingBeatsAHigherDensity() {
        long water = material("water").joulesPerCubicMetre(293, 0);
        long lead = material("lead").joulesPerCubicMetre(293, 0);
        long tungsten = material("tungsten").joulesPerCubicMetre(293, 0);
        long carbon = material("carbon").joulesPerCubicMetre(293, 0);

        assertTrue("premise: lead really is far denser than water",
                material("lead").densityKgPerCubicMetre()
                        > 10 * material("water").densityKgPerCubicMetre());
        assertTrue("and yet a litre of it is worth less than twice a litre of water, because it melts"
                + " at 600 K: water=" + water + " lead=" + lead, lead < 2 * water);
        assertTrue("graphite is denser than nothing much and beats iron anyway, on ceiling alone:"
                + " carbon=" + carbon, carbon > material("iron").joulesPerCubicMetre(293, 0));
        assertTrue("and only tungsten beats graphite: tungsten=" + tungsten, tungsten > carbon);
    }

    @Test
    public void volumeScalesTheSlugLinearly() {
        ThermalMaterial iron = material("iron");

        long oneLitre = ThermalMaterials.slugCapacity(iron, 1_000);
        long fourLitres = ThermalMaterials.slugCapacity(iron, 4_000);

        assertTrue("premise: a litre of iron must be worth something at all", oneLitre > 0);
        assertEquals("four times the material is four times the heat - the slug is a quantity of a"
                + " substance, not a magic item", 4 * oneLitre, fourLitres);
    }

    @Test
    public void theConversionScalesEveryMaterialAndReordersNone() {
        ThermalMaterial iron = material("iron");
        ThermalMaterial copper = material("copper");
        long ironBefore = ThermalMaterials.slugCapacity(iron, 1_000);
        long copperBefore = ThermalMaterials.slugCapacity(copper, 1_000);

        ARConfiguration.getCurrentConfig().shipHeatSlugJoulesPerUnit = 2000;

        long ironAfter = ThermalMaterials.slugCapacity(iron, 1_000);
        long copperAfter = ThermalMaterials.slugCapacity(copper, 1_000);

        assertEquals("halving what a heat unit is worth must halve the slug", ironBefore / 2, ironAfter);
        assertTrue("and it must not change which material is the better slug",
                (ironBefore > copperBefore) == (ironAfter > copperAfter));
    }

    @Test
    public void anUnknownSubstanceIsNotSilentlyGivenACapacity() {
        assertNull("a material nobody described must read as absent",
                ThermalMaterials.INSTANCE.byName("unobtainium"));
        assertEquals("and absent must carry nothing rather than a default", 0L,
                ThermalMaterials.slugCapacity(null, 1_000));
    }

    /**
     * One row per substance, reached from every shape it comes in. The point of keying by material is
     * that an ingot, a block and a nugget of the same metal are the same substance.
     */
    @Test
    public void everyShapeOfOneSubstanceResolvesToTheSameRow() {
        ThermalMaterial fromIngot = ThermalMaterials.INSTANCE.byOreName("ingotIron");
        ThermalMaterial fromBlock = ThermalMaterials.INSTANCE.byOreName("blockIron");
        ThermalMaterial fromDust = ThermalMaterials.INSTANCE.byOreName("dustIron");

        assertNotNull("an ore-dictionary ingot name must resolve", fromIngot);
        assertEquals("a block of it is the same substance", fromIngot.name(), fromBlock.name());
        assertEquals("so is a dust of it", fromIngot.name(), fromDust.name());
    }

    // ─── volume: derived from the shape, never authored beside the item ─────────

    @Test
    public void theShapeOfAnItemIsWhatSaysHowMuchSubstanceItIs() {
        long block = ThermalMaterials.volumeMillilitres("blockIron");
        long ingot = ThermalMaterials.volumeMillilitres("ingotIron");
        long nugget = ThermalMaterials.volumeMillilitres("nuggetIron");

        assertEquals("a block is a cubic metre of the stuff", 1_000_000L, block);
        assertEquals("nine ingots to a block, which is the arithmetic the whole ecosystem uses",
                block / 9, ingot);
        assertEquals("and nine nuggets to an ingot", ingot / 9, nugget);
    }

    @Test
    public void aShapeNobodyDescribedIsNoVolumeRatherThanADefaultOne() {
        assertEquals("an unrecognised shape must not be silently given a size", 0L,
                ThermalMaterials.volumeMillilitres("clumpIron"));
    }

    @Test
    public void twoShapesOfOneMetalDifferOnlyByHowMuchOfItThereIs() {
        ThermalMaterial iron = material("iron");
        long fromBlock = ThermalMaterials.slugCapacity(iron,
                ThermalMaterials.volumeMillilitres("blockIron"));
        long fromIngot = ThermalMaterials.slugCapacity(iron,
                ThermalMaterials.volumeMillilitres("ingotIron"));

        assertTrue("premise: an iron block must be worth something", fromBlock > 0);
        // Per LITRE rather than as a ratio of the two totals: nine ingots are 999 999 ml against a
        // block's 1 000 000, because the ecosystem's own ninth does not divide evenly. That one
        // millilitre is the convention's rounding, not a difference in the substance - and asserting
        // the ratio of the totals would be asserting the rounding.
        long blockPerLitre = fromBlock * 1_000L / ThermalMaterials.volumeMillilitres("blockIron");
        long ingotPerLitre = fromIngot * 1_000L / ThermalMaterials.volumeMillilitres("ingotIron");
        assertEquals("the substance is the same, so a litre of it is worth the same either way",
                blockPerLitre, ingotPerLitre);
    }

    @Test
    public void anOreNameOfSomethingTheTableDoesNotKnowResolvesToNothing() {
        assertNull("a prefix is not a licence to invent a material",
                ThermalMaterials.INSTANCE.byOreName("ingotUnobtainium"));
    }
}
