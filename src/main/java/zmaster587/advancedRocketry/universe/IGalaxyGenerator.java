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
    Optional<PlanetarySystem> systemAt(long seed, GalacticCoord coord);

    /**
     * Enumerate every procedural system whose cell falls within the inclusive sector box {@code [min, max]}.
     *
     * @return a map from each occupied cell-centre coordinate to its system (empty when the region is void)
     */
    Map<GalacticCoord, PlanetarySystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max);

    /**
     * The procedural CONTENT of the system at {@code systemCoord}'s cell — its star plus planets/moons/POIs
     * as addressable {@link SystemBody} data (universe-model.md &sect;4). Must be deterministic in
     * {@code (seed, systemCoord)}. The default is empty (a system with no derivable content); a real
     * generator overrides it. Implementations should accept ANY cell of the system's neighbourhood
     * (resolving the anchor via {@link #anchorAt}) and return the FULL body list.
     */
    default List<SystemBody> bodiesFor(long seed, GalacticCoord systemCoord) {
        return Collections.emptyList();
    }

    /**
     * The ANCHOR cell of the system whose neighbourhood contains {@code cell}, or empty for void space.
     * Under amendment A#1a a system spans many cells (star at the anchor, each planet in its own cell);
     * this is how a member cell is attributed back to its owning system. Must be deterministic in
     * {@code (seed, cell)}. The default treats a system as single-cell (pre-A#1a behaviour).
     */
    default Optional<GalacticCoord> anchorAt(long seed, GalacticCoord cell) {
        return systemAt(seed, cell).isPresent() ? Optional.of(cell.cellCentre()) : Optional.<GalacticCoord>empty();
    }

    /**
     * The nebulae seated within {@code radiusLy} light years of {@code cell} — what a sky asks, because
     * a cloud is meant to be seen from OUTSIDE it.
     *
     * <p>A DIRECTION-and-size query, never a placement one: a nebula has no cell name and is not a
     * body, so nothing here can be flown to. The default is empty, which is the correct answer for a
     * generator with no clusters rather than a stub — no clusters means no gas.</p>
     */
    default List<Nebula> nebulaeAround(long seed, GalacticCoord cell, double radiusLy) {
        return Collections.emptyList();
    }

    /**
     * How much diffuse matter lies between two cells, in density-light-years — the column an
     * observer at {@code from} looks THROUGH to see {@code to}.
     *
     * <p>The one query every looking-consequence of a cloud is written against, in both directions:
     * what a survey loses to a cloud in the way, and what a ship inside one loses looking out, are
     * this integral with the endpoints moved. Zero for a generator with no clouds, which is the
     * correct answer for clear space and not a stub.</p>
     */
    default double columnDensityBetween(long seed, GalacticCoord from, GalacticCoord to) {
        return 0d;
    }

    /**
     * The super-cell edge (in cells) this generator partitions space by — at most one system per
     * {@code minSpacingCells}-cube. The registry uses it to attribute member cells of AUTHORED systems and
     * to bound body-offset clamping ({@code radius <= minSpacingCells/2 - margin}).
     */
    /**
     * The DERIVED retinue an AUTHORED system asks for — {@code count} major bodies from
     * {@code (seed, anchor)}, avoiding {@code takenCells}.
     *
     * <p>Default: none. A generator with no procedural content has no retinue to lend, and an
     * authored system then holds exactly what its pack authored — which is the honest answer rather
     * than a stub, and is what the {@code EmptyGalaxyGenerator} means.</p>
     */
    default java.util.List<SystemBody> authoredRetinueFor(long seed,
            zmaster587.advancedRocketry.space.GalacticCoord anchor,
            zmaster587.advancedRocketry.api.dimension.solar.StellarBody star, int starId, int count,
            java.util.Set<String> takenCells) {
        return java.util.Collections.emptyList();
    }

    default int minSpacingCells() {
        return GalaxyGenConfig.DEFAULT_MIN_SPACING;
    }

    /**
     * The tunables this generator was built from, when it has any — what a {@code <galaxyGen>} element
     * would have to say to reproduce it, and what the save fingerprints so a later load can tell that
     * the pack has been retuned underneath it.
     *
     * <p>Empty is a real answer and not a stub: a generator with no parameters (the authored-anchors-only
     * default, or one an addon fabricates from something other than this config) has nothing to write
     * back, and a pack file that carried a {@code <galaxyGen>} section for it would describe a generator
     * nobody installed.
     */
    default Optional<GalaxyGenConfig> tuning() {
        return Optional.empty();
    }

    /**
     * How this generator's bodies are derived — the half of a world model that says WHAT a body is,
     * where {@link #systemAt} says where it is.
     *
     * <p>It hangs here rather than on the schema because the generator is what a schema selects, so a
     * version picks a derivation by picking a generator, and anything outside the universe layer that
     * needs a body's physics asks the generator that produced the body. The default is version 1's,
     * which is the right answer for a generator that does not derive anything of its own.
     */
    default IBodyDerivation derivation() {
        return BodyDerivationV0.INSTANCE;
    }

    /**
     * The metric and expansion this generator measures with — how many cells a light year is, and how
     * the whole thing grows.
     *
     * <p>Beside {@link #derivation()} and for the same reason: a schema selects the laws by selecting a
     * generator, and anything outside this package that must convert a length in THIS world's terms
     * asks the world's generator rather than a global. The default is version 1's.
     */
    default IUniverseLaws laws() {
        return UniverseLawsV0.INSTANCE;
    }

    /**
     * The cell an authored anchor declared against {@code key} is measured FROM, or empty when this
     * generator has no galaxies.
     *
     * <p>What an authored {@link GalacticAnchor} is resolved against. The default is empty, and that
     * is the right answer rather than a stub: a generator with no galaxy tier has nothing for a
     * declaration to be LOCAL to, so a declared position is already absolute — which is exactly the
     * behaviour an authored-only universe had before galaxies existed.</p>
     */
    default Optional<GalacticCoord> declarationOriginOf(long seed, GalaxyKey key) {
        return Optional.empty();
    }

    /**
     * How far from its DECLARATION ORIGIN authored content is guaranteed to stay inside its galaxy,
     * in light years, or {@code 0} when this generator has no galaxies (and therefore no wall).
     */
    default double guaranteedAuthoredReachLy() {
        return 0d;
    }
}
