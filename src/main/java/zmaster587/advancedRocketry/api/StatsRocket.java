package zmaster587.advancedRocketry.api;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;
import zmaster587.advancedRocketry.util.WeightEngine;
import zmaster587.libVulpes.util.HashedBlockPosition;
import zmaster587.libVulpes.util.Vector3F;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The derived stat vector of one tier-1 rocket.
 *
 * <p><b>Units.</b> {@code mass} is a MASS, in kilograms — never a weight-at-1g. {@code thrust}
 * is a force, in newtons. Local weight is therefore {@code mass * STANDARD_GRAVITY *
 * gravitationalMultiplier}, and every gravity-dependent quantity (net acceleration, TWR, the
 * launch gate) derives from that one expression instead of assuming the rocket sits at one gee.
 */
public class StatsRocket {

    private static final String TAGNAME = "rocketStats";
    public static final int INVALID_SEAT = Integer.MIN_VALUE;

    /** The sentinel a fluid slot carries when nothing has been loaded into it yet. */
    private static final String NO_FLUID = "null";

    /** Standard gravity, m/s². One unit of {@code gravitationalMultiplier} is one standard gravity. */
    public static final float STANDARD_GRAVITY = 9.81f;

    /** Game ticks per second; the step the per-tick accelerations below are expressed over. */
    private static final float TICKS_PER_SECOND = 20f;

    /**
     * The newtons that one unit of the pre-3.0.0 dimensionless thrust rating maps onto
     * (5 tonnes-force). Empirical curves that were fitted against that rating — only the
     * exhaust scorch radius — normalise by this so their shape survives the move to SI.
     * Nothing else may read it: thrust is newtons everywhere else.
     */
    public static final float THRUST_RATING_UNIT_NEWTONS = 49050f;

    private final List<HashedBlockPosition> passengerSeats = new ArrayList<>();
    //Used for orbital height calculations
    public int orbitHeight;
    public float injectionBurnLenghtMult;
    HashedBlockPosition pilotSeatPos;
    /** Engine thrust, newtons. */
    private int thrust;
    /** Dry mass, kilograms. Fuel mass is added by {@link #getMass()}. */
    private float mass;
    private float drillingPower;
    private String fuelFluid;
    private String oxidizerFluid;
    private String workingFluid;
    private int fuelMonopropellant;
    private int fuelNuclearWorkingFluid;
    private int fuelBipropellant;
    private int fuelOxidizer;
    private int fuelIon;
    private int fuelWarp;
    private int fuelImpulse;
    private int fuelCapacityMonopropellant;
    private int fuelCapacityBipropellant;
    private int fuelCapacityOxidizer;
    private int fuelCapacityNuclearWorkingFluid;
    private int fuelCapacityIon;
    private int fuelCapacityWarp;
    private int fuelCapacityImpulse;
    private int fuelRateMonopropellant;
    private int fuelRateBipropellant;
    private int fuelRateOxidizer;
    private int fuelRateNuclearWorkingFluid;
    private int fuelRateIon;
    private int fuelRateWarp;
    private int fuelRateImpulse;
    private int fuelBaseRateMonopropellant;
    private int fuelBaseRateBipropellant;
    private int fuelBaseRateOxidizer;
    private int fuelBaseRateNuclearWorkingFluid;
    private int fuelBaseRateIon;
    private int fuelBaseRateWarp;
    private int fuelBaseRateImpulse;
    private List<Vector3F<Float>> engineLoc;
    private HashMap<String, Object> statTags;

    public StatsRocket() {
        thrust = 0;
        mass = 0;
        fuelFluid = "null";
        oxidizerFluid = "null";
        workingFluid = "null";
        fuelMonopropellant = 0;
        fuelBipropellant = 0;
        fuelOxidizer = 0;
        fuelRateMonopropellant = 0;
        fuelRateBipropellant = 0;
        fuelRateOxidizer = 0;
        drillingPower = 0f;
        orbitHeight = ARConfiguration.getCurrentConfig().orbit;
        injectionBurnLenghtMult = 1;
        pilotSeatPos = new HashedBlockPosition(0, 0, 0);
        pilotSeatPos.x = INVALID_SEAT;
        engineLoc = new ArrayList<>();
        statTags = new HashMap<>();
    }

	/*public StatsRocket(int thrust, int weight, int fuelRate, int fuel) {
		this.thrust = thrust;
		this.weight = weight;
		this.fuelLiquid = fuel;
		lastSeatX = -1;
		engineLoc = new ArrayList<Vector3F>();
	}*/

    public static StatsRocket createFromNBT(NBTTagCompound nbt) {
        if (nbt.hasKey(TAGNAME)) {
            NBTTagCompound stats = nbt.getCompoundTag(TAGNAME);
            StatsRocket statsRocket = new StatsRocket();
            statsRocket.readFromNBT(stats);
            return statsRocket;
        }

        return new StatsRocket();
    }

    public int getSeatX() {
        return pilotSeatPos.x;
    }

    public int getSeatY() {
        return pilotSeatPos.y;
    }

    public int getSeatZ() {
        return pilotSeatPos.z;
    }

    public HashedBlockPosition getPassengerSeat(int index) {
        return passengerSeats.get(index);
    }

    public int getNumPassengerSeats() {
        return passengerSeats.size();
    }

    /** Engine thrust in newtons, after the config multiplier. */
    public int getThrust() {
        return (int) (thrust * ARConfiguration.getCurrentConfig().rocketThrustMultiplier);
    }

    /** @param thrust engine thrust, newtons; saturates rather than wrapping, because the scan
     *                paths sum a per-engine rating that can exceed the int range on a large hull */
    public void setThrust(long thrust) {
        this.thrust = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, thrust));
    }

    /** Dry mass in kilograms — the rocket with empty tanks. */
    public float getDryMass() {
        return mass;
    }

    /** True if the named fluid exists. The sentinel is answered without touching the registry:
     *  an empty rocket is the common case, and it lets the mass of one be taken before the fluid
     *  registry is up. */
    private static boolean isLoadedFluid(String name) {
        return name != null && !NO_FLUID.equals(name) && !name.isEmpty()
                && FluidRegistry.isFluidRegistered(name);
    }

    /** Wet mass in kilograms — dry mass plus the fuel and oxidizer currently carried. */
    public float getMass() {
        float fluidMass = 0;
        if (ARConfiguration.getCurrentConfig().advancedWeightSystem) {
            if (isLoadedFluid(getFuelFluid())) {
                Fluid f = FluidRegistry.getFluid(getFuelFluid());
                fluidMass += WeightEngine.INSTANCE.getWeight(f, getFuelAmount(FuelType.LIQUID_MONOPROPELLANT));
                fluidMass += WeightEngine.INSTANCE.getWeight(f, getFuelAmount(FuelType.LIQUID_BIPROPELLANT));
            }
            if (isLoadedFluid(getOxidizerFluid())) {
                Fluid f = FluidRegistry.getFluid(getOxidizerFluid());
                fluidMass += WeightEngine.INSTANCE.getWeight(f, getFuelAmount(FuelType.LIQUID_OXIDIZER));
            }
            if (isLoadedFluid(getWorkingFluid())) {
                Fluid f = FluidRegistry.getFluid(getWorkingFluid());
                fluidMass += WeightEngine.INSTANCE.getWeight(f, getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID));
            }
        }
        return mass + fluidMass;
    }

    /** @param mass dry mass, kilograms */
    public void setMass(float mass) {
        this.mass = mass;
    }

    public String getFuelFluid() {
        return fuelFluid;
    }

    public void setFuelFluid(String fuelFluid) {
        this.fuelFluid = fuelFluid;
    }

    public String getOxidizerFluid() {
        return oxidizerFluid;
    }

    public void setOxidizerFluid(String oxidizerFluid) {
        this.oxidizerFluid = oxidizerFluid;
    }

    public String getWorkingFluid() {
        return workingFluid;
    }

    public void setWorkingFluid(String workingFluid) {
        this.workingFluid = workingFluid;
    }

    public float getDrillingPower() {
        return drillingPower;
    }

    public void setDrillingPower(float power) {
        drillingPower = power;
    }

    /**
     * The gravity actually seen by the flight model, in standard gravities. Reading it through
     * one method is what keeps the launch gate and the flight model from disagreeing about which
     * planet the rocket is on: {@code gravityAffectsFuel = false} pins both to one gee.
     */
    private static float effectiveGravityMultiplier(float gravitationalMultiplier) {
        return ARConfiguration.getCurrentConfig().gravityAffectsFuel ? gravitationalMultiplier : 1f;
    }

    /** Local weight in newtons of a given mass — the force the engines have to beat to hover. */
    public static float weightNewtons(float massKg, float gravitationalMultiplier) {
        return massKg * STANDARD_GRAVITY * effectiveGravityMultiplier(gravitationalMultiplier);
    }

    /** Local weight of the wet rocket, newtons. */
    public float getWeightNewtons(float gravitationalMultiplier) {
        return weightNewtons(getMass(), gravitationalMultiplier);
    }

    /**
     * Net climb per tick at full thrust, in the units the entity adds to its motion.
     * The net specific force {@code (thrust - weight) / mass} is expressed in standard
     * gravities and then stepped once per tick.
     */
    private float netClimbPerTick(float massKg, float gravitationalMultiplier) {
        if (massKg <= 0) {
            return 0;
        }
        float netNewtons = getThrust() - weightNewtons(massKg, gravitationalMultiplier);
        return netNewtons / massKg / STANDARD_GRAVITY / TICKS_PER_SECOND;
    }

    public float getAcceleration(float gravitationalMultiplier) {
        return netClimbPerTick(getMass(), gravitationalMultiplier);
    }

    /** Acceleration with empty tanks (dry mass only) — the upper bound reached as fuel burns off. */
    public float getDryAcceleration(float gravitationalMultiplier) {
        return netClimbPerTick(getDryMass(), gravitationalMultiplier);
    }

    /** Thrust-to-weight ratio against the wet mass at the LOCAL gravity. 0 if massless. */
    public float getThrustToWeightRatio(float gravitationalMultiplier) {
        if (getMass() <= 0) {
            return 0;
        }
        float localWeight = getWeightNewtons(gravitationalMultiplier);
        if (localWeight <= 0) {
            // A body with no gravity: any thrust at all is enough to leave it.
            return getThrust() > 0 ? Float.POSITIVE_INFINITY : 0;
        }
        return getThrust() / localWeight;
    }

    /** True if the rocket clears the configured minimum thrust-to-weight ratio to launch
     *  from a body of the given gravity. When the advanced weight system is disabled the
     *  mass-based launch gate is off entirely (classic behaviour — no TWR check), so this
     *  returns true regardless of thrust or mass. This is the single source of truth for
     *  mass-based launch gating; callers must not re-derive the TWR check independently.
     *  @param gravitationalMultiplier gravity of the body being launched from, in standard
     *                                 gravities — a light moon is easier to leave than Earth */
    public boolean canLaunch(float gravitationalMultiplier) {
        if (!ARConfiguration.getCurrentConfig().advancedWeightSystem) {
            return true;
        }
        return getThrustToWeightRatio(gravitationalMultiplier) >= ARConfiguration.getCurrentConfig().minLaunchTWR;
    }

    public List<Vector3F<Float>> getEngineLocations() {
        return engineLoc;
    }

    public boolean isNuclear() {
        return fuelBaseRateNuclearWorkingFluid > 0;
    }

    public void setSeatLocation(int x, int y, int z) {
        pilotSeatPos.x = x;
        pilotSeatPos.y = (short) y;
        pilotSeatPos.z = z;
    }

    public void addPassengerSeat(int x, int y, int z) {
        if (!hasSeat())
            setSeatLocation(x, y, z);
        passengerSeats.add(new HashedBlockPosition(x, y, z));
    }

    /**
     * Adds an engine location to the given coordinates
     * the engine location is only currently used to track the location for spawning particle effects
     *
     * @param x
     * @param y
     * @param z
     */
    public void addEngineLocation(float x, float y, float z) {
        //We want to be in the center of the block
        //System.out.println("ADD engine at "+x+":"+y+":"+z);
        engineLoc.add(new Vector3F<>(x, y, z));
    }

    /**
     * Removes all engine locations
     */
    public void clearEngineLocations() {
        engineLoc.clear();
    }

    /**
     * @return a duplicate of the rocket stats
     */
    public StatsRocket copy() {
        StatsRocket stat = new StatsRocket();

        stat.thrust = this.thrust;
        stat.mass = this.mass;
        stat.fuelFluid = this.fuelFluid;
        stat.oxidizerFluid = this.oxidizerFluid;
        stat.workingFluid = this.workingFluid;
        stat.drillingPower = this.drillingPower;

        for (FuelType type : FuelType.values()) {
            stat.setFuelAmount(type, this.getFuelAmount(type));
            stat.setFuelRate(type, this.getFuelRate(type));
            stat.setFuelCapacity(type, this.getFuelCapacity(type));
            stat.setBaseFuelRate(type, this.getBaseFuelRate(type));
        }

        stat.pilotSeatPos = new HashedBlockPosition(this.pilotSeatPos.x, this.pilotSeatPos.y, this.pilotSeatPos.z);
        stat.passengerSeats.addAll(passengerSeats);
        stat.engineLoc = new ArrayList<>(engineLoc);
        stat.statTags = new HashMap<>(statTags);
        return stat;
    }

    /**
     * @param type type of fuel to check
     * @return the amount of fuel of the type currently contained in the stat
     */
    public int getFuelAmount(@Nullable FuelRegistry.FuelType type) {
        if (type != null) {
            switch (type) {
                case WARP:
                    return fuelWarp;
                case IMPULSE:
                    return fuelImpulse;
                case ION:
                    return fuelIon;
                case LIQUID_MONOPROPELLANT:
                    return fuelMonopropellant;
                case LIQUID_BIPROPELLANT:
                    return fuelBipropellant;
                case LIQUID_OXIDIZER:
                    return fuelOxidizer;
                case NUCLEAR_WORKING_FLUID:
                    return fuelNuclearWorkingFluid;
            }
        }

        return 0;
    }

    /**
     * @param type
     * @return the largest amount of fuel of the type that can be stored in the stat
     */
    public int getFuelCapacity(@Nullable FuelRegistry.FuelType type) {
        if (type != null) {
            switch (type) {
                case WARP:
                    return fuelCapacityWarp;
                case IMPULSE:
                    return fuelCapacityImpulse;
                case ION:
                    return fuelCapacityIon;
                case LIQUID_MONOPROPELLANT:
                    return fuelCapacityMonopropellant;
                case LIQUID_BIPROPELLANT:
                    return fuelCapacityBipropellant;
                case LIQUID_OXIDIZER:
                    return fuelCapacityOxidizer;
                case NUCLEAR_WORKING_FLUID:
                    return fuelCapacityNuclearWorkingFluid;
            }
        }

        return 0;
    }

    /**
     * @param type
     * @return the consumption rate of the fuel per tick
     */
    public int getFuelRate(@Nullable FuelRegistry.FuelType type) {
        if (!ARConfiguration.getCurrentConfig().rocketRequireFuel || type == null)
            return 0;

        switch (type) {
            case WARP:
                return fuelRateWarp;
            case IMPULSE:
                return fuelRateImpulse;
            case ION:
                return fuelRateIon;
            case LIQUID_MONOPROPELLANT:
                return fuelRateMonopropellant;
            case LIQUID_BIPROPELLANT:
                return fuelRateBipropellant;
            case LIQUID_OXIDIZER:
                return fuelRateOxidizer;
            case NUCLEAR_WORKING_FLUID:
                return fuelRateNuclearWorkingFluid;
        }

        return 0;
    }

    /**
     * @param type
     * @return the base engine consumption rate of the fuel per tick
     */
    public int getBaseFuelRate(@Nullable FuelRegistry.FuelType type) {

        if (!ARConfiguration.getCurrentConfig().rocketRequireFuel || type == null)
            return 0;

        switch (type) {
            case WARP:
                return fuelBaseRateWarp;
            case IMPULSE:
                return fuelBaseRateImpulse;
            case ION:
                return fuelBaseRateIon;
            case LIQUID_MONOPROPELLANT:
                return fuelBaseRateMonopropellant;
            case LIQUID_BIPROPELLANT:
                return fuelBaseRateBipropellant;
            case LIQUID_OXIDIZER:
                return fuelBaseRateOxidizer;
            case NUCLEAR_WORKING_FLUID:
                return fuelBaseRateNuclearWorkingFluid;
        }
        return 0;
    }

    /**
     * Sets the amount of a given fuel type in the stat
     *
     * @param type
     * @param amt
     */
    public void setFuelAmount(@Nonnull FuelRegistry.FuelType type, int amt) {
        switch (type) {
            case WARP:
                fuelWarp = amt;
                break;
            case IMPULSE:
                fuelImpulse = amt;
                break;
            case ION:
                fuelIon = amt;
                break;
            case LIQUID_MONOPROPELLANT:
                fuelMonopropellant = amt;
                break;
            case LIQUID_BIPROPELLANT:
                fuelBipropellant = amt;
                break;
            case LIQUID_OXIDIZER:
                fuelOxidizer = amt;
                break;
            case NUCLEAR_WORKING_FLUID:
                fuelNuclearWorkingFluid = amt;
        }
    }

    /**
     * Sets the fuel consumption rate per tick of the stat
     *
     * @param type
     * @param rate
     */
    public void setFuelRate(@Nonnull FuelRegistry.FuelType type, int rate) {
        switch (type) {
            case WARP:
                fuelRateWarp = rate;
                break;
            case IMPULSE:
                fuelRateImpulse = rate;
                break;
            case ION:
                fuelRateIon = rate;
                break;
            case LIQUID_MONOPROPELLANT:
                fuelRateMonopropellant = rate;
                break;
            case LIQUID_BIPROPELLANT:
                fuelRateBipropellant = rate;
                break;
            case LIQUID_OXIDIZER:
                fuelRateOxidizer = rate;
                break;
            case NUCLEAR_WORKING_FLUID:
                fuelRateNuclearWorkingFluid = rate;
        }
    }

    /**
     * Sets the engine consumption rate per tick of the stat
     *
     * @param type
     * @param rate
     */
    public void setBaseFuelRate(@Nonnull FuelRegistry.FuelType type, int rate) {
        switch (type) {
            case WARP:
                fuelBaseRateWarp = rate;
                break;
            case IMPULSE:
                fuelBaseRateImpulse = rate;
                break;
            case ION:
                fuelBaseRateIon = rate;
                break;
            case LIQUID_MONOPROPELLANT:
                fuelBaseRateMonopropellant = rate;
                break;
            case LIQUID_BIPROPELLANT:
                fuelBaseRateBipropellant = rate;
                break;
            case LIQUID_OXIDIZER:
                fuelBaseRateOxidizer = rate;
                break;
            case NUCLEAR_WORKING_FLUID:
                fuelBaseRateNuclearWorkingFluid = rate;
        }
    }

    /**
     * Sets the fuel capacity of the fuel type in this stat
     *
     * @param type
     * @param amt
     */
    public void setFuelCapacity(@Nonnull FuelRegistry.FuelType type, int amt) {
        switch (type) {
            case WARP:
                fuelCapacityWarp = amt;
                break;
            case IMPULSE:
                fuelCapacityImpulse = amt;
                break;
            case ION:
                fuelCapacityIon = amt;
                break;
            case LIQUID_MONOPROPELLANT:
                fuelCapacityMonopropellant = amt;
                break;
            case LIQUID_BIPROPELLANT:
                fuelCapacityBipropellant = amt;
                break;
            case LIQUID_OXIDIZER:
                fuelCapacityOxidizer = amt;
                break;
            case NUCLEAR_WORKING_FLUID:
                fuelCapacityNuclearWorkingFluid = amt;
        }
    }

    /**
     * @param type type of fuel
     * @param amt  amount of fuel to add
     * @return amount of fuel added
     */
    public int addFuelAmount(@Nonnull FuelRegistry.FuelType type, int amt) {
        switch (type) {
            case WARP:
                int maxAddWarp = fuelCapacityWarp - fuelWarp;
                int amountToAddWarp = Math.min(amt, maxAddWarp);
                fuelWarp += amountToAddWarp;
                return amountToAddWarp;
            case IMPULSE:
                int maxAddImpulse = fuelCapacityImpulse - fuelImpulse;
                int amountToAddImpulse = Math.min(amt, maxAddImpulse);
                fuelImpulse += amountToAddImpulse;
                return amountToAddImpulse;
            case ION:
                int maxAddIon = fuelCapacityIon - fuelIon;
                int amountToAddIon = Math.min(amt, maxAddIon);
                fuelIon += amountToAddIon;
                return amountToAddIon;
            case LIQUID_MONOPROPELLANT:
                int maxAddMonopropellant = fuelCapacityMonopropellant - fuelMonopropellant;
                int amountToAddMonopropellant = Math.min(amt, maxAddMonopropellant);
                fuelMonopropellant += amountToAddMonopropellant;
                return amountToAddMonopropellant;
            case LIQUID_BIPROPELLANT:
                int maxAddBipropellant = fuelCapacityBipropellant - fuelBipropellant;
                int amountToAddBipropellant = Math.min(amt, maxAddBipropellant);
                fuelBipropellant += amountToAddBipropellant;
                return amountToAddBipropellant;
            case LIQUID_OXIDIZER:
                int maxAddOxidizer = fuelCapacityOxidizer - fuelOxidizer;
                int amountToAddOxidizer = Math.min(amt, maxAddOxidizer);
                fuelOxidizer += amountToAddOxidizer;
                return amountToAddOxidizer;
            case NUCLEAR_WORKING_FLUID:
                int maxAddNuclearWorkingFluid = fuelCapacityNuclearWorkingFluid - fuelNuclearWorkingFluid;
                int amountToAddNuclearWorkingFluid = Math.min(amt, maxAddNuclearWorkingFluid);
                fuelNuclearWorkingFluid += amountToAddNuclearWorkingFluid;
                return amountToAddNuclearWorkingFluid;
        }
        return 0;
    }

    /**
     * @return true if a seat exists on this stat
     */
    public boolean hasSeat() {
        return pilotSeatPos.x != INVALID_SEAT;
    }

    /**
     * resets all values to default
     */
    public void reset() {
        thrust = 0;
        mass = 0;
        fuelFluid = "null";
        oxidizerFluid = "null";
        workingFluid = "null";
        drillingPower = 0f;

        for (FuelType type : FuelType.values()) {
            setFuelAmount(type, 0);
            setFuelRate(type, 0);
            setFuelCapacity(type, 0);
        }

        fuelMonopropellant = 0;
        fuelBipropellant = 0;
        fuelOxidizer = 0;
        pilotSeatPos.x = INVALID_SEAT;
        clearEngineLocations();
        passengerSeats.clear();
        statTags.clear();
    }
    public void reset_no_fuel() {
        thrust = 0;
        mass = 0;
        drillingPower = 0f;

        pilotSeatPos.x = INVALID_SEAT;
        clearEngineLocations();
        passengerSeats.clear();
        statTags.clear();
    }

    public void setStatTag(String str, float value) {
        statTags.put(str, value);
    }

    public void setStatTag(String str, int value) {
        statTags.put(str, value);
    }

    /**
     * @param str name of the tag to get
     * @return the value of the tag as float or int, or 0 if tag does not exist
     */
    public Object getStatTag(String str) {
        Object obj = statTags.get(str);
        return obj == null ? 0 : obj;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound stats = new NBTTagCompound();

        stats.setInteger("thrust", this.thrust);
        stats.setFloat("mass", this.mass);
        stats.setFloat("drillingPower", this.drillingPower);
        stats.setString("fuelFluid", this.fuelFluid);
        stats.setString("oxidizerFluid", this.oxidizerFluid);
        stats.setString("workingFluid", this.workingFluid);

        stats.setInteger("fuelMonopropellant", this.fuelMonopropellant);
        stats.setInteger("fuelBipropellant", this.fuelBipropellant);
        stats.setInteger("fuelOxidizer", this.fuelOxidizer);
        stats.setInteger("fuelImpulse", this.fuelImpulse);
        stats.setInteger("fuelIon", this.fuelIon);
        stats.setInteger("fuelNuclearWorkingFluid", this.fuelNuclearWorkingFluid);
        stats.setInteger("fuelWarp", this.fuelWarp);

        stats.setInteger("fuelCapacityMonopropellant", this.fuelCapacityMonopropellant);
        stats.setInteger("fuelCapacityBipropellant", this.fuelCapacityBipropellant);
        stats.setInteger("fuelCapacityOxidizer", this.fuelCapacityOxidizer);
        stats.setInteger("fuelCapacityImpulse", this.fuelCapacityImpulse);
        stats.setInteger("fuelCapacityIon", this.fuelCapacityIon);
        stats.setInteger("fuelCapacityNuclearWorkingFluid", this.fuelCapacityNuclearWorkingFluid);
        stats.setInteger("fuelCapacityWarp", this.fuelCapacityWarp);

        stats.setInteger("fuelRateMonopropellant", this.fuelRateMonopropellant);
        stats.setInteger("fuelRateBipropellant", this.fuelRateBipropellant);
        stats.setInteger("fuelRateOxidizer", this.fuelRateOxidizer);
        stats.setInteger("fuelRateImpulse", this.fuelRateImpulse);
        stats.setInteger("fuelRateIon", this.fuelRateIon);
        stats.setInteger("fuelRateNuclearWorkingFluid", this.fuelRateNuclearWorkingFluid);
        stats.setInteger("fuelRateWarp", this.fuelRateWarp);

        stats.setFloat("fuelBaseRateMonopropellant", this.fuelBaseRateMonopropellant);
        stats.setFloat("fuelBaseRateBipropellant", this.fuelBaseRateBipropellant);
        stats.setFloat("fuelBaseRateOxidizer", this.fuelBaseRateOxidizer);
        stats.setFloat("fuelBaseRateImpulse", this.fuelBaseRateImpulse);
        stats.setFloat("fuelBaseRateIon", this.fuelBaseRateIon);
        stats.setFloat("fuelBaseRateNuclearWorkingFluid", this.fuelBaseRateNuclearWorkingFluid);
        stats.setFloat("fuelBaseRateWarp", this.fuelBaseRateWarp);

        NBTTagCompound dynStats = new NBTTagCompound();
        for (String key : statTags.keySet()) {
            Object obj = statTags.get(key);

            if (obj instanceof Float)
                dynStats.setFloat(key, (float) obj);
            else if (obj instanceof Integer)
                dynStats.setInteger(key, (int) obj);
        }
        if (!dynStats.hasNoTags())
            stats.setTag("dynStats", dynStats);

        stats.setInteger("playerXPos", pilotSeatPos.x);
        stats.setInteger("playerYPos", pilotSeatPos.y);
        stats.setInteger("playerZPos", pilotSeatPos.z);

        if (!engineLoc.isEmpty()) {
            // I make a little hack here to pass double positions by *2 in write and /2 in read method
            int[] locs = new int[engineLoc.size() * 3];

            for (int i = 0; (i / 3) < engineLoc.size(); i += 3) {
                Vector3F<Float> vec = engineLoc.get(i / 3);
                locs[i] = (int)(vec.x*2);
                locs[i + 1] = (int)(vec.y*2);
                locs[i + 2] = (int)(vec.z*2);
            }
            stats.setIntArray("engineLoc", locs);
        }

        if (!passengerSeats.isEmpty()) {
            int[] locs = new int[passengerSeats.size() * 3];

            for (int i = 0; (i / 3) < passengerSeats.size(); i += 3) {
                HashedBlockPosition vec = passengerSeats.get(i / 3);
                locs[i] = vec.x;
                locs[i + 1] = vec.y;
                locs[i + 2] = vec.z;

            }
            stats.setIntArray("passengerSeats", locs);
        }

        nbt.setTag(TAGNAME, stats);
    }

    public void readFromNBT(NBTTagCompound nbt) {
this.reset();
        if (nbt.hasKey(TAGNAME)) {
            NBTTagCompound stats = nbt.getCompoundTag(TAGNAME);
            this.thrust = stats.getInteger("thrust");
            this.mass = stats.getFloat("mass");
            this.fuelFluid = stats.getString("fuelFluid");
            this.oxidizerFluid = stats.getString("oxidizerFluid");
            this.workingFluid = stats.getString("workingFluid");
            this.drillingPower = stats.getFloat("drillingPower");

            this.fuelMonopropellant = stats.getInteger("fuelMonopropellant");
            this.fuelBipropellant = stats.getInteger("fuelBipropellant");
            this.fuelOxidizer = stats.getInteger("fuelOxidizer");
            this.fuelImpulse = stats.getInteger("fuelImpulse");
            this.fuelIon = stats.getInteger("fuelIon");
            this.fuelNuclearWorkingFluid = stats.getInteger("fuelNuclearWorkingFluid");
            this.fuelWarp = stats.getInteger("fuelWarp");

            this.fuelCapacityMonopropellant = stats.getInteger("fuelCapacityMonopropellant");
            this.fuelCapacityBipropellant = stats.getInteger("fuelCapacityBipropellant");
            this.fuelCapacityOxidizer = stats.getInteger("fuelCapacityOxidizer");
            this.fuelCapacityImpulse = stats.getInteger("fuelCapacityImpulse");
            this.fuelCapacityIon = stats.getInteger("fuelCapacityIon");
            this.fuelCapacityNuclearWorkingFluid = stats.getInteger("fuelCapacityNuclearWorkingFluid");
            this.fuelCapacityWarp = stats.getInteger("fuelCapacityWarp");

            this.fuelRateMonopropellant = stats.getInteger("fuelRateMonopropellant");
            this.fuelRateBipropellant = stats.getInteger("fuelRateBipropellant");
            this.fuelRateOxidizer = stats.getInteger("fuelRateOxidizer");
            this.fuelRateImpulse = stats.getInteger("fuelRateImpulse");
            this.fuelRateIon = stats.getInteger("fuelRateIon");
            this.fuelRateNuclearWorkingFluid = stats.getInteger("fuelRateNuclearWorkingFluid");
            this.fuelRateWarp = stats.getInteger("fuelRateWarp");

            this.fuelBaseRateMonopropellant = (int)stats.getFloat("fuelBaseRateMonopropellant");
            this.fuelBaseRateBipropellant = (int)stats.getFloat("fuelBaseRateBipropellant");
            this.fuelBaseRateOxidizer = (int)stats.getFloat("fuelBaseRateOxidizer");
            this.fuelBaseRateImpulse = stats.getInteger("fuelBaseRateImpulse");
            this.fuelBaseRateIon = stats.getInteger("fuelBaseRateIon");
            this.fuelBaseRateNuclearWorkingFluid = (int)stats.getFloat("fuelBaseRateNuclearWorkingFluid");
            this.fuelBaseRateWarp = stats.getInteger("fuelBaseRateWarp");


            if (stats.hasKey("dynStats")) {
                NBTTagCompound dynStats = stats.getCompoundTag("dynStats");


                for (String key : dynStats.getKeySet()) {
                    Object obj = dynStats.getTag(key);

                    if (obj instanceof NBTTagFloat)
                        setStatTag(key, dynStats.getFloat(key));
                    else if (obj instanceof NBTTagInt)
                        setStatTag(key, dynStats.getInteger(key));
                }
            }

            pilotSeatPos.x = stats.getInteger("playerXPos");
            pilotSeatPos.y = (short) stats.getInteger("playerYPos");
            pilotSeatPos.z = stats.getInteger("playerZPos");

            if (stats.hasKey("engineLoc")) {
                int[] locations = stats.getIntArray("engineLoc");

                for (int i = 0; i < locations.length; i += 3) {

                    this.addEngineLocation((float)locations[i]/2, (float)locations[i + 1]/2, (float)locations[i + 2]/2);
                }
            }

            if (stats.hasKey("passengerSeats")) {
                int[] locations = stats.getIntArray("passengerSeats");

                for (int i = 0; i < locations.length; i += 3) {

                    this.addPassengerSeat(locations[i], locations[i + 1], locations[i + 2]);
                }
            }
        }
    }
}
