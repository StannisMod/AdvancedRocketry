package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Schema version 0's body derivation — every law exactly as {@link PlanetDerivation} states it.
 *
 * <p>A pure forwarder, and deliberately so: the arithmetic stays in one place, where its constants are
 * documented next to the observations they come from, and this class is only the handle a schema holds
 * it by. A version 2 is a second implementation of {@link IBodyDerivation}, not an edit here.
 *
 * <p>Stateless, so one instance serves every world.
 */
public final class BodyDerivationV0 implements IBodyDerivation {

    public static final BodyDerivationV0 INSTANCE = new BodyDerivationV0();

    private BodyDerivationV0() {
    }

    @Override
    public double metallicityOf(long seed, GalacticCoord anchor) {
        return PlanetDerivation.metallicityOf(seed, anchor);
    }

    @Override
    public int referenceDistance(StellarBody star) {
        return PlanetDerivation.referenceDistance(star);
    }

    @Override
    public int orbitalDistanceOf(long seed, GalacticCoord anchor, int index, int count,
                                 StellarBody star) {
        return PlanetDerivation.orbitalDistanceOf(seed, anchor, index, count, star);
    }

    @Override
    public double innerOrbit(StellarBody star) {
        return PlanetDerivation.innerOrbit(star);
    }

    @Override
    public double outerOrbit(StellarBody star) {
        return PlanetDerivation.outerOrbit(star);
    }

    @Override
    public int bareTemperature(StellarBody star, int orbitalDistance) {
        return PlanetDerivation.bareTemperature(star, orbitalDistance);
    }

    @Override
    public boolean tidallyLockedAt(StellarBody star, int orbitalDistance) {
        return PlanetDerivation.tidallyLockedAt(star, orbitalDistance);
    }

    @Override
    public boolean isGiantAt(long seed, GalacticCoord anchor, int index, int bareTemperatureK) {
        return PlanetDerivation.isGiantAt(seed, anchor, index, bareTemperatureK);
    }

    @Override
    public BodyProfile derive(long seed, GalacticCoord anchor, GalacticCoord bodyCell, int variant,
                              StellarBody star, boolean moon, int orbitalDistance) {
        return PlanetDerivation.derive(seed, anchor, bodyCell, variant, star, moon, orbitalDistance);
    }

    @Override
    public BodyProfile deriveRogue(long seed, GalacticCoord bodyCell, int variant,
                                   double giantFraction) {
        return PlanetDerivation.deriveRogue(seed, bodyCell, variant, giantFraction);
    }

    @Override
    public int residualTemperature(double massEarths, double radiusEarths) {
        return PlanetDerivation.residualTemperature(massEarths, radiusEarths);
    }
}
