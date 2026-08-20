package zmaster587.advancedRocketry.universe;

/**
 * The METRIC and the EXPANSION — what a cell is worth in light years, and how the whole thing grows.
 *
 * <p><b>One interface for both, because they are one thing.</b> The expansion rate is expressed per
 * tick, and a tick's worth of anything is a length, so {@code Cosmology}'s Hubble constant is derived
 * through the metric's own conversion. Versioning them apart would let a build pair one release's
 * metric with another's expansion, which is a universe neither of them describes.
 *
 * <p><b>Why this is an instance and not the static class it forwards to.</b> A released world model has
 * to keep being derivable the way it was released, and a save re-derives everything untouched on every
 * load. If the metric were global, a build that changed it would silently re-answer every existing
 * world: an address a player wrote down would denote a different distance, with the same generator
 * still running over it. Behind this seam the same build can hold a new metric for new worlds and the
 * old one for the worlds that were made under it — which is the whole point of versioning the schema
 * rather than the mod.
 *
 * <p><b>What is deliberately NOT here.</b> The lattice DEFAULTS ({@code DEFAULT_SPACING_CELLS},
 * {@code DEFAULT_GALAXY_SPACING_CELLS}) stay static: they only decide what a NEW world is given, and an
 * existing world carries the numbers it was made with in its own {@code GalaxyGenConfig}. So do the
 * drive-band constants, which price a machine rather than measure space — a rebalanced drive is a mod
 * feature, and mod features are exactly what an old world is supposed to keep receiving.
 *
 * <p>Implementations are pure and stateless: same arguments, same answer, for the life of the save.
 */
public interface IUniverseLaws {

    /** Cells spanned by {@code lightYears} — the chart metric, rounded down to whole cells. */
    long cellsForLightYears(double lightYears);

    /** The same conversion where a partial cell must not vanish (offsets rather than extents). */
    long cellsAt(double lightYears);

    /** What {@code cells} are worth in light years. */
    double lightYearsForCells(double cells);

    /** A speed quoted in km/s, in light years per tick. */
    double lightYearsPerTick(double kilometresPerSecond);

    /** Cells spanned by an orbital distance in Advanced Rocketry units. */
    long cellsForOrbitUnits(double orbitUnits);

    /** The inverse: what {@code cells} are worth in Advanced Rocketry orbital units. */
    double orbitUnitsForCells(long cells);

    /** The clear space a seat keeps inside a super-cell of {@code spacingCells}. */
    long seatMarginCells(long spacingCells);

    /** How far a primary's retinue reaches, given its radius in light years. */
    double retinueReachLy(double primaryRadiusLy);

    /** How much the universe has expanded by {@code tick}, as a factor on comoving distance. */
    double scaleFactorAt(long tick);

    /** The horizon a galaxy's peculiar drift is budgeted against, in ticks. */
    long driftHorizonTicks();
}
