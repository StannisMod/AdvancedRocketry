package zmaster587.advancedRocketry.test.integration;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.SystemContent;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Authored system content (universe-model.md &sect;4): a catalogued {@link StellarBody} with planets resolves
 * to addressable {@link SystemBody} data, and a planet resolves to its system's coordinate. Needs
 * {@link MinecraftBootstrap} for {@link DimensionProperties} construction.
 */
public class SystemContentTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private static DimensionProperties planet(int dimId, int orbitalDist, double theta) {
        DimensionProperties p = new DimensionProperties(dimId);
        p.orbitalDist = orbitalDist;
        p.orbitTheta = theta;
        p.orbitalPhi = 0;
        return p;
    }

    @Test
    public void authoredSystemYieldsStarAtCentreAndPlanetsInsideTheCell() {
        StellarBody star = new StellarBody();
        star.setId(4242);
        star.setName("TestStar");
        planet(700, 100, 0.0).setStar(star);       // setStar back-adds the planet to the star
        planet(701, 200, Math.PI / 2).setStar(star);

        GalacticCoord coord = GalacticCoord.ofSectorLocal(10, 20, 30, 0, 0, 0);
        List<SystemBody> bodies = SystemContent.bodiesOf(star, coord);

        assertEquals("first body is the star at the cell centre", SystemBodyKind.STAR, bodies.get(0).kind());
        assertTrue(bodies.get(0).address().sameCell(coord));
        assertEquals(0, bodies.get(0).address().localX());

        int planets = 0;
        SystemBody aPlanet = null;
        for (SystemBody b : bodies) {
            assertTrue("every body shares the system's cell", b.address().sameCell(coord));
            assertEquals("every body belongs to the star", 4242, b.starId());
            if (b.kind() == SystemBodyKind.PLANET) {
                planets++;
                aPlanet = b;
            }
        }
        assertEquals("both authored planets become bodies", 2, planets);
        assertNotNull(aPlanet);
        assertTrue("an authored planet body is a descend target (real dim)", aPlanet.isDescendTarget());
        assertTrue(aPlanet.dimId() == 700 || aPlanet.dimId() == 701);
    }

    @Test
    public void planetResolvesToItsSystemCoord() {
        StellarBody star = new StellarBody();
        star.setId(4243);
        DimensionProperties p = planet(710, 120, 0.0);
        p.setStar(star);

        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord coord = GalacticCoord.ofSectorLocal(5, 5, 5, 0, 0, 0);
        reg.place(coord, 4243);
        assertEquals("planet -> starId -> system -> coord", Optional.of(coord), reg.coordForPlanet(p));
    }
}
