package zmaster587.advancedRocketry.api.dimension.solar;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.util.Constants.NBT;
import zmaster587.advancedRocketry.api.dimension.IDimensionProperties;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.util.SpacePosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class StellarBody {

    /** Sentinel for {@link #mass}: nobody has stated one, so it follows from the radius. */
    public static final float MASS_UNSET = 0f;
    /** {@code M ≈ R^1.25} — the inverse of the main-sequence {@code R ≈ M^0.8}. Exact for Sol. */
    private static final double MAIN_SEQUENCE_MASS_EXPONENT = 1.25d;

    /**
     * How far a companion orbits its primary when nothing has said — in the same distance units a
     * planet's orbit is in (100 = 1 AU), so this is 0.05 AU: a close pair, the kind that reads as two
     * suns in one sky rather than as a second star elsewhere in the system.
     *
     * <p>The field this replaces was an ANGLE with the same default of 5, applied to the sky as a
     * tilt. An angle cannot say where a companion is — only how far off the primary it looks from one
     * particular world — so nothing could place it, light a planet by it, or let it move.</p>
     */
    public static final int DEFAULT_COMPANION_ORBIT = 5;

    /**
     * Solar-map units per AU — the multiplier {@code DimensionProperties.getSpacePosition} lays a
     * planet out with (100 map units per 100 distance units, i.e. per AU). Stated here so a star and
     * a planet at the same orbital distance land at the same place on one map.
     */
    private static final double PLANET_MAP_UNITS_PER_AU = 100d;

    /** Sentinel for {@link #baseTheta}: nobody has stated one, so binding picks a phase. */
    private static final double THETA_UNSTATED = Double.NaN;
    /** The golden angle, in radians — how unstated companion phases are spread. */
    private static final double GOLDEN_ANGLE = 2.399963229728653d;

    public List<StellarBody> subStars;
    int numPlanets;
    int discoveredPlanets;
    float[] color;
    int id;
    float size;
    private float mass = MASS_UNSET;
    String name;
    short posX, posZ;
    /** This star's orbit about its primary, in distance units (100 = 1 AU). Zero for a primary. */
    private int orbitalDistance;
    /** Its angle on that orbit at tick zero, in radians; {@link #THETA_UNSTATED} until bound. */
    private double baseTheta = THETA_UNSTATED;
    StellarBody parentStar;
    private int temperature;
    private HashMap<Integer, IDimensionProperties> planets;
    private boolean isBlackHole;
    public float diskAngle;

    public StellarBody() {
        planets = new HashMap<>();
        size = 1f;
        subStars = new LinkedList<>();
        orbitalDistance = DEFAULT_COMPANION_ORBIT;
        isBlackHole = false;
        diskAngle = 70;
    }

    public List<StellarBody> getSubStars() {
        return subStars;
    }

    /**
     * Bind {@code star} as a companion of this one.
     *
     * <p><b>The companion keeps its own identity.</b> This used to overwrite the companion's id with
     * the primary's, which made a companion unaddressable: a planet binds to its star by a flat
     * {@code starId}, so with both stars answering the same number there was no value that could mean
     * "I orbit the companion" — no companion could own a world, and neither a wide binary nor a
     * three-star hierarchy was expressible however well the storage nested. Minting the id is the star
     * registry's job, because the id space is the registry's; this method only states the
     * relationship.</p>
     */
    public void addSubStar(StellarBody star) {
        if (star.name == null)
            star.setName(name + "-" + (subStars.size() + 1));
        if (Double.isNaN(star.baseTheta))
            star.baseTheta = subStars.size() * GOLDEN_ANGLE;
        subStars.add(star);
        star.parentStar = this;
    }

    /** This star's primary, or {@code null} when it is the one its system is named for. */
    public StellarBody getParentStar() {
        return parentStar;
    }

    public boolean isBlackHole() {
        return isBlackHole;
    }

    public void setBlackHole(boolean isBlackHole) {
        this.isBlackHole = isBlackHole;
    }

    public int getDisplayRadius() {
        return (int) (100 * size);
    }

    /**
     * How far this star orbits its primary, in distance units (100 = 1 AU) — the same field a planet
     * carries, meaning the same thing. Zero, and meaningless, for a star that is nobody's companion.
     */
    public int getOrbitalDistance() {
        return orbitalDistance;
    }

    public void setOrbitalDistance(int distanceUnits) {
        this.orbitalDistance = Math.max(0, distanceUnits);
    }

    /** This star's angle on its orbit at tick zero, in radians. */
    public double getBaseTheta() {
        return Double.isNaN(baseTheta) ? 0d : baseTheta;
    }

    public void setBaseTheta(double radians) {
        this.baseTheta = radians;
    }

    /**
     * This star's offset from the one its SYSTEM is named for, in AU, as a two-element
     * {@code (x, z)} pair at tick zero — the barycentric geometry a companion needs to be placed,
     * lit by, or measured against.
     *
     * <p>Zero for a primary, and composed up the chain for a companion of a companion, so a
     * three-star hierarchy is the same arithmetic as a pair rather than a special case.</p>
     */
    public double[] offsetFromSystemAu() {
        if (parentStar == null) {
            return new double[] {0d, 0d};
        }
        double[] parent = parentStar.offsetFromSystemAu();
        double a = orbitalDistance / 100d; // 100 distance units to the AU
        double theta = getBaseTheta();
        return new double[] {parent[0] + a * Math.cos(theta), parent[1] + a * Math.sin(theta)};
    }

    /** The distance between two stars of one system, in AU, at tick zero. */
    public double separationAuFrom(StellarBody other) {
        if (other == null) {
            return 0d;
        }
        double[] a = offsetFromSystemAu();
        double[] b = other.offsetFromSystemAu();
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /**
     * How far apart this star and its primary look, in DEGREES, seen from a world orbiting the
     * primary at {@code observerOrbitalDistance}.
     *
     * <p>A real angle from a real distance, so a close pair reads as two suns almost together and a
     * wide one puts its companion somewhere else in the sky entirely — which is the difference the
     * old constant tilt could not express.</p>
     */
    public float apparentSeparationDegrees(int observerOrbitalDistance) {
        if (parentStar == null || orbitalDistance <= 0 || observerOrbitalDistance <= 0) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(orbitalDistance, observerOrbitalDistance));
    }

    public float getSize() {
        return size;
    }

    public void setSize(float size) {
        this.size = size;
    }

    /**
     * This star's mass in SOLAR MASSES — what an orbital law about it needs.
     *
     * <p>Where nothing has stated one it is derived from the radius through the main-sequence relation
     * {@code R ≈ M^0.8}, i.e. {@code M ≈ R^1.25}, which is exact for Sol and the honest reading of a
     * star described only by its size. Mass and radius are NOT interchangeable anywhere else: Kepler's
     * third law is {@code P ∝ a^1.5 / sqrt(M)}, and feeding it a radius made a 2 R☉ star's planets
     * orbit 1.83× too fast and a 0.3 R☉ red dwarf's 2.87× too slowly.</p>
     */
    public float getMass() {
        if (mass > MASS_UNSET) {
            return mass;
        }
        return (float) Math.pow(Math.max(0.01f, size), MAIN_SEQUENCE_MASS_EXPONENT);
    }

    /** State this star's mass in solar masses; {@link #MASS_UNSET} hands it back to the radius. */
    public void setMass(float solarMasses) {
        this.mass = Math.max(MASS_UNSET, solarMasses);
    }

    public int getPosX() {
        return posX;
    }

    public void setPosX(int x) {
        posX = (short) x;
    }

    public int getPosZ() {
        return posZ;
    }

    public void setPosZ(int x) {
        posZ = (short) x;
    }

    /**
     * @param planet registers this planet to be in orbit around this star
     */
    public void addPlanet(IDimensionProperties planet) {
        if (!planets.containsKey(planet.getId()))
            numPlanets++;
        planets.put(planet.getId(), planet);
    }

    /**
     * @param planet
     * @return the {@link DimensionProperties} of the planet orbiting this star, or null if the planet does not exist
     */
    public IDimensionProperties removePlanet(IDimensionProperties planet) {
        numPlanets--;
        return planets.remove(planet.getId());
    }

    /**
     * @return the number of planets orbiting THIS star
     *
     * <p>A companion answers for its own worlds, not for its primary's. It used to delegate upward
     * while {@link #addPlanet} filled the companion's own map, so a companion with planets reported
     * its primary's count and a companion with none reported a number that was not zero — the same
     * object disagreeing with itself about what it holds.</p>
     */
    public int getNumPlanets() {
        return numPlanets;
    }

    /**
     * @return returns the unique id of this star
     */
    public int getId() {
        return id;
    }

    /**
     * @param id the new id of this star
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * @return the temperature, in kelvin, of the star
     */
    public int getTemperature() {
        return temperature;
    }

    /**
     * @param temp the temperature, in Kelvin, of this star
     */
    public void setTemperature(int temp) {
        temperature = temp;
        color = getColor();
    }

    /**
     * @return the RGB color of this star represented as an int
     */
    public int getColorRGB8() {
        if (color == null) {
            color = getColor();
        }

        return (int) (color[0] * 0xFF) | ((int) (color[1] * 0xFF) << 8) | ((int) (color[2] * 0xFF) << 16);
    }

    //Thank you to http://www.tannerhelland.com/4435/convert-temperature-rgb-algorithm-code/

    /**
     * @return the color of the star as an array of floats with length 3
     */
    public float[] getColor() {


        //Define
        float[] color = new float[3];
        float temperature = ((getTemperature() * .477f) + 10f); //0 -> 10 100 -> 57.7

        //Find red
        if (temperature < 66)
            color[0] = 1f;
        else {
            color[0] = temperature - 60;
            color[0] = 329.69f * (float) Math.pow(color[0], -0.1332f);

            color[0] = MathHelper.clamp(color[0] / 255f, 0f, 1f);
        }

        //Calc Green
        if (temperature < 66) {
            color[1] = temperature;
            color[1] = (float) (99.47f * Math.log(color[1]) - 161.1f);
            color[1] = MathHelper.clamp(color[1]/255, 0f, 1f);
        } else {
            color[1] = temperature - 60;
            color[1] = 288f * (float) Math.pow(color[1], -0.07551);
            color[1] = MathHelper.clamp(color[1] / 255f, 0f, 1f);
        }


        //Calculate Blue
        if (temperature > 67)
            color[2] = 1f;
        else if (temperature <= 19) {
            color[2] = 0f;
        } else {
            color[2] = temperature - 10;
            color[2] = (float) (138.51f * Math.log(color[2]) - 305.04f);
            color[2] = MathHelper.clamp(color[2] / 255f, 0f, 1f);
        }

        return color;
    }

    public String getName() {
        return name;
    }

    public void setName(String str) {
        name = str;
    }

    /**
     * @return List of {@link DimensionProperties} of planets orbiting this star
     */
    public List<IDimensionProperties> getPlanets() {
        return new ArrayList<>(planets.values());
    }

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("id", this.id);
        nbt.setInteger("temperature", temperature);
        nbt.setString("name", name);
        nbt.setShort("posX", posX);
        nbt.setShort("posZ", posZ);
        nbt.setFloat("size", size);
        if (mass > MASS_UNSET) {
            nbt.setFloat("mass", mass);
        }
        nbt.setInteger("companionOrbit", orbitalDistance);
        nbt.setDouble("companionTheta", getBaseTheta());
        nbt.setBoolean("isBlackHole", isBlackHole);
        nbt.setFloat("diskAngle", diskAngle);

        NBTTagList list = new NBTTagList();

        for (StellarBody body : subStars) {
            NBTTagCompound tag = new NBTTagCompound();
            body.writeToNBT(tag);
            list.appendTag(tag);
        }

        if (!list.hasNoTags())
            nbt.setTag("subStars", list);
    }

    public void readFromNBT(NBTTagCompound nbt) {
        id = nbt.getInteger("id");
        temperature = nbt.getInteger("temperature");
        name = nbt.getString("name");
        posX = nbt.getShort("posX");
        posZ = nbt.getShort("posZ");
        isBlackHole = nbt.getBoolean("isBlackHole");
        diskAngle = nbt.getFloat("diskAngle");

        if (nbt.hasKey("size"))
            size = nbt.getFloat("size");

        mass = nbt.hasKey("mass") ? nbt.getFloat("mass") : MASS_UNSET;

        if (nbt.hasKey("companionOrbit"))
            orbitalDistance = nbt.getInteger("companionOrbit");
        baseTheta = nbt.hasKey("companionTheta") ? nbt.getDouble("companionTheta") : THETA_UNSTATED;

        subStars.clear();
        if (nbt.hasKey("subStars")) {
            NBTTagList list = nbt.getTagList("subStars", NBT.TAG_COMPOUND);

            for (int i = 0; i < list.tagCount(); i++) {
                StellarBody star = new StellarBody();
                star.readFromNBT(list.getCompoundTagAt(i));
                subStars.add(star);
                star.parentStar = this;
            }
        }
    }

    /**
     * Where this star stands on the legacy solar map: the system's own star at the origin, and a
     * companion offset by its orbit about whatever it orbits.
     *
     * <p>It used to answer an empty position for every star, so the space layer placed every
     * companion of every system at the same point — the one place a star of a binary certainly is
     * not. The offset uses the same distance multiplier a planet's does, so a companion and a planet
     * at the same orbital distance land at the same place on the map, which is the whole reason the
     * two carry the same field in the same unit.</p>
     */
    public SpacePosition getSpacePosition() {
        SpacePosition position = new SpacePosition();
        position.star = this;
        double[] offset = offsetFromSystemAu();
        position.x = offset[0] * PLANET_MAP_UNITS_PER_AU;
        position.z = offset[1] * PLANET_MAP_UNITS_PER_AU;
        return position;
    }
}
