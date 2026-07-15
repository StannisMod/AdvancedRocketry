package zmaster587.advancedRocketry.universe;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * The addon-replaceable galaxy-generation seam (universe-model.md &sect;3). Given the world seed and an
 * absolute {@link GalacticCoord}, it answers "is there a procedural system here, and what" plus a region
 * enumeration used as the telescope-scan backend.
 *
 * <p>Both methods MUST be a pure, deterministic function of {@code (seed, coord)} — the same arguments
 * always yield the same system — so a scan and a later jump agree, and a re-materialised cell regenerates
 * identically. The registry supplies the RAW world seed (it does not pre-mix per cell), leaving the mixing
 * policy to the generator so a clustered sampler can correlate neighbouring cells.</p>
 *
 * <p>This interface is defined here (the Layer-1 universe package) but only a trivial default —
 * {@link EmptyGalaxyGenerator} — ships with it. The real clustered sampler and its {@code <galaxyGen>} XML
 * parameters are a follow-up; an addon installs its own via {@link UniverseRegistry#setGenerator}.</p>
 */
public interface IGalaxyGenerator {

    /**
     * @param seed  the world seed (the generator owns any per-cell mixing)
     * @param coord an absolute galactic coordinate; implementations should treat it at cell granularity
     * @return the procedural system at {@code coord}'s cell, or empty for void space
     */
    Optional<StarSystem> systemAt(long seed, GalacticCoord coord);

    /**
     * Enumerate every procedural system whose cell falls within the inclusive sector box {@code [min, max]}.
     *
     * @return a map from each occupied cell-centre coordinate to its system (empty when the region is void)
     */
    Map<GalacticCoord, StarSystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max);

    /**
     * The procedural CONTENT of the system at {@code systemCoord}'s cell — its star plus planets/moons/POIs
     * as addressable {@link SystemBody} data (universe-model.md &sect;4). Must be deterministic in
     * {@code (seed, systemCoord)}. The default is empty (a system with no derivable content); a real
     * generator overrides it. Callers should only invoke this for a cell where {@link #systemAt} is present.
     */
    default List<SystemBody> bodiesFor(long seed, GalacticCoord systemCoord) {
        return Collections.emptyList();
    }
}
