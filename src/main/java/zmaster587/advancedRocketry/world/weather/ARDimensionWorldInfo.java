package zmaster587.advancedRocketry.world.weather;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.DimensionType;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;

/**
 * {@link WorldInfo} wrapper installed on AR-planet {@link net.minecraft.world.WorldServer}s
 * by {@link PlanetWeatherManager}. Delegates every non-weather call to the
 * underlying {@link WorldInfo} (the secondary world's
 * {@link net.minecraft.world.storage.DerivedWorldInfo} that vanilla
 * {@code WorldServerMulti} installed) and serves weather state from a
 * per-dimension {@link PlanetWeatherState} living in
 * {@link PlanetWeatherSavedData}.
 *
 * <p>Why a wrapper instead of subclassing {@code DerivedWorldInfo}: this class
 * cares only about behaviour, not about the secret persistent state hidden
 * inside vanilla's {@code WorldInfo} (level format version, custom boss event
 * registry, etc.). Delegation keeps that state intact.</p>
 *
 * <p>The {@code dirtyMarker} callback lets the manager push "saved-data is
 * stale, schedule a re-save" without us holding a {@link net.minecraft.world.World}
 * reference (the wrapper outlives world unload during dim flicker — a hard
 * world reference would leak the entire dimension).</p>
 */
public final class ARDimensionWorldInfo extends WorldInfo {

    private final WorldInfo delegate;
    private final PlanetWeatherState weatherState;
    private final Runnable dirtyMarker;
    /**
     * When {@code true} weather is served from the per-dim {@link PlanetWeatherState}
     * (custom planet weather); when {@code false} weather delegates to the
     * underlying {@link WorldInfo} (vanilla shared behaviour). Time-of-day is
     * always per-dim regardless of this flag — that is the sleep/day-night fix
     * and must work even when custom weather is disabled.
     */
    private final boolean weatherManaged;

    public ARDimensionWorldInfo(WorldInfo delegate, PlanetWeatherState weatherState,
                              Runnable dirtyMarker, boolean weatherManaged) {
        // Call the WorldInfo no-arg ctor — initialises the (never-read)
        // internal scaffolding (GameRules, dimensionData, customBossEvents)
        // to safe defaults. We deliberately do NOT seed from the delegate's
        // NBT (that path goes through FMLCommonHandler.getDataFixer() and is
        // brittle outside a fully-initialised Forge runtime, e.g. unit tests).
        // Every public getter is overridden to delegate, and cloneNBTCompound
        // is overridden too, so the wrapper's own super-state stays inert.
        // The deleted CustomDerivedWorldInfo used the same pattern in production.
        super();
        this.delegate = delegate;
        this.weatherState = weatherState;
        this.dirtyMarker = dirtyMarker;
        this.weatherManaged = weatherManaged;
        // Seed the per-dim clock from the delegate on first install so existing
        // saves (which shared the overworld clock) don't visibly jump.
        weatherState.seedTimeIfNeeded(delegate.getWorldTime(), delegate.getWorldTotalTime());
    }

    /**
     * Rounds a sleep wake-up to the next planetary dawn for a world whose day is
     * {@code rotationalPeriod} ticks long (vanilla hard-codes 24000). Result is
     * the smallest multiple of {@code rotationalPeriod} strictly after
     * {@code current}, i.e. {@code result % rotationalPeriod == 0} (dawn).
     *
     * <p>Used by {@code MixinWorldServer} at the sleep site so beds bring the
     * planet's morning instead of vanilla's 24000-rounded (often still-night)
     * time. See issue #66.</p>
     */
    public static long computeSleepWakeTime(long current, int rotationalPeriod) {
        if (rotationalPeriod <= 0) {
            rotationalPeriod = zmaster587.advancedRocketry.dimension.DimensionProperties.DEFAULT_ROTATIONAL_PERIOD;
        }
        long next = current + rotationalPeriod;
        return next - Math.floorMod(next, (long) rotationalPeriod);
    }

    /** Used by {@link PlanetWeatherManager#unwrap} to peel the wrapper off without losing state. */
    public WorldInfo getDelegate() {
        return delegate;
    }

    // ── Weather: backed by PlanetWeatherState ─────────────────────────────

    @Override
    public int getCleanWeatherTime() {
        return weatherManaged ? weatherState.getCleanWeatherTime() : delegate.getCleanWeatherTime();
    }

    @Override
    public void setCleanWeatherTime(int cleanWeatherTimeIn) {
        if (weatherManaged) {
            weatherState.setCleanWeatherTime(cleanWeatherTimeIn);
            dirtyMarker.run();
        } else {
            delegate.setCleanWeatherTime(cleanWeatherTimeIn);
        }
    }

    @Override
    public boolean isRaining() {
        return weatherManaged ? weatherState.isRaining() : delegate.isRaining();
    }

    @Override
    public void setRaining(boolean isRaining) {
        if (weatherManaged) {
            weatherState.setRaining(isRaining);
            dirtyMarker.run();
        } else {
            delegate.setRaining(isRaining);
        }
    }

    @Override
    public int getRainTime() {
        return weatherManaged ? weatherState.getRainTime() : delegate.getRainTime();
    }

    @Override
    public void setRainTime(int time) {
        if (weatherManaged) {
            weatherState.setRainTime(time);
            dirtyMarker.run();
        } else {
            delegate.setRainTime(time);
        }
    }

    @Override
    public boolean isThundering() {
        return weatherManaged ? weatherState.isThundering() : delegate.isThundering();
    }

    @Override
    public void setThundering(boolean thunderingIn) {
        if (weatherManaged) {
            weatherState.setThundering(thunderingIn);
            dirtyMarker.run();
        } else {
            delegate.setThundering(thunderingIn);
        }
    }

    @Override
    public int getThunderTime() {
        return weatherManaged ? weatherState.getThunderTime() : delegate.getThunderTime();
    }

    @Override
    public void setThunderTime(int time) {
        if (weatherManaged) {
            weatherState.setThunderTime(time);
            dirtyMarker.run();
        } else {
            delegate.setThunderTime(time);
        }
    }

    // ── Time-of-day + world age: per-dimension, always (not gated by weather) ─
    //
    // Vanilla DerivedWorldInfo delegates these to the overworld and no-ops the
    // setters, so AR planets shared the overworld clock and the sleep skip was
    // swallowed (issue #66). We own them in PlanetWeatherState instead.

    @Override
    public long getWorldTime() {
        return weatherState.getWorldTime();
    }

    @Override
    public void setWorldTime(long time) {
        weatherState.setWorldTime(time);
        dirtyMarker.run();
    }

    @Override
    public long getWorldTotalTime() {
        return weatherState.getWorldTotalTime();
    }

    @Override
    public void setWorldTotalTime(long time) {
        weatherState.setWorldTotalTime(time);
        dirtyMarker.run();
    }

    // ── Everything else: delegate ─────────────────────────────────────────
    //
    // The setters mostly no-op (vanilla DerivedWorldInfo does the same — a
    // secondary world is not supposed to mutate shared overworld state).
    // Where vanilla DerivedWorldInfo does write through (dimension data), we
    // forward to match its semantics.

    @Override
    public NBTTagCompound cloneNBTCompound(@Nullable NBTTagCompound nbt) {
        return delegate.cloneNBTCompound(nbt);
    }

    @Override
    public long getSeed() {
        return delegate.getSeed();
    }

    @Override
    public int getSpawnX() {
        return delegate.getSpawnX();
    }

    @Override
    public int getSpawnY() {
        return delegate.getSpawnY();
    }

    @Override
    public int getSpawnZ() {
        return delegate.getSpawnZ();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public long getSizeOnDisk() {
        return delegate.getSizeOnDisk();
    }

    @Override
    public NBTTagCompound getPlayerNBTTagCompound() {
        return delegate.getPlayerNBTTagCompound();
    }

    @Override
    public String getWorldName() {
        return delegate.getWorldName();
    }

    @Override
    public int getSaveVersion() {
        return delegate.getSaveVersion();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public long getLastTimePlayed() {
        return delegate.getLastTimePlayed();
    }

    @Override
    public GameType getGameType() {
        return delegate.getGameType();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void setSpawnX(int x) {
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void setSpawnY(int y) {
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void setSpawnZ(int z) {
    }

    @Override
    public void setSpawn(BlockPos spawnPoint) {
    }

    @Override
    public void setWorldName(String worldName) {
    }

    @Override
    public void setSaveVersion(int version) {
    }

    @Override
    public boolean isMapFeaturesEnabled() {
        return delegate.isMapFeaturesEnabled();
    }

    @Override
    public boolean isHardcoreModeEnabled() {
        return delegate.isHardcoreModeEnabled();
    }

    @Override
    public WorldType getTerrainType() {
        return delegate.getTerrainType();
    }

    @Override
    public void setTerrainType(WorldType type) {
    }

    @Override
    public boolean areCommandsAllowed() {
        return delegate.areCommandsAllowed();
    }

    @Override
    public void setAllowCommands(boolean allow) {
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public void setServerInitialized(boolean initializedIn) {
    }

    @Override
    public GameRules getGameRulesInstance() {
        return delegate.getGameRulesInstance();
    }

    @Override
    public EnumDifficulty getDifficulty() {
        return delegate.getDifficulty();
    }

    @Override
    public void setDifficulty(EnumDifficulty newDifficulty) {
    }

    @Override
    public boolean isDifficultyLocked() {
        return delegate.isDifficultyLocked();
    }

    @Override
    public void setDifficultyLocked(boolean locked) {
    }

    @Override
    @Deprecated
    public void setDimensionData(DimensionType dimensionIn, NBTTagCompound compound) {
        delegate.setDimensionData(dimensionIn, compound);
    }

    @Override
    @Deprecated
    public NBTTagCompound getDimensionData(DimensionType dimensionIn) {
        return delegate.getDimensionData(dimensionIn);
    }

    @Override
    public void setDimensionData(int dimensionID, NBTTagCompound compound) {
        delegate.setDimensionData(dimensionID, compound);
    }

    @Override
    public NBTTagCompound getDimensionData(int dimensionID) {
        return delegate.getDimensionData(dimensionID);
    }
}
