package zmaster587.advancedRocketry.universe;

/**
 * Schema version 0 ("0.1", the ALPHA) — the clustered galaxy field as first released: nested galaxy and star lattices,
 * cluster sub-lattices, seated nebulae, the unbound population out in the void, and the body
 * derivation those systems are filled with.
 *
 * <p>Deliberately thin. A schema version exists to be NAMED and found again, not to hold logic; the
 * behaviour lives in the classes it selects, and this class is the record that this particular set of
 * them was once shipped.
 *
 * <p><b>The zero is a promise about maturity, not a placeholder.</b> This model may be replaced outright
 * in a later release rather than extended, so a world generated under it is not guaranteed a future —
 * and the player is told exactly that when he loads one.
 */
public final class UniverseSchemaV0 implements UniverseSchema {

    public static final int VERSION = 0;

    /** The alpha, and its leading zero says so. */
    public static final String LABEL = "0.1";

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public IUniverseLaws laws() {
        return UniverseLawsV0.INSTANCE;
    }

    @Override
    public IGalaxyGenerator generator(GalaxyGenConfig config) {
        return (config == null)
                ? new EmptyGalaxyGenerator()
                : new ClusteredGalaxyGenerator(config, BodyDerivationV0.INSTANCE, laws());
    }
}
