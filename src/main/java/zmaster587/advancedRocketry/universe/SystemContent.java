package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.List;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.IDimensionProperties;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Derives the addressable {@link SystemBody} content of an AUTHORED system (a catalogued {@link StellarBody})
 * from its planets/moons (universe-model.md &sect;4). The star sits at the system's cell centre; each planet
 * is placed at its orbital position scaled into the in-system local frame (always kept inside the cell, so a
 * body's {@link GalacticCoord} address always shares its system's sector); each moon sits near its parent.
 *
 * <p>Pure DATA — a walkable realization is Layer 2. Scale constants are {@code tunable}.</p>
 */
public final class SystemContent {

    /** Blocks per unit of {@code DimensionProperties} orbital distance (max orbit ~800 stays under HALF_CELL). */
    static final long ORBIT_UNIT_BLOCKS = 2000L;
    /** Blocks per unit of a moon's (parent-relative) orbital distance — moons cluster near their planet. */
    static final long MOON_UNIT_BLOCKS = 200L;

    private static final long MAX_LOCAL = GalacticCoord.HALF_CELL - 1L; // keep every body inside its cell

    private SystemContent() {
    }

    public static List<SystemBody> bodiesOf(StellarBody star, GalacticCoord systemCoord) {
        List<SystemBody> bodies = new ArrayList<>();
        if (star == null) {
            return bodies;
        }
        int starId = star.getId();
        GalacticCoord cell = systemCoord.cellCentre();
        bodies.add(new SystemBody(cell, SystemBodyKind.STAR, Constants.INVALID_PLANET, starId));

        for (IDimensionProperties p : star.getPlanets()) {
            if (!(p instanceof DimensionProperties)) {
                continue;
            }
            DimensionProperties planet = (DimensionProperties) p;
            double[] pos = planet.getPlanetPosition();
            long px = clampLocal(Math.round(pos[0] * ORBIT_UNIT_BLOCKS));
            long py = clampLocal(Math.round(pos[1] * ORBIT_UNIT_BLOCKS));
            long pz = clampLocal(Math.round(pos[2] * ORBIT_UNIT_BLOCKS));
            GalacticCoord planetAddr = GalacticCoord.ofSectorLocal(
                    cell.sectorX(), cell.sectorY(), cell.sectorZ(), px, py, pz);
            bodies.add(new SystemBody(planetAddr, SystemBodyKind.PLANET, planet.getId(), starId));

            for (int moonId : planet.getChildPlanets()) {
                DimensionProperties moon = DimensionManager.getInstance().getDimensionProperties(moonId);
                if (moon == null) {
                    continue;
                }
                double[] moonPos = moon.getPlanetPosition();
                long mx = clampLocal(px + Math.round(moonPos[0] * MOON_UNIT_BLOCKS));
                long my = clampLocal(py + Math.round(moonPos[1] * MOON_UNIT_BLOCKS));
                long mz = clampLocal(pz + Math.round(moonPos[2] * MOON_UNIT_BLOCKS));
                GalacticCoord moonAddr = GalacticCoord.ofSectorLocal(
                        cell.sectorX(), cell.sectorY(), cell.sectorZ(), mx, my, mz);
                bodies.add(new SystemBody(moonAddr, SystemBodyKind.MOON, moon.getId(), starId));
            }
        }
        return bodies;
    }

    private static long clampLocal(long v) {
        if (v > MAX_LOCAL) {
            return MAX_LOCAL;
        }
        return v < -MAX_LOCAL ? -MAX_LOCAL : v;
    }
}
