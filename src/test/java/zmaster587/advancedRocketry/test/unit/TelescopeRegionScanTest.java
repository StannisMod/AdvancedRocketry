package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.EmptyGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.RegionScan;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.TelescopeScan;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
 * <p>These pin player-facing promises — a far survey costs more than a near one, the horizon is a
 * LENGTH a telescope could have, a look finds the system that OWNS the cell rather than only a star
 * seated on it, empty sky stays empty, one step never enumerates the sky, an unknown system is
 * discoverable, and what a telescope writes is a BODY at the coarsest grade, dated — plus the save
 * contract that a sweep outlives the chunk it started in and resumes where it stood. They do not pin
 * the time formula, the sweep order or the storage shape.</p>
 */
public class TelescopeRegionScanTest {

    private static final GalacticCoord HOME = GalacticCoord.ofSectorLocal(0, 0, 0, 0, 0, 0);

    /**
     * One survey STEP: the edge of the cube that holds at most one system, which is what the sweep
     * strides by and what an operator's aim is counted in. Taken from the generator rather than
     * invented — the registry attributes a member cell to its system by the same number.
     */
    private static final long STEP = GalaxyGenConfig.DEFAULT_MIN_SPACING;

    /** Reach 50 light years, a 3×3×3 patch of territories, room for it, 100 ticks a step + 50 a ly. */
    private static RegionScan.Tuning tuning() {
        return new RegionScan.Tuning(50d, 1, 512, 100, 50d, 2, STEP);
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

    /** The cell {@code steps} territories out along +X — where an aim of {@code steps} lands. */
    private static GalacticCoord stepsOut(long steps) {
        return cell(steps * STEP, 0, 0);
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
    public void theReachIsALengthAndTheAimIsCountedInStars() {
        // The defect this replaced: a reach stated in cells read as 0.16 AU — a fifth of the way to
        // Mercury — and no aim inside it could ever leave the solar system. A horizon is a LENGTH,
        // and what it buys is a number of star territories, so both must be recognisable.
        RegionScan.Tuning tuning = tuning();

        assertTrue("a telescope's horizon must be quoted in light years: " + tuning.maxRangeLightYears(),
                tuning.maxRangeLightYears() >= 1d);
        assertTrue("and must reach at least the nearest few stars, or nothing is discoverable: "
                        + tuning.maxRangeSteps() + " steps",
                tuning.maxRangeSteps() >= 3);

        RegionScan aimed = RegionScan.directed(HOME, 1, 0, 0, 3, 0L, tuning);
        assertEquals("an aim of three stars must land three territories out, not three cells",
                stepsOut(3).cellKey(),
                cell(aimed.distanceCells(), 0, 0).cellKey());
        assertTrue("and that distance, read as a length, must be interstellar: "
                        + aimed.distanceLightYears() + " ly",
                aimed.distanceLightYears() >= 3d);
    }

    @Test
    public void theHorizonIsTheConfiguredReach() {
        // "You cannot see beyond your own cluster": an aim past the reach is answered at the reach,
        // and costs exactly what looking at the reach costs — not more.
        int horizon = tuning().maxRangeSteps();
        RegionScan reached = RegionScan.directed(HOME, 0, 0, 1, horizon, 0L, tuning());
        RegionScan overreached = RegionScan.directed(HOME, 0, 0, 1, 9999, 0L, tuning());

        assertEquals("an aim past the horizon must be answered at the horizon",
                reached.distanceCells(), overreached.distanceCells());
        assertEquals("and must cost what the horizon costs",
                reached.estimatedTicks(), overreached.estimatedTicks());
        assertEquals("the region itself must be the one at the horizon",
                reached.min().cellKey(), overreached.min().cellKey());
    }

    @Test
    public void oneStepNeverResolvesMoreThanItsCellBudget() {
        // The structural guard against reading an endless procedural universe off one instrument:
        // a survey may cover a large region, but never in one step.
        RegionScan.Tuning wide = new RegionScan.Tuning(50d, 2, 1000, 100, 50d, 3, STEP);
        RegionScan scan = RegionScan.directed(HOME, 1, 1, 0, 3, 0L, wide);

        assertTrue("the fixture must be a region worth sweeping", scan.totalCells() > 3);
        assertEquals("a step may never resolve more cells than its budget",
                3, scan.cellsDueAt(scan.stepDeadline()));
    }

    @Test
    public void aRegionNeverExceedsItsCeiling() {
        // Ask for a 9×9×9 region with room for 27 cells and the ceiling wins.
        RegionScan.Tuning greedy = new RegionScan.Tuning(50d, 4, 27, 100, 50d, 2, STEP);
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
    public void aSweepStridesByOneStarsTerritoryRatherThanByCells() {
        // What makes a sweep worth its time: every look is a different candidate system. Walking
        // cell by cell would spend a whole survey inside one system's own neighbourhood, since
        // every cell of that neighbourhood answers with the same system.
        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, 4, 0L, tuning());

        assertEquals("a directed survey strides by one star's territory", STEP, scan.strideCells());
        assertTrue("the fixture must span more than one look along X", scan.totalCells() > 1);

        long first = scan.cellAt(0).sectorX();
        long second = scan.cellAt(1).sectorX();
        assertEquals("two consecutive looks must be a whole territory apart, not a cell",
                STEP, Math.abs(second - first));
    }

    @Test
    public void theLocalRadarWalksCellsNotTerritories() {
        // The other half of the same decision: close to home the cells ARE the granularity — the
        // planet in the next cell is a different destination from its star — so the radar keeps a
        // cell stride while the directed survey strides by stars.
        RegionScan radar = RegionScan.local(HOME, 1, 0L, tuning());

        assertEquals("the local radar walks cell by cell", 1L, radar.strideCells());
        assertEquals("a radius of one cell is a 3x3x3 neighbourhood", 27, radar.totalCells());

        boolean looksAtHome = false;
        for (int i = 0; i < radar.totalCells(); i++) {
            looksAtHome |= radar.cellAt(i).cellKey().equals(HOME.cellKey());
        }
        assertTrue("and it must look at the cell the instrument is standing in", looksAtHome);
    }

    @Test
    public void aRegionWithMoreLooksThanCanBeWalkedIsREFUSEDratherThanClamped() {
        // A survey is walked by an int cursor, and its look count used to be CLAMPED to fit one. A
        // clamped count does not make the sweep long — it makes it report itself complete at 2·10⁹
        // looks with the rest of the region never visited, and progress read 100 % while the sky was
        // untouched. The local radar is the reachable route: its radius is a config number and a cell
        // stride cubes it, so ~1 300 cells of radius is already past an int.
        try {
            RegionScan.local(HOME, 2_000, 0L, tuning());
            fail("a region of (2*2000+1)^3 looks cannot be walked and must be refused, not clamped");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal must name what it could not do: " + expected.getMessage(),
                    expected.getMessage().contains("cannot be walked"));
        }

        // And the boundary is not a cliff into silence: one that DOES fit is accepted and counted.
        RegionScan fits = RegionScan.local(HOME, 100, 0L, tuning());
        assertEquals("a region that fits must be counted exactly, never rounded",
                201 * 201 * 201, fits.totalCells());
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

    /**
     * A registry holding two systems inside the surveyed patch and one well outside it.
     *
     * <p><b>Not one of the three stars is seated on a cell the sweep looks at</b>, and that is the
     * fixture's whole point. A star is one cell of a territory millions of cells wide, so a survey
     * that could only see a system by landing on its star's own address would find nothing here —
     * which is exactly what the instrument used to do. Each seat is offset from the look that must
     * find it, by a distance that is inside its own neighbourhood and nowhere near the next.</p>
     */
    private UniverseRegistry threeSystems() {
        UniverseRegistry.setGenerator(new EmptyGalaxyGenerator());
        UniverseRegistry.setStarLookup(TelescopeRegionScanTest::star);

        UniverseRegistry registry = new UniverseRegistry();
        GalacticCoord inner = cell(4 * STEP - 20, 0, 0);          // found by the look at 4 steps out
        registry.place(inner, 4);
        registry.addPoi(SystemBody.fixedAt(inner, SystemBodyKind.STAR, Constants.INVALID_PLANET, 4));
        registry.addPoi(SystemBody.fixedAt(inner, SystemBodyKind.PLANET, 401, 4));

        GalacticCoord edge = cell(5 * STEP + 7, STEP - 3, 0);     // found by the look at the corner
        registry.place(edge, 5);
        registry.addPoi(SystemBody.fixedAt(edge, SystemBodyKind.PLANET, 501, 5));

        GalacticCoord beyond = cell(9 * STEP, 0, 0);              // far outside the patch
        registry.place(beyond, 9);
        registry.addPoi(SystemBody.fixedAt(beyond, SystemBodyKind.PLANET, 901, 9));
        return registry;
    }

    /** The survey the fixture is built around: 4 territories out along +X, one territory wide. */
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
    public void aSystemIsFoundFromAnyCellItOWNS_notOnlyFromItsStarsSeat() {
        // THE defect. A system is a neighbourhood: its star holds one cell of it and its planets hold
        // others. Asking "is a star seated exactly here" makes discovery a lottery whose odds are one
        // cell in a territory millions wide — so a survey found nothing and reported an empty sky.
        // Asking "which system owns this cell" is the same question a telescope asks of the light.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();

        GalacticCoord look = stepsOut(4);
        assertFalse("the fixture is worthless unless the look is NOT the star's own seat",
                registry.starIdForCoord(look).isPresent());

        TelescopeScan.resolveCell(registry, look, crystal, 7_000L, dimId -> "Body-" + dimId);

        assertNotNull("a survey must discover the system that OWNS the cell it looked at",
                crystal.forBody(401));
    }

    /**
     * The real generator, counting every question the survey asks it.
     *
     * <p>A wrapper rather than a mock, because the claim under test is about the REAL galaxy: the one
     * that now holds of the order of 10¹¹ systems. A test against an empty generator would pass by
     * having nothing to enumerate.</p>
     */
    private static final class CountingGenerator implements zmaster587.advancedRocketry.universe.IGalaxyGenerator {

        private final ClusteredGalaxyGenerator real;
        int queries;

        CountingGenerator(GalaxyGenConfig config) {
            this.real = new ClusteredGalaxyGenerator(config);
        }

        @Override
        public java.util.Optional<zmaster587.advancedRocketry.universe.PlanetarySystem> systemAt(
                long seed, GalacticCoord coord) {
            queries++;
            return real.systemAt(seed, coord);
        }

        @Override
        public java.util.Map<GalacticCoord, zmaster587.advancedRocketry.universe.PlanetarySystem>
                systemsInRegion(long seed, GalacticCoord min, GalacticCoord max) {
            queries++;
            return real.systemsInRegion(seed, min, max);
        }

        @Override
        public java.util.Optional<GalacticCoord> anchorAt(long seed, GalacticCoord cell) {
            queries++;
            return real.anchorAt(seed, cell);
        }

        @Override
        public java.util.List<SystemBody> bodiesFor(long seed, GalacticCoord systemCoord) {
            queries++;
            return real.bodiesFor(seed, systemCoord);
        }

        @Override
        public int minSpacingCells() {
            return real.minSpacingCells();
        }
    }

    @Test
    public void aSurveyResolvesPerLookAndNeverWalksTheGalaxy() {
        // The claim the scale change rests on: a galaxy holding 10^11 systems is affordable ONLY
        // because nothing ever enumerates one. A survey asks a bounded number of questions — a
        // constant per look — and that number is a property of the INSTRUMENT, not of how much sky
        // there is. A full-galaxy walk introduced anywhere on this path would blow the bound by nine
        // orders, and this test would not merely fail: it would never return.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        CountingGenerator counting = new CountingGenerator(config);
        UniverseRegistry.setGenerator(counting);
        UniverseRegistry.setStarLookup(TelescopeRegionScanTest::star);

        UniverseRegistry registry = new UniverseRegistry();
        registry.bindWorldSeed(0xC0FFEEL);

        // Aimed at the real reach, through the real config's own stride.
        RegionScan.Tuning live = new RegionScan.Tuning(100d, 1, 512, 100, 50d, 4,
                config.minSpacing);
        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, live.maxRangeSteps(), 0L, live);
        int looks = scan.totalCells();
        assertTrue("the fixture must be a real sweep", looks >= 27);

        CrystalMemory crystal = new CrystalMemory();
        TelescopeScan.resolveBatch(registry, scan, 0, looks, crystal, 7_000L, dimId -> "Body-" + dimId);

        // A handful of questions per look: which system owns the cell, and what that system holds.
        int budget = looks * 8;
        System.out.println("survey of " + looks + " looks asked the generator " + counting.queries
                + " questions (budget " + budget + ")");
        assertTrue("a survey asked the generator " + counting.queries + " questions for " + looks
                        + " looks — something on this path is enumerating rather than resolving",
                counting.queries <= budget);
    }

    // ── a look is a touch ─────────────────────────────────────────────────────

    /** How a system reads to a test: what it is, and where each of its bodies stands. */
    private static String describe(UniverseRegistry registry, GalacticCoord anchor) {
        StringBuilder sb = new StringBuilder();
        // Asked through systemForCoord, which answers pinned OR derived. starIdForCoord reads the
        // override store alone, so it would report the PIN rather than the system and turn "this system
        // did not move" into "this system is now in the store", which is a different claim.
        sb.append(registry.systemForCoord(anchor)
                .map(s -> s.systemId() + "/" + s.primaryKind() + "/" + s.name())
                .orElse("none"));
        java.util.List<String> bodies = new java.util.ArrayList<>();
        for (SystemBody b : registry.systemBodiesAt(anchor)) {
            bodies.add(b.name().cellKey() + ':' + b.kind() + ':' + b.radiusEarths());
        }
        java.util.Collections.sort(bodies);
        return sb.append(bodies).toString();
    }

    /** The same universe with one knob moved — everything untouched is derived differently under it. */
    private static GalaxyGenConfig retuned() {
        return new GalaxyGenConfig(GalaxyGenConfig.DEFAULT_MIN_SPACING, 0.9d,
                GalaxyGenConfig.DEFAULT_GALAXY_SPACING, GalaxyGenConfig.DEFAULT_GALAXY_DENSITY,
                null, null);
    }

    @Test
    public void aSystemAScanReportedIsFrozenAgainstALaterRetune() {
        // The promise the whole schema-versioning rests on: what the player has SEEN stops moving.
        // A survey answers out of the derivation, so without a pin the system on his crystal is a
        // function of the pack's current knobs — and he finds that out by flying there.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(config));
        UniverseRegistry.setStarLookup(TelescopeRegionScanTest::star);
        UniverseRegistry registry = new UniverseRegistry();
        registry.bindWorldSeed(0xC0FFEEL);

        GalacticCoord looked = cell(0, 0, 0);
        GalacticCoord neverLooked = cell(3 * STEP, 0, 0);
        GalacticCoord lookedAnchor = registry.anchorForCell(looked).orElse(null);
        GalacticCoord otherAnchor = registry.anchorForCell(neverLooked).orElse(null);
        assertNotNull("arrangement: the looked-at cell must hold a system", lookedAnchor);
        assertNotNull("arrangement: the control cell must hold a system", otherAnchor);
        String lookedBefore = describe(registry, lookedAnchor);
        String otherBefore = describe(registry, otherAnchor);

        CrystalMemory crystal = new CrystalMemory();
        assertTrue("arrangement: the look must report something",
                TelescopeScan.resolveCell(registry, looked, crystal, 7_000L, dimId -> "Body-" + dimId) > 0);

        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(retuned()));

        assertNotEquals("arrangement: the retune must actually move an untouched system, or this test "
                        + "proves nothing", otherBefore, describe(registry, otherAnchor));
        assertEquals("a system a telescope reported must survive a retune of the universe it was "
                        + "derived from", lookedBefore, describe(registry, lookedAnchor));
    }

    @Test
    public void aLookIntoTheVoidFreezesNothing() {
        // The pin must follow the REPORT, not the look: freezing empty sky would fill the save with
        // snapshots of nothing and take space out of the pack author's hands for no promise made.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();

        TelescopeScan.resolveCell(registry, cell(400 * STEP, 0, 0), crystal, 7_000L,
                dimId -> "Body-" + dimId);

        assertEquals("a look at nothing must write no snapshot into the save", 0, pinnedCount(registry));
    }

    @Test
    public void whatASurveyFreezesIsMeasuredNotAssumed() {
        // A pin snapshots a whole system, so a wide sweep is a write. The cost is stated here as a
        // NUMBER rather than asserted to be small: the bound below is a tripwire against an order of
        // magnitude, and the printed figures are what a decision about survey width is made from.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(config));
        UniverseRegistry.setStarLookup(TelescopeRegionScanTest::star);
        UniverseRegistry registry = new UniverseRegistry();
        registry.bindWorldSeed(0xC0FFEEL);

        RegionScan.Tuning live = new RegionScan.Tuning(100d, 1, 512, 100, 50d, 4, config.minSpacing);
        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, live.maxRangeSteps(), 0L, live);
        int looks = scan.totalCells();
        CrystalMemory crystal = new CrystalMemory();

        long startedAt = System.nanoTime();
        TelescopeScan.resolveBatch(registry, scan, 0, looks, crystal, 7_000L, dimId -> "Body-" + dimId);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        NBTTagCompound tag = new NBTTagCompound();
        registry.writeToNBT(tag);
        int pins = pinnedCount(registry);
        int bytes = tag.toString().length();
        System.out.println("survey of " + looks + " looks froze " + pins + " systems in " + elapsedMs
                + " ms; the universe save renders as " + bytes + " chars");

        assertTrue("a survey must not freeze more systems than it had looks (" + pins + " pins for "
                + looks + " looks)", pins <= looks);
        assertTrue("arrangement: the sweep must have frozen something", pins > 0);
    }

    private static int pinnedCount(UniverseRegistry registry) {
        NBTTagCompound tag = new NBTTagCompound();
        registry.writeToNBT(tag);
        return tag.getTagList("pinnedSystems", 10).tagCount();
    }

    @Test
    public void aLookIntoTheVoidDiscoversNothing() {
        // The gate exists so that empty sky does not manufacture addresses — and the fix must not
        // trade one failure for its opposite by attributing every cell to some system.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();

        int written = TelescopeScan.resolveCell(registry, cell(400 * STEP, 0, 0), crystal, 7_000L,
                dimId -> "Body-" + dimId);

        assertEquals("interstellar void must yield no addresses at all", 0, written);
        assertEquals("and must write nothing onto the crystal", 0, crystal.size());
    }

    @Test
    public void anInstrumentInsideASystemResolvesTheSystemItStandsIn() {
        // An observatory does not stand on its own star: it stands on a planet, in one of its
        // system's member cells. Under the old gate that cell reported empty, so the machine could
        // not name the system it was sitting in — which is also what the local radar is for.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();
        GalacticCoord standingOn = cell(4 * STEP - 20 + 5_000, 3_000, 0); // a member cell, not the seat

        TelescopeScan.resolveCell(registry, standingOn, crystal, 7_000L, dimId -> "Body-" + dimId);

        assertNotNull("an instrument inside a system must be able to name that system",
                crystal.forBody(401));
    }

    @Test
    public void aSystemTheCrystalNeverHeardOfIsStillDiscovered() {
        // The discriminator against the tempting wrong shape — reporting only what is already known.
        // The crystal is armed with ONE of the two systems in the region, so the other is genuinely
        // new: a knowledge gate anywhere on this path leaves it missing and this test red.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(new CrystalEntry(stepsOut(4), "Body-401", SystemBodyKind.PLANET,
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
            if (scan.cellAt(i).cellKey().equals(stepsOut(4).cellKey())) {
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
