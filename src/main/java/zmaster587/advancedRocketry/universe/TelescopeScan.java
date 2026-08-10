package zmaster587.advancedRocketry.universe;

import java.util.Map;

import net.minecraft.item.ItemStack;

import zmaster587.advancedRocketry.item.ItemMemoryCrystal;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Turns a finished {@link RegionScan} into addresses a ship can navigate by.
 *
 * <p>This is the discovery instrument, so it deliberately asks the registry what is THERE rather than
 * what is already known: an instrument that only reported systems the player had already found could
 * never find anything.</p>
 *
 * <p>What a region scan resolves is a system's <b>address</b> — a point in the galaxy with a star's
 * name on it. It does not resolve the bodies inside that system; those need a closer look, and until
 * something takes it the entry stays at the coarsest detail an observation can carry.</p>
 */
public final class TelescopeScan {

    private TelescopeScan() {
    }

    /**
     * Record every system inside {@code scan}'s region onto {@code crystal}.
     *
     * @param observedTick the tick the observation completed — what dates the knowledge
     * @return how many entries the crystal actually gained or refreshed; 0 when it already knew
     *         everything the scan saw
     */
    public static int recordInto(UniverseRegistry registry, RegionScan scan,
                                 ItemStack crystal, long observedTick) {
        if (!ItemMemoryCrystal.isCrystal(crystal)) {
            return 0;
        }
        CrystalMemory memory = ItemMemoryCrystal.memoryOf(crystal);
        int written = recordInto(registry, scan, memory, observedTick);
        if (written > 0) {
            ItemMemoryCrystal.writeMemory(crystal, memory);
        }
        return written;
    }

    /**
     * The same, onto an already-opened memory. This is where the discovery actually happens; the
     * item-level method above is the crystal's wrapper around it.
     */
    public static int recordInto(UniverseRegistry registry, RegionScan scan,
                                 CrystalMemory memory, long observedTick) {
        if (registry == null || scan == null || memory == null) {
            return 0;
        }
        int written = 0;
        for (Map.Entry<GalacticCoord, StarSystem> found
                : registry.systemsInRegion(scan.min(), scan.max()).entrySet()) {
            if (memory.record(entryFor(found.getKey(), found.getValue(), observedTick))) {
                written++;
            }
        }
        return written;
    }

    /**
     * The address a telescope writes for one system: the cell it sits in, the star's name, and the
     * coarsest detail grade — a point of light resolved from very far away, dated by when it was seen.
     * It names no dimension, because a system is not somewhere you land.
     */
    public static CrystalEntry entryFor(GalacticCoord coord, StarSystem system, long observedTick) {
        String name = system != null && system.star() != null ? system.star().getName() : "";
        return new CrystalEntry(coord.cellCentre(), name, SystemBodyKind.STAR,
                InfoTier.TELESCOPE, observedTick);
    }
}
