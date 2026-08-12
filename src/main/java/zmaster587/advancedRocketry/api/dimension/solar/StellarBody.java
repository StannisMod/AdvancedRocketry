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

    public List<StellarBody> subStars;
    int numPlanets;
    int discoveredPlanets;
    float[] color;
    int id;
    float size;
    private float mass = MASS_UNSET;
    String name;
    short posX, posZ;
    float starSeperation;
    StellarBody parentStar;
    private int temperature;
    private HashMap<Integer, IDimensionProperties> planets;
    private boolean isBlackHole;
    public float diskAngle;

    public StellarBody() {
        planets = new HashMap<>();
        size = 1f;
        subStars = new LinkedList<>();
        starSeperation = 5f;
        isBlackHole = false;
        diskAngle = 70;
    }

    public List<StellarBody> getSubStars() {
        return subStars;
    }

    public void addSubStar(StellarBody star) {
        if (star.name == null)
            star.setName(name + "-" + (subStars.size() + 1));
        star.setId(this.id);
        subStars.add(star);
        star.parentStar = this;
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

    //Returns the distance between the star and sub stars
    public float getStarSeparation() {
        return starSeperation;
    }

    public void setStarSeparation(float seperation) {
        this.starSeperation = seperation;
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
     * @return the number of planets orbiting this star
     */
    public int getNumPlanets() {
        if (parentStar != null)
            return parentStar.getNumPlanets();
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
        nbt.setFloat("seperation", starSeperation);
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

        if (nbt.hasKey("seperation"))
            starSeperation = nbt.getFloat("seperation");

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

    public SpacePosition getSpacePosition() {
        //TODO
        return new SpacePosition();
    }
}
