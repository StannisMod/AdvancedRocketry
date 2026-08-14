package zmaster587.advancedRocketry.test.unit;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.PlanetDerivation;
import zmaster587.advancedRocketry.universe.PlanetTypes;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for a system's RETINUE — how many bodies it has, where they sit, and what it always
 * contains.
 *
 * <p>The shape is what is pinned, never the constants that produce it: a long-tailed body count rather
 * than a fixed ceiling, an outer belt on every system without exception, moons living inside their
 * parent's cell, and — the one that is not cosmetic — <b>no two real bodies sharing a cell</b>. A cell
 * is the unit a jump is aimed at and the unit a ship arrives into, so two real bodies in one are two
 * destinations a player can neither tell apart nor choose between.</p>
 */
public class SystemRetinueTest {

    private static final long SEED = 0xA57E401DL;

    @After
    public void restoreGlobals() {
        PlanetTypes.resetToStock();
        PlanetTypes.setWorldTypeAvailability(null);
    }

    private static GalacticCoord cell(long sx, long sy, long sz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, 0L, 0L, 0L);
    }

    /** The shipped spacing: a system laid out here is the system the game ships. */
    private static final int SPACING = GalaxyGenConfig.DEFAULT_MIN_SPACING;

    /**
     * A spacing tight enough that a system's own clear space, not its star's zone, decides how far its
     * outermost body may sit. It is where the collision risk bites, because every body is squeezed into
     * far fewer distinct cells.
     */
    private static final int CRAMPED_SPACING = 1_000;

    /** A galaxy dense enough to sample: every cube occupied, so a small sweep finds many systems. */
    private static ClusteredGalaxyGenerator gen(int minSpacing) {
        return new ClusteredGalaxyGenerator(new GalaxyGenConfig(0.9d, minSpacing, 8, 0.0d, null));
    }

    /** Every occupied system anchor in a sweep of super-cells. */
    private static List<GalacticCoord> anchors(ClusteredGalaxyGenerator g, long seed, int minSpacing,
                                               int supercells) {
        Set<String> seen = new HashSet<>();
        List<GalacticCoord> out = new ArrayList<>();
        for (long sx = -supercells; sx <= supercells; sx++) {
            for (long sy = -supercells; sy <= supercells; sy++) {
                for (long sz = -supercells; sz <= supercells; sz++) {
                    Optional<GalacticCoord> a = g.anchorAt(seed,
                            cell(sx * minSpacing, sy * minSpacing, sz * minSpacing));
                    if (a.isPresent() && seen.add(a.get().cellKey())) {
                        out.add(a.get());
                    }
                }
            }
        }
        return out;
    }

    // ─── The invariant the audit exists to protect ─────────────────────────────

    @Test
    public void noTwoRealBodiesOfOneSystemShareACell() {
        // Measured the way SystemContent.auditOneRealBodyPerCell measures it — moons exempt, because a
        // moon lives in its parent's cell by construction — so the generator and the audit cannot
        // disagree silently about what the invariant says.
        int minSpacing = SPACING;
        ClusteredGalaxyGenerator g = gen(minSpacing);
        int checked = 0;
        for (GalacticCoord anchor : anchors(g, SEED, minSpacing, 3)) {
            Map<String, Integer> perCell = new HashMap<>();
            for (SystemBody b : g.bodiesFor(SEED, anchor)) {
                if (b.kind() == SystemBodyKind.MOON) {
                    continue;
                }
                perCell.merge(b.name().cellKey(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : perCell.entrySet()) {
                assertEquals("system " + anchor.cellKey() + " put " + e.getValue()
                        + " real bodies in cell " + e.getKey(), 1, (int) e.getValue());
            }
            checked++;
        }
        assertTrue("the sweep must actually find systems", checked > 5);
    }

    @Test
    public void theInvariantHoldsEvenWhenTheNeighbourhoodIsCrampedForRoom() {
        // The collision risk grows with the square of the body count, so the tightest spacing that still
        // has more than one cell is where it bites. A cramped system is allowed to hold FEWER bodies;
        // it is not allowed to hold two in one cell.
        int minSpacing = CRAMPED_SPACING;
        ClusteredGalaxyGenerator g = gen(minSpacing);
        int checked = 0;
        for (GalacticCoord anchor : anchors(g, SEED, minSpacing, 3)) {
            Set<String> cells = new HashSet<>();
            for (SystemBody b : g.bodiesFor(SEED, anchor)) {
                if (b.kind() == SystemBodyKind.MOON) {
                    continue;
                }
                assertTrue("cell " + b.name().cellKey() + " of system " + anchor.cellKey()
                        + " holds a second real body", cells.add(b.name().cellKey()));
            }
            checked++;
        }
        assertTrue(checked > 5);
    }

    // ─── what a system loses when it does not fit ──────────────────────────────

    @Test
    public void atTheShippedScaleNoSystemLosesABodyAtAll() {
        // The bound is a GUARD, not a mechanic anybody meets. Measured 2026-08-14: the widest zone
        // any shipped star archetype can draw is 569 AU against a clear space of 5 000 — a factor of
        // nearly nine. If this ever goes red, either the star table gained something far hotter or
        // the spacing was cut by two orders, and both are worth knowing about deliberately.
        ClusteredGalaxyGenerator g = gen(SPACING);
        int checked = 0;
        for (GalacticCoord anchor : anchors(g, SEED, SPACING, 2)) {
            int wanted = ClusteredGalaxyGenerator.retinueSize(SEED, anchor);
            int got = 0;
            for (SystemBody b : g.bodiesFor(SEED, anchor)) {
                if (b.kind() == SystemBodyKind.PLANET || b.kind() == SystemBodyKind.GAS_GIANT) {
                    got++;
                }
            }
            assertEquals("system " + anchor.cellKey() + " lost a body it had room for", wanted, got);
            checked++;
        }
        assertTrue(checked > 10);
    }

    @Test
    public void aCrampedSystemDropsBodiesAndNeverMovesTheOnesItKeeps() {
        // The distinction the whole placement seam exists for. A system squeezed by its neighbours
        // holds FEWER worlds; it does not hold the same worlds at distances their own climate,
        // insolation and year do not describe. So every body a cramped system keeps must stand at an
        // orbit the star's own zone drew, unchanged — never at one scaled to fit the room.
        ClusteredGalaxyGenerator g = gen(CRAMPED_SPACING);
        int droppedSomewhere = 0;
        int checked = 0;
        for (GalacticCoord anchor : anchors(g, SEED, CRAMPED_SPACING, 2)) {
            StellarBody star = g.systemAt(SEED, anchor).get().star();
            int count = ClusteredGalaxyGenerator.retinueSize(SEED, anchor);
            Set<Integer> drawn = new HashSet<>();
            for (int i = 0; i < count; i++) {
                drawn.add(PlanetDerivation.orbitalDistanceOf(SEED, anchor, i, count, star));
            }
            int kept = 0;
            for (SystemBody b : g.bodiesFor(SEED, anchor)) {
                if (b.kind() != SystemBodyKind.PLANET && b.kind() != SystemBodyKind.GAS_GIANT) {
                    continue;
                }
                kept++;
                assertTrue("a kept body stands at orbit " + b.orbitalDistance()
                                + ", which its star never drew — it was moved to fit",
                        drawn.contains(b.orbitalDistance()));
            }
            if (kept < count) {
                droppedSomewhere++;
            }
            checked++;
        }
        assertTrue(checked > 10);
        assertTrue("the cramped fixture must actually be cramped, or this proves nothing",
                droppedSomewhere > 0);
    }

    // ─── E1: a long-tailed body count ──────────────────────────────────────────

    @Test
    public void systemSizeIsLongTailedRatherThanCapped() {
        List<Integer> counts = new ArrayList<>();
        for (long x = -400; x <= 400; x++) {
            counts.add(ClusteredGalaxyGenerator.retinueSize(SEED, cell(x, 0, 0)));
        }
        Collections.sort(counts);
        int median = counts.get(counts.size() / 2);
        int biggest = counts.get(counts.size() - 1);
        int smallest = counts.get(0);

        assertTrue("an ordinary system must be a handful of bodies, saw a median of " + median,
                median >= 4 && median <= 8);
        assertTrue("a rare system must be genuinely large — a find, not just a bit bigger; biggest "
                + "seen was " + biggest, biggest >= 15);
        assertTrue("and no system may be empty", smallest >= 1);
        // The tail must be a TAIL: large systems rare, not a second mode.
        int large = 0;
        for (int c : counts) {
            if (c >= 12) {
                large++;
            }
        }
        assertTrue("large systems must stay rare, saw " + large + "/" + counts.size(),
                large * 10 < counts.size());
        assertTrue("but they must exist at all", large > 0);
    }

    @Test
    public void theRetinueSizeIsDeterministic() {
        for (long x = -50; x <= 50; x++) {
            GalacticCoord c = cell(x, 7, -3);
            assertEquals(ClusteredGalaxyGenerator.retinueSize(SEED, c),
                    ClusteredGalaxyGenerator.retinueSize(SEED, c));
        }
    }

    // ─── E3: every system has an outer belt ────────────────────────────────────

    @Test
    public void everySystemEndsInABelt() {
        // Load-bearing beyond this task: drifting out of jump range is only survivable because every
        // system has something to mine without landing. "Usually" would be a soft-lock.
        int minSpacing = SPACING;
        ClusteredGalaxyGenerator g = gen(minSpacing);
        int checked = 0;
        for (GalacticCoord anchor : anchors(g, SEED, minSpacing, 3)) {
            List<SystemBody> bodies = g.bodiesFor(SEED, anchor);
            int belts = 0;
            int outermostMajor = 0;
            int outermostBelt = 0;
            for (SystemBody b : bodies) {
                if (b.kind() == SystemBodyKind.ASTEROID_BELT) {
                    belts++;
                    outermostBelt = Math.max(outermostBelt, b.orbitalDistance());
                } else if (b.kind() != SystemBodyKind.STAR && b.kind() != SystemBodyKind.MOON) {
                    outermostMajor = Math.max(outermostMajor, b.orbitalDistance());
                }
            }
            assertTrue("system " + anchor.cellKey() + " has no belt at all", belts >= 1);
            assertTrue("the outermost body of a system must be a belt (major " + outermostMajor
                    + ", belt " + outermostBelt + ")", outermostBelt > outermostMajor);
            checked++;
        }
        assertTrue(checked > 5);
    }

    @Test
    public void anInnerBeltAppearsOnlyWhereAGiantClearedOne() {
        // A belt is material a giant's resonances stopped from accreting, so a second belt inside the
        // system implies a giant. The converse is not asserted: a giant near the edge has no room for a
        // gap inside it, and a cramped neighbourhood may have no free cell to put one in.
        int minSpacing = SPACING;
        ClusteredGalaxyGenerator g = gen(minSpacing);
        int systemsWithInnerBelt = 0;
        for (GalacticCoord anchor : anchors(g, SEED, minSpacing, 3)) {
            List<SystemBody> bodies = g.bodiesFor(SEED, anchor);
            boolean hasGiant = false;
            int belts = 0;
            for (SystemBody b : bodies) {
                if (b.kind() == SystemBodyKind.GAS_GIANT) {
                    hasGiant = true;
                } else if (b.kind() == SystemBodyKind.ASTEROID_BELT) {
                    belts++;
                }
            }
            if (belts > 1) {
                systemsWithInnerBelt++;
                assertTrue("system " + anchor.cellKey() + " has an inner belt with no giant to have "
                        + "cleared it", hasGiant);
            }
        }
        assertTrue("the sweep must contain systems with giants and inner belts", systemsWithInnerBelt > 0);
    }

    // ─── E2: moons ─────────────────────────────────────────────────────────────

    @Test
    public void moonsExistAndLiveInsideTheirParentsCell() {
        // Without moons the whole outer system is look-only: nothing out there is landable, because the
        // bodies big enough to be out there are the ones with no surface.
        int minSpacing = SPACING;
        ClusteredGalaxyGenerator g = gen(minSpacing);
        int moons = 0;
        int checked = 0;
        for (GalacticCoord anchor : anchors(g, SEED, minSpacing, 3)) {
            List<SystemBody> bodies = g.bodiesFor(SEED, anchor);
            Set<String> majorCells = new HashSet<>();
            for (SystemBody b : bodies) {
                if (b.kind() != SystemBodyKind.MOON) {
                    majorCells.add(b.name().cellKey());
                }
            }
            for (SystemBody b : bodies) {
                if (b.kind() != SystemBodyKind.MOON) {
                    continue;
                }
                moons++;
                assertTrue("a moon must share a major body's cell — a planet and its moons are ONE "
                        + "destination", majorCells.contains(b.name().cellKey()));
                assertTrue("a moon must be landable", b.kind().canDescend());
            }
            checked++;
        }
        assertTrue(checked > 5);
        assertTrue("a sweep of systems must produce moons", moons > 3);
    }

    @Test
    public void aMoonIsSomewhereElseInsideItsCellThanItsParent() {
        // A moon that never moved inside the cell would be at the cell centre, i.e. exactly where the
        // planet is — one address, two bodies, and a descent that cannot say which it came for.
        int minSpacing = SPACING;
        ClusteredGalaxyGenerator g = gen(minSpacing);
        boolean checkedAny = false;
        for (GalacticCoord anchor : anchors(g, SEED, minSpacing, 3)) {
            for (SystemBody b : g.bodiesFor(SEED, anchor)) {
                if (b.kind() != SystemBodyKind.MOON) {
                    continue;
                }
                checkedAny = true;
                assertFalse("a moon must stand off its cell's centre",
                        b.inCellOffsetAt(0L).isZero() && b.inCellOffsetAt(6000L).isZero());
                assertTrue("a moon must carry the orbit its climate is derived from — its PARENT's "
                        + "distance from the star", b.orbitalDistance() > 0);
            }
        }
        assertTrue(checkedAny);
    }

    // ─── E6: the layout follows the orbits ─────────────────────────────────────

    @Test
    public void aSystemsCellLayoutFollowsItsOrbits() {
        // The cell radius is derived from the orbit, so a body further from its star is further from the
        // anchor cell. If the two ever came apart, the map would show a system laid out differently from
        // the one the physics describes.
        int minSpacing = SPACING;
        ClusteredGalaxyGenerator g = gen(minSpacing);
        int checked = 0;
        for (GalacticCoord anchor : anchors(g, SEED, minSpacing, 2)) {
            SystemBody inner = null;
            SystemBody outer = null;
            for (SystemBody b : g.bodiesFor(SEED, anchor)) {
                if (b.kind() == SystemBodyKind.STAR || b.kind() == SystemBodyKind.MOON) {
                    continue;
                }
                if (inner == null || b.orbitalDistance() < inner.orbitalDistance()) {
                    inner = b;
                }
                if (outer == null || b.orbitalDistance() > outer.orbitalDistance()) {
                    outer = b;
                }
            }
            if (inner == null || outer == null || inner == outer) {
                continue;
            }
            assertTrue("the outermost body must sit further from the anchor cell than the innermost "
                            + "(inner " + inner.orbitalDistance() + " at "
                            + cellDistance(anchor, inner) + ", outer " + outer.orbitalDistance()
                            + " at " + cellDistance(anchor, outer) + ")",
                    cellDistance(anchor, outer) >= cellDistance(anchor, inner));
            checked++;
        }
        assertTrue(checked > 3);
    }

    // ─── determinism of the whole retinue ──────────────────────────────────────

    @Test
    public void theWholeRetinueIsDeterministic() {
        int minSpacing = SPACING;
        ClusteredGalaxyGenerator g = gen(minSpacing);
        int checked = 0;
        for (GalacticCoord anchor : anchors(g, SEED, minSpacing, 2)) {
            assertEquals("a system must regenerate identically", g.bodiesFor(SEED, anchor),
                    g.bodiesFor(SEED, anchor));
            // And a member cell must answer for the whole system, not just for itself.
            List<SystemBody> viaAnchor = g.bodiesFor(SEED, anchor);
            assertEquals(viaAnchor, g.bodiesFor(SEED, viaAnchor.get(viaAnchor.size() - 1).name()));
            checked++;
        }
        assertTrue(checked > 3);
    }

    private static long cellDistance(GalacticCoord anchor, SystemBody body) {
        long dx = body.name().sectorX() - anchor.sectorX();
        long dy = body.name().sectorY() - anchor.sectorY();
        long dz = body.name().sectorZ() - anchor.sectorZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
