package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;

/**
 * An immutable query-time handle to a star system, returned by the {@link UniverseRegistry} and
 * {@link IGalaxyGenerator}. It is a thin wrapper over the existing {@link StellarBody} content object
 * (star + its planets + companion sub-stars) and deliberately carries <b>no coordinate</b>: a system is
 * LOCATION-AGNOSTIC and its galactic address is owned solely by the registry (universe-model.md &sect;2/&sect;10).
 *
 * <p>Identity is the system's int star-id (a whole multi-star system shares one id — sub-stars mirror the
 * primary). This is the stable return type downstream tasks (generation, content/POIs, discovery) build on;
 * they can grow richer accessors here without reshaping the registry's persistent index.</p>
 */
public final class StarSystem {

    private final StellarBody star;

    public StarSystem(StellarBody star) {
        if (star == null) {
            throw new NullPointerException("star");
        }
        this.star = star;
    }

    /** The reused content object: the primary star plus its planets and companion sub-stars. */
    public StellarBody star() {
        return star;
    }

    /** The system id (== the primary star's id). */
    public int starId() {
        return star.getId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StarSystem)) {
            return false;
        }
        return starId() == ((StarSystem) o).starId();
    }

    @Override
    public int hashCode() {
        return star.getId();
    }

    @Override
    public String toString() {
        return "StarSystem[id=" + star.getId() + ", name=" + star.getName() + "]";
    }
}
