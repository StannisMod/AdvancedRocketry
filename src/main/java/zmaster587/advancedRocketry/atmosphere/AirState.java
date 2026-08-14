package zmaster587.advancedRocketry.atmosphere;

import net.minecraft.nbt.NBTTagCompound;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IAtmosphere;

/**
 * The gas contents of one sealed zone: nitrogen, oxygen and carbon dioxide, each held as a
 * partial pressure.
 * <p>
 * Units are <b>micro-atmospheres</b> (1_000_000 = 1 atm). That is fine enough for the trace
 * gases the life-support loop cares about — dangerous CO2 sits around 1% of an atmosphere — and
 * it converts to the pressure figure the rest of the mod already speaks: the atmosphere analyser
 * prints {@code pressure / 100f + " atm"}, so pressure is hundredths of an atmosphere and
 * {@link #getPressureCentiAtm()} divides by 10_000. {@link #earthLike()} totals exactly one
 * atmosphere, which is the constant the zone pressure used to be hard-coded to.
 * <p>
 * Nitrogen is inert: nothing produces or consumes it. It exists so that the oxygen fraction is a
 * quantity a governor can act on rather than a synonym for "how much gas is in the room".
 */
public class AirState {

    /** One atmosphere, in the internal unit. */
    public static final int ONE_ATM = 1_000_000;

    /** Below this total pressure the zone is not air at all, whatever its composition. */
    private static final int VACUUM_CEILING = ONE_ATM / 100;

    private int nitrogen;
    private int oxygen;
    private int carbonDioxide;

    public AirState(int nitrogen, int oxygen, int carbonDioxide) {
        this.nitrogen = Math.max(0, nitrogen);
        this.oxygen = Math.max(0, oxygen);
        this.carbonDioxide = Math.max(0, carbonDioxide);
    }

    /**
     * Breathable sea-level air. Totals exactly {@link #ONE_ATM}, so a zone that has never been
     * touched by life support reports the same pressure it reported before zones had contents.
     */
    public static AirState earthLike() {
        return new AirState(790_000, 210_000, 0);
    }

    public static AirState vacuum() {
        return new AirState(0, 0, 0);
    }

    public int getNitrogen() {
        return nitrogen;
    }

    public int getOxygen() {
        return oxygen;
    }

    public int getCarbonDioxide() {
        return carbonDioxide;
    }

    public int getTotalPressure() {
        return nitrogen + oxygen + carbonDioxide;
    }

    /** The pressure figure the HUD, the analyser and {@code PacketAtmSync} speak: 100 = 1 atm. */
    public int getPressureCentiAtm() {
        return getTotalPressure() / (ONE_ATM / 100);
    }

    /**
     * Move oxygen into carbon dioxide, as breathing does. Both gases move by the same amount, so
     * the total pressure is unchanged — respiration rearranges air, it does not consume it.
     *
     * @param amount partial pressure to convert; clamped to the oxygen actually present
     * @return the amount actually converted, which is less than requested once the zone runs out
     */
    public int respire(int amount) {
        int converted = Math.min(Math.max(0, amount), oxygen);
        oxygen -= converted;
        carbonDioxide += converted;
        return converted;
    }

    /**
     * The reverse of {@link #respire}: carbon dioxide becomes oxygen again, the carbon leaving the
     * air as a solid. One molecule of CO2 yields one of O2, so total pressure is unchanged here
     * too — the carbon that departs was never contributing pressure on its own.
     *
     * @param amount partial pressure to regenerate; clamped to the CO2 actually present
     * @return the amount actually converted, which is the carbon the caller must now deal with
     */
    public int regenerate(int amount) {
        int converted = Math.min(Math.max(0, amount), carbonDioxide);
        carbonDioxide -= converted;
        oxygen += converted;
        return converted;
    }

    /**
     * Which registered atmosphere this zone presents to everything downstream — tick damage, the
     * suit immunity check, the sync packet, the detector. The gas state is the model; the
     * {@link AtmosphereType} singletons stay the interface, so nothing outside life support has
     * to learn about partial pressures.
     */
    public IAtmosphere deriveAtmosphere() {
        if (getTotalPressure() <= VACUUM_CEILING)
            return AtmosphereType.VACUUM;

        ARConfiguration config = ARConfiguration.getCurrentConfig();
        // An un-loaded config leaves both bounds at zero, which would otherwise read as "every
        // zone is oxygen-toxic". No usable band means no governor, not a hazard.
        if (config.lifeSupportMaxPartialO2 <= config.lifeSupportMinPartialO2)
            return AtmosphereType.PRESSURIZEDAIR;

        if (oxygen < config.lifeSupportMinPartialO2)
            return oxygen <= 0 ? AtmosphereType.NOO2 : AtmosphereType.LOWOXYGEN;
        if (oxygen > config.lifeSupportMaxPartialO2)
            return AtmosphereType.HIGHOXYGEN;

        return AtmosphereType.PRESSURIZEDAIR;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("n2", nitrogen);
        nbt.setInteger("o2", oxygen);
        nbt.setInteger("co2", carbonDioxide);
    }

    public static AirState readFromNBT(NBTTagCompound nbt) {
        return new AirState(nbt.getInteger("n2"), nbt.getInteger("o2"), nbt.getInteger("co2"));
    }

    @Override
    public String toString() {
        return "AirState[n2=" + nitrogen + ", o2=" + oxygen + ", co2=" + carbonDioxide + "]";
    }
}
