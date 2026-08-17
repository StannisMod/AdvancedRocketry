package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.BodyProfile;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Galaxy;
import zmaster587.advancedRocketry.universe.GalaxyField;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.PlanetDerivation;
import zmaster587.advancedRocketry.universe.PlanetarySystem;
import zmaster587.advancedRocketry.universe.StarCluster;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.UniverseScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for what is out there in the INTERGALACTIC VOID: rogue worlds, rogue stars, and the
 * globulars that were thrown clear of a galaxy. Pure JUnit; no MC bootstrap.
 *
 * <p>The void's content is not a second placement rule. It is the SAME star lattice, drawn a second
 * time against the SAME material function — the galaxy's own profile where there is a galaxy, and its
 * ejecta halo where there is not. So the contracts here are about that one function's shape and about
 * what a starless system is, never about the numbers either of them happens to be tuned to.</p>
 *
 * <p><b>Sampling is by SUPER-CELL and the sweeps are large.</b> Out past a galaxy's edge the occupancy
 * is percent-scale, so a sweep of a few dozen cubes finds nothing whatever the model says. Where a
 * count would need thousands of samples to be stable, the test reads the PROFILE instead, which is
 * exact and is the thing the contract is actually about.</p>
 */
public class VoidContentTest {

    private static final long SEED = 0x5EEDF00DL;
    private static final int SPACING = GalaxyGenConfig.DEFAULT_MIN_SPACING;

    /** Every cube occupied at the galaxy's densest point, so a void sweep is not fighting the draw too. */
    private static ClusteredGalaxyGenerator gen() {
        return new ClusteredGalaxyGenerator(new GalaxyGenConfig(SPACING, 1.0d,
                GalaxyGenConfig.DEFAULT_GALAXY_SPACING, GalaxyGenConfig.DEFAULT_GALAXY_DENSITY,
                null, null));
    }

    private static GalacticCoord cell(long sx, long sy, long sz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, 0L, 0L, 0L);
    }

    /**
     * A sector on the +X axis, {@code radii} of the home galaxy's radius out from its CENTRE.
     *
     * <p>Measured from the centre and in units of the radius, because the radius is drawn per seed: a
     * fixed light-year distance would be inside the galaxy on one seed and deep in the void on the
     * next, and the test would be pinning that draw rather than the model.</p>
     */
    private static long xAt(Galaxy home, double radii) {
        return home.centre().sectorX() + UniverseScale.cellsForLightYears(home.radiusLy() * radii);
    }

    // ─── The material function: what the void is made of ───────────────────────

    @Test
    public void pastAGalaxysEdgeTheMaterialIsUnboundAndOnlyUnbound() {
        // The split IS the model: inside a galaxy, material a star can condense out of; outside it,
        // material that was thrown out of one and can only be arrived at.
        ClusteredGalaxyGenerator gen = gen();
        Galaxy home = gen.galaxies().home(SEED);
        GalaxyField field = gen.galaxies();

        GalaxyField.Material inside = field.materialAtSector(SEED, home.centre().sectorX(),
                home.centre().sectorY(), home.centre().sectorZ());
        assertTrue("a galaxy's own centre must hold bound material", inside.bound > 0d);
        assertEquals("and none of it is unbound: the profile already counts every body there",
                0d, inside.unbound, 0d);

        // 1.5 radii out is outside the primary and out of every satellite's reach as well: a satellite
        // is seated at least one full DIAMETER out and is at most 0.3 R across, so the nearest surface
        // any of them can present is 1.7 R.
        GalaxyField.Material outside = field.materialAtSector(SEED, xAt(home, 1.5d),
                home.centre().sectorY(), home.centre().sectorZ());
        assertEquals("nothing FORMS past the declared radius", 0d, outside.bound, 0d);
        assertTrue("but the void is not empty: the galaxy's ejecta reaches into it",
                outside.unbound > 0d);
    }

    @Test
    public void theEjectaHaloThinsWithDistanceFromItsGalaxy() {
        // A power law anchored at the edge, so the void has a GRADIENT: a ship stepping out of a galaxy
        // meets worlds often and meets them less and less the further out it goes. A flat floor would
        // have made a galaxy's doorstep and the middle of nowhere read identically.
        ClusteredGalaxyGenerator gen = gen();
        Galaxy home = gen.galaxies().home(SEED);
        GalaxyField field = gen.galaxies();

        double previous = Double.MAX_VALUE;
        for (double radii : new double[] {1.5d, 3d, 6d, 12d, 24d}) {
            double here = field.materialAtSector(SEED, xAt(home, radii), home.centre().sectorY(),
                    home.centre().sectorZ()).unbound;
            assertTrue("the halo must be present at " + radii + " radii", here > 0d);
            assertTrue("the halo must thin outwards: " + here + " at " + radii
                    + " radii is not below " + previous, here < previous);
            previous = here;
        }
    }

    @Test
    public void aGalaxyCubeWithNoGalaxyInItIsCompletelyEmpty() {
        // The deepest void, and it is genuinely nothing: the population out here is what the cube's own
        // galaxies threw out, so a cube that never held one has thrown out nothing. That is what makes
        // half the universe a place only a galactic drive can cross, rather than a uniform fog.
        ClusteredGalaxyGenerator gen = gen();
        GalaxyField field = gen.galaxies();
        long spacing = GalaxyGenConfig.DEFAULT_GALAXY_SPACING;

        boolean checkedAny = false;
        for (long g = 1; g <= 40 && !checkedAny; g++) {
            if (field.galaxyAtIndex(SEED, g, 0L, 0L).isPresent()) {
                continue;
            }
            checkedAny = true;
            long sector = g * spacing + spacing / 2L;
            GalaxyField.Material material = field.materialAtSector(SEED, sector, 0L, 0L);
            assertEquals("an empty galaxy cube holds no bound material", 0d, material.bound, 0d);
            assertEquals("nor any ejecta: nothing was ever here to throw it", 0d, material.unbound, 0d);
            assertFalse("and therefore no system at all",
                    gen.anchorAt(SEED, cell(sector, 0L, 0L)).isPresent());
        }
        assertTrue("the sweep must find a galaxy cube that is empty", checkedAny);
    }

    // ─── What the second draw actually seats ───────────────────────────────────

    @Test
    public void theVoidHoldsSystemsAndTheyAreMostlyStarless() {
        // The whole point of the feature: out past the edge a ship meets things, and what it meets is
        // overwhelmingly a world with no sun. A rogue STAR is drawn from the same table at a small
        // weight, which is what makes finding a whole lit system out here an event rather than routine.
        ClusteredGalaxyGenerator gen = gen();
        Galaxy home = gen.galaxies().home(SEED);
        long x0 = xAt(home, 1.5d);

        int starless = 0;
        int lit = 0;
        Set<String> seen = new HashSet<>();
        for (long i = -6; i <= 6; i++) {
            for (long j = -6; j <= 6; j++) {
                for (long k = -6; k <= 6; k++) {
                    GalacticCoord probe = cell(x0 + i * SPACING,
                            home.centre().sectorY() + j * SPACING,
                            home.centre().sectorZ() + k * SPACING);
                    Optional<GalacticCoord> anchor = gen.anchorAt(SEED, probe);
                    if (!anchor.isPresent() || !seen.add(anchor.get().cellKey())) {
                        continue;
                    }
                    if (gen.systemAt(SEED, anchor.get()).get().star().isPresent()) {
                        lit++;
                    } else {
                        starless++;
                    }
                }
            }
        }
        assertTrue("the void just outside a galaxy must hold systems (found none in 13³ cubes)",
                starless + lit > 0);
        assertTrue("what it holds must be mostly starless (starless " + starless + ", lit " + lit + ")",
                starless > lit);
    }

    @Test
    public void aStarlessSystemNamesItselfAndIsFoundLikeAnyOther() {
        // Registered as an anchor is the whole of "discoverable": a survey resolves a look through the
        // system that OWNS the cell, so being registered IS being findable, and a rogue needed no
        // discovery mechanism of its own.
        ClusteredGalaxyGenerator gen = gen();
        GalacticCoord anchor = aRogueAnchor(gen);
        PlanetarySystem system = gen.systemAt(SEED, anchor).get();

        assertEquals("its primary is a starless world", SystemBodyKind.ROGUE_PLANET,
                system.primaryKind());
        assertFalse("and it has no star to be asked for", system.star().isPresent());
        assertFalse("it carries a designation of its own", system.name().isEmpty());
        assertTrue("its id is synthetic, so it can never collide with a catalogued star or a dim",
                system.systemId() < 0);

        // Member attribution works exactly as it does for a star: an ordinary cell beside the seat
        // resolves back to it, which is what lets a ship arrive anywhere near one and know where it is.
        Optional<GalacticCoord> viaMember = gen.anchorAt(SEED,
                anchor.plusLocal(GalacticCoord.CELL, 0L, 0L));
        assertTrue("a member cell must attribute to the rogue's anchor", viaMember.isPresent());
        assertTrue(viaMember.get().sameCell(anchor));
    }

    @Test
    public void aStarlessSystemIsTheWorldItsMoonsAndNothingElse() {
        // No belt and no companion, and neither is an omission: a belt is material that never accreted
        // in a star's own well, and a companion is another star. What survives being thrown out of a
        // system is the world and whatever was held tightly enough to come with it.
        ClusteredGalaxyGenerator gen = gen();
        GalacticCoord anchor = aRogueAnchor(gen);
        List<SystemBody> bodies = gen.bodiesFor(SEED, anchor);

        assertFalse("a rogue system must have bodies", bodies.isEmpty());
        assertEquals("the first is the rogue itself, at the anchor", SystemBodyKind.ROGUE_PLANET,
                bodies.get(0).kind());
        assertTrue(bodies.get(0).name().sameCell(anchor));

        int framesDefined = 0;
        for (SystemBody body : bodies) {
            assertTrue("everything a rogue keeps shares its one cell", body.name().sameCell(anchor));
            assertTrue("nothing here is a star or a belt",
                    body.kind() == SystemBodyKind.ROGUE_PLANET || body.kind() == SystemBodyKind.MOON);
            assertFalse("a rogue is not a descend target yet, so neither is anything in its system",
                    body.isDescendTarget());
            if (body.definesFrame()) {
                framesDefined++;
            }
        }
        assertEquals("AT MOST ONE REAL BODY PER CELL holds for a rogue too, moons excepted",
                1, framesDefined);
        assertEquals("and it is deterministic", bodies, gen.bodiesFor(SEED, anchor));
    }

    // ─── What a starless world IS ──────────────────────────────────────────────

    @Test
    public void aRogueIsWarmedByItselfAndByNothingElse() {
        // Its temperature is leftover formation heat leaking out through its own surface, so it is a
        // function of the body and of nothing external — which is the design opportunity in having no
        // star, rather than a gap where the insolation used to be.
        ClusteredGalaxyGenerator gen = gen();
        GalacticCoord anchor = aRogueAnchor(gen);
        BodyProfile profile = PlanetDerivation.deriveRogue(SEED, anchor, 0);

        assertEquals(SystemBodyKind.ROGUE_PLANET, profile.kind());
        assertTrue("a starless world is colder than anything a star lights: " + profile.temperatureKelvin()
                + " K", profile.temperatureKelvin() < 200);
        assertTrue("but it is not at absolute zero either", profile.temperatureKelvin() > 0);
        assertFalse("free oxygen is biology AND a gas; a world whose air is ice on the ground has neither",
                profile.hasOxygen());
        assertFalse("there is nothing for it to be tidally locked TO", profile.tidallyLocked());
        assertEquals("and no orbit of its own", SystemBody.ORBIT_UNKNOWN, profile.orbitalDistance());
        assertEquals("deterministic, like every other derived body",
                profile.temperatureKelvin(),
                PlanetDerivation.deriveRogue(SEED, anchor, 0).temperatureKelvin());
    }

    @Test
    public void aHeavierRogueRunsWarmerThanALighterOne() {
        // The law and not the draw: heat leaks out in proportion to the mass behind each square metre
        // of surface, which is the same M/R² this derivation already calls gravity. A test that pinned
        // the constant would be pinning a balance number; what is a contract is the DIRECTION.
        int earthLike = PlanetDerivation.residualTemperature(1d, 1d);
        int heavy = PlanetDerivation.residualTemperature(10d, 1.5d);
        int feather = PlanetDerivation.residualTemperature(0.05d, 0.5d);

        assertTrue("a heavier world holds more of its own heat: " + heavy + " K vs " + earthLike + " K",
                heavy > earthLike);
        assertTrue("and a small light one has almost none left: " + feather + " K vs " + earthLike + " K",
                feather < earthLike);
    }

    // ─── Clusters that were thrown clear of a galaxy ────────────────────────────

    @Test
    public void anIntergalacticClusterIsSeatedAndItIsSelfBound() {
        // Seating one outside a galaxy used to be refused by construction, on the reasoning that there
        // would be no stars out there to gather. A cluster does not gather the field — it arrived with
        // its own — so the refusal is lifted, and what is lifted with it is only the types that could
        // actually survive the crossing.
        ClusteredGalaxyGenerator gen = gen();
        Galaxy home = gen.galaxies().home(SEED);
        long clusterSpacing = gen.clusters().spacingSuperCells();
        long baseIndex = Math.floorDiv(Math.floorDiv(xAt(home, 1.5d), (long) SPACING), clusterSpacing);

        StarCluster found = null;
        for (long i = 0; i < 400 && found == null; i++) {
            Optional<StarCluster> cluster = gen.clusters().clusterAtIndex(SEED, null,
                    baseIndex + i, 0L, 0L);
            if (cluster.isPresent()) {
                found = cluster.get();
            }
        }
        assertNotNull("the void must be able to hold a cluster at all", found);
        assertTrue("and only a SELF-BOUND one: an open cluster or a cloud would have dispersed on the "
                + "way out. Got " + found.type().name, found.type().selfBound);
    }

    @Test
    public void aClusterOutsideAGalaxyStillHoldsItsStars() {
        // The reason the type filter is not the whole story. A cluster's density is expressed as a
        // CONTRAST against what surrounds it, and out here what surrounds it is nearly nothing — so
        // k³ times nearly nothing would have produced a globular that was named, addressable and
        // completely empty. It brings its own field, so it holds what a globular holds.
        ClusteredGalaxyGenerator gen = gen();
        Galaxy home = gen.galaxies().home(SEED);
        long clusterSpacing = gen.clusters().spacingSuperCells();
        long baseIndex = Math.floorDiv(Math.floorDiv(xAt(home, 1.5d), (long) SPACING), clusterSpacing);

        StarCluster cluster = null;
        for (long i = 0; i < 400 && cluster == null; i++) {
            Optional<StarCluster> c = gen.clusters().clusterAtIndex(SEED, null, baseIndex + i, 0L, 0L);
            if (c.isPresent()) {
                cluster = c.get();
            }
        }
        assertNotNull(cluster);

        // Probe the coarse super-cell the cluster's own core sits in, against one well outside it.
        long inX = cluster.centreSuperX();
        long inY = cluster.centreSuperY();
        long inZ = cluster.centreSuperZ();
        int inside = 0;
        for (long d = 0; d < 4; d++) {
            if (gen.anchorAt(SEED, cell((inX + d) * SPACING, inY * SPACING, inZ * SPACING)).isPresent()) {
                inside++;
            }
        }
        assertTrue("a cluster out in the void must still be full of systems (found " + inside
                + " in 4 probes at its core)", inside > 0);
    }

    /**
     * The anchor of the first STARLESS system found just outside the home galaxy.
     *
     * <p>Swept rather than named: which cube holds one is a draw, so a fixture that insisted on one
     * particular cube would be testing the draw. It fails loudly if the sweep comes up dry, because a
     * silent skip here would make every test that uses it vacuous.</p>
     */
    private static GalacticCoord aRogueAnchor(ClusteredGalaxyGenerator gen) {
        Galaxy home = gen.galaxies().home(SEED);
        long x0 = xAt(home, 1.5d);
        for (long i = -6; i <= 6; i++) {
            for (long j = -6; j <= 6; j++) {
                for (long k = -6; k <= 6; k++) {
                    Optional<GalacticCoord> anchor = gen.anchorAt(SEED,
                            cell(x0 + i * SPACING, home.centre().sectorY() + j * SPACING,
                                    home.centre().sectorZ() + k * SPACING));
                    if (anchor.isPresent()
                            && !gen.systemAt(SEED, anchor.get()).get().star().isPresent()) {
                        return anchor.get();
                    }
                }
            }
        }
        throw new AssertionError("no starless system anywhere in 13³ super-cells just outside the home "
                + "galaxy - the void draw is not producing anything");
    }
}
