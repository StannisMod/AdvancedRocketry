package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.EmptyGalaxyGenerator;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.RegionScan;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.TelescopeScan;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Contract tests for the telescope's region survey: how far it can reach, how much of the sky one
 * STEP may resolve, that a sweep works through its region and can be resumed, and what a discovery
 * writes onto a crystal. Pure-JUnit — no MC bootstrap; the registry's generator and star lookup are
 * the injectable seams.
 *
 * <p>These pin player-facing promises — a far survey costs more than a near one, the horizon is the
 * configured reach, one step never enumerates the sky, an unknown system is discoverable, and what a
 * telescope writes is a BODY at the coarsest grade, dated — plus the save contract that a sweep
 * outlives the chunk it started in and resumes where it stood. They do not pin the time formula, the
 * sweep order or the storage shape.</p>
 */
public class TelescopeRegionScanTest {

    private static final GalacticCoord HOME = GalacticCoord.ofSectorLocal(0, 0, 0, 0, 0, 0);

    /** Reach 10 sectors, a 3×3×3 region, room for it, 100 ticks a step plus 50 per sector, 2 cells a step. */
    private static RegionScan.Tuning tuning() {
        return new RegionScan.Tuning(10, 1, 512, 100, 50, 2);
    }

    private static StellarBody star(int id) {
        StellarBody s = new StellarBody();
        s.setId(id);
        s.setName("Star-" + id);
        return s;
    }

    private static GalacticCoord cell(long x, long y, long z) {
        return GalacticCoord.ofSectorLocal(x, y, z, 0L, 0L, 0L);
    }

    @After
    public void resetSeams() {
        UniverseRegistry.setGenerator(null);
        UniverseRegistry.setStarLookup(null);
    }

    // ── the instrument ────────────────────────────────────────────────────────

    @Test
    public void aFartherRegionIsALongerSurvey() {
        // The whole point of aiming far: distance is paid for in time.
        RegionScan near = RegionScan.directed(HOME, 1, 0, 0, 2, 0L, tuning());
        RegionScan far = RegionScan.directed(HOME, 1, 0, 0, 8, 0L, tuning());

        assertTrue("a farther region must take longer to survey: near=" + near.estimatedTicks()
                        + " far=" + far.estimatedTicks(),
                far.estimatedTicks() > near.estimatedTicks());
    }

    @Test
    public void theHorizonIsTheConfiguredReach() {
        // "You cannot see beyond your own cluster": an aim past the reach is answered at the reach,
        // and costs exactly what looking at the reach costs — not more.
        RegionScan reached = RegionScan.directed(HOME, 0, 0, 1, 10, 0L, tuning());
        RegionScan overreached = RegionScan.directed(HOME, 0, 0, 1, 9999, 0L, tuning());

        assertEquals("an aim past the horizon must be answered at the horizon",
                reached.distanceSectors(), overreached.distanceSectors());
        assertEquals("and must cost what the horizon costs",
                reached.estimatedTicks(), overreached.estimatedTicks());
        assertEquals("the region itself must be the one at the horizon",
                reached.min().cellKey(), overreached.min().cellKey());
    }

    @Test
    public void oneStepNeverResolvesMoreThanItsCellBudget() {
        // The structural guard against reading an endless procedural universe off one instrument:
        // a survey may cover a large region, but never in one step.
        RegionScan.Tuning wide = new RegionScan.Tuning(10, 2, 1000, 100, 50, 3);
        RegionScan scan = RegionScan.directed(HOME, 1, 1, 0, 3, 0L, wide);

        assertTrue("the fixture must be a region worth sweeping", scan.totalCells() > 3);
        assertEquals("a step may never resolve more cells than its budget",
                3, scan.cellsDueAt(scan.stepDeadline()));
    }

    @Test
    public void aRegionNeverExceedsItsCeiling() {
        // Ask for a 9×9×9 region with room for 27 cells and the ceiling wins.
        RegionScan.Tuning greedy = new RegionScan.Tuning(10, 4, 27, 100, 50, 2);
        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, 3, 0L, greedy);

        assertTrue("a survey may never cover more than its ceiling: " + scan.totalCells(),
                scan.totalCells() <= 27);
    }

    @Test
    public void aSweepWorksThroughItsRegionAndFinishes() {
        // The automation the instrument exists for: one aim, then it works through the patch.
        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, 2, 0L, tuning());
        int total = scan.totalCells();
        assertTrue("the fixture must need more than one step", total > scan.cellsPerStep());

        long now = scan.stepDeadline();
        int guard = 0;
        while (!scan.isComplete() && guard++ < 1000) {
            int due = scan.cellsDueAt(now);
            assertTrue("a due step must have cells to resolve", due > 0);
            scan = scan.advanced(now, due);
            now = scan.stepDeadline();
        }

        assertTrue("the sweep must finish", scan.isComplete());
        assertEquals("and must have covered every cell of its region", total, scan.cellsDone());
    }

    @Test
    public void nothingIsResolvedBeforeTheStepIsDue() {
        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, 2, 1_000L, tuning());
        assertEquals("a step that is not due yet resolves nothing",
                0, scan.cellsDueAt(scan.stepDeadline() - 1));
        assertTrue("and the one that is due resolves cells",
                scan.cellsDueAt(scan.stepDeadline()) > 0);
    }

    @Test
    public void everyCellOfTheRegionIsVisitedExactlyOnce() {
        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, 2, 0L, tuning());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < scan.totalCells(); i++) {
            assertTrue("the sweep order must not repeat a cell: " + scan.cellAt(i).cellKey(),
                    seen.add(scan.cellAt(i).cellKey()));
        }
        assertEquals("and must cover the whole region", scan.totalCells(), seen.size());
    }

    @Test
    public void aSurveyWithNoDirectionIsRefused() {
        try {
            RegionScan.directed(HOME, 0, 0, 0, 4, 0L, tuning());
            fail("a survey with no direction does not name a region and must be refused");
        } catch (IllegalArgumentException expected) {
            // the contract: the caller is told, rather than silently given some default sky
        }
    }

    @Test
    public void aSweepResumesWhereItStoodAfterTheChunkComesBack() {
        // A survey is a deadline plus a cursor, not a counter: it is saved mid-sweep, reloaded, and
        // continues — which is what lets an observatory be unloaded without owing a tick replay.
        RegionScan started = RegionScan.directed(HOME, 1, 0, -1, 6, 5_000L, tuning());
        RegionScan halfway = started.advanced(started.stepDeadline(), started.cellsPerStep());
        NBTTagCompound nbt = new NBTTagCompound();
        halfway.writeToNBT(nbt);

        RegionScan reloaded = RegionScan.readFromNBT(nbt);
        assertNotNull("a saved survey must come back", reloaded);
        assertEquals("it must come back looking at the same region",
                halfway.min().cellKey(), reloaded.min().cellKey());
        assertEquals(halfway.max().cellKey(), reloaded.max().cellKey());
        assertEquals("and must not have forgotten what it already surveyed",
                halfway.cellsDone(), reloaded.cellsDone());
        assertEquals("nor when its next step lands", halfway.stepDeadline(), reloaded.stepDeadline());
    }

    @Test
    public void nothingIsStoredForAnObservatoryThatIsNotLooking() {
        assertNull("an absent survey must read back as absent, not as a survey at tick zero",
                RegionScan.readFromNBT(new NBTTagCompound()));
    }

    // ── what a survey discovers ───────────────────────────────────────────────

    /** A registry holding two systems with bodies, plus one well outside the surveyed region. */
    private UniverseRegistry threeSystems() {
        UniverseRegistry.setGenerator(new EmptyGalaxyGenerator());
        UniverseRegistry.setStarLookup(TelescopeRegionScanTest::star);

        UniverseRegistry registry = new UniverseRegistry();
        registry.place(cell(4, 0, 0), 4);
        registry.addPoi(new SystemBody(cell(4, 0, 0), SystemBodyKind.STAR, Constants.INVALID_PLANET, 4));
        registry.addPoi(new SystemBody(cell(4, 0, 0), SystemBodyKind.PLANET, 401, 4));

        registry.place(cell(5, 1, 0), 5);
        registry.addPoi(new SystemBody(cell(5, 1, 0), SystemBodyKind.PLANET, 501, 5));

        registry.place(cell(9, 0, 0), 9);
        registry.addPoi(new SystemBody(cell(9, 0, 0), SystemBodyKind.PLANET, 901, 9));
        return registry;
    }

    /** The survey the fixture is built around: 4 sectors out along +X, one sector wide. */
    private RegionScan boxAroundFourthSector() {
        return RegionScan.directed(HOME, 1, 0, 0, 4, 0L, tuning());
    }

    /** Resolve the whole region at once — what the instant path does when research is off. */
    private int surveyAll(UniverseRegistry registry, RegionScan scan, CrystalMemory crystal, long tick) {
        return TelescopeScan.resolveBatch(registry, scan, 0, scan.totalCells(), crystal, tick,
                dimId -> "Body-" + dimId);
    }

    @Test
    public void whatIsInTheRegionIsLearnedAndWhatIsOutsideItIsNot() {
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();

        surveyAll(registry, boxAroundFourthSector(), crystal, 7_000L);

        assertNotNull("the body the instrument was pointed at must be learned", crystal.forBody(401));
        assertNotNull("so must the one at the edge of the same region", crystal.forBody(501));
        assertNull("a system outside the surveyed region must NOT be", crystal.forBody(901));
    }

    @Test
    public void aSurveyWritesTheBODIESItResolved() {
        // The payoff of a discovery is not a coordinate: an entry that names a body is what lets the
        // console show what is known about it, and what lets a pilot aim at it.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();

        surveyAll(registry, boxAroundFourthSector(), crystal, 7_000L);

        CrystalEntry planet = crystal.forBody(401);
        assertNotNull("the region's planet must have its own address", planet);
        assertTrue("and that address must name the body, not just its cell", planet.namesBody());
        assertEquals("named the way every other screen names it", "Body-401", planet.name());
    }

    @Test
    public void aSystemTheCrystalNeverHeardOfIsStillDiscovered() {
        // The discriminator against the tempting wrong shape — reporting only what is already known.
        // The crystal is armed with ONE of the two systems in the region, so the other is genuinely
        // new: a knowledge gate anywhere on this path leaves it missing and this test red.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(new CrystalEntry(cell(4, 0, 0), "Body-401", SystemBodyKind.PLANET,
                InfoTier.TELESCOPE, 1_000L, 401));
        assertNull("the fixture must start ignorant of the body under test", crystal.forBody(501));

        surveyAll(registry, boxAroundFourthSector(), crystal, 7_000L);

        assertNotNull("a telescope discovers what nobody knew, or it discovers nothing",
                crystal.forBody(501));
    }

    @Test
    public void whatATelescopeWritesIsCoarseAndDated() {
        // The graded-discovery ladder: a body resolved from very far away may claim the coarsest
        // grade and no more, and it carries when it was seen so a closer look supersedes it.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();

        surveyAll(registry, boxAroundFourthSector(), crystal, 7_000L);
        CrystalEntry learned = crystal.forBody(401);

        assertNotNull(learned);
        assertEquals("a region survey resolves no more than telescope-grade detail",
                InfoTier.TELESCOPE, learned.detail());
        assertEquals("and dates what it saw", 7_000L, learned.observedTick());
    }

    @Test
    public void aSweepWritesOnlyTheCellsItHasReached() {
        // What makes the sweep a mechanic rather than a formality: the crystal fills as the
        // instrument works, not all at the end.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();
        RegionScan scan = boxAroundFourthSector();

        int firstCellWithContent = -1;
        for (int i = 0; i < scan.totalCells(); i++) {
            if (scan.cellAt(i).cellKey().equals(cell(4, 0, 0).cellKey())) {
                firstCellWithContent = i;
                break;
            }
        }
        assertTrue("the fixture's system must lie inside the swept region", firstCellWithContent >= 0);

        TelescopeScan.resolveBatch(registry, scan, 0, firstCellWithContent, crystal, 7_000L,
                dimId -> "Body-" + dimId);
        assertNull("a cell the sweep has not reached yet must not be on the crystal",
                crystal.forBody(401));

        TelescopeScan.resolveBatch(registry, scan, firstCellWithContent, 1, crystal, 7_100L,
                dimId -> "Body-" + dimId);
        assertNotNull("and must land the moment the sweep reaches it", crystal.forBody(401));
    }
}
