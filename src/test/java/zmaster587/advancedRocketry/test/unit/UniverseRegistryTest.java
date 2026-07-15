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
import zmaster587.advancedRocketry.space.GalacticCoord;
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
 * Contract tests for the Layer-1 universe registry (TASK-88): the cell-keyed coord&harr;system placement
 * index, its NBT override store, the planet&rarr;coord seam, the anchor-drain lifecycle, and the pluggable
 * generator. Pure-JUnit — no MC bootstrap. The forward coord&rarr;system path resolves stars through the
 * injectable {@link UniverseRegistry#setStarLookup} seam so it never boots the legacy catalogue.
 *
 * <p>These pin the placement CONTRACTS (coord&harr;system both ways, key-by-cell, override round-trip,
 * location-agnostic systems), not internal field shapes.</p>
 */
public class UniverseRegistryTest {

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

        // An unplaced cell falls through to the generator.
        Optional<StarSystem> atVoid = reg.systemForCoord(GalacticCoord.ofSectorLocal(6, 6, 6, 0, 0, 0));
        assertTrue(atVoid.isPresent());
        assertEquals(777, atVoid.get().starId());
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
        source.addPoi(new SystemBody(GalacticCoord.ofSectorLocal(3, 3, 3, 50_000, 0, 0),
                SystemBodyKind.STATION_SLOT, Constants.INVALID_PLANET, 7));
        source.addPoi(new SystemBody(GalacticCoord.ofSectorLocal(3, 3, 3, -20_000, 10_000, 0),
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
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(new GalaxyGenConfig(0.9d, 1, 8, 0.0d, null)));

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

        reg.addPoi(new SystemBody(
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
