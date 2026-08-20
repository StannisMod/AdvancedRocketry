package zmaster587.advancedRocketry.universe;

/**
 * One released version of the WORLD MODEL — the thing a save is generated under and must keep being
 * read under for the life of that save.
 *
 * <p><b>What is inside the version number.</b> The schema is not the generator alone; it is
 * {@link IGalaxyGenerator} + {@link PlanetDerivation} + {@link UniverseScale} + {@link Cosmology}
 * together. Everything a telescope PROMISES versions as one unit: an address is worth nothing if the
 * derivation that gives that address a radius, a temperature and an orbit has moved underneath it, and
 * a light year is worth nothing if the metric that converts it to cells has.
 *
 * <p><b>All four are versioned the same way: by implementation.</b> A version hands out a generator,
 * and the generator carries the other two — {@link IGalaxyGenerator#derivation()} and
 * {@link IGalaxyGenerator#laws()} — so selecting a version selects all of it. Nothing about a released
 * world model reads a global, which is what lets one build hold a new model for new worlds and the old
 * one for the worlds already made under it. That is the whole purpose: <b>the mod moves on, and a world
 * does not have to.</b>
 *
 * <p><b>What stays global, and why that is not a hole.</b> The lattice DEFAULTS
 * ({@code DEFAULT_SPACING_CELLS} and friends) decide only what a NEW world is given — an existing one
 * carries its own numbers in its {@code GalaxyGenConfig}. The drive-band constants price a machine
 * rather than measure space, and a rebalanced drive is a mod feature, which is exactly what an old
 * world is supposed to keep receiving.
 *
 * <p><b>The stamp that remains is a tripwire, not a barrier.</b>
 * {@code UniverseRegistry.lawsFingerprintOf} measures a version's laws — fixed inputs through every
 * conversion — and the save records what its own version measured when the world was made. A mismatch
 * therefore no longer means "the mod moved on"; it means a RELEASED version was edited in place, which
 * is a developer error nobody downstream can accept away.
 *
 * <p>All of it is backed by one mechanical check: the golden corpus renders what the whole chain
 * produces — placement, derivation, metric and expansion — and compares it byte for byte, so a change
 * in any of the four turns a test red and forces the version decision rather than shipping as a
 * surprise.
 *
 * <p><b>How a new version is written.</b> As a DECORATOR over the one before it, delegating everything
 * it does not deliberately change:
 *
 * <pre>
 * final class UniverseSchemaV2 implements UniverseSchema {
 *     private final UniverseSchema previous = new UniverseSchemaV0();
 *     public int version() { return 2; }
 *     public IGalaxyGenerator generator(GalaxyGenConfig config) { ...the one thing that changed... }
 * }
 * </pre>
 *
 * <p>Delegation rather than a fresh implementation is what makes the invariants inherited instead of
 * re-typed: a v2 that only re-prices rogues has one method of its own, and every other guarantee is
 * still v1's code rather than a copy of it that will drift.
 *
 * <p><b>Implementations are immutable and hold no world state.</b> A schema may be instantiated many
 * times, and two instances of the same version must be indistinguishable.
 */
public interface UniverseSchema {

    /**
     * The released version number. Stamped into the save and used to find this schema again when that
     * save is opened by a later build. Never reused, never renumbered — it is an identifier, like an
     * NBT key.
     */
    int version();

    /**
     * The human name of this version — {@code MAJOR.MINOR}, and the MAJOR is a promise.
     *
     * <p><b>A leading zero means ALPHA: the model may be replaced outright rather than extended, and
     * nothing about it is guaranteed to survive to the next release.</b> That is not a disclaimer, it is
     * the whole meaning of the digit, and players are told so on the world where it applies.
     *
     * <p>It is a separate thing from {@link #version()} because the two answer different questions. The
     * number is the world's IDENTITY: it is stamped into saves, keys the registry, and may never be
     * reused or reordered. The label is a STATEMENT ABOUT MATURITY, and several successive alphas can
     * be shipped — {@code "0.1"}, {@code "0.2"} — each with its own identity, none of them stable.
     */
    String label();

    /**
     * Whether this version is a stable release. False for anything whose {@link #label()} begins with
     * {@code 0.} — an alpha, which a player is warned about and which may be replaced rather than
     * carried forward.
     */
    default boolean isStable() {
        return !label().startsWith("0.");
    }

    /**
     * The generator this schema produces for {@code config}, or the empty generator when the pack
     * declares no {@code <galaxyGen>} (an authored-anchors-only universe, which is a legitimate world
     * rather than a missing configuration).
     */
    IGalaxyGenerator generator(GalaxyGenConfig config);

    /**
     * The metric and expansion this version measures with. One source: the generator this schema builds
     * is handed this very instance, so the two can never describe different universes.
     */
    IUniverseLaws laws();
}
