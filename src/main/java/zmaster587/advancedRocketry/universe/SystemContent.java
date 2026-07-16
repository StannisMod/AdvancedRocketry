package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.List;

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
        return bodies;
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
        if (cx != bodyCell.sectorX() || cy != bodyCell.sectorY() || cz != bodyCell.sectorZ()) {
            LOGGER.warn("orbit of dim {} exceeds the system neighbourhood bound (minSpacing {} cells); "
                    + "clamping its cell from ({},{},{}) into the anchor's super-cell",
                    dimId, s, bodyCell.sectorX(), bodyCell.sectorY(), bodyCell.sectorZ());
        }
        return GalacticCoord.ofSectorLocal(cx, cy, cz, 0L, 0L, 0L);
    }

    private static long clampAxis(long sector, long anchorSector, long s, long margin) {
        long sup = Math.floorDiv(anchorSector, s);
        long lo = sup * s + margin;
        long hi = sup * s + s - 1L - margin;
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
