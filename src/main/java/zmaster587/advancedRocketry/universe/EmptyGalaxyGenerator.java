package zmaster587.advancedRocketry.universe;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * The default {@link IGalaxyGenerator}: void space everywhere. Trivially deterministic — no seed or RNG is
 * consulted, so {@code (seed, coord)} always resolves to empty. This is the "empty between authored anchors"
 * behaviour the universe registry ships with; a follow-up replaces it with the clustered procedural sampler
 * via {@link UniverseRegistry#setGenerator}.
 */
public final class EmptyGalaxyGenerator implements IGalaxyGenerator {

    @Override
    public Optional<PlanetarySystem> systemAt(long seed, GalacticCoord coord) {
        return Optional.empty();
    }

    @Override
    public Map<GalacticCoord, PlanetarySystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max) {
        return Collections.emptyMap();
    }
}
