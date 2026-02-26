package zmaster587.advancedRocketry.world;

import com.google.common.base.Preconditions;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.WorldInfo;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class WorldInfoSavedData extends WorldSavedData {

    public static final String NAME = "WorldInfoSavedData";

    @Nullable
    private WorldInfo worldInfo;
    @Nullable
    private WorldInfo readInfo;

    public WorldInfoSavedData(String name) {
        super(name);
    }

    public WorldInfoSavedData(CustomDerivedWorldInfo worldInfo) {
        this(NAME);
        this.worldInfo = worldInfo;
        this.markDirty();
    }

    @Override
    public void readFromNBT(@Nonnull NBTTagCompound nbt) {
        readInfo = new WorldInfo(nbt);
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(@Nonnull NBTTagCompound compound) {
        Preconditions.checkNotNull(worldInfo);

        return addWeatherData(worldInfo, compound);
    }

    public static NBTTagCompound addWeatherData(@Nonnull WorldInfo info, @Nonnull NBTTagCompound compound) {
        compound.setInteger("clearWeatherTime", info.getCleanWeatherTime());
        compound.setInteger("rainTime", info.getRainTime());
        compound.setInteger("thunderTime", info.getThunderTime());
        compound.setBoolean("raining", info.isRaining());
        compound.setBoolean("thundering", info.isThundering());
        return compound;
    }

    public void updateWorldInfo(WorldInfo target) {
        if (readInfo == null) {
            // WorldSavedData not loaded from NBT
            return;
        }

        this.worldInfo = target;

        target.setCleanWeatherTime(readInfo.getCleanWeatherTime());
        target.setRaining(readInfo.isRaining());
        target.setRainTime(readInfo.getRainTime());
        target.setThundering(readInfo.isThundering());
        target.setThunderTime(readInfo.getThunderTime());
    }
}
