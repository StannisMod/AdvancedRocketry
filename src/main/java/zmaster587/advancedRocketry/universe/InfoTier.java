package zmaster587.advancedRocketry.universe;

/**
 * How close a ship must be to reveal a planet's info, coarsest first. Declaration order is ascending
 * reveal, so a higher tier reveals a superset of the lower ones (see {@link PlanetInfoField}). This is the
 * universe-layer classification schema for graded discovery; the scan tech that realises it (telescope,
 * on-arrival scan) is a separate gameplay layer.
 */
public enum InfoTier {
    /** Reveal-from-afar: coordinate, name, atmosphere + water presence, coarse orbital / stellar params. */
    TELESCOPE,
    /** Revealed on approach: terrain, biomes, weather, hydrology, habitability. */
    APPROACH,
    /** Full detail, only at orbit: resources, life, artifacts, operational state. */
    ORBIT;

    /** {@code true} iff this tier reveals at least as much as {@code other} (i.e. this &gt;= other). */
    public boolean atLeast(InfoTier other) {
        return this.ordinal() >= other.ordinal();
    }
}
