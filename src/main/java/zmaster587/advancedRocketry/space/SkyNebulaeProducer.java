package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import zmaster587.advancedRocketry.network.PacketSystemBodiesSync.RenderNebula;
import zmaster587.advancedRocketry.universe.IGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Nebula;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.advancedRocketry.universe.UniverseScale;

/**
 * Server-side producer for the nebula half of the cell sky: turns the clouds seated around a cell into
 * the DIRECTIONS and apparent SIZES a client draws.
 *
 * <p>A cloud is the only landmark the universe layer has. A star cluster is invisible from outside it —
 * it can be identified only by counting stars, which no player will ever do — and a cloud is the thing
 * that makes a region recognisable at a glance, from a long way off. That is what this feed is for.</p>
 *
 * <h3>A direction, never a position</h3>
 * <p>A cloud is light years across and hundreds of light years away, so it does not move on the sky when
 * a ship crosses a cell: a cell is about 4·10⁻⁴ light years. Its bearing is therefore computed from the
 * CELL and not from a ship inside it, unlike the bodies beside it, and nothing here is a place that can
 * be flown to — a nebula has no cell name by design (attribution reads names, not matter).</p>
 *
 * <h3>What is dropped, and it is not silent</h3>
 * <p>Two bounds, both LOD and both stated: a cloud smaller than {@link #MIN_ANGULAR_RADIUS} on the sky
 * is a smudge and is left out, and at most {@link #MAX_PER_CELL} are sent, largest first. The cap
 * drops the SMALLEST, so what is lost is always what would have been least visible — but a caller that
 * needs to know how much was dropped can compare against {@link #countAround}.</p>
 */
public final class SkyNebulaeProducer {

    /**
     * How far out clouds are gathered, in light years. A cluster lattice cell is 300 ly, so this is a
     * few cluster cells each way; a cloud tens of light years across still subtends more than a degree
     * at this range, and past it the angular filter below would drop it anyway.
     */
    public static final double SKY_REACH_LY = 1_000d;

    /**
     * The smallest a cloud may look and still be worth drawing, in radians (~0.6°, a little wider than
     * the Moon from Earth). Below it a nebula is a few pixels of haze that cannot be a landmark.
     */
    public static final double MIN_ANGULAR_RADIUS = 0.01d;

    /** How many clouds one cell's sky may carry. Largest first; the sky is a backdrop, not a catalogue. */
    public static final int MAX_PER_CELL = 12;

    /**
     * What each cell's sky showed last time it was asked, keyed {@code seed|cellKey}.
     *
     * <p>Derived data and never a dependency: every entry can be recomputed from {@code (seed, cell)}
     * alone, and {@link #reset()} restores the empty map rather than nulling anything. It exists
     * because the answer is CONSTANT — a cloud is hundreds of light years away and a cell is 4·10⁻⁴ of
     * one across, so re-deriving it once a second per loaded cell would burn a few thousand hashes and
     * a heap of short-lived clusters to arrive at the same list.</p>
     */
    private static final Map<String, List<RenderNebula>> CACHE = new LinkedHashMap<>();

    /** How many cells the cache keeps. Oldest out first; a pool of live cells is far smaller than this. */
    private static final int CACHE_LIMIT = 64;

    private SkyNebulaeProducer() {
    }

    /** Drop the per-cell cache (server stop, or a generator/seed change under a test). */
    public static void reset() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /**
     * The clouds visible from {@code cell}, as render records, largest first.
     *
     * @param generator the seam the clouds come from; a generator with no clusters answers empty
     * @param seed      the world seed the generator is deterministic in
     */
    public static List<RenderNebula> around(IGalaxyGenerator generator, long seed,
                                            GalacticCoord cell) {
        if (generator == null || cell == null) {
            return Collections.emptyList();
        }
        List<Nebula> found = generator.nebulaeAround(seed, cell, SKY_REACH_LY);
        if (found == null || found.isEmpty()) {
            return Collections.emptyList();
        }
        GalacticCoord c = cell.cellCentre();
        // Measured by the generator that produced these clouds, not by a global: a sky drawn under
        // one schema's metric and clouds seated under another's would not line up.
        zmaster587.advancedRocketry.universe.IUniverseLaws laws = generator.laws();
        double observerX = laws.lightYearsForCells(c.sectorX());
        double observerY = laws.lightYearsForCells(c.sectorY());
        double observerZ = laws.lightYearsForCells(c.sectorZ());

        List<RenderNebula> out = new ArrayList<>();
        for (Nebula nebula : found) {
            RenderNebula drawn = renderOf(nebula, observerX, observerY, observerZ);
            if (drawn != null) {
                out.add(drawn);
            }
        }
        // Largest first, so the cap below can only ever drop the least visible.
        Collections.sort(out, new Comparator<RenderNebula>() {
            @Override
            public int compare(RenderNebula a, RenderNebula b) {
                return Float.compare(b.angularRadius, a.angularRadius);
            }
        });
        return out.size() <= MAX_PER_CELL ? out : new ArrayList<>(out.subList(0, MAX_PER_CELL));
    }

    /** How many clouds are seated in reach of {@code cell} before any LOD filter — what was dropped. */
    public static int countAround(IGalaxyGenerator generator, long seed, GalacticCoord cell) {
        if (generator == null || cell == null) {
            return 0;
        }
        List<Nebula> found = generator.nebulaeAround(seed, cell, SKY_REACH_LY);
        return found == null ? 0 : found.size();
    }

    /**
     * One cloud as seen from an observer, or {@code null} when it is too small on the sky to draw.
     *
     * <p>The half-angle is {@code asin(radius / distance)}, so a cloud OPENS as a ship closes on it, and
     * a viewer inside one gets a right angle — the cloud is all around him, which is the honest limit
     * rather than an overflow. The direction is then arbitrary and the sky is filled either way, so the
     * degenerate zero-distance case keeps a fixed axis instead of a NaN.</p>
     */
    public static RenderNebula renderOf(Nebula nebula, double observerXLy, double observerYLy,
                                        double observerZLy) {
        if (nebula == null) {
            return null;
        }
        double dx = nebula.centreXLy() - observerXLy;
        double dy = nebula.centreYLy() - observerYLy;
        double dz = nebula.centreZLy() - observerZLy;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double angularRadius;
        double nx;
        double ny;
        double nz;
        if (distance <= nebula.radiusLy()) {
            // Inside it: the cloud fills the sky, and which way its centre lies stops mattering.
            angularRadius = Math.PI / 2d;
            double length = distance < 1.0E-9d ? 0d : distance;
            nx = length == 0d ? 0d : dx / length;
            ny = length == 0d ? 1d : dy / length;
            nz = length == 0d ? 0d : dz / length;
        } else {
            angularRadius = Math.asin(nebula.radiusLy() / distance);
            if (angularRadius < MIN_ANGULAR_RADIUS) {
                return null;
            }
            nx = dx / distance;
            ny = dy / distance;
            nz = dz / distance;
        }
        return new RenderNebula((float) nx, (float) ny, (float) nz, (float) angularRadius,
                nebula.appearance().ordinal(), (float) nebula.peakDensity());
    }

    /**
     * The clouds of every materialized cell, keyed by the slot dim that cell is bound to — the same
     * keying the bodies beside them use, and read from the same bindings.
     *
     * <p>A live cell with no cloud gets a present-and-EMPTY entry, exactly as the bodies feed does:
     * "present and empty" is what clears a stale sky, where "absent" would leave one standing.</p>
     */
    public static Map<Integer, List<RenderNebula>> buildByDim(Map<String, Integer> loadedCells,
                                                              IGalaxyGenerator generator, long seed) {
        Map<Integer, List<RenderNebula>> byDim = new LinkedHashMap<>();
        if (loadedCells == null) {
            return byDim;
        }
        for (Map.Entry<String, Integer> bound : loadedCells.entrySet()) {
            Integer slotDim = bound.getValue();
            GalacticCoord cell = GalacticCoord.fromCellKey(bound.getKey());
            if (slotDim == null || slotDim == SpaceManager.UNBOUND_SLOT || cell == null) {
                continue;
            }
            byDim.put(slotDim, cached(generator, seed, cell));
        }
        return byDim;
    }

    /** {@link #around} through the per-cell cache. */
    private static List<RenderNebula> cached(IGalaxyGenerator generator, long seed,
                                             GalacticCoord cell) {
        String key = seed + "|" + cell.cellCentre().cellKey();
        synchronized (CACHE) {
            List<RenderNebula> hit = CACHE.get(key);
            if (hit != null) {
                return hit;
            }
        }
        List<RenderNebula> computed = around(generator, seed, cell);
        synchronized (CACHE) {
            if (CACHE.size() >= CACHE_LIMIT) {
                java.util.Iterator<String> oldest = CACHE.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            CACHE.put(key, computed);
        }
        return computed;
    }

    /** The live per-slot-dim clouds from the production bindings + the installed generator. */
    public static Map<Integer, List<RenderNebula>> currentByDim(net.minecraft.server.MinecraftServer server) {
        UniverseRegistry reg = UniverseRegistry.get(server);
        SpaceManager space = SpaceSubsystem.space();
        if (reg == null || space == null) {
            return new LinkedHashMap<>();
        }
        return buildByDim(space.loadedCells(), UniverseRegistry.getGenerator(), reg.worldSeed());
    }
}
