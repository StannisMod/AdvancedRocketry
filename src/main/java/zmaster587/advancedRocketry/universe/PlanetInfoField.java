package zmaster587.advancedRocketry.universe;

import java.util.EnumSet;

/**
 * The logical fields of a planet's public info, each tagged with the minimum {@link InfoTier} at which it
 * becomes visible (graded discovery: telescope = global, approach = terrain/life-ish, orbit = full).
 *
 * <p>Purely a classification schema: it carries no per-planet data and reads nothing off a
 * {@code DimensionProperties} - the tier of a field is the same for every planet. A future scanner or info
 * GUI asks {@link #isVisible} or {@link #fieldsVisibleAt} to decide what it may reveal at a given range.
 * The mapping of these logical fields onto concrete params / packets is the consuming layer's concern.</p>
 */
public enum PlanetInfoField {
    // GLOBAL - obtainable from afar by telescope.
    COORDINATE(InfoTier.TELESCOPE),
    NAME(InfoTier.TELESCOPE),
    MASS(InfoTier.TELESCOPE),
    STELLAR_CLASS(InfoTier.TELESCOPE),
    RINGS(InfoTier.TELESCOPE),
    SKY_COLOR(InfoTier.TELESCOPE),
    TOPOLOGY(InfoTier.TELESCOPE),
    ATMOSPHERE_PRESENCE(InfoTier.TELESCOPE),
    ATMOSPHERE_DENSITY(InfoTier.TELESCOPE),
    TEMPERATURE(InfoTier.TELESCOPE),
    WATER_PRESENCE(InfoTier.TELESCOPE),

    // APPROACH - revealed on arrival.
    TERRAIN_TYPE(InfoTier.APPROACH),
    SEA(InfoTier.APPROACH),
    HYDROLOGY(InfoTier.APPROACH),
    LANDFORMS(InfoTier.APPROACH),
    DECORATORS(InfoTier.APPROACH),
    BIOMES(InfoTier.APPROACH),
    WEATHER(InfoTier.APPROACH),
    HABITABILITY(InfoTier.APPROACH),

    // ORBIT - full detail, only at orbit.
    RESOURCES(InfoTier.ORBIT),
    LIFE(InfoTier.ORBIT),
    ARTIFACTS(InfoTier.ORBIT),
    OPERATIONAL(InfoTier.ORBIT);

    private final InfoTier minTier;

    PlanetInfoField(InfoTier minTier) {
        this.minTier = minTier;
    }

    /** The lowest tier at which this field becomes visible. */
    public InfoTier minTier() {
        return minTier;
    }

    /** {@code true} iff a scanner at {@code tier} may reveal {@code field}. */
    public static boolean isVisible(PlanetInfoField field, InfoTier tier) {
        return tier.atLeast(field.minTier());
    }

    /** Every field visible to a scanner at {@code tier} - a superset as {@code tier} rises. */
    public static EnumSet<PlanetInfoField> fieldsVisibleAt(InfoTier tier) {
        EnumSet<PlanetInfoField> visible = EnumSet.noneOf(PlanetInfoField.class);
        for (PlanetInfoField field : values()) {
            if (isVisible(field, tier)) {
                visible.add(field);
            }
        }
        return visible;
    }
}
