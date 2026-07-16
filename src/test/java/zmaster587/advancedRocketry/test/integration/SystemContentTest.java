package zmaster587.advancedRocketry.test.integration;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.SystemContent;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Authored system content (universe-model.md &sect;2 A#1a + &sect;4): a catalogued {@link StellarBody} with
 * planets resolves to addressable {@link SystemBody} data — star at the anchor, each planet in its OWN cell
 * (snapped to the cell centre) inside the anchor's super-cell box — and a planet dim resolves to its own
 * cell through the registry. Needs {@link MinecraftBootstrap} for {@link DimensionProperties} construction.
 * Scale constants are {@code tunable} and never pinned; only the placement SHAPE is.
 */
public class SystemContentTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @After
    public void resetSeams() {
        UniverseRegistry.setStarLookup(null);
        UniverseRegistry.setGenerator(null);
    }

    private static DimensionProperties planet(int dimId, int orbitalDist, double theta) {
        DimensionProperties p = new DimensionProperties(dimId);
        p.orbitalDist = orbitalDist;
        p.orbitTheta = theta;
        p.orbitalPhi = 0;
        return p;
    }

    @Test
    public void authoredPlanetsGetTheirOwnCellsInsideTheSuperCellBox() {
        StellarBody star = new StellarBody();
        star.setId(4242);
        star.setName("TestStar");
        planet(700, 100, 0.0).setStar(star);       // setStar back-adds the planet to the star
        planet(701, 200, Math.PI / 2).setStar(star);

        GalacticCoord anchor = GalacticCoord.ofSectorLocal(10, 20, 30, 0, 0, 0);
        long s = GalaxyGenConfig.DEFAULT_MIN_SPACING;
        List<SystemBody> bodies = SystemContent.bodiesOf(star, anchor);

        assertEquals("first body is the star at the anchor cell", SystemBodyKind.STAR, bodies.get(0).kind());
        assertTrue(bodies.get(0).address().sameCell(anchor));
        assertEquals(0, bodies.get(0).address().localX());

        int planets = 0;
        SystemBody aPlanet = null;
        for (SystemBody b : bodies) {
            assertEquals("every body belongs to the star", 4242, b.starId());
            // Snapped to its own cell's centre (zone content sits near the cell centre — A#1a).
            assertEquals(0, b.address().localX());
            assertEquals(0, b.address().localY());
            assertEquals(0, b.address().localZ());
            // Inside the anchor's super-cell box, so member attribution stays exact.
            assertEquals(Math.floorDiv(anchor.sectorX(), s), Math.floorDiv(b.address().sectorX(), s));
            assertEquals(Math.floorDiv(anchor.sectorY(), s), Math.floorDiv(b.address().sectorY(), s));
            assertEquals(Math.floorDiv(anchor.sectorZ(), s), Math.floorDiv(b.address().sectorZ(), s));
            if (b.kind() == SystemBodyKind.PLANET) {
                planets++;
                aPlanet = b;
                assertFalse("a planet sits in its OWN cell, not the anchor's (A#1a)",
                        b.address().sameCell(anchor));
            }
        }
        assertEquals("both authored planets become bodies", 2, planets);
        assertNotNull(aPlanet);
        assertTrue("an authored planet body is a descend target (real dim)", aPlanet.isDescendTarget());
        assertTrue(aPlanet.dimId() == 700 || aPlanet.dimId() == 701);

        // Distinct orbits land in distinct cells (per-body cells are real, not a shared one).
        SystemBody first = null;
        for (SystemBody b : bodies) {
            if (b.kind() != SystemBodyKind.PLANET) {
                continue;
            }
            if (first == null) {
                first = b;
            } else {
                assertFalse("planets on different orbits sit in different cells",
                        b.address().sameCell(first.address()));
            }
        }
    }

    @Test
    public void planetResolvesToItsOwnCellThroughTheRegistry() {
        StellarBody star = new StellarBody();
        star.setId(4243);
        DimensionProperties p = planet(710, 120, 0.0);
        p.setStar(star);

        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord anchor = GalacticCoord.ofSectorLocal(5, 5, 5, 0, 0, 0);
        reg.place(anchor, 4243);

        // Without content resolution (star not in the catalogue) the seam falls back to the anchor.
        assertEquals("catalogue-miss fallback = the system anchor", Optional.of(anchor), reg.coordForPlanet(p));

        // With content resolvable, the planet resolves to its OWN cell (A#1a), which is where its body sits.
        UniverseRegistry.setStarLookup(id -> id == 4243 ? star : null);
        Optional<GalacticCoord> resolved = reg.coordForPlanet(p);
        assertTrue(resolved.isPresent());
        assertFalse("the planet's coord is its own zone cell, NOT the anchor (A#1a)",
                resolved.get().sameCell(anchor));

        GalacticCoord bodyCell = null;
        for (SystemBody b : reg.systemBodiesAt(anchor)) {
            if (b.dimId() == 710) {
                bodyCell = b.address().cellCentre();
            }
        }
        assertNotNull(bodyCell);
        assertEquals("coordForPlanet agrees with the body's own cell", bodyCell, resolved.get());
    }
}
