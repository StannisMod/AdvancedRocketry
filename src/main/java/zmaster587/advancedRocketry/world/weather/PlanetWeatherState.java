package zmaster587.advancedRocketry.world.weather;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Per-dimension weather state pulled out of {@link net.minecraft.world.storage.WorldInfo}.
 *
 * <p>Held by {@link PlanetWeatherSavedData} keyed by dimension id; mutated only
 * via {@link ARDimensionWorldInfo} setters. Mutations flip the {@code dirty} flag
 * — the manager pushes that flip down to the saved-data so vanilla disk save
 * picks it up. Per-listener "lastSynced" snapshots support the explicit
 * client sync (begin/end raining edges) emitted on player join / dim change.</p>
 */
public final class PlanetWeatherState {

    private int cleanWeatherTime;
    private int rainTime;
    private int thunderTime;
    private boolean raining;
    private boolean thundering;

    // Per-dimension time-of-day + world age. Vanilla derived worlds delegate
    // these to the overworld (and their setters are no-ops), so every AR planet
    // shared the overworld clock and the sleep skip was swallowed. Owning them
    // here makes each dimension's day/night and sleep independent.
    private long worldTime;
    private long worldTotalTime;
    private boolean timeInitialized;

    private transient boolean lastSyncedRaining;
    private transient boolean lastSyncedThundering;

    public PlanetWeatherState() {
    }

    public int getCleanWeatherTime() {
        return cleanWeatherTime;
    }

    public void setCleanWeatherTime(int value) {
        this.cleanWeatherTime = value;
    }

    public int getRainTime() {
        return rainTime;
    }

    public void setRainTime(int value) {
        this.rainTime = value;
    }

    public int getThunderTime() {
        return thunderTime;
    }

    public void setThunderTime(int value) {
        this.thunderTime = value;
    }

    public boolean isRaining() {
        return raining;
    }

    public void setRaining(boolean value) {
        this.raining = value;
    }

    public boolean isThundering() {
        return thundering;
    }

    public void setThundering(boolean value) {
        this.thundering = value;
    }

    public long getWorldTime() {
        return worldTime;
    }

    public void setWorldTime(long value) {
        this.worldTime = value;
        this.timeInitialized = true;
    }

    public long getWorldTotalTime() {
        return worldTotalTime;
    }

    public void setWorldTotalTime(long value) {
        this.worldTotalTime = value;
        this.timeInitialized = true;
    }

    public boolean isTimeInitialized() {
        return timeInitialized;
    }

    /**
     * Seed the per-dim clock from the delegate's current value the first time
     * this dimension is wrapped, so existing saves don't visibly jump. No-op
     * once the clock has been initialised (from a setter or NBT load).
     */
    public void seedTimeIfNeeded(long worldTimeIn, long worldTotalTimeIn) {
        if (!timeInitialized) {
            this.worldTime = worldTimeIn;
            this.worldTotalTime = worldTotalTimeIn;
            this.timeInitialized = true;
        }
    }

    public boolean wasLastSyncedRaining() {
        return lastSyncedRaining;
    }

    public void markSyncedRaining(boolean value) {
        this.lastSyncedRaining = value;
    }

    public boolean wasLastSyncedThundering() {
        return lastSyncedThundering;
    }

    public void markSyncedThundering(boolean value) {
        this.lastSyncedThundering = value;
    }

    public void readFromNBT(NBTTagCompound nbt) {
        this.cleanWeatherTime = nbt.getInteger("cleanWeatherTime");
        this.rainTime = nbt.getInteger("rainTime");
        this.thunderTime = nbt.getInteger("thunderTime");
        this.raining = nbt.getBoolean("raining");
        this.thundering = nbt.getBoolean("thundering");
        if (nbt.hasKey("worldTime")) {
            this.worldTime = nbt.getLong("worldTime");
            this.worldTotalTime = nbt.getLong("worldTotalTime");
            this.timeInitialized = true;
        }
    }

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("cleanWeatherTime", cleanWeatherTime);
        nbt.setInteger("rainTime", rainTime);
        nbt.setInteger("thunderTime", thunderTime);
        nbt.setBoolean("raining", raining);
        nbt.setBoolean("thundering", thundering);
        if (timeInitialized) {
            nbt.setLong("worldTime", worldTime);
            nbt.setLong("worldTotalTime", worldTotalTime);
        }
    }
}
