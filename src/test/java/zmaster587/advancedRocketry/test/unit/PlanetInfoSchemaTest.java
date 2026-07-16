package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.PlanetInfoField;

import java.util.EnumSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the graded-discovery info-tier schema (axis-E E-4). Pure JUnit, no MC bootstrap.
 *
 * <p>Pins the reveal CONTRACT (telescope = global fields only, approach adds terrain, orbit = full) and the
 * monotone tier ordering. Deliberately does NOT pin enum declaration order, ordinal integers, iteration
 * order, or a field count — those are impl details a later field addition may legitimately change.</p>
 */
public class PlanetInfoSchemaTest {

    @Test
    public void telescopeRevealsGlobalFieldsOnly() {
        EnumSet<PlanetInfoField> t = PlanetInfoField.fieldsVisibleAt(InfoTier.TELESCOPE);
        // Global data is obtainable from afar.
        assertTrue(t.contains(PlanetInfoField.COORDINATE));
        assertTrue(t.contains(PlanetInfoField.ATMOSPHERE_PRESENCE));
        assertTrue(t.contains(PlanetInfoField.WATER_PRESENCE));
        // Approach- and orbit-tier data is not.
        assertFalse("terrain must not be telescope-visible", t.contains(PlanetInfoField.TERRAIN_TYPE));
        assertFalse("life must not be telescope-visible", t.contains(PlanetInfoField.LIFE));
        assertFalse("resources must not be telescope-visible", t.contains(PlanetInfoField.RESOURCES));
    }

    @Test
    public void approachAddsTerrainButNotOrbitFields() {
        EnumSet<PlanetInfoField> a = PlanetInfoField.fieldsVisibleAt(InfoTier.APPROACH);
        assertTrue("approach still sees global fields", a.contains(PlanetInfoField.COORDINATE));
        assertTrue("approach reveals terrain", a.contains(PlanetInfoField.TERRAIN_TYPE));
        assertTrue("approach reveals biomes", a.contains(PlanetInfoField.BIOMES));
        assertFalse("orbit-only life stays hidden at approach", a.contains(PlanetInfoField.LIFE));
        assertFalse("orbit-only resources stay hidden at approach", a.contains(PlanetInfoField.RESOURCES));
    }

    @Test
    public void orbitRevealsEveryField() {
        EnumSet<PlanetInfoField> o = PlanetInfoField.fieldsVisibleAt(InfoTier.ORBIT);
        assertTrue("orbit is 100% of a planet's info", o.containsAll(EnumSet.allOf(PlanetInfoField.class)));
    }

    @Test
    public void tiersAreMonotoneSupersets() {
        EnumSet<PlanetInfoField> t = PlanetInfoField.fieldsVisibleAt(InfoTier.TELESCOPE);
        EnumSet<PlanetInfoField> a = PlanetInfoField.fieldsVisibleAt(InfoTier.APPROACH);
        EnumSet<PlanetInfoField> o = PlanetInfoField.fieldsVisibleAt(InfoTier.ORBIT);
        // Each higher tier is a superset of the lower — more range never hides a field.
        assertTrue("approach must contain all telescope fields", a.containsAll(t));
        assertTrue("orbit must contain all approach fields", o.containsAll(a));
    }

    @Test
    public void isVisibleAgreesWithFieldsVisibleAtAndAtLeast() {
        for (PlanetInfoField f : PlanetInfoField.values()) {
            for (InfoTier tier : InfoTier.values()) {
                boolean viaPredicate = PlanetInfoField.isVisible(f, tier);
                boolean viaSet = PlanetInfoField.fieldsVisibleAt(tier).contains(f);
                boolean viaTier = tier.atLeast(f.minTier());
                assertTrue("isVisible / fieldsVisibleAt / atLeast must agree for " + f + " @ " + tier,
                        viaPredicate == viaSet && viaSet == viaTier);
            }
        }
    }

    @Test
    public void infoTierIsTotallyOrdered() {
        assertTrue(InfoTier.ORBIT.atLeast(InfoTier.APPROACH));
        assertTrue(InfoTier.APPROACH.atLeast(InfoTier.TELESCOPE));
        assertTrue("atLeast is reflexive", InfoTier.TELESCOPE.atLeast(InfoTier.TELESCOPE));
        assertFalse(InfoTier.TELESCOPE.atLeast(InfoTier.APPROACH));
        assertFalse(InfoTier.APPROACH.atLeast(InfoTier.ORBIT));
    }
}
