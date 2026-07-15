package zmaster587.advancedRocketry.test.unit;

import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Regression guard (bug-report-workflow, Path B) for the C076/C045 SOURCE fix:
 * {@code SpaceStationObject.getInsolationMultiplier()} must return 0 (not NPE)
 * when the station's orbiting planet is unresolved
 * ({@code getOrbitingPlanetId()==INVALID_PLANET}).
 *
 * <p>The committed {@code SolarTileSpaceDimUnresolvedStationNpeTest} covers only
 * the null-STATION path (the tile-side C045 guard). This covers the distinct
 * non-null-station / unresolved-planet path: a real, grid-registered station whose
 * planet id is INVALID — its own null-check passes, so it derefs
 * {@code getInsolationMultiplier()}, which pre-fix NPE'd inside
 * {@code getOrbitingPlanet()==null.getPeakInsolationMultiplierWithoutAtmosphere()}.</p>
 *
 * <p>Two legitimately-reachable states, both via public production methods (no
 * reflection, no impossible state): (A) a freshly constructed station
 * ({@code created=false}, before {@code onModuleUnpack}); (B) after
 * {@code beginTransition} ({@code created=true} but {@code parentPlanet} still
 * INVALID — the between-assignment state).</p>
 */
public class SpaceStationInsolationUnresolvedPlanetTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void insolationZeroWhenNotYetCreated() {
        SpaceStationObject s = new SpaceStationObject();
        assertEquals(Constants.INVALID_PLANET, s.getOrbitingPlanetId());
        assertEquals("getInsolationMultiplier must be 0 (not NPE) for an uncreated station "
                        + "whose orbiting planet is unresolved",
                0.0, s.getInsolationMultiplier(), 0.0);
    }

    @Test
    public void insolationZeroWhenCreatedButPlanetUnresolved() {
        SpaceStationObject s = new SpaceStationObject();
        s.beginTransition(0);
        assertEquals(Constants.INVALID_PLANET, s.getOrbitingPlanetId());
        assertFalse("INVALID_PLANET must not read as warping — proving the 0.0 comes from the "
                        + "null-planet guard, not the isWarping() short-circuit",
                s.isWarping());
        assertEquals("getInsolationMultiplier must be 0 (not NPE) for a created station whose "
                        + "orbiting planet is still unresolved",
                0.0, s.getInsolationMultiplier(), 0.0);
    }
}
