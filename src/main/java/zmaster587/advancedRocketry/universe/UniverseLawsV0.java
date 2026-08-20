package zmaster587.advancedRocketry.universe;

/**
 * Schema version 0's metric and expansion — every number exactly as {@link UniverseScale} and
 * {@link Cosmology} state it.
 *
 * <p>A pure forwarder, for the same reason {@link BodyDerivationV0} is one: the arithmetic stays where
 * its constants are documented next to the observations they come from, and this class is only the
 * handle a schema holds it by. A version 2 is a second implementation, never an edit to those two
 * classes — editing them in place would change the universe under every world already made, which is
 * what {@code universeLawsFingerprint} exists to catch.
 *
 * <p>Stateless, so one instance serves every world.
 */
public final class UniverseLawsV0 implements IUniverseLaws {

    public static final UniverseLawsV0 INSTANCE = new UniverseLawsV0();

    private UniverseLawsV0() {
    }

    @Override
    public long cellsForLightYears(double lightYears) {
        return UniverseScale.cellsForLightYears(lightYears);
    }

    @Override
    public long cellsAt(double lightYears) {
        return UniverseScale.cellsAt(lightYears);
    }

    @Override
    public double lightYearsForCells(double cells) {
        return UniverseScale.lightYearsForCells(cells);
    }

    @Override
    public double lightYearsPerTick(double kilometresPerSecond) {
        return UniverseScale.lightYearsPerTick(kilometresPerSecond);
    }

    @Override
    public long cellsForOrbitUnits(double orbitUnits) {
        return UniverseScale.cellsForOrbitUnits(orbitUnits);
    }

    @Override
    public double orbitUnitsForCells(long cells) {
        return UniverseScale.orbitUnitsForCells(cells);
    }

    @Override
    public long seatMarginCells(long spacingCells) {
        return UniverseScale.seatMarginCells(spacingCells);
    }

    @Override
    public double retinueReachLy(double primaryRadiusLy) {
        return UniverseScale.retinueReachLy(primaryRadiusLy);
    }

    @Override
    public double scaleFactorAt(long tick) {
        return Cosmology.scaleFactorAt(tick);
    }

    @Override
    public long driftHorizonTicks() {
        return Cosmology.DRIFT_HORIZON_TICKS;
    }
}
