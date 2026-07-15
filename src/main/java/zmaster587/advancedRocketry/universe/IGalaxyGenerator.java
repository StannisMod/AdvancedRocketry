package zmaster587.advancedRocketry.universe;

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
}
