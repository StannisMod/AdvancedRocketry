package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.atmosphere.AirState;
import zmaster587.advancedRocketry.atmosphere.AtmosphereType;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The gas contents of a life-support zone: what a room's air is made of, what pressure that
 * amounts to, and which atmosphere the rest of the mod therefore sees.
 */
public class AirStateTest {

    private static final int SAFE_MIN = 160_000;
    private static final int SAFE_MAX = 300_000;

    /** Where the crew rungs sit for these tests, so no assertion depends on the shipped defaults. */
    private static final int VERY_HOT = 323;
    private static final int SUPERHEATED = 373;

    private int prevMin;
    private int prevMax;
    private boolean prevShipHeat;
    private int prevVeryHot;
    private int prevSuperheated;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void setSafeBand() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        prevMin = config.lifeSupportMinPartialO2;
        prevMax = config.lifeSupportMaxPartialO2;
        prevShipHeat = config.shipHeat;
        prevVeryHot = config.shipHeatCrewVeryHotKelvin;
        prevSuperheated = config.shipHeatCrewSuperheatedKelvin;
        config.lifeSupportMinPartialO2 = SAFE_MIN;
        config.lifeSupportMaxPartialO2 = SAFE_MAX;
        config.shipHeat = true;
        config.shipHeatCrewVeryHotKelvin = VERY_HOT;
        config.shipHeatCrewSuperheatedKelvin = SUPERHEATED;
    }

    @After
    public void restoreSafeBand() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = prevMin;
        config.lifeSupportMaxPartialO2 = prevMax;
        config.shipHeat = prevShipHeat;
        config.shipHeatCrewVeryHotKelvin = prevVeryHot;
        config.shipHeatCrewSuperheatedKelvin = prevSuperheated;
    }

    /** Breathable sea-level air at a stated temperature, in kelvin. */
    private static AirState earthLikeAt(int kelvin) {
        return new AirState(790_000, 210_000, 0, kelvin * 1000);
    }

    @Test
    public void breathableAirReadsAsOneAtmosphere() {
        // The pressure a sealed zone reports is what the analyser turns into "1.00 atm"; it read
        // a flat 100 before zones had contents and must keep reading 100 for untouched air.
        assertEquals(100, AirState.earthLike().getPressureCentiAtm());
    }

    @Test
    public void breathingConvertsOxygenIntoCarbonDioxideWithoutChangingPressure() {
        AirState air = AirState.earthLike();
        int before = air.getTotalPressure();

        air.respire(10_000);

        assertEquals("oxygen must fall by exactly what was breathed", 200_000, air.getOxygen());
        assertEquals("the same amount must appear as CO2", 10_000, air.getCarbonDioxide());
        assertEquals("respiration rearranges air, it does not consume it", before, air.getTotalPressure());
    }

    @Test
    public void breathingCannotTakeOxygenThatIsNotThere() {
        AirState air = new AirState(790_000, 5_000, 0);

        int taken = air.respire(50_000);

        assertEquals("only the oxygen present may be converted", 5_000, taken);
        assertEquals(0, air.getOxygen());
        assertEquals(5_000, air.getCarbonDioxide());
    }

    @Test
    public void regenerationIsBreathingRunBackwards() {
        AirState air = AirState.earthLike();
        air.respire(30_000);
        int pressureWithCrewAboard = air.getTotalPressure();

        int carbon = air.regenerate(30_000);

        assertEquals("all of it must come back as oxygen", 210_000, air.getOxygen());
        assertEquals(0, air.getCarbonDioxide());
        assertEquals("the carbon that left the air is what the machine must now handle", 30_000, carbon);
        assertEquals("pressure is unchanged: the solid carbon never held any", pressureWithCrewAboard, air.getTotalPressure());
    }

    @Test
    public void regenerationCannotInventCarbonDioxide() {
        AirState air = new AirState(790_000, 200_000, 10_000);

        int carbon = air.regenerate(50_000);

        assertEquals("only the CO2 present may be processed", 10_000, carbon);
        assertEquals(0, air.getCarbonDioxide());
        assertEquals(210_000, air.getOxygen());
    }

    @Test
    public void aRecirculatorCanBringAStaleRoomBackIntoTheBand() {
        AirState air = AirState.earthLike();
        air.respire(60_000);
        assertSame("premise: the room has gone stale", AtmosphereType.LOWOXYGEN, air.deriveAtmosphere());

        air.regenerate(60_000);

        assertTrue("and regeneration must be able to undo that, not merely stop it",
                air.deriveAtmosphere().isBreathable());
    }

    @Test
    public void airInsideTheSafeBandIsBreathable() {
        assertTrue(AirState.earthLike().deriveAtmosphere().isBreathable());
    }

    @Test
    public void oxygenBelowTheBandSuffocates() {
        AirState air = new AirState(790_000, SAFE_MIN - 1, 10_000);
        assertSame(AtmosphereType.LOWOXYGEN, air.deriveAtmosphere());
    }

    @Test
    public void airWithNoOxygenLeftIsNotMerelyLowOnIt() {
        AirState air = new AirState(790_000, 0, 210_000);
        assertSame(AtmosphereType.NOO2, air.deriveAtmosphere());
    }

    @Test
    public void oxygenAboveTheBandIsToxicAndStillFeedsFire() {
        AirState air = new AirState(400_000, SAFE_MAX + 1, 0);

        assertSame(AtmosphereType.HIGHOXYGEN, air.deriveAtmosphere());
        assertTrue("an oxygen-rich room being flammable is the hazard, not a bug",
                AtmosphereType.HIGHOXYGEN.allowsCombustion());
        assertTrue(!AtmosphereType.HIGHOXYGEN.isBreathable());
    }

    // ─── The first rung of the failure ladder: hot air is what hurts the crew ───────────────────
    //
    // The subject is the zone's AIR and the consequence is one of the hostile atmospheres a scorching
    // planet already presents, so the suit that protects there protects here. What these pin is that
    // the room's temperature decides the rung and its gases only decide which variant of it - never
    // that a particular number is dangerous, which is config.

    @Test
    public void airHotEnoughToHurtIsTheSameHostileAtmosphereAScorchingPlanetPresents() {
        assertSame("a breathable room can still be a room that cooks you",
                AtmosphereType.VERYHOT, earthLikeAt(VERY_HOT).deriveAtmosphere());
    }

    @Test
    public void airJustBelowTheRungIsUnaffectedByHowWarmItIs() {
        assertSame("the rung is a threshold, not a slope: below it the gases decide alone",
                AtmosphereType.PRESSURIZEDAIR, earthLikeAt(VERY_HOT - 1).deriveAtmosphere());
    }

    @Test
    public void lethallyHotAirIsTheHarsherOfTheTwoRungs() {
        assertSame(AtmosphereType.SUPERHEATED, earthLikeAt(SUPERHEATED).deriveAtmosphere());
    }

    @Test
    public void hotAirWithNothingToBreatheSaysBothThingsAtOnce() {
        AirState suffocatingAndHot = new AirState(1_000_000, 0, 0, SUPERHEATED * 1000);

        assertSame("the NoO2 variants exist precisely so neither hazard hides the other",
                AtmosphereType.SUPERHEATEDNOO2, suffocatingAndHot.deriveAtmosphere());
    }

    @Test
    public void heatOutranksAnOxygenSurplus() {
        AirState enrichedAndHot = new AirState(400_000, SAFE_MAX + 1, 0, VERY_HOT * 1000);

        assertSame("a room that is burning its crew is not made safe by its gas mix",
                AtmosphereType.VERYHOT, enrichedAndHot.deriveAtmosphere());
    }

    @Test
    public void aVacuumIsNotHotHoweverHotTheGasThatLeftItWas() {
        AirState breached = new AirState(0, 0, 0, SUPERHEATED * 1000);

        assertSame("there is no body left in the room to be hot",
                AtmosphereType.VACUUM, breached.deriveAtmosphere());
    }

    @Test
    public void aThresholdOfZeroIsNoRungRatherThanARungEveryRoomTrips() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.shipHeatCrewVeryHotKelvin = 0;
        config.shipHeatCrewSuperheatedKelvin = 0;

        assertSame("an unloaded or switched-off threshold must not make every room lethal",
                AtmosphereType.PRESSURIZEDAIR, earthLikeAt(1_000).deriveAtmosphere());
    }

    @Test
    public void withShipHeatOffARoomNeverCooksItsCrew() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.shipHeat = false;

        assertSame("the flag that removes the mechanic removes its hazard too",
                AtmosphereType.PRESSURIZEDAIR, earthLikeAt(1_000).deriveAtmosphere());
    }

    @Test
    public void aZoneWithNoGasInItIsVacuumWhateverItsComposition() {
        assertSame(AtmosphereType.VACUUM, AirState.vacuum().deriveAtmosphere());
    }

    @Test
    public void anUnconfiguredSafeBandGovernsNothing() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = 0;
        config.lifeSupportMaxPartialO2 = 0;

        assertSame("no usable band means no governor, not a hazard",
                AtmosphereType.PRESSURIZEDAIR, AirState.earthLike().deriveAtmosphere());
    }

    @Test
    public void theGovernorLeavesRoomOnlyUpToTheToxicityCeiling() {
        AirState air = AirState.earthLike();

        assertEquals("headroom is the distance to the ceiling, not to infinity",
                SAFE_MAX - 210_000, air.oxygenHeadroom());
    }

    @Test
    public void anAlreadyEnrichedRoomGetsNoMoreOxygen() {
        AirState air = new AirState(400_000, SAFE_MAX + 50_000, 0);

        assertEquals("a combiner must be unable to make a fire hazard worse", 0, air.oxygenHeadroom());
    }

    @Test
    public void anUnconfiguredBandImposesNoCeilingEither() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = 0;
        config.lifeSupportMaxPartialO2 = 0;

        assertEquals("with no band there is no governor, in both directions",
                Integer.MAX_VALUE, AirState.earthLike().oxygenHeadroom());
    }

    @Test
    public void aSeparatorTakesTheStaleGasAndLeavesTheBreathableOne() {
        AirState air = new AirState(790_000, 210_000, 90_000);

        int co2 = air.drawCarbonDioxide(50_000);
        int n2 = air.drawNitrogen(40_000);

        assertEquals(50_000, co2);
        assertEquals(40_000, n2);
        assertEquals("splitting must not touch the oxygen the crew are breathing", 210_000, air.getOxygen());
        assertEquals(40_000, air.getCarbonDioxide());
        assertEquals(750_000, air.getNitrogen());
    }

    @Test
    public void gasesSurviveASaveAndReload() {
        AirState air = new AirState(700_000, 180_000, 40_000);
        NBTTagCompound nbt = new NBTTagCompound();

        air.writeToNBT(nbt);
        AirState reloaded = AirState.readFromNBT(nbt);

        assertEquals(air.getNitrogen(), reloaded.getNitrogen());
        assertEquals(air.getOxygen(), reloaded.getOxygen());
        assertEquals(air.getCarbonDioxide(), reloaded.getCarbonDioxide());
    }
}
