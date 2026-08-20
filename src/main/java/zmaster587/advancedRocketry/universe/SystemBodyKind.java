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
    GAS_GIANT,
    /**
     * A world with no star: a planet that was thrown out of the system it formed in, and now stands
     * alone as the PRIMARY of its own cell. Its warmth is what is left of its own formation, so
     * everything a star decides for an ordinary world — insolation, a year, a zone — it decides for
     * itself or not at all.
     *
     * <p>It is a kind of its own rather than a cold {@link #PLANET} around a cold {@link #STAR}, and
     * that is the whole point of it existing: the arithmetic of a tiny 30 K star does come out right,
     * and it would leave the model holding a {@code STAR} that is not a star. What a name is for is
     * being true.</p>
     *
     * <p>Appended last, like {@link #GAS_GIANT} before it: this ordinal travels on the render wire
     * ({@code PacketSystemBodiesSync}), so the existing kinds keep the numbers they already had.</p>
     */
    ROGUE_PLANET;

    /**
     * {@code true} for the body kinds that can back a walkable dimension (planets and moons).
     *
     * <p><b>A {@link #ROGUE_PLANET} is not among them yet, and that is a bound of the DIMENSION model
     * rather than of the world.</b> A realized dimension resolves its sky colour, its insolation, its
     * year and its temperature through a star it is required to have, in some thirty unguarded places;
     * a starless world is that model's own piece of work. Until it is done a rogue is a place a ship
     * flies to and looks at, and the descent trigger never fires on one rather than failing at it.</p>
     */
    public boolean canDescend() {
        return this == PLANET || this == MOON;
    }

    /** {@code true} for the kinds that are a WORLD — something with a surface, lit or not. */
    public boolean isWorld() {
        return this == PLANET || this == MOON || this == ROGUE_PLANET;
    }
}
