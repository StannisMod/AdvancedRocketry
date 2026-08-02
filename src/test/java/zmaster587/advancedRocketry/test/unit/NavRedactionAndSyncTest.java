package zmaster587.advancedRocketry.test.unit;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.navigation.CrystalSync;
import zmaster587.advancedRocketry.navigation.NavInfoRedaction;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.PlanetInfoField;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the two navigation rules a pilot can feel: how much a body tells you depends on
 * how close you got to it (and on what you once recorded), and syncing two crystals leaves BOTH of
 * them holding everything either one knew.
 */
public class NavRedactionAndSyncTest {

    private static GalacticCoord coord(long sector, long localX) {
        return GalacticCoord.ofSectorLocal(sector, 0L, 0L, localX, 0L, 0L);
    }

    /**
      * The tier now takes the true distance as a number, because measuring it is the caller's job:
      * two cells' frames both move, so only something holding the registry can say how far apart
      * they are RIGHT NOW (C15 ADDR-9). These fixtures supply it directly, which is what makes the
      * tier rule checkable without a universe.
      */
    private static double blocksApart(GalacticCoord a, GalacticCoord b) {
        return a.staticFrameDistanceTo(b);
    }

    private static CrystalEntry entry(long sector, String name, InfoTier detail, long tick) {
        return new CrystalEntry(coord(sector, 0), name, SystemBodyKind.PLANET, detail, tick);
    }

    // ─── Redaction ─────────────────────────────────────────────────────────────

    @Test
    public void aBodyInAnotherSystemIsOnlyReadableFromAfar() {
        InfoTier tier = NavInfoRedaction.tierFor(coord(1, 0), coord(9, 0),
                blocksApart(coord(1, 0), coord(9, 0)), null);

        assertEquals("another system's body is a telescope target, nothing more",
                InfoTier.TELESCOPE, tier);
    }

    @Test
    public void beingInTheSameSystemRevealsTheApproachFields() {
        InfoTier tier = NavInfoRedaction.tierFor(coord(3, 0), coord(3, 1_000_000L),
                blocksApart(coord(3, 0), coord(3, 1_000_000L)), null);

        assertEquals(InfoTier.APPROACH, tier);
    }

    @Test
    public void closingOnTheBodyRevealsEverything() {
        InfoTier tier = NavInfoRedaction.tierFor(coord(3, 0), coord(3, 100L),
                blocksApart(coord(3, 0), coord(3, 100L)), null);

        assertEquals("inside the body's own zone the ship sees all of it", InfoTier.ORBIT, tier);
    }

    @Test
    public void aBodyOnceSurveyedStaysSurveyedAfterYouLeave() {
        InfoTier tier = NavInfoRedaction.tierFor(coord(1, 0), coord(9, 0),
                blocksApart(coord(1, 0), coord(9, 0)), InfoTier.ORBIT);

        assertEquals("you do not forget a planet you orbited by flying away", InfoTier.ORBIT, tier);
    }

    @Test
    public void proximityBeatsAStaleRecord() {
        InfoTier tier = NavInfoRedaction.tierFor(coord(3, 0), coord(3, 100L),
                blocksApart(coord(3, 0), coord(3, 100L)), InfoTier.TELESCOPE);

        assertEquals("arriving reveals a body whether or not it was ever recorded",
                InfoTier.ORBIT, tier);
    }

    /**
     * ORBIT is a real distance, so it follows the number the caller measured — not the cell name.
     * A moon shares its parent's cell and can sit tens of thousands of blocks away inside it, and a
     * neighbouring cell's body can be closer than one in your own; the rule has to read the metre,
     * or a pilot alongside a moon is told less about it than the planet he is nowhere near.
     */
    @Test
    public void theOrbitTierFollowsTheMeasuredDistanceNotTheCellName() {
        InfoTier sameCellFarAway = NavInfoRedaction.tierFor(coord(3, 0), coord(3, 0),
                NavInfoRedaction.ORBIT_ZONE_BLOCKS * 10, null);
        assertEquals("sharing a cell is not being in the body's zone",
                InfoTier.APPROACH, sameCellFarAway);

        InfoTier otherCellClose = NavInfoRedaction.tierFor(coord(3, 0), coord(4, 0),
                NavInfoRedaction.ORBIT_ZONE_BLOCKS / 2, null);
        assertEquals("a body whose frame has swung close is close, whatever its name",
                InfoTier.ORBIT, otherCellClose);
    }

    @Test
    public void redactionDropsEveryFieldAboveTheTier() {
        Map<PlanetInfoField, String> full = new LinkedHashMap<>();
        full.put(PlanetInfoField.NAME, "Kepler");
        full.put(PlanetInfoField.BIOMES, "7");
        full.put(PlanetInfoField.RESOURCES, "iron, gold");

        Map<PlanetInfoField, String> visible = NavInfoRedaction.redact(full, InfoTier.TELESCOPE);

        assertTrue("a name is visible from a telescope", visible.containsKey(PlanetInfoField.NAME));
        assertFalse("biomes need an approach", visible.containsKey(PlanetInfoField.BIOMES));
        assertFalse("resources need orbit", visible.containsKey(PlanetInfoField.RESOURCES));
    }

    @Test
    public void everythingIsVisibleFromOrbit() {
        Map<PlanetInfoField, String> full = new LinkedHashMap<>();
        full.put(PlanetInfoField.NAME, "Kepler");
        full.put(PlanetInfoField.BIOMES, "7");
        full.put(PlanetInfoField.RESOURCES, "iron, gold");

        assertEquals(3, NavInfoRedaction.redact(full, InfoTier.ORBIT).size());
    }

    // ─── Sync ──────────────────────────────────────────────────────────────────

    @Test
    public void syncLeavesBothSidesHoldingTheUnion() {
        CrystalMemory ship = new CrystalMemory();
        ship.record(entry(1, "found out there", InfoTier.TELESCOPE, 10L));
        CrystalMemory base = new CrystalMemory();
        base.record(entry(2, "found at home", InfoTier.TELESCOPE, 10L));

        CrystalSync.sync(ship, base);

        assertEquals("the ship gains the base's address", 2, ship.size());
        assertEquals("and the base gains the ship's", 2, base.size());
        assertTrue(ship.knows(coord(2, 0)));
        assertTrue(base.knows(coord(1, 0)));
    }

    @Test
    public void syncKeepsTheFresherObservationOnBothSides() {
        CrystalMemory ship = new CrystalMemory();
        ship.record(entry(5, "surveyed today", InfoTier.ORBIT, 900L));
        CrystalMemory base = new CrystalMemory();
        base.record(entry(5, "glimpsed last year", InfoTier.TELESCOPE, 10L));

        CrystalSync.sync(ship, base);

        assertEquals(InfoTier.ORBIT, ship.get(coord(5, 0)).detail());
        assertEquals("the base must end up with the ship's newer observation, not its own stale one",
                InfoTier.ORBIT, base.get(coord(5, 0)).detail());
    }

    @Test
    public void syncingTwiceChangesNothingTheSecondTime() {
        CrystalMemory ship = new CrystalMemory();
        ship.record(entry(1, "a", InfoTier.TELESCOPE, 10L));
        CrystalMemory base = new CrystalMemory();
        base.record(entry(2, "b", InfoTier.TELESCOPE, 10L));

        assertTrue("the first sync moves something", CrystalSync.sync(ship, base) > 0);
        assertEquals("a second sync of two crystals already in step is a no-op",
                0, CrystalSync.sync(ship, base));
    }
}
