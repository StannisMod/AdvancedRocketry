package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.IDimensionProperties;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Derives the addressable {@link SystemBody} content of an AUTHORED system (a catalogued {@link StellarBody})
 * from its planets/moons (universe-model.md &sect;2 amendment A#1a + &sect;4). A system is an anchored
 * NEIGHBOURHOOD of cells: the star sits at the anchor cell's centre; every planet/belt gets its <b>own cell</b>
 * at a sector offset scaled from its orbital position ({@link #ORBIT_UNIT_BLOCKS ~1M blocks per orbit-unit},
 * {@code tunable}), snapped to that cell's centre (zone content sits near the cell centre); moons stay LOCAL
 * inside their parent planet's cell. Inter-body space is cells of void.
 *
 * <p>The neighbourhood is BOUNDED: every body cell is clamped (with a WARN) into the anchor's
 * {@code minSpacing}-cube super-cell, {@link #BOX_MARGIN_CELLS} cells clear of its faces — the load-time
 * guard that keeps two systems' neighbourhoods from interleaving, whatever an XML author wrote for
 * {@code orbitalDistance} (its cap is {@code Integer.MAX_VALUE}).</p>
 *
 * <p>Pure DATA — a walkable realization is Layer 2. Scale constants are {@code tunable}.</p>
 */
public final class SystemContent {

    /** Blocks per unit of {@code DimensionProperties} orbital distance (A#1a: ~1M blocks per orbit-unit). */
    static final long ORBIT_UNIT_BLOCKS = 1_000_000L;
    /** Blocks per unit of a moon's (parent-relative) orbital distance — moons cluster near their planet. */
    static final long MOON_UNIT_BLOCKS = 200L;
    /** Cells kept clear of the super-cell faces when clamping a body cell into its system's box. */
    static final int BOX_MARGIN_CELLS = 2;

    private static final long MAX_MOON_LOCAL = GalacticCoord.HALF_CELL - 1L; // moons stay inside the parent cell

    // Self-contained logger (not AdvancedRocketry.logger): loading the mod class triggers Forge bootstrap,
    // which would break pure unit tests of this derivation.
    private static final Logger LOGGER = LogManager.getLogger("AdvancedRocketry|Universe");

    private SystemContent() {
    }

    /** Legacy-spacing overload: derives with the default super-cell edge. */
    public static List<SystemBody> bodiesOf(StellarBody star, GalacticCoord systemCoord) {
        return bodiesOf(star, systemCoord, GalaxyGenConfig.DEFAULT_MIN_SPACING);
    }

    /**
     * The system's bodies, per-body-cell (A#1a). {@code minSpacingCells} is the active generator's
     * super-cell edge — the box every body cell is clamped into.
     */
    public static List<SystemBody> bodiesOf(StellarBody star, GalacticCoord systemCoord, int minSpacingCells) {
        List<SystemBody> bodies = new ArrayList<>();
        if (star == null) {
            return bodies;
        }
        int starId = star.getId();
        GalacticCoord anchor = systemCoord.cellCentre();
        bodies.add(new SystemBody(anchor, SystemBodyKind.STAR, Constants.INVALID_PLANET, starId));

        for (IDimensionProperties p : star.getPlanets()) {
            if (!(p instanceof DimensionProperties)) {
                continue;
            }
            DimensionProperties planet = (DimensionProperties) p;
            double[] pos = planet.getPlanetPosition();
            long px = Math.round(pos[0] * ORBIT_UNIT_BLOCKS);
            long py = Math.round(pos[1] * ORBIT_UNIT_BLOCKS);
            long pz = Math.round(pos[2] * ORBIT_UNIT_BLOCKS);
            // The planet's address is its OWN cell's centre; the cell is clamped into the anchor's
            // super-cell box so the neighbourhood can never reach a neighbouring system's territory.
            GalacticCoord planetAddr = clampIntoBox(
                    anchor.plusLocal(px, py, pz).cellCentre(), anchor, minSpacingCells, planet.getId());
            bodies.add(new SystemBody(planetAddr, SystemBodyKind.PLANET, planet.getId(), starId));

            for (int moonId : planet.getChildPlanets()) {
                DimensionProperties moon = DimensionManager.getInstance().getDimensionProperties(moonId);
                if (moon == null) {
                    continue;
                }
                double[] moonPos = moon.getPlanetPosition();
                long mx = clampMoonLocal(Math.round(moonPos[0] * MOON_UNIT_BLOCKS));
                long my = clampMoonLocal(Math.round(moonPos[1] * MOON_UNIT_BLOCKS));
                long mz = clampMoonLocal(Math.round(moonPos[2] * MOON_UNIT_BLOCKS));
                // Moons stay LOCAL: same cell as the parent planet, small offset from its centre.
                GalacticCoord moonAddr = GalacticCoord.ofSectorLocal(
                        planetAddr.sectorX(), planetAddr.sectorY(), planetAddr.sectorZ(), mx, my, mz);
                bodies.add(new SystemBody(moonAddr, SystemBodyKind.MOON, moon.getId(), starId));
            }
        }
        auditOneRealBodyPerCell(bodies, starId);
        return bodies;
    }

    /**
     * Layout problems already reported. This derivation runs on EVERY query — the console's forecast
     * once a second, the render broadcast, the entry resolver, every probe — so an unguarded report
     * is a flood, not a diagnostic: a 28-minute playtest produced 28,061 clamp warnings and drowned
     * the log it was needed in. One report per distinct problem, per session.
     */
    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<String>());

    /**
     * INVARIANT: at most ONE real body per cell. A star and a planet are real bodies and each owns its
     * own cell; moons are exempt by construction — a moon lives in its parent planet's cell, which is
     * what makes a planet-and-its-moons one destination.
     *
     * <p>Two real bodies in one cell is not a cosmetic problem. A cell is what a jump can be aimed at
     * and what a ship arrives into, so a collision means two destinations the player cannot tell apart
     * or choose between, and an arrival that cannot say which body it came for. It is reported, not
     * repaired: silently moving an authored body would make the address a player wrote down mean
     * something else, and the honest repair belongs where the layout is decided.</p>
     */
    private static void auditOneRealBodyPerCell(List<SystemBody> bodies, int starId) {
        Map<String, List<Integer>> realBodiesByCell = new LinkedHashMap<>();
        for (SystemBody body : bodies) {
            if (body.kind() == SystemBodyKind.MOON) {
                continue; // exempt: a moon shares its parent's cell on purpose
            }
            String cell = body.address().cellKey();
            List<Integer> occupants = realBodiesByCell.get(cell);
            if (occupants == null) {
                occupants = new ArrayList<>();
                realBodiesByCell.put(cell, occupants);
            }
            occupants.add(body.dimId());
        }
        for (Map.Entry<String, List<Integer>> e : realBodiesByCell.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            if (REPORTED.add("collision:" + starId + ':' + e.getKey() + ':' + e.getValue())) {
                LOGGER.error("system {}: cell {} holds {} REAL bodies (dims {}) - a cell may hold at "
                        + "most one, moons excepted. They are one indistinguishable destination: a jump "
                        + "aimed at that address cannot say which body it meant, and an arrival cannot "
                        + "either. Spread the authored orbits, or give the bodies explicit cells.",
                        starId, e.getKey(), e.getValue().size(), e.getValue());
            }
        }
    }

    /**
     * Clamp a body's cell into its anchor's super-cell box, {@link #BOX_MARGIN_CELLS} clear of the faces.
     * A clamp means the authored orbit exceeds what the spacing guarantee can host — WARN, don't crash.
     */
    private static GalacticCoord clampIntoBox(GalacticCoord bodyCell, GalacticCoord anchor,
                                              int minSpacingCells, int dimId) {
        long s = Math.max(1, minSpacingCells);
        long margin = (s > 2L * BOX_MARGIN_CELLS) ? BOX_MARGIN_CELLS : 0L;
        long cx = clampAxis(bodyCell.sectorX(), anchor.sectorX(), s, margin);
        long cy = clampAxis(bodyCell.sectorY(), anchor.sectorY(), s, margin);
        long cz = clampAxis(bodyCell.sectorZ(), anchor.sectorZ(), s, margin);
        if ((cx != bodyCell.sectorX() || cy != bodyCell.sectorY() || cz != bodyCell.sectorZ())
                && REPORTED.add("clamp:" + dimId + ':' + bodyCell.cellKey())) {
            LOGGER.warn("orbit of dim {} exceeds the system neighbourhood bound (minSpacing {} cells); "
                    + "clamping its cell from ({},{},{}) into the anchor's super-cell",
                    dimId, s, bodyCell.sectorX(), bodyCell.sectorY(), bodyCell.sectorZ());
        }
        return GalacticCoord.ofSectorLocal(cx, cy, cz, 0L, 0L, 0L);
    }

    /**
     * The per-axis bound: {@code half - margin} cells either side OF THE ANCHOR.
     *
     * <p>This used to snap to the GRID super-cell containing the anchor —
     * {@code [floorDiv(anchor,s)*s + margin, … + s-1-margin]} — which is a different box, and for the
     * home system a disastrous one: with the anchor at sector 0 and {@code s = 512} the legal range
     * was {@code [2, 509]}, i.e. the POSITIVE OCTANT ONLY. Every body with a negative offset was
     * clamped flat onto the faces {@code x=2}/{@code y=2}, so half of every orbit collapsed into a
     * handful of cells and several real bodies ended up sharing one address (INV-UNI-01, ledger
     * #118). Measured 2026-07-28: dim 0's own cell derived as {@code (-3,0,25)} and clamped to
     * {@code (2,2,25)} — which is exactly the cell a ship entering space from Earth then settled in.
     * Centring the box on the anchor is also what this class's javadoc and
     * {@code ClusteredGalaxyGenerator} ("minSpacing/2 - margin") always claimed it did.</p>
     */
    private static long clampAxis(long sector, long anchorSector, long s, long margin) {
        long half = s / 2L;
        long reach = half - margin;
        if (reach < 0L) {
            reach = 0L;
        }
        long lo = anchorSector - reach;
        long hi = anchorSector + reach;
        if (sector < lo) {
            return lo;
        }
        return sector > hi ? hi : sector;
    }

    private static long clampMoonLocal(long v) {
        if (v > MAX_MOON_LOCAL) {
            return MAX_MOON_LOCAL;
        }
        return v < -MAX_MOON_LOCAL ? -MAX_MOON_LOCAL : v;
    }
}
