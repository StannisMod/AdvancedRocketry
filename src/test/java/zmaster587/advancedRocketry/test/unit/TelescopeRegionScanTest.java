package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.EmptyGalaxyGenerator;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.RegionScan;
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
 * Contract tests for the telescope's region scan: what an observation costs in time, how far it can
 * reach, how much of the sky one look may enumerate, and what a discovery writes onto a crystal.
 * Pure-JUnit — no MC bootstrap; the registry's generator and star lookup are the injectable seams.
 *
 * <p>These pin player-facing promises — a far look takes longer, the horizon is the configured reach,
 * an unknown system is discoverable, a telescope's word is coarse and dated — and the save contract
 * that an observation outlives the chunk it started in. They do not pin the time formula, the box
 * arithmetic or the storage shape.</p>
 */
public class TelescopeRegionScanTest {

    private static final GalacticCoord HOME = GalacticCoord.ofSectorLocal(0, 0, 0, 0, 0, 0);

    /** Reach 10 sectors, a 3x3x3 look, a generous budget, 100 ticks to point plus 50 per sector. */
    private static RegionScan.Tuning tuning() {
        return new RegionScan.Tuning(10, 1, 512, 100, 50);
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
    public void aFartherRegionTakesLongerToLookAt() {
        // The whole point of a region scan: distance is paid for in time.
        RegionScan near = RegionScan.directed(HOME, 1, 0, 0, 2, 0L, tuning());
        RegionScan far = RegionScan.directed(HOME, 1, 0, 0, 8, 0L, tuning());

        assertTrue("a farther region must take longer to resolve: near=" + near.durationTicks()
                        + " far=" + far.durationTicks(),
                far.durationTicks() > near.durationTicks());
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
                reached.durationTicks(), overreached.durationTicks());
        assertEquals("the region itself must be the one at the horizon",
                reached.min().cellKey(), overreached.min().cellKey());
    }

    @Test
    public void oneLookNeverEnumeratesMoreThanItsSectorBudget() {
        // The structural guard against reading an endless procedural universe off one instrument: ask
        // for a 9x9x9 look with a budget of 27 and the budget wins.
        RegionScan.Tuning greedy = new RegionScan.Tuning(10, 4, 27, 100, 50);
        RegionScan scan = RegionScan.directed(HOME, 1, 1, 0, 3, 0L, greedy);

        assertTrue("a scan may never enumerate more sectors than its budget: " + scan.sectorCount(),
                scan.sectorCount() <= 27);
    }

    @Test
    public void aScanWithNoDirectionIsRefused() {
        try {
            RegionScan.directed(HOME, 0, 0, 0, 4, 0L, tuning());
            fail("a scan with no direction does not name a region and must be refused");
        } catch (IllegalArgumentException expected) {
            // the contract: the caller is told, rather than silently given some default sky
        }
    }

    @Test
    public void progressIsReadOffTheClock() {
        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, 4, 1000L, tuning());
        long done = scan.deadlineTick();

        assertEquals("nothing has been resolved at the moment it starts", 0f, scan.progress(1000L), 1e-6f);
        assertEquals("it is finished at its deadline", 1f, scan.progress(done), 1e-6f);
        float midway = scan.progress(1000L + (done - 1000L) / 2);
        assertTrue("and part-done in between, not 0 and not 1: " + midway, midway > 0f && midway < 1f);
    }

    @Test
    public void anObservationOutlivesTheChunkItStartedIn() {
        // A scan is a deadline, not a counter: it is saved, reloaded, and still finishes on time —
        // which is what lets an observatory be unloaded mid-look without owing a tick replay.
        RegionScan started = RegionScan.directed(HOME, 1, 0, -1, 6, 5_000L, tuning());
        NBTTagCompound nbt = new NBTTagCompound();
        started.writeToNBT(nbt);

        RegionScan reloaded = RegionScan.readFromNBT(nbt);
        assertNotNull("a saved scan must come back", reloaded);
        assertEquals("it must come back looking at the same region",
                started.min().cellKey(), reloaded.min().cellKey());
        assertEquals(started.max().cellKey(), reloaded.max().cellKey());
        assertFalse("and it is not finished a tick early",
                reloaded.isComplete(started.deadlineTick() - 1));
        assertTrue("but is finished at the tick it was always going to finish",
                reloaded.isComplete(started.deadlineTick()));
    }

    @Test
    public void nothingIsStoredForAnObservatoryThatIsNotLooking() {
        assertNull("an absent scan must read back as absent, not as a scan at tick zero",
                RegionScan.readFromNBT(new NBTTagCompound()));
    }

    // ── what a look discovers ─────────────────────────────────────────────────

    /** A registry holding three systems: two inside the scanned box, one well outside it. */
    private UniverseRegistry threeSystems() {
        UniverseRegistry.setGenerator(new EmptyGalaxyGenerator());
        UniverseRegistry.setStarLookup(TelescopeRegionScanTest::star);

        UniverseRegistry registry = new UniverseRegistry();
        registry.place(cell(4, 0, 0), 4);    // dead centre of the box
        registry.place(cell(5, 1, 0), 5);    // corner of the box
        registry.place(cell(9, 0, 0), 9);    // beyond it
        return registry;
    }

    /** The scan the fixture above is built around: 4 sectors out along +X, one sector wide. */
    private RegionScan boxAroundFourthSector() {
        return RegionScan.directed(HOME, 1, 0, 0, 4, 0L, tuning());
    }

    @Test
    public void whatIsInTheRegionIsLearnedAndWhatIsOutsideItIsNot() {
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();

        TelescopeScan.recordInto(registry, boxAroundFourthSector(), crystal, 7_000L);

        assertTrue("the system the instrument was pointed at must be learned", crystal.knows(cell(4, 0, 0)));
        assertTrue("so must the one at the edge of the same region", crystal.knows(cell(5, 1, 0)));
        assertFalse("a system outside the scanned region must NOT be", crystal.knows(cell(9, 0, 0)));
    }

    @Test
    public void aSystemTheCrystalNeverHeardOfIsStillDiscovered() {
        // The discriminator against the tempting wrong shape — reporting only what is already known.
        // The crystal is armed with ONE of the two systems in the region, so the other is genuinely
        // new: a knowledge gate anywhere on this path leaves it missing and this test red.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();
        crystal.record(new CrystalEntry(cell(4, 0, 0), "Star-4", SystemBodyKind.STAR,
                InfoTier.TELESCOPE, 1_000L));
        assertFalse("the fixture must start ignorant of the system under test",
                crystal.knows(cell(5, 1, 0)));

        TelescopeScan.recordInto(registry, boxAroundFourthSector(), crystal, 7_000L);

        assertTrue("a telescope discovers what nobody knew, or it discovers nothing",
                crystal.knows(cell(5, 1, 0)));
    }

    @Test
    public void whatATelescopeWritesIsCoarseAndDated() {
        // The graded-discovery ladder: a point of light resolved from far away may claim the coarsest
        // tier and no more, and it carries when it was seen so a later, closer look supersedes it.
        UniverseRegistry registry = threeSystems();
        CrystalMemory crystal = new CrystalMemory();

        TelescopeScan.recordInto(registry, boxAroundFourthSector(), crystal, 7_000L);
        CrystalEntry learned = crystal.get(cell(4, 0, 0));

        assertNotNull(learned);
        assertEquals("a region scan resolves no more than telescope-grade detail",
                InfoTier.TELESCOPE, learned.detail());
        assertEquals("and dates what it saw", 7_000L, learned.observedTick());
        assertFalse("a system is an address, not a place to land", learned.namesBody());
    }
}
