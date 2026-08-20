package zmaster587.advancedRocketry.universe;

import java.util.Optional;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;

/**
 * An immutable query-time handle to what stands at one anchor cell, returned by the
 * {@link UniverseRegistry} and {@link IGalaxyGenerator}. It carries deliberately <b>no coordinate</b>:
 * a system is LOCATION-AGNOSTIC and its galactic address is owned solely by the registry
 * (universe-model.md &sect;2/&sect;10).
 *
 * <h3>An anchor holds a PRIMARY BODY, and the primary need not be a star</h3>
 * <p>Most of them are: a star, its planets and its companion sub-stars, reusing the existing
 * {@link StellarBody} content object. Out in the intergalactic void the commonest thing there is to
 * meet is a world that was thrown out of the system it formed in, and it anchors a system of its own —
 * it may keep moons, it has an address, and a telescope finds it exactly as it finds a star.</p>
 *
 * <p>So {@link #primaryKind()} says WHAT is here and {@link #star()} is an {@link Optional}, which is
 * the point of the shape: a caller has to decide what it does about a system with no star instead of
 * receiving a {@code null} or — worse — a 30 K, zero-radius {@code StellarBody} whose arithmetic comes
 * out right while its name is a lie. The alternative shapes were both rejected for that reason: a
 * nullable star hides the decision, and a rogue path of its own would duplicate the whole
 * {@code coord → system → bodies} chain.</p>
 *
 * <p>Identity is the system's int id — the primary star's id where the primary IS a star, so a whole
 * multi-star system shares one id and sub-stars mirror the primary.</p>
 */
public final class PlanetarySystem {

    private final SystemBodyKind primaryKind;
    /** The primary, when it is a star. {@code null} for a system whose primary is not one. */
    private final StellarBody star;
    private final int id;
    private final String name;

    private PlanetarySystem(SystemBodyKind primaryKind, StellarBody star, int id, String name) {
        this.primaryKind = primaryKind;
        this.star = star;
        this.id = id;
        this.name = name == null ? "" : name;
    }

    /** The ordinary case: a system anchored on a star, with its planets and companions. */
    public static PlanetarySystem ofStar(StellarBody star) {
        if (star == null) {
            throw new NullPointerException("star");
        }
        return new PlanetarySystem(SystemBodyKind.STAR, star, star.getId(), star.getName());
    }

    /**
     * A system anchored on a starless world — a {@link SystemBodyKind#ROGUE_PLANET}.
     *
     * <p>It carries an id and a name and nothing else, because there is nothing else to carry: a
     * rogue's physics is derived from {@code (seed, cell)} by {@link PlanetDerivation} exactly as
     * every other procedural world's is, and it has no star whose temperature or size anything here
     * would have to remember.</p>
     */
    public static PlanetarySystem ofRogue(int id, String name) {
        return new PlanetarySystem(SystemBodyKind.ROGUE_PLANET, null, id, name);
    }

    /** What stands at this system's anchor — {@link SystemBodyKind#STAR} or a starless world. */
    public SystemBodyKind primaryKind() {
        return primaryKind;
    }

    /**
     * The reused content object — the primary star plus its planets and companion sub-stars — or empty
     * when this system's primary is not a star.
     */
    public Optional<StellarBody> star() {
        return Optional.ofNullable(star);
    }

    /** The system id (the primary star's id, where the primary is a star). */
    public int systemId() {
        return id;
    }

    /** What this system is called — the star's name, or the rogue's designation. */
    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlanetarySystem)) {
            return false;
        }
        return id == ((PlanetarySystem) o).id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return "PlanetarySystem[" + primaryKind + " id=" + id + ", name=" + name + "]";
    }
}
