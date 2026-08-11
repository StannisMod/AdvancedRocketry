package zmaster587.advancedRocketry.universe;

/**
 * Everything a procedural body IS, derived from {@code (seed, cell)} alone — the object that crosses
 * the universe&rarr;dimension layer boundary.
 *
 * <p>The UNIVERSE layer produces one of these ({@link PlanetDerivation}); the DIMENSION layer consumes
 * it when a body is realized into a real world. Nothing here is a world, a block, a biome or a
 * dimension id: a profile can be computed for a body nobody has ever visited, which is precisely what
 * lets a telescope report a world's mass, atmosphere and temperature from across the system while the
 * player still has no suit for it.</p>
 *
 * <p><b>Determinism is the contract, not an optimisation.</b> The scan and the landing must describe
 * the same world, so realization MATERIALIZES this profile rather than rolling fresh values. Terrain is
 * the one field a scan does not promise from afar ({@code TERRAIN_TYPE} sits at the approach tier), and
 * it is still derived here so that everything about a body has exactly one origin.</p>
 *
 * <p>Immutable value object.</p>
 */
public final class BodyProfile {

    private final SystemBodyKind kind;
    private final String typeName;
    private final PlanetTypePreset preset;
    private final int orbitalDistance;
    private final double massEarths;
    private final double radiusEarths;
    private final int gravityPercent;
    private final int pressure;
    private final int temperatureKelvin;
    private final boolean hasOxygen;
    private final boolean tidallyLocked;
    private final boolean hasRings;
    private final double metallicity;
    private final TerrainOption terrain;

    public BodyProfile(SystemBodyKind kind, String typeName, PlanetTypePreset preset, int orbitalDistance,
                       double massEarths, double radiusEarths, int gravityPercent, int pressure,
                       int temperatureKelvin, boolean hasOxygen, boolean tidallyLocked, boolean hasRings,
                       double metallicity, TerrainOption terrain) {
        this.kind = kind;
        this.typeName = typeName;
        this.preset = preset;
        this.orbitalDistance = orbitalDistance;
        this.massEarths = massEarths;
        this.radiusEarths = radiusEarths;
        this.gravityPercent = gravityPercent;
        this.pressure = pressure;
        this.temperatureKelvin = temperatureKelvin;
        this.hasOxygen = hasOxygen;
        this.tidallyLocked = tidallyLocked;
        this.hasRings = hasRings;
        this.metallicity = metallicity;
        this.terrain = terrain;
    }

    /** What this body is as an addressable object — planet, giant, moon or belt. */
    public SystemBodyKind kind() {
        return kind;
    }

    /** The planet type's name, or {@link PlanetTypes#UNCLASSIFIED} when no preset admitted this world. */
    public String typeName() {
        return typeName;
    }

    /** The admitting preset, or {@code null} when none did. */
    public PlanetTypePreset preset() {
        return preset;
    }

    /** Orbital radius in Advanced Rocketry distance units (100 = 1 AU). */
    public int orbitalDistance() {
        return orbitalDistance;
    }

    /** Mass in Earth masses — PRIMARY, not derived from gravity. */
    public double massEarths() {
        return massEarths;
    }

    /** Radius in Earth radii — PRIMARY. */
    public double radiusEarths() {
        return radiusEarths;
    }

    /** Surface gravity in percent of Earth's, derived as {@code M/R²} and clamped to the game's range. */
    public int gravityPercent() {
        return gravityPercent;
    }

    /** Surface pressure in atmosphere-density units (100 = 1 atm). */
    public int pressure() {
        return pressure;
    }

    /** Surface temperature in Kelvin, computed WITH the derived atmosphere. */
    public int temperatureKelvin() {
        return temperatureKelvin;
    }

    /** Whether the atmosphere is breathable — an independent rare roll, never a consequence of the rest. */
    public boolean hasOxygen() {
        return hasOxygen;
    }

    /**
     * Whether this world keeps one face to its star: permanent day, permanent night, and a habitable
     * terminator strip between them as the only temperate ground.
     */
    public boolean tidallyLocked() {
        return tidallyLocked;
    }

    /**
     * Whether this body wears a ring system.
     *
     * <p>Rings are where the "something was torn apart" story actually lives: a moon that wandered
     * inside its planet's Roche limit came apart into a disc, and only a body massive enough for that
     * limit to reach beyond its own surface can hold the result. Every one of the Solar System's four
     * giants has rings, so on a giant this is COMMON rather than a rare flourish; on a rocky world it
     * effectively never happens.</p>
     */
    public boolean hasRings() {
        return hasRings;
    }

    /**
     * The parent star's metal content, relative to Sol. A metal-poor star formed a metal-poor disk, so
     * this scales the METAL fraction of whatever ore palette the world's climate earns it — it does not
     * change which kinds of deposit are possible.
     */
    public double metallicity() {
        return metallicity;
    }

    /** How this world's terrain is generated, drawn from its type's weighted list. */
    public TerrainOption terrain() {
        return terrain;
    }

    /** Whether this body can be stood on at all — the giants cannot. */
    public boolean hasSurface() {
        return kind != SystemBodyKind.GAS_GIANT && kind != SystemBodyKind.ASTEROID_BELT
                && kind != SystemBodyKind.STAR;
    }

    @Override
    public String toString() {
        return "BodyProfile[" + kind + " " + typeName + " d=" + orbitalDistance + " M=" + massEarths
                + " R=" + radiusEarths + " g=" + gravityPercent + "% p=" + pressure + " T="
                + temperatureKelvin + "K" + (hasOxygen ? " O2" : "") + (tidallyLocked ? " locked" : "")
                + (hasRings ? " rings" : "") + " Z=" + metallicity + " " + terrain + ']';
    }
}
