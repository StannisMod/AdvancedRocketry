package zmaster587.advancedRocketry.universe;

import java.util.Map;
import java.util.function.IntFunction;

import net.minecraft.item.ItemStack;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.item.ItemMemoryCrystal;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Turns surveyed cells into addresses a ship can navigate by.
 *
 * <p>This is the discovery instrument, so it asks the registry what is THERE rather than what is
 * already known: an instrument that only reported what the player had already found could never find
 * anything.</p>
 *
 * <p>What a cell yields is its system's <b>bodies</b>, one address each, at the coarsest detail an
 * observation can carry. That grade is not a formality — it is what the navigation console reads to
 * decide which of a body's fields it may show, and at telescope grade that is already the whole
 * global set: name, mass, stellar class, rings, sky colour, topology, atmosphere and its density,
 * temperature, water. A cell whose system has no resolvable content still yields its bare
 * coordinate, so the address is learned even when nothing can yet be said about it.</p>
 */
public final class TelescopeScan {

    private TelescopeScan() {
    }

    /** How production names a body: by its dimension, the way every other GUI does. */
    public static IntFunction<String> dimensionNames() {
        return dimId -> {
            DimensionProperties props = zmaster587.advancedRocketry.dimension.DimensionManager
                    .getInstance().getDimensionProperties(dimId);
            return props == null || props.getName() == null ? "" : props.getName();
        };
    }

    /**
     * Resolve the next {@code count} cells of {@code scan} onto {@code crystal}.
     *
     * @return how many entries the crystal gained or refreshed
     */
    public static int resolveBatch(UniverseRegistry registry, RegionScan scan, int from, int count,
                                   ItemStack crystal, long observedTick, IntFunction<String> nameOf) {
        if (!ItemMemoryCrystal.isCrystal(crystal)) {
            return 0;
        }
        CrystalMemory memory = ItemMemoryCrystal.memoryOf(crystal);
        int written = resolveBatch(registry, scan, from, count, memory, observedTick, nameOf);
        if (written > 0) {
            ItemMemoryCrystal.writeMemory(crystal, memory);
        }
        return written;
    }

    /** The same, onto an already-opened memory. This is where the discovery actually happens. */
    public static int resolveBatch(UniverseRegistry registry, RegionScan scan, int from, int count,
                                   CrystalMemory memory, long observedTick, IntFunction<String> nameOf) {
        if (registry == null || scan == null || memory == null) {
            return 0;
        }
        int written = 0;
        for (int index = from; index < from + count && index < scan.totalCells(); index++) {
            written += resolveCell(registry, scan.cellAt(index), memory, observedTick, nameOf);
        }
        return written;
    }

    /**
     * Resolve ONE cell: every body of the system standing there, or the bare coordinate when the
     * system has no content the registry can name.
     */
    public static int resolveCell(UniverseRegistry registry, GalacticCoord cell, CrystalMemory memory,
                                  long observedTick, IntFunction<String> nameOf) {
        if (registry == null || cell == null || memory == null) {
            return 0;
        }
        Map<GalacticCoord, StarSystem> here = registry.systemsInRegion(cell, cell);
        if (here.isEmpty()) {
            return 0;
        }
        int written = 0;
        boolean namedSomething = false;
        for (SystemBody body : registry.systemBodiesAt(cell)) {
            namedSomething = true;
            if (memory.record(entryFor(body, observedTick, nameOf))) {
                written++;
            }
        }
        if (!namedSomething) {
            for (Map.Entry<GalacticCoord, StarSystem> system : here.entrySet()) {
                if (memory.record(entryForSystem(system.getKey(), system.getValue(), observedTick))) {
                    written++;
                }
            }
        }
        return written;
    }

    /** One body's address, at the coarsest grade, dated by when it was seen. */
    public static CrystalEntry entryFor(SystemBody body, long observedTick, IntFunction<String> nameOf) {
        String name = "";
        if (body.dimId() != Constants.INVALID_PLANET && nameOf != null) {
            name = nameOf.apply(body.dimId());
        }
        return new CrystalEntry(body.name(), name, body.kind(), InfoTier.TELESCOPE, observedTick,
                body.dimId());
    }

    /**
     * A system with nothing the registry can enumerate: the address alone, so a pilot can still aim
     * at the light and go look. It names no body, because none has been resolved.
     */
    public static CrystalEntry entryForSystem(GalacticCoord coord, StarSystem system, long observedTick) {
        String name = system != null && system.star() != null ? system.star().getName() : "";
        return new CrystalEntry(coord.cellCentre(), name, SystemBodyKind.STAR, InfoTier.TELESCOPE,
                observedTick);
    }
}
