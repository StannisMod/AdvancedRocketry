package zmaster587.advancedRocketry.universe;

/**
 * The kind of a {@link SystemBody} inside a star system (universe-model.md &sect;4). Only {@link #PLANET} and
 * {@link #MOON} are descend-into-a-dimension targets; the star, a {@link #GAS_GIANT} and the POIs
 * ({@link #ASTEROID_BELT}, {@link #STATION_SLOT}) are in-space objects you share the bubble with, never a
 * dimension you drop into.
 */
public enum SystemBodyKind {
    STAR,
    PLANET,
    MOON,
    ASTEROID_BELT,
    STATION_SLOT,
    /**
     * A body with a dimension but NO SURFACE — in this game, a gas giant. It is a real destination: it
     * owns a cell, it is worth flying to, and its dimension is what an orbital harvester works against.
     * What it is not is somewhere a ship can put down, which is the whole reason it is a kind of its own
     * rather than a {@link #PLANET}. Appended last on purpose: this ordinal travels on the render wire
     * ({@code PacketSystemBodiesSync}), so the existing kinds keep the numbers they already had.
     */
    GAS_GIANT;

    /** {@code true} for the body kinds that can back a walkable dimension (planets and moons). */
    public boolean canDescend() {
        return this == PLANET || this == MOON;
    }
}
