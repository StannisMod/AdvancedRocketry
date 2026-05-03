package zmaster587.advancedRocketry.mission;


import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.api.IMission;
import zmaster587.advancedRocketry.api.StatsRocket;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.util.StorageChunk;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.util.HashedBlockPosition;

import java.util.LinkedList;


public abstract class MissionResourceCollection extends SatelliteBase implements IMission {


    //stores the coordinates of infrastructures, used for when the world loads/saves
    protected LinkedList<HashedBlockPosition> infrastructureCoords;
    long startWorldTime;
    double x, y, z;
    long duration;
    int launchDimension;
    StorageChunk rocketStorage;
    StatsRocket rocketStats;
    int worldId;
    NBTTagCompound missionPersistantNBT;


    // If world loads with an invalid RocketStorage (rocket stored in mission) remove it cleanly.
    boolean invalidRocketStorage;
    private int completionCheckTimer; // Don't check every tick if a mission should complete
    private static final int MISSION_COMPLETION_TICKS = 60;

    public MissionResourceCollection() {
        infrastructureCoords = new LinkedList<>();
        missionPersistantNBT = new NBTTagCompound();
    }

    public MissionResourceCollection(long duration, EntityRocket entity, LinkedList<IInfrastructure> infrastructureCoords) {
        super();
        missionPersistantNBT = new NBTTagCompound();
        entity.writeMissionPersistentNBT(missionPersistantNBT);

        satelliteProperties.setId(zmaster587.advancedRocketry.dimension.DimensionManager.getInstance().getNextSatelliteId());

        startWorldTime = DimensionManager.getWorld(0).getTotalWorldTime();
        this.duration = duration;
        if (this.duration <= 0L) {
            this.duration = 1L; // at least 1 tick
        }

        this.launchDimension = entity.world.provider.getDimension();
        rocketStorage = entity.storage;
        rocketStats = entity.stats;
        x = entity.posX;
        y = entity.posY;
        z = entity.posZ;
        worldId = entity.world.provider.getDimension();

        this.infrastructureCoords = new LinkedList<>();

        for (IInfrastructure tile : infrastructureCoords)
            this.infrastructureCoords.add(new HashedBlockPosition(((TileEntity) tile).getPos()));
    }

    public long getPlannedHarvestMbOrDefault() {
        if (missionPersistantNBT != null && missionPersistantNBT.hasKey("plannedHarvestMb")) {
            return Math.max(0L, missionPersistantNBT.getLong("plannedHarvestMb"));
        }
        return -1L; // means "unknown/not provided"
    }    

    @Override
    public double getProgress(World world) {
        if (duration <= 0L) return 1.0d;        
        return Math.max((AdvancedRocketry.proxy.getWorldTimeUniversal(0) - startWorldTime) / (double) duration, 0);
    }

    @Override
    public int getTimeRemainingInSeconds() {
        return (int) Math.max(((duration - AdvancedRocketry.proxy.getWorldTimeUniversal(0) + startWorldTime) / 20), 0);
    }

    @Override
    public String getInfo(World world) {
        return null;
    }

    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("mission.asteroidmining.name");
    }

    @Override
    public boolean performAction(EntityPlayer player, World world, BlockPos pos) {
        return false;
    }

    @Override
    public double failureChance() {
        return 0;
    }

    @Override
    public boolean canTick() {
        return true;
    }

    @Override
    public abstract void onMissionComplete();

    @Override
    public void tickEntity() {
        if (invalidRocketStorage) {
            setDead();
            return;
        }

        if (++completionCheckTimer < MISSION_COMPLETION_TICKS) {
            return;
        }
        completionCheckTimer = 0;

        World overworld = DimensionManager.getWorld(0);
        if (overworld == null || overworld.isRemote) {
            return;
        }

        World launchWorld = DimensionManager.getWorld(launchDimension);
        if (launchWorld == null) {
            return;
        }

        if (getProgress(overworld) >= 1) {
            setDead();
            onMissionComplete();
        }
    }

    private void abandonInvalidMission(String reason, Throwable cause) {
        invalidRocketStorage = true;

        AdvancedRocketry.logger.error(
                "Removed corrupt Advanced Rocketry mission: missionClass={} satelliteId={} reason={} launchDim={} startDim={} launchPos={},{},{} durationTicks={} elapsedTicks={}",
                this.getClass().getName(),
                this.getId(),
                reason,
                launchDimension,
                worldId,
                x, y, z,
                duration,
                Math.max(0L, AdvancedRocketry.proxy.getWorldTimeUniversal(0) - startWorldTime),
                cause
        );

        try {
            World world = DimensionManager.getWorld(launchDimension);
            if (world != null && infrastructureCoords != null) {
                for (HashedBlockPosition inf : infrastructureCoords) {
                    TileEntity tile = world.getTileEntity(new BlockPos(inf.x, inf.y, inf.z));
                    if (tile instanceof IInfrastructure) {
                        ((IInfrastructure) tile).unlinkMission();
                    }
                }
            }
        } catch (Throwable t) {
            AdvancedRocketry.logger.warn(
                    "Failed to unlink infrastructure while removing corrupt mission {}",
                    this.getId(),
                    t
            );
        }

        setDead();
    }

    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        nbt.setTag("persist", missionPersistantNBT);

        NBTTagCompound nbt2 = new NBTTagCompound();
        if (rocketStats != null) {
            rocketStats.writeToNBT(nbt2);}
        nbt.setTag("rocketStats", nbt2);

        nbt2 = new NBTTagCompound();
        if (!invalidRocketStorage && rocketStorage != null) {
            rocketStorage.writeToNBT(nbt2);}
        nbt.setTag("rocketStorage", nbt2);

        nbt.setDouble("launchPosX", x);
        nbt.setDouble("launchPosY", y);
        nbt.setDouble("launchPosZ", z);

        nbt.setLong("startWorldTime", startWorldTime);
        nbt.setLong("duration", duration);
        nbt.setInteger("startDimid", worldId);
        nbt.setInteger("launchDim", launchDimension);

        NBTTagList itemList = new NBTTagList();
        for (HashedBlockPosition inf : infrastructureCoords) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setIntArray("loc", new int[]{inf.x, inf.y, inf.z});
            itemList.appendTag(tag);

        }
        nbt.setTag("infrastructure", itemList);
    }

    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        missionPersistantNBT = nbt.hasKey("persist") ? nbt.getCompoundTag("persist") : new NBTTagCompound();


        rocketStats = new StatsRocket();
        rocketStats.readFromNBT(nbt.getCompoundTag("rocketStats"));

        x = nbt.getDouble("launchPosX");
        y = nbt.getDouble("launchPosY");
        z = nbt.getDouble("launchPosZ");

        startWorldTime = nbt.getLong("startWorldTime");
        duration = nbt.getLong("duration");
        worldId = nbt.getInteger("startDimid");
        launchDimension = nbt.getInteger("launchDim");

        NBTTagList tagList = nbt.getTagList("infrastructure", 10);
        infrastructureCoords.clear();

        for (int i = 0; i < tagList.tagCount(); i++) {
            int[] coords = tagList.getCompoundTagAt(i).getIntArray("loc");
            if (coords.length >= 3) {
                infrastructureCoords.add(new HashedBlockPosition(coords[0], coords[1], coords[2]));
            }
        }
        rocketStorage = new StorageChunk();
        NBTTagCompound storageNbt = nbt.getCompoundTag("rocketStorage");

        try {
            rocketStorage.readFromNBT(storageNbt);
        } catch (Throwable e) {
            rocketStorage = new StorageChunk();
            abandonInvalidMission("rocketStorage failed to deserialize", e);
        }
    }

    @Override
    public long getMissionId() {
        return getId();
    }

    @Override
    public int getOriginatingDimension() {
        return worldId;
    }

    @Override
    public void unlinkInfrastructure(IInfrastructure tile) {
        HashedBlockPosition pos = new HashedBlockPosition(((TileEntity) tile).getPos());
        infrastructureCoords.remove(pos);
    }
}
