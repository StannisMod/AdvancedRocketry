package zmaster587.advancedRocketry.universe;

/**
 * The kind of a {@link SystemBody} inside a star system (universe-model.md &sect;4). Only {@link #PLANET} and
 * {@link #MOON} are descend-into-a-dimension targets; the star and the POIs ({@link #ASTEROID_BELT},
 * {@link #STATION_SLOT}) are in-space objects you share the bubble with, never a dimension you drop into.
 */
public enum SystemBodyKind {
    STAR,
    PLANET,
    MOON,
    ASTEROID_BELT,
    STATION_SLOT;

    /** {@code true} for the body kinds that can back a walkable dimension (planets and moons). */
    public boolean canDescend() {
        return this == PLANET || this == MOON;
    }
}
