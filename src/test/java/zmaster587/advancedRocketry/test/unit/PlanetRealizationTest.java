package zmaster587.advancedRocketry.test.unit;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the registry half of realization — the half that decides whether a body has a
 * world, and therefore the half that has to be idempotent.
 *
 * <p>Minting the dimension itself needs a live server and is pinned by the server e2e. What is pinned
 * HERE is the property that makes minting safe to drive from a per-tick proximity check: asking twice
 * gives the same answer, and a body that already has a world is never handed a second one. If that ever
 * stopped holding, a pilot hovering at the descent boundary would allocate a dimension per tick.</p>
 */
public class PlanetRealizationTest {

    private static final long SEED = 0x5EED5EEDL;

    @After
    public void resetSeams() {
        UniverseRegistry.setGenerator(null);
        UniverseRegistry.setStarLookup(null);
    }

    /** The shipped spacing: a system sampled here is a system the game ships. */
    private static final int SPACING = GalaxyGenConfig.DEFAULT_MIN_SPACING;

    /** A dense, void-free galaxy, so the first super-cell probed holds a system. */
    private static UniverseRegistry registryWithProceduralGalaxy() {
        UniverseRegistry reg = new UniverseRegistry();
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(SPACING, 1.0d, GalaxyGenConfig.DEFAULT_GALAXY_SPACING,
                        GalaxyGenConfig.DEFAULT_GALAXY_DENSITY, null, null)));
        reg.bindWorldSeed(SEED);
        return reg;
    }

    /**
     * The seat of a system near the origin.
     *
     * <p>Probed one TERRITORY at a time, never cell by cell: a star's seat is one cell in a cube of
     * tens of millions, so a sweep of adjacent cells finds nothing however full the galaxy is. The
     * partition is the thing to walk, and it is what the generator itself walks — and it is asked
     * what the whole territory HOLDS, because a territory is divided uniformly and resolving its
     * corner point would sample one seat in k-cubed and read a full galaxy as an empty one.</p>
     */
    private static GalacticCoord systemAnchor(UniverseRegistry reg) {
        for (long i = 0; i <= 8; i++) {
            for (GalacticCoord anchor : reg.anchorsInTerritory(
                    GalacticCoord.ofSectorLocal(i * SPACING, 0L, 0L, 0L, 0L, 0L), 64)) {
                // A system with a STAR. A territory's seats include unbound worlds, which hold one
                // world and no retinue - everything below is about a body that ORBITS something.
                if (reg.starAt(anchor).isPresent()) {
                    return anchor;
                }
            }
        }
        return null;
    }

    /** The cell of the first body in that system a ship could land on but that has no world yet. */
    private static GalacticCoord findLandableCell(UniverseRegistry reg) {
        GalacticCoord anchor = systemAnchor(reg);
        if (anchor == null) {
            return null;
        }
        for (SystemBody b : reg.systemBodiesAt(anchor)) {
            if (b.kind().canDescend() && b.dimId() == Constants.INVALID_PLANET) {
                return b.name();
            }
        }
        return null;
    }

    /**
     * The first {@code (parent, moon)} pair found in a sweep of nearby systems, or {@code null}s.
     *
     * <p>Several systems, because moons are a draw: most bodies have none and a giant has several, so
     * one system is not guaranteed to hold a pair and a fixture that assumed it would be flaky for a
     * reason that has nothing to do with what it tests.</p>
     */
    private static SystemBody[] findPlanetWithMoon(UniverseRegistry reg) {
        for (long i = 0; i <= 8; i++) {
            for (GalacticCoord seat : reg.anchorsInTerritory(
                    GalacticCoord.ofSectorLocal(i * SPACING, 0L, 0L, 0L, 0L, 0L), 64)) {
            SystemBody parent = null;
            for (SystemBody b : reg.systemBodiesAt(seat)) {
                if (b.kind() != SystemBodyKind.MOON && b.kind().canDescend()) {
                    parent = b;
                } else if (b.kind() == SystemBodyKind.MOON && parent != null
                        && b.name().sameCell(parent.name())) {
                    return new SystemBody[] {parent, b};
                }
            }
            }
        }
        return new SystemBody[] {null, null};
    }

    @Test
    public void theProceduralGalaxyOffersLandableBodiesThatHaveNoWorldYet() {
        // The precondition of everything below, and the defect the whole batch exists to fix: the
        // generator places bodies a ship could stand on, and not one of them is a descent target.
        UniverseRegistry reg = registryWithProceduralGalaxy();
        GalacticCoord cell = findLandableCell(reg);
        assertNotNull("a dense procedural galaxy must contain landable bodies", cell);
        for (SystemBody b : reg.bodiesAt(cell)) {
            if (b.kind().canDescend()) {
                assertFalse("an unrealized body must not advertise itself as a descent target",
                        b.isDescendTarget());
            }
        }
    }

    /**
     * A moon carries TWO distances, and they are different numbers.
     *
     * <p>{@code SystemBody.orbitalDistance()} deliberately holds the PARENT's distance from the star,
     * because that is what a moon's climate depends on. Its own distance from the parent lives in its
     * ephemeris and nowhere else — which is exactly what realization needs to write into a moon's
     * {@code orbitalDist}, since that field means "from my parent" for a moon. If the generator ever
     * stops carrying it, a realized moon silently lands on top of its parent again.</p>
     */
    @Test
    public void aMoonCarriesItsOwnDistanceFromItsParentSeparatelyFromItsParentsFromTheStar() {
        UniverseRegistry reg = registryWithProceduralGalaxy();
        SystemBody[] pair = findPlanetWithMoon(reg);
        SystemBody itsParent = pair[0];
        SystemBody moon = pair[1];
        assertNotNull("the procedural galaxy must produce a moon to test with", moon);
        assertNotNull(itsParent);

        double ownDistance = moon.offsetLaw().distUnits();
        assertTrue("a moon's own distance from its parent must be a real, positive number: " + ownDistance,
                ownDistance > 0d);
        assertEquals("a moon's orbitalDistance() is its PARENT's distance from the star",
                itsParent.orbitalDistance(), moon.orbitalDistance());
        assertNotEquals("the two distances must not be the same number, or the seam is undetectable",
                (double) moon.orbitalDistance(), ownDistance, 1e-9);
    }

    /**
     * A procedural planet ORBITS its star, and its moons travel with it.
     *
     * <p>This was false: the convenience {@code SystemBody(address, kind, dimId, starId, orbit)}
     * constructor hard-wires a static frame and a fixed offset, so every procedural planet stood
     * still relative to its star forever — while its own moons orbited it, and while the identical
     * system authored in XML moved. Nothing pinned it, which is why it survived.</p>
     *
     * <p>Two assertions, because either alone can be satisfied by the wrong thing: the planet must
     * MOVE, and the moon must stay NEAR it while it does. A moon on its own static frame would leave
     * its planet behind; a planet that only moved because its moon's law leaked into it would drag
     * the separation open.</p>
     */
    @Test
    public void aProceduralPlanetOrbitsItsStarAndItsMoonsTravelWithIt() {
        UniverseRegistry reg = registryWithProceduralGalaxy();
        SystemBody[] pair = findPlanetWithMoon(reg);
        SystemBody planet = pair[0];
        SystemBody moon = pair[1];
        assertNotNull("the procedural galaxy must produce a planet with a moon", planet);
        assertNotNull(moon);

        // One Earth-like year of ticks. A body at any orbit this generator produces turns by a
        // substantial fraction of a revolution in that time, so "did it move" is not a rounding test.
        long later = 24000L * 48L;
        double planetTravelled = planet.absoluteAt(0L).minus(planet.absoluteAt(later)).length();
        assertTrue("a procedural planet must go round its star, not stand at a fixed point"
                + " (it moved " + planetTravelled + " blocks in a year)", planetTravelled > 1000d);

        double separationNow = planet.absoluteAt(0L).minus(moon.absoluteAt(0L)).length();
        double separationLater = planet.absoluteAt(later).minus(moon.absoluteAt(later)).length();
        assertTrue("a moon must ride its parent's frame, so their separation stays a moon's orbit"
                + " wide while both travel (" + separationNow + " -> " + separationLater
                + ", planet moved " + planetTravelled + ")",
                separationLater < planetTravelled / 2d);
    }

    @Test
    public void realizingABodyMakesItADescentTargetAndRecordsItsCellName() {
        UniverseRegistry reg = registryWithProceduralGalaxy();
        GalacticCoord cell = findLandableCell(reg);
        assertNotNull(cell);

        assertTrue("touching a procedural system must pin it before anything is written into it",
                reg.pinSystem(cell));
        assertTrue("the pinned body must accept a dimension", reg.realizeBody(cell, 4242));

        OptionalInt realized = reg.realizedDimAt(cell);
        assertTrue("the cell must now report a realized world", realized.isPresent());
        assertEquals(4242, realized.getAsInt());

        boolean sawTarget = false;
        for (SystemBody b : reg.bodiesAt(cell)) {
            if (b.dimId() == 4242) {
                assertTrue("a realized body must be a descent target", b.isDescendTarget());
                sawTarget = true;
            }
        }
        assertTrue(sawTarget);

        assertEquals("the body's cell must be recorded as that dimension's durable name",
                Optional.of(cell.cellCentre()), reg.recordedName(4242));
    }

    @Test
    public void asecondDescentIntoTheSameCellReusesTheWorld() {
        // The idempotency contract. The trigger is a per-tick proximity check, so "ask again" is the
        // normal case, not an edge one — a pilot who hovers at the boundary must not mint a dimension
        // per tick.
        UniverseRegistry reg = registryWithProceduralGalaxy();
        GalacticCoord cell = findLandableCell(reg);
        assertNotNull(cell);
        reg.pinSystem(cell);
        assertTrue(reg.realizeBody(cell, 777));

        assertEquals("asking again must answer the SAME world", 777,
                reg.realizedDimAt(cell).getAsInt());
        assertTrue("re-realizing with the same id is a no-op, not a failure",
                reg.realizeBody(cell, 777));
        assertEquals(777, reg.realizedDimAt(cell).getAsInt());
    }

    @Test
    public void aBodyThatAlreadyHasAWorldRefusesASecondOne() {
        UniverseRegistry reg = registryWithProceduralGalaxy();
        GalacticCoord cell = findLandableCell(reg);
        assertNotNull(cell);
        reg.pinSystem(cell);
        assertTrue(reg.realizeBody(cell, 100));

        assertFalse("a body must never be re-pointed at a different world", reg.realizeBody(cell, 200));
        assertEquals("and it must still hold the first one", 100, reg.realizedDimAt(cell).getAsInt());
    }

    @Test
    public void anUnpinnedSystemCannotBeRealizedIntoAtAll() {
        // Not a limitation but the mechanism: a derived body list is regenerated on the next query, so
        // writing a dimension into one would be writing into a value that is about to be thrown away.
        UniverseRegistry reg = registryWithProceduralGalaxy();
        GalacticCoord cell = findLandableCell(reg);
        assertNotNull(cell);
        assertFalse("an unpinned system must refuse the rewrite rather than lose it silently",
                reg.realizeBody(cell, 55));
        assertFalse(reg.realizedDimAt(cell).isPresent());
    }

    @Test
    public void aPinnedSystemsStarSurvivesAChangeOfGenerator() {
        // Realization derives a body's physics from its STAR, so the star a landing uses has to be the
        // one the scan described — even after a config edit that would have fabricated a different one.
        UniverseRegistry reg = registryWithProceduralGalaxy();
        GalacticCoord cell = findLandableCell(reg);
        assertNotNull(cell);
        reg.pinSystem(cell);

        Optional<StellarBody> before = reg.starAt(cell);
        assertTrue("a pinned system must have a star", before.isPresent());

        // A pack edit: a different spacing, a different density, a whole different galaxy.
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(SPACING / 2, 0.2d, GalaxyGenConfig.DEFAULT_GALAXY_SPACING,
                        GalaxyGenConfig.DEFAULT_GALAXY_DENSITY, null, null)));

        Optional<StellarBody> after = reg.starAt(cell);
        assertTrue(after.isPresent());
        assertEquals("a pinned star's identity must not move", before.get().getId(),
                after.get().getId());
        assertEquals("nor its temperature", before.get().getTemperature(), after.get().getTemperature());
        assertEquals("nor its size", before.get().getSize(), after.get().getSize(), 0f);
    }

    @Test
    public void aRealizedBodyKeepsItsCellItsOrbitAndItsKind() {
        // Realization materializes what was derived; it must not MOVE the body. An address a player
        // wrote down before landing has to keep denoting the world they landed on.
        UniverseRegistry reg = registryWithProceduralGalaxy();
        GalacticCoord cell = findLandableCell(reg);
        assertNotNull(cell);

        SystemBody before = null;
        for (SystemBody b : reg.bodiesAt(cell)) {
            if (b.kind().canDescend()) {
                before = b;
                break;
            }
        }
        assertNotNull(before);
        reg.pinSystem(cell);
        assertTrue(reg.realizeBody(cell, 999));

        SystemBody after = null;
        for (SystemBody b : reg.bodiesAt(cell)) {
            if (b.dimId() == 999) {
                after = b;
                break;
            }
        }
        assertNotNull(after);
        assertEquals("the cell name must not move", before.name(), after.name());
        assertEquals("the orbit must not move", before.orbitalDistance(), after.orbitalDistance());
        assertEquals("the kind must not change", before.kind(), after.kind());
        assertEquals("the owning system must not change", before.starId(), after.starId());
        assertNotEquals("but it must now have a world", before.dimId(), after.dimId());
    }

    @Test
    public void aProceduralBodyCarriesTheOrbitItsPhysicsWasDerivedFrom() {
        // The orbit travels ON the body so a pinned system's worlds stay derivable after any change to
        // the placement arithmetic. A body with no orbit would have no climate.
        UniverseRegistry reg = registryWithProceduralGalaxy();
        GalacticCoord cell = findLandableCell(reg);
        assertNotNull(cell);
        List<SystemBody> here = reg.bodiesAt(cell);
        boolean checked = false;
        for (SystemBody b : here) {
            if (b.kind() == SystemBodyKind.STAR) {
                continue;
            }
            assertTrue("a procedural body must carry a real orbital distance, got "
                    + b.orbitalDistance(), b.orbitalDistance() > 0);
            checked = true;
        }
        assertTrue(checked);
    }

    @Test
    public void theOrbitSurvivesAnNbtRoundTrip() {
        SystemBody body = SystemBody.fixedAt(GalacticCoord.ofSectorLocal(3, 4, 5, 0, 0, 0),
                SystemBodyKind.PLANET, 12, -7, 1234);
        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        body.writeToNBT(nbt);
        SystemBody back = SystemBody.readFromNBT(nbt);
        assertEquals("a pinned body's orbit must survive the save, or its world is not re-derivable",
                1234, back.orbitalDistance());
        assertEquals(body, back);
    }
}
