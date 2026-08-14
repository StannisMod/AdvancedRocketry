package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.EmptyGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.IGalaxyGenerator;
import zmaster587.advancedRocketry.universe.StarSystem;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the Layer-1 universe registry: the cell-keyed coord&harr;system placement
 * index, its NBT override store, the planet&rarr;coord seam, the anchor-drain lifecycle, and the pluggable
 * generator. Pure-JUnit — no MC bootstrap. The forward coord&rarr;system path resolves stars through the
 * injectable {@link UniverseRegistry#setStarLookup} seam so it never boots the legacy catalogue.
 *
 * <p>These pin the placement CONTRACTS (coord&harr;system both ways, key-by-cell, override round-trip,
 * location-agnostic systems), not internal field shapes.</p>
 */
public class UniverseRegistryTest {

    /**
     * A sector far enough away to be a DIFFERENT system's territory. An anchor owns every cell of its
     * super-cell, so "elsewhere" has to be stated in super-cells; a literal few thousand sectors is
     * the same neighbourhood, and a fixture using one proves nothing about attribution.
     */
    private static final long ANOTHER_SUPER_CELL = 2L * GalaxyGenConfig.DEFAULT_MIN_SPACING;

    private static StellarBody star(int id) {
        StellarBody s = new StellarBody();
        s.setId(id);
        s.setName("Star-" + id);
        return s;
    }

    /** Restore the JVM-global seams after any test that swapped them. */
    @After
    public void resetSeams() {
        UniverseRegistry.setGenerator(null);
        UniverseRegistry.setStarLookup(null);
    }

    @Test
    public void storageKeyIsStable() {
        // The .dat filename in the save; renaming silently orphans the whole placement store.
        assertEquals("advancedrocketry_universe", UniverseRegistry.STORAGE_KEY);
    }

    @Test
    public void placeRoundTripsBothDirectionsInMemory() {
        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord cell = GalacticCoord.ofSectorLocal(3, -4, 5, 0, 0, 0);
        reg.place(cell, 7);

        assertEquals(7, reg.starIdForCoord(cell).getAsInt());
        assertEquals(Optional.of(cell), reg.coordForSystem(7));
        assertTrue("placing a system must mark the saved-data dirty", reg.isDirty());
    }

    @Test
    public void keysByCellNotExactPosition() {
        UniverseRegistry reg = new UniverseRegistry();
        // Place with a local-carrying coord; two positions in the same cell must resolve identically.
        GalacticCoord placed = GalacticCoord.ofSectorLocal(10, 20, 30, 100_000, -200_000, 300_000);
        reg.place(placed, 42);

        GalacticCoord elsewhereInSameCell = GalacticCoord.ofSectorLocal(10, 20, 30, -50_000, 60_000, -70_000);
        assertEquals(42, reg.starIdForCoord(elsewhereInSameCell).getAsInt());

        // The stored coord is the cell centre (locals zeroed) — the registry never keys by exact position.
        GalacticCoord stored = reg.coordForSystem(42).get();
        assertEquals(0, stored.localX());
        assertEquals(0, stored.localY());
        assertEquals(0, stored.localZ());
        assertEquals(placed.cellKey(), stored.cellKey());

        // A different cell never collides.
        GalacticCoord otherCell = GalacticCoord.ofSectorLocal(10, 20, 31, 0, 0, 0);
        assertFalse(reg.starIdForCoord(otherCell).isPresent());
    }

    @Test
    public void overrideStoreRoundTripsThroughNbt() {
        UniverseRegistry source = new UniverseRegistry();
        source.place(GalacticCoord.ofSectorLocal(1, 1, 1, 0, 0, 0), 5);
        source.place(GalacticCoord.ofSectorLocal(-9, 0, 42, 0, 0, 0), 8);

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);

        UniverseRegistry round = new UniverseRegistry();
        round.readFromNBT(tag);

        assertEquals(Optional.of(GalacticCoord.ofSectorLocal(1, 1, 1, 0, 0, 0)), round.coordForSystem(5));
        assertEquals(Optional.of(GalacticCoord.ofSectorLocal(-9, 0, 42, 0, 0, 0)), round.coordForSystem(8));
        assertEquals(5, round.starIdForCoord(GalacticCoord.ofSectorLocal(1, 1, 1, 0, 0, 0)).getAsInt());
        assertEquals(8, round.starIdForCoord(GalacticCoord.ofSectorLocal(-9, 0, 42, 0, 0, 0)).getAsInt());
    }

    /**
     * A body's cell NAME survives a save, and the loaded name beats what a fresh derivation would
     * now say.
     *
     * <p>This is the guarantee the whole store exists for, and it is the one with the worst failure:
     * a coordinate reads back as {@code ORIGIN} when its sub-tag is missing, so a broken write does
     * not throw — it silently addresses every body in the galaxy at one cell, forever, on the next
     * restart. The authored angle is CHANGED between save and load so a re-derivation would give a
     * different answer; without that the test would pass against a registry that persisted nothing
     * and simply re-derived the same value.</p>
     */
    @Test
    public void cellNamesRoundTripThroughNbtAndBeatALaterDerivation() {
        StellarBody host = star(4321);
        host.setSize(1f);
        DimensionProperties body = new DimensionProperties(4322);
        body.orbitalDist = 120;
        body.baseOrbitTheta = 0.4;
        body.orbitalPhi = 0;
        body.setStar(host);
        UniverseRegistry.setStarLookup(id -> id == 4321 ? host : null);

        UniverseRegistry source = new UniverseRegistry();
        source.place(GalacticCoord.ORIGIN, 4321);
        Optional<GalacticCoord> namedAtFirstDerivation = source.coordForPlanet(body);
        assertTrue("the fixture must derive a name at all", namedAtFirstDerivation.isPresent());

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);

        // The authored orbit changes under it — a re-save of the world's XML, an author's edit, a
        // change to this arithmetic. A recorded name must not notice.
        body.baseOrbitTheta = 2.9;

        UniverseRegistry round = new UniverseRegistry();
        round.readFromNBT(tag);
        round.place(GalacticCoord.ORIGIN, 4321);

        assertEquals("a recorded name must survive the save and win over a fresh derivation",
                namedAtFirstDerivation, round.coordForPlanet(body));
        assertEquals("...and it must be the recorded one, not a coincidence of re-derivation",
                namedAtFirstDerivation, round.recordedName(4322));

        // The negative leg: a registry that loaded nothing derives from the CHANGED orbit instead,
        // which is what proves the assertion above is about persistence and not about determinism.
        UniverseRegistry fresh = new UniverseRegistry();
        fresh.place(GalacticCoord.ORIGIN, 4321);
        assertFalse("a fresh registry must derive the CHANGED orbit's name, or this test proves nothing",
                namedAtFirstDerivation.equals(fresh.coordForPlanet(body)));
    }

    @Test
    public void emptyRegistryRoundTripsToEmpty() {
        UniverseRegistry source = new UniverseRegistry();
        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);

        UniverseRegistry round = new UniverseRegistry();
        round.readFromNBT(tag);
        assertFalse(round.coordForSystem(0).isPresent());
        assertFalse(round.starIdForCoord(GalacticCoord.ORIGIN).isPresent());
    }

    @Test
    public void reverseLookupViaStarProxyDimension() {
        // The dim->coord seam: a star's proxy dimension id (STAR_ID_OFFSET + starId) resolves to the
        // system's coordinate without touching the catalogue.
        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord cell = GalacticCoord.ofSectorLocal(2, 2, 2, 0, 0, 0);
        reg.place(cell, 3);

        assertEquals(reg.coordForSystem(3), reg.coordForPlanet(Constants.STAR_ID_OFFSET + 3));
        assertEquals(Optional.of(cell), reg.coordForPlanet(Constants.STAR_ID_OFFSET + 3));
        // An unplaced system's proxy dim resolves to nothing.
        assertFalse(reg.coordForPlanet(Constants.STAR_ID_OFFSET + 99).isPresent());
    }

    @Test
    public void removeDropsThePlacement() {
        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord cell = GalacticCoord.ofSectorLocal(6, 6, 6, 0, 0, 0);
        reg.place(cell, 11);
        assertTrue(reg.hasOverrideAt(cell));

        assertTrue(reg.remove(cell));
        assertFalse(reg.hasOverrideAt(cell));
        assertFalse(reg.coordForSystem(11).isPresent());
        assertFalse("removing a non-existent cell returns false", reg.remove(cell));
    }

    @Test
    public void rePlaceMovesTheSystemAndFreesTheOldCell() {
        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord first = GalacticCoord.ofSectorLocal(1, 0, 0, 0, 0, 0);
        GalacticCoord second = GalacticCoord.ofSectorLocal(2, 0, 0, 0, 0, 0);
        reg.place(first, 4);
        reg.place(second, 4);

        // one-coord-per-system: the star moved; its old cell is now vacant.
        assertEquals(Optional.of(second), reg.coordForSystem(4));
        assertFalse("the vacated cell must no longer resolve", reg.starIdForCoord(first).isPresent());
        assertEquals(4, reg.starIdForCoord(second).getAsInt());
    }

    @Test
    public void collidingPlacementDisplacesTheOccupant() {
        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord cell = GalacticCoord.ofSectorLocal(7, 7, 7, 0, 0, 0);
        reg.place(cell, 1);
        reg.place(cell, 2); // one-system-per-cell: star 2 takes the cell, star 1 is displaced

        assertEquals(2, reg.starIdForCoord(cell).getAsInt());
        assertFalse("the displaced system no longer has a coord", reg.coordForSystem(1).isPresent());
        assertEquals(Optional.of(cell), reg.coordForSystem(2));
    }

    @Test
    public void defaultGeneratorIsVoidAndDeterministic() {
        IGalaxyGenerator gen = new EmptyGalaxyGenerator();
        GalacticCoord a = GalacticCoord.ofSectorLocal(1, 2, 3, 0, 0, 0);
        assertFalse(gen.systemAt(12345L, a).isPresent());
        assertFalse(gen.systemAt(999L, a).isPresent());
        // Same (seed, coord) is stably empty; region enumeration is empty.
        assertEquals(gen.systemAt(12345L, a), gen.systemAt(12345L, a));
        assertTrue(gen.systemsInRegion(12345L, GalacticCoord.ORIGIN, a).isEmpty());
    }

    @Test
    public void systemForCoordPrefersStoredOverGenerator() {
        StellarBody stored = star(42);
        UniverseRegistry.setStarLookup(id -> id == 42 ? stored : null);
        // A generator that would claim EVERY cell — the stored placement must still win at its cell.
        UniverseRegistry.setGenerator(new AllClaimingGenerator(star(777)));

        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord placedCell = GalacticCoord.ofSectorLocal(5, 5, 5, 0, 0, 0);
        reg.place(placedCell, 42);

        Optional<StarSystem> atPlaced = reg.systemForCoord(placedCell);
        assertTrue(atPlaced.isPresent());
        assertSame("stored placement must win over the generator", stored, atPlaced.get().star());
        assertEquals(42, atPlaced.get().starId());

        // A member cell of the stored anchor's super-cell attributes to the STORED system, not the
        // generator: an authored anchor owns every cell of its super-cell.
        Optional<StarSystem> nearStored = reg.systemForCoord(GalacticCoord.ofSectorLocal(6, 6, 6, 0, 0, 0));
        assertTrue(nearStored.isPresent());
        assertEquals(42, nearStored.get().starId());

        // A cell in a DIFFERENT super-cell falls through to the generator.
        Optional<StarSystem> farAway = reg.systemForCoord(
                GalacticCoord.ofSectorLocal(ANOTHER_SUPER_CELL, ANOTHER_SUPER_CELL, ANOTHER_SUPER_CELL, 0, 0, 0));
        assertTrue(farAway.isPresent());
        assertEquals(777, farAway.get().starId());
    }

    @Test
    public void memberCellResolvesToItsOwningProceduralSystem() {
        // Member semantics end-to-end through the registry: a system is a neighbourhood of cells round
        // its anchor, so a planet's own zone cell (and the void between bodies) resolves to the owning
        // system; the zone read returns exactly that cell's body.
        UniverseRegistry reg = new UniverseRegistry();
        reg.bindWorldSeed(0xBEEF);
        GalaxyGenConfig cfg = new GalaxyGenConfig(16, 0.9d, GalaxyGenConfig.DEFAULT_GALAXY_SPACING,
                GalaxyGenConfig.DEFAULT_GALAXY_DENSITY, null, null);
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(cfg));

        // Find an occupied super-cell and a non-star body of its system.
        GalacticCoord anchor = null;
        SystemBody planet = null;
        for (long sup = 0; sup < 8 && planet == null; sup++) {
            Optional<StarSystem> sys = reg.systemForCoord(
                    GalacticCoord.ofSectorLocal(sup * cfg.minSpacing, 0, 0, 0, 0, 0));
            if (!sys.isPresent()) {
                continue;
            }
            for (SystemBody b : reg.systemBodiesAt(
                    GalacticCoord.ofSectorLocal(sup * cfg.minSpacing, 0, 0, 0, 0, 0))) {
                if (b.kind() == SystemBodyKind.STAR) {
                    anchor = b.name();
                } else if (planet == null) {
                    planet = b;
                }
            }
        }
        assertNotNull("need a procedural system with a non-star body", planet);
        assertNotNull(anchor);
        assertFalse("the sampled body must sit in its OWN cell", planet.name().sameCell(anchor));

        // The body's cell resolves to the same system (member attribution).
        Optional<StarSystem> atBody = reg.systemForCoord(planet.name());
        assertTrue(atBody.isPresent());
        assertEquals(planet.starId(), atBody.get().starId());

        // Zone read at the body's cell returns the body; at the anchor it returns the star, not the body.
        List<SystemBody> zone = reg.bodiesAt(planet.name());
        assertTrue("the zone read must contain the cell's own body", zone.contains(planet));
        for (SystemBody b : reg.bodiesAt(anchor)) {
            assertTrue("the anchor's zone holds only anchor-cell bodies", b.name().sameCell(anchor));
        }

        // System read from the member cell returns the whole neighbourhood (star included).
        boolean sawStar = false;
        for (SystemBody b : reg.systemBodiesAt(planet.name())) {
            if (b.kind() == SystemBodyKind.STAR) {
                sawStar = true;
            }
        }
        assertTrue("the system read from a member cell must include the star", sawStar);
    }

    @Test
    public void pinOnTouchSnapshotsAProceduralSystemAgainstSeedChange() {
        UniverseRegistry reg = new UniverseRegistry();
        reg.bindWorldSeed(1234L);
        GalaxyGenConfig cfg = new GalaxyGenConfig(8, 0.9d, GalaxyGenConfig.DEFAULT_GALAXY_SPACING,
                GalaxyGenConfig.DEFAULT_GALAXY_DENSITY, null, null);
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(cfg));

        GalacticCoord anchor = null;
        for (long sup = 0; sup < 8 && anchor == null; sup++) {
            GalacticCoord probe = GalacticCoord.ofSectorLocal(sup * cfg.minSpacing, 0, 0, 0, 0, 0);
            Optional<GalacticCoord> a = reg.anchorForCell(probe);
            if (a.isPresent()) {
                anchor = a.get();
            }
        }
        assertNotNull("need an occupied procedural super-cell", anchor);

        int starIdBefore = reg.systemForCoord(anchor).get().starId();
        List<SystemBody> bodiesBefore = reg.systemBodiesAt(anchor);

        // TOUCH: pin the system (addPoi would do the same implicitly).
        assertTrue("first touch must write a pin", reg.pinSystem(anchor));
        assertFalse("a second touch is a no-op", reg.pinSystem(anchor));

        // A config/seed change (the drift scenario) must NOT move or reshape the pinned system…
        reg.bindWorldSeed(999_999L);
        assertEquals("pinned system survives a seed change", starIdBefore,
                reg.systemForCoord(anchor).get().starId());
        assertEquals("pinned bodies survive a seed change", bodiesBefore, reg.systemBodiesAt(anchor));

        // …and the pin round-trips through NBT (reads from the save, not the generator or catalogue).
        NBTTagCompound tag = new NBTTagCompound();
        reg.writeToNBT(tag);
        UniverseRegistry round = new UniverseRegistry();
        round.readFromNBT(tag);
        round.bindWorldSeed(999_999L);
        assertTrue(round.systemForCoord(anchor).isPresent());
        assertEquals(starIdBefore, round.systemForCoord(anchor).get().starId());
        assertEquals(bodiesBefore, round.systemBodiesAt(anchor));
    }

    @Test
    public void systemForCoordIsEmptyOnVoidCellWithDefaultGenerator() {
        UniverseRegistry reg = new UniverseRegistry();
        assertFalse(reg.systemForCoord(GalacticCoord.ofSectorLocal(3, 3, 3, 0, 0, 0)).isPresent());
    }

    @Test
    public void systemsAreLocationAgnostic() {
        // The coordinate is obtainable ONLY from the registry; the system handle exposes no coordinate.
        StellarBody body = star(9);
        StarSystem sys = new StarSystem(body);
        assertEquals(9, sys.starId());
        assertSame(body, sys.star());

        UniverseRegistry reg = new UniverseRegistry();
        assertFalse("an unregistered system has no coord", reg.coordForStar(body).isPresent());
        reg.place(GalacticCoord.ofSectorLocal(8, 8, 8, 0, 0, 0), 9);
        assertTrue(reg.coordForStar(body).isPresent());
    }

    @Test
    public void anchorsDrainOnceThenPersistedStoreWins() {
        UniverseRegistry reg = new UniverseRegistry();
        Map<Integer, GalacticCoord> anchors = new HashMap<>();
        anchors.put(1, GalacticCoord.ofSectorLocal(1, 0, 0, 0, 0, 0));
        anchors.put(2, GalacticCoord.ofSectorLocal(2, 0, 0, 0, 0, 0));

        reg.applyAnchors(anchors, false);
        assertEquals(Optional.of(GalacticCoord.ofSectorLocal(1, 0, 0, 0, 0, 0)), reg.coordForSystem(1));
        assertEquals(Optional.of(GalacticCoord.ofSectorLocal(2, 0, 0, 0, 0, 0)), reg.coordForSystem(2));

        // Second drain with DIFFERENT anchors is a no-op (already seeded) unless a reset is forced.
        Map<Integer, GalacticCoord> moved = new HashMap<>();
        moved.put(1, GalacticCoord.ofSectorLocal(50, 0, 0, 0, 0, 0));
        reg.applyAnchors(moved, false);
        assertEquals("re-draining without reset must not move the anchor",
                Optional.of(GalacticCoord.ofSectorLocal(1, 0, 0, 0, 0, 0)), reg.coordForSystem(1));

        // A forced reset re-applies.
        reg.applyAnchors(moved, true);
        assertEquals(Optional.of(GalacticCoord.ofSectorLocal(50, 0, 0, 0, 0, 0)), reg.coordForSystem(1));
    }

    @Test
    public void anchorsSeededLatchPersistsThroughNbt() {
        UniverseRegistry source = new UniverseRegistry();
        source.applyAnchors(new HashMap<>(), false); // seeds the latch even with no anchors

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);
        UniverseRegistry round = new UniverseRegistry();
        round.readFromNBT(tag);

        // The latch survived, so a fresh anchor drain is ignored (persisted store wins across restarts).
        Map<Integer, GalacticCoord> anchors = new HashMap<>();
        anchors.put(3, GalacticCoord.ofSectorLocal(3, 0, 0, 0, 0, 0));
        round.applyAnchors(anchors, false);
        assertFalse("a restart must not re-seed anchors over the persisted store",
                round.coordForSystem(3).isPresent());
    }

    @Test
    public void fallbackCoordsAreTotalAndCollisionFree() {
        UniverseRegistry reg = new UniverseRegistry();
        // Sol (id 0) is pre-placed at the origin by an anchor; a fallback for another star must not evict it.
        reg.place(GalacticCoord.ORIGIN, 0);

        reg.assignFallbackCoords(java.util.Arrays.asList(star(0), star(1), star(2)));

        assertEquals(Optional.of(GalacticCoord.ORIGIN), reg.coordForSystem(0));
        assertTrue("every catalogued star gets a coord", reg.coordForSystem(1).isPresent());
        assertTrue(reg.coordForSystem(2).isPresent());
        // Distinct cells for distinct stars.
        assertFalse(reg.coordForSystem(1).equals(reg.coordForSystem(2)));
        assertFalse(reg.coordForSystem(1).equals(reg.coordForSystem(0)));
    }

    @Test
    public void anchorFormatRoundTrips() {
        GalacticCoord c = GalacticCoord.ofSectorLocal(12, -34, 56, 0, 0, 0);
        assertEquals(c, UniverseRegistry.parseAnchor(UniverseRegistry.formatAnchor(c)));
        assertEquals("12,-34,56", UniverseRegistry.formatAnchor(c));
        // A local-carrying coord formats to its cell centre.
        GalacticCoord withLocal = GalacticCoord.ofSectorLocal(12, -34, 56, 111, 222, 333);
        assertEquals("12,-34,56", UniverseRegistry.formatAnchor(withLocal));
        // Blank / malformed defaults to the origin (Sol default).
        assertEquals(GalacticCoord.ORIGIN, UniverseRegistry.parseAnchor(null));
        assertEquals(GalacticCoord.ORIGIN, UniverseRegistry.parseAnchor(""));
        assertEquals(GalacticCoord.ORIGIN, UniverseRegistry.parseAnchor("not,a,number"));
        assertEquals(GalacticCoord.ORIGIN, UniverseRegistry.parseAnchor("1,2"));
    }

    @Test
    public void worldSeedIsTransientAndNotPersisted() {
        UniverseRegistry source = new UniverseRegistry();
        source.bindWorldSeed(123456789L);
        assertEquals(123456789L, source.worldSeed());

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);
        UniverseRegistry round = new UniverseRegistry();
        round.readFromNBT(tag);
        assertEquals("the seed is re-derived on load, never persisted", 0L, round.worldSeed());
    }

    @Test
    public void poiStoreRoundTripsThroughNbt() {
        UniverseRegistry source = new UniverseRegistry();
        GalacticCoord sys = GalacticCoord.ofSectorLocal(3, 3, 3, 0, 0, 0);
        source.addPoi(SystemBody.fixedAt(GalacticCoord.ofSectorLocal(3, 3, 3, 50_000, 0, 0),
                SystemBodyKind.STATION_SLOT, Constants.INVALID_PLANET, 7));
        source.addPoi(SystemBody.fixedAt(GalacticCoord.ofSectorLocal(3, 3, 3, -20_000, 10_000, 0),
                SystemBodyKind.ASTEROID_BELT, Constants.INVALID_PLANET, 7));
        assertTrue("adding a POI must mark dirty", source.isDirty());

        NBTTagCompound tag = new NBTTagCompound();
        source.writeToNBT(tag);
        UniverseRegistry round = new UniverseRegistry();
        round.readFromNBT(tag);

        List<SystemBody> pois = round.poisAt(sys);
        assertEquals("both POIs must round-trip, keyed by their system cell", 2, pois.size());
        assertTrue(round.removePois(sys));
        assertTrue(round.poisAt(sys).isEmpty());
    }

    @Test
    public void bodiesAtIsEmptyOnVoidCellWithDefaultGenerator() {
        UniverseRegistry reg = new UniverseRegistry();
        assertTrue(reg.bodiesAt(GalacticCoord.ofSectorLocal(9, 9, 9, 0, 0, 0)).isEmpty());
    }

    @Test
    public void bodiesAtMergesProceduralBodiesAndPois() {
        UniverseRegistry reg = new UniverseRegistry();
        reg.bindWorldSeed(0xABCDEFL);
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(new GalaxyGenConfig(1, 0.9d,
                GalaxyGenConfig.DEFAULT_GALAXY_SPACING, GalaxyGenConfig.DEFAULT_GALAXY_DENSITY,
                null, null)));

        GalacticCoord found = null;
        for (long x = 0; x < 300 && found == null; x++) {
            GalacticCoord c = GalacticCoord.ofSectorLocal(x, 0, 0, 0, 0, 0);
            if (reg.systemForCoord(c).isPresent() && !reg.hasOverrideAt(c)) {
                found = c;
            }
        }
        assertNotNull("a procedural system must exist to test against", found);

        List<SystemBody> procedural = reg.bodiesAt(found);
        assertFalse("a procedural system must have bodies", procedural.isEmpty());
        int before = procedural.size();

        reg.addPoi(SystemBody.fixedAt(
                GalacticCoord.ofSectorLocal(found.sectorX(), found.sectorY(), found.sectorZ(), 100_000, 0, 0),
                SystemBodyKind.STATION_SLOT, Constants.INVALID_PLANET, -5));
        List<SystemBody> merged = reg.bodiesAt(found);
        assertEquals("bodiesAt must merge the added POI", before + 1, merged.size());
        boolean sawStation = false;
        for (SystemBody b : merged) {
            if (b.kind() == SystemBodyKind.STATION_SLOT) {
                sawStation = true;
            }
        }
        assertTrue("the player POI must appear in bodiesAt", sawStation);
    }

    // ── A recorded name has a LIFECYCLE (ledger #154, #155) ─────────────────────────────────────

    /** A catalogued star's authored planet, wired through the lookup seam. */
    private static DimensionProperties bodyOfStar(StellarBody host, int dimId, int dist, double theta) {
        DimensionProperties body = new DimensionProperties(dimId);
        body.orbitalDist = dist;
        body.baseOrbitTheta = theta;
        body.orbitalPhi = 0;
        body.setStar(host);
        return body;
    }

    /**
     * Ledger #155. A dimension id goes straight back into circulation when a planet is deleted, so a
     * name kept on the id alone is inherited by whatever is generated next. The two bodies then
     * belong to DIFFERENT systems, so nothing downstream ever compares them: the collision audit is
     * per-system, and attribution answers happily with the wrong anchor. The name has to know which
     * system it was recorded for.
     */
    @Test
    public void aRecycledDimensionIdDoesNotInheritTheOldBodysName() {
        StellarBody sol = star(6001);
        sol.setSize(1f);
        StellarBody other = star(6002);
        other.setSize(1f);
        UniverseRegistry.setStarLookup(id -> id == 6001 ? sol : (id == 6002 ? other : null));

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 6001);
        reg.place(GalacticCoord.ofSectorLocal(ANOTHER_SUPER_CELL, 0, 0, 0, 0, 0), 6002);

        DimensionProperties original = bodyOfStar(sol, 6100, 150, 0.3);
        Optional<GalacticCoord> firstName = reg.coordForPlanet(original);
        assertTrue(firstName.isPresent());
        assertTrue("control: the first body's name is recorded", reg.recordedName(6100).isPresent());

        // The planet is deleted and its id reissued to a body of a DIFFERENT star.
        sol.removePlanet(original);
        DimensionProperties reissued = bodyOfStar(other, 6100, 150, 0.3);
        Optional<GalacticCoord> secondName = reg.coordForPlanet(reissued);

        assertTrue(secondName.isPresent());
        assertFalse("a recycled id must not inherit the deleted body's cell",
                firstName.get().sameCell(secondName.get()));
        assertTrue("the new body's name must lie in ITS system's neighbourhood",
                reg.anchorForCell(secondName.get()).isPresent());
        assertEquals("...which is its own star's anchor",
                GalacticCoord.ofSectorLocal(ANOTHER_SUPER_CELL, 0, 0, 0, 0, 0),
                reg.anchorForCell(secondName.get()).get());
    }

    /** Deleting a dimension drops its recorded name outright — the direct half of the same defect. */
    @Test
    public void forgettingADimensionDropsItsRecordedName() {
        StellarBody sol = star(6003);
        sol.setSize(1f);
        UniverseRegistry.setStarLookup(id -> id == 6003 ? sol : null);
        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 6003);
        reg.coordForPlanet(bodyOfStar(sol, 6101, 150, 0.3));

        assertTrue("control: the name was recorded", reg.recordedName(6101).isPresent());
        assertTrue("forgetting reports that it held one", reg.forgetName(6101));
        assertFalse("...and the name is gone", reg.recordedName(6101).isPresent());
        assertFalse("forgetting twice is a no-op, not a lie", reg.forgetName(6101));
    }

    /**
     * Ledger #154. Containment is what makes member&rarr;anchor attribution work, so a recorded name
     * that no longer lies inside its own system's box names a cell that attributes to nothing: the
     * body stays listed and jumpable and can never be arrived at. Moving a star's anchor does exactly
     * that to every name recorded under the old layout, and nothing said so.
     */
    @Test
    public void aRecordedNameThatLeftItsSystemsBoxIsReDerivedRatherThanServed() {
        StellarBody host = star(6004);
        host.setSize(1f);
        UniverseRegistry.setStarLookup(id -> id == 6004 ? host : null);
        DimensionProperties body = bodyOfStar(host, 6102, 150, 0.3);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 6004);
        Optional<GalacticCoord> underOldAnchor = reg.coordForPlanet(body);
        assertTrue(underOldAnchor.isPresent());

        // The star is re-placed a long way off — an XML edit, a re-authored layout. The recorded name
        // is now nowhere near the system it belongs to.
        GalacticCoord newAnchor = GalacticCoord.ofSectorLocal(3L * ANOTHER_SUPER_CELL, 0, 0, 0, 0, 0);
        reg.place(newAnchor, 6004);

        Optional<GalacticCoord> served = reg.coordForPlanet(body);
        assertTrue(served.isPresent());
        assertFalse("a name outside its own system's box may not be served",
                served.get().sameCell(underOldAnchor.get()));
        assertTrue("what is served must attribute back to the system it belongs to",
                reg.anchorForCell(served.get()).isPresent());
        assertEquals(newAnchor, reg.anchorForCell(served.get()).get());
        assertFalse("...and the cell must report the body standing in it",
                reg.bodiesAt(served.get()).isEmpty());
        assertEquals("the re-derived name replaces the stale record", Optional.of(served.get()),
                reg.recordedName(6102));
    }

    /** A name that is still inside its box is served unchanged — the control for the clause above. */
    @Test
    public void aRecordedNameInsideItsBoxSurvivesASmallAnchorMove() {
        StellarBody host = star(6005);
        host.setSize(1f);
        UniverseRegistry.setStarLookup(id -> id == 6005 ? host : null);
        DimensionProperties body = bodyOfStar(host, 6103, 150, 0.3);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 6005);
        Optional<GalacticCoord> first = reg.coordForPlanet(body);
        assertTrue(first.isPresent());

        // One cell over: the recorded name is still well inside the neighbourhood box.
        reg.place(GalacticCoord.ofSectorLocal(1, 0, 0, 0, 0, 0), 6005);
        assertEquals("a name that still names a cell of its own system is not disturbed",
                first, reg.coordForPlanet(body));
    }

    // ── Frames: where a cell IS, and what makes it move ──────────────────────────────────────────

    /**
     * Both halves of the frame rule at once. A cell with a primary body in it rides that body; a cell
     * with none is static at {@code sector * CELL}. The void half is the control — without it "the
     * frame moves" would pass against a lookup that returned an arbitrary function of the tick for
     * everything.
     */
    @Test
    public void aBodyCellRidesItsPrimaryWhileAVoidCellStandsStill() {
        StellarBody host = star(6006);
        host.setSize(1f);
        UniverseRegistry.setStarLookup(id -> id == 6006 ? host : null);
        DimensionProperties body = bodyOfStar(host, 6104, 150, 0.3);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 6006);
        Optional<GalacticCoord> name = reg.coordForPlanet(body);
        assertTrue(name.isPresent());

        long quarterOrbit = (long) (24000d
                * AstronomicalBodyHelper.getOrbitalPeriod(150, 1f) / 4d);

        assertFalse("a cell with a primary in it moves with that primary",
                reg.originAt(name.get(), 0L).equals(reg.originAt(name.get(), quarterOrbit)));

        GalacticCoord empty = GalacticCoord.ofSectorLocal(-777, 0, 0, 0, 0, 0);
        assertEquals("a cell with no primary is static, at the position its name states",
                AbsolutePos.ofCellName(empty), reg.originAt(empty, 0L));
        assertEquals(reg.originAt(empty, 0L), reg.originAt(empty, quarterOrbit));
    }

    /**
     * The sky shows the SYSTEM, not the cell: the feed is the system's bodies unioned with whatever is
     * keyed at the observer's own cell, and the union is what stops a straight swap erasing a station
     * standing in a void cell — the system read aggregates POIs of BODY cells only, and answers empty
     * for a cell no anchor attributes.
     */
    @Test
    public void theSkyFeedUnionsTheSystemWithTheObserversOwnCell() {
        StellarBody host = star(6007);
        host.setSize(1f);
        UniverseRegistry.setStarLookup(id -> id == 6007 ? host : null);
        DimensionProperties body = bodyOfStar(host, 6105, 150, 0.3);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 6007);
        reg.coordForPlanet(body); // record the name

        // An empty cell of the same system, with a player-built station standing in it.
        GalacticCoord voidCell = GalacticCoord.ofSectorLocal(3, 1, 0, 0, 0, 0);
        assertTrue("the fixture's void cell must belong to the system",
                reg.anchorForCell(voidCell).isPresent());
        assertTrue("...and hold no body of its own", reg.bodiesAt(voidCell).isEmpty());
        reg.addPoi(SystemBody.fixedAt(voidCell.plusLocalSaturating(1_000L, 0L, 0L),
                SystemBodyKind.STATION_SLOT, Constants.INVALID_PLANET, 6007));

        List<SystemBody> sky = reg.skyBodiesAt(voidCell);
        boolean sawStar = false;
        boolean sawPlanet = false;
        boolean sawStation = false;
        for (SystemBody b : sky) {
            sawStar |= b.kind() == SystemBodyKind.STAR;
            sawPlanet |= b.dimId() == 6105;
            sawStation |= b.kind() == SystemBodyKind.STATION_SLOT;
        }
        assertTrue("standing in void you still see your star", sawStar);
        assertTrue("...and your system's planets", sawPlanet);
        assertTrue("...and whatever is keyed at your own cell", sawStation);

        // The control the union exists for: the SYSTEM read alone drops the station.
        boolean systemReadSawStation = false;
        for (SystemBody b : reg.systemBodiesAt(voidCell)) {
            systemReadSawStation |= b.kind() == SystemBodyKind.STATION_SLOT;
        }
        assertFalse("if the system read already carried it, the union would be proving nothing",
                systemReadSawStation);
    }

    /** Interstellar void — a cell no anchor attributes — is fed the union's EMPTY case. */
    @Test
    public void interstellarVoidIsFedNothing() {
        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 6008);
        GalacticCoord farAway = GalacticCoord.ofSectorLocal(ANOTHER_SUPER_CELL, 0, 0, 0, 0, 0);
        assertFalse("the fixture's cell must belong to no system",
                reg.anchorForCell(farAway).isPresent());
        assertTrue("the space between stars is black", reg.skyBodiesAt(farAway).isEmpty());
    }

    /** A test generator that claims every cell with one fixed system — to prove stored placements win. */
    private static final class AllClaimingGenerator implements IGalaxyGenerator {
        private final StellarBody body;

        AllClaimingGenerator(StellarBody body) {
            this.body = body;
        }

        @Override
        public Optional<StarSystem> systemAt(long seed, GalacticCoord coord) {
            return Optional.of(new StarSystem(body));
        }

        @Override
        public Map<GalacticCoord, StarSystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max) {
            Map<GalacticCoord, StarSystem> m = new HashMap<>();
            m.put(min.cellCentre(), new StarSystem(body));
            return m;
        }
    }
}
