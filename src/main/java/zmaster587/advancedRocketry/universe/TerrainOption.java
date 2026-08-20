package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.dimension.TerrainSource;

/**
 * One weighted entry of a {@link PlanetTypePreset}'s terrain list: a way this kind of world may be
 * generated, plus how often it is chosen relative to the type's other entries.
 *
 * <p>A third-party world generator is a FIRST-CLASS terrain source here, not a fallback. A foreign
 * generator brings authored terrain logic, so its variety does not degrade with repetition the way one
 * procedural pen does — which is why a preset declares a LIST rather than a single generator.</p>
 *
 * <p>The three shapes mirror {@link TerrainSource}: {@link TerrainSource#NATIVE} carries a
 * {@link #genType()} (Advanced Rocketry's own sub-flavour selector), {@link TerrainSource#MOD_WORLDTYPE}
 * a {@link #worldType()} name resolved against the live {@code WorldType} registry, and
 * {@link TerrainSource#TEMPLATE} a {@link #template()} folder name. {@link #options()} is the
 * per-dimension generator-settings string handed to whichever generator is drawn; empty means
 * "your defaults".</p>
 *
 * <p>Immutable and free of world state: a draw over these is part of a pure derivation.</p>
 */
public final class TerrainOption {

    private final TerrainSource source;
    private final String worldType;
    private final String template;
    private final int genType;
    private final String options;
    private final int weight;

    public TerrainOption(TerrainSource source, String worldType, String template, int genType,
                         String options, int weight) {
        this.source = source == null ? TerrainSource.NATIVE : source;
        this.worldType = worldType == null ? "" : worldType.trim();
        this.template = template == null ? "" : template.trim();
        this.genType = Math.max(0, genType);
        this.options = options == null ? "" : options;
        // A zero or negative weight would silently drop the entry from every draw while still LOOKING
        // authored; floor it at 1 so "present in the XML" and "reachable" mean the same thing.
        this.weight = Math.max(1, weight);
    }

    /** Advanced Rocketry's own generator, sub-flavour {@code genType}. */
    public static TerrainOption ofNative(int genType, int weight) {
        return new TerrainOption(TerrainSource.NATIVE, "", "", genType, "", weight);
    }

    /** A foreign {@code WorldType}, resolved by name, with an optional generator-settings string. */
    public static TerrainOption ofWorldType(String worldTypeName, String options, int weight) {
        return new TerrainOption(TerrainSource.MOD_WORLDTYPE, worldTypeName, "", 0, options, weight);
    }

    /** Pre-generated region files loaded verbatim from {@code config/advRocketry/templates/<name>/}. */
    public static TerrainOption ofTemplate(String templateName, int weight) {
        return new TerrainOption(TerrainSource.TEMPLATE, "", templateName, 0, "", weight);
    }

    public TerrainSource source() {
        return source;
    }

    public String worldType() {
        return worldType;
    }

    public String template() {
        return template;
    }

    public int genType() {
        return genType;
    }

    public String options() {
        return options;
    }

    public int weight() {
        return weight;
    }

    /**
     * Whether this entry names a generator supplied by another mod — the only kind that can be MISSING
     * from a given modset, and therefore the only kind the availability filter has anything to say
     * about.
     */
    public boolean needsForeignWorldType() {
        return source == TerrainSource.MOD_WORLDTYPE && !worldType.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TerrainOption)) {
            return false;
        }
        TerrainOption other = (TerrainOption) o;
        return source == other.source && genType == other.genType && weight == other.weight
                && worldType.equals(other.worldType) && template.equals(other.template)
                && options.equals(other.options);
    }

    @Override
    public int hashCode() {
        int result = source.hashCode();
        result = 31 * result + worldType.hashCode();
        result = 31 * result + template.hashCode();
        result = 31 * result + genType;
        result = 31 * result + options.hashCode();
        return 31 * result + weight;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TerrainOption[").append(source);
        if (!worldType.isEmpty()) {
            sb.append(' ').append(worldType);
        }
        if (!template.isEmpty()) {
            sb.append(' ').append(template);
        }
        if (source == TerrainSource.NATIVE) {
            sb.append(" genType=").append(genType);
        }
        return sb.append(" w=").append(weight).append(']').toString();
    }
}
