package zmaster587.advancedRocketry.mission;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.entity.EntityStationDeployedRocket;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.util.HashedBlockPosition;

import java.util.LinkedList;

public class MissionGasCollection extends MissionResourceCollection {

    private Fluid gasFluid;

    public MissionGasCollection() {
        super();
    }

    public MissionGasCollection(long l, EntityRocket entityRocket, LinkedList<IInfrastructure> connectedInfrastructure, Fluid gasFluid) {
        super((long) (l * ARConfiguration.getCurrentConfig().gasCollectionMult), entityRocket, connectedInfrastructure);
        this.gasFluid = gasFluid;
    }

    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("mission.gascollection.name");
    }

    @Override
    public void onMissionComplete() {

        Object ipObj = rocketStats.getStatTag("intakePower");
        int ip = (ipObj instanceof Number) ? Math.max(0, ((Number) ipObj).intValue()) : 0;

        if (ip > 0 && gasFluid != null) {
            final Fluid type = gasFluid;

            // Planned harvest written by the rocket at launch
            final boolean hasPlanned = missionPersistantNBT.hasKey("plannedHarvestMb");
            final long planned = hasPlanned ? Math.max(0L, missionPersistantNBT.getLong("plannedHarvestMb")) : -1L;

            // Config
            final boolean infinite = ARConfiguration.getCurrentConfig().gasHarvestInfinite;
            final double mult = Math.max(0.0, ARConfiguration.getCurrentConfig().gasHarvestAmountMultiplier);
            final long basePerMission = 64_000L; // mB

            long remaining;
            if (hasPlanned) {
                remaining = Math.min(Integer.MAX_VALUE, planned);
            } else {
                remaining = infinite
                    ? Integer.MAX_VALUE
                    : Math.min(Integer.MAX_VALUE, Math.round(basePerMission * mult));
            }



            for (TileEntity tile : this.rocketStorage.getFluidTiles()) {
                net.minecraftforge.fluids.capability.IFluidHandler handler =
                        tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
                if (handler == null) continue;

                if (remaining <= 0) break;

                int want = (int)Math.min(Integer.MAX_VALUE, remaining);
                int couldTake = handler.fill(new FluidStack(type, want), false); // simulate
                if (couldTake > 0) {
                    int filled = handler.fill(new FluidStack(type, couldTake), true);
                    remaining -= Math.max(0, filled);
                }
            }
        }
      


        World world = DimensionManager.getWorld(launchDimension);
        if (world == null) {
            DimensionManager.initDimension(launchDimension);
            world = DimensionManager.getWorld(launchDimension);
        }

        EntityStationDeployedRocket rocket = new EntityStationDeployedRocket(world, rocketStorage, rocketStats, x, y, z);

        FuelRegistry.FuelType fuelType = rocket.getRocketFuelType();
        //System.out.println("Fuel:"+ rocketStats.getFuelAmount(fuelType));
        if (fuelType != null) {
            rocket.setFuelAmount(fuelType, Math.max(rocketStats.getFuelAmount(fuelType)-1000,0));
            if (fuelType == FuelRegistry.FuelType.LIQUID_BIPROPELLANT)
                rocket.setFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER, Math.max(rocketStats.getFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER)-1000,0));
        }
        rocket.readMissionPersistentNBT(missionPersistantNBT);

        EnumFacing dir = rocket.forwardDirection;
        rocket.forceSpawn = true;

        rocket.setPosition(dir.getFrontOffsetX() * 64d + rocket.launchLocation.x + (rocketStorage.getSizeX() % 2 == 0 ? 0 : 0.5d), y, dir.getFrontOffsetZ() * 64d + rocket.launchLocation.z + (rocketStorage.getSizeZ() % 2 == 0 ? 0 : 0.5d));
        world.spawnEntity(rocket);
        rocket.setInOrbit(true);
        rocket.setInFlight(true);
        //rocket.motionY = -1.0;

        for (HashedBlockPosition i : infrastructureCoords) {
            TileEntity tile = world.getTileEntity(new BlockPos(i.x, i.y, i.z));
            if (tile instanceof IInfrastructure) {
                ((IInfrastructure) tile).unlinkMission();
                rocket.linkInfrastructure(((IInfrastructure) tile));
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        if (gasFluid != null) {
            nbt.setString("gas", gasFluid.getName());
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        String name = nbt.getString("gas");
        gasFluid = name != null && !name.isEmpty() ? FluidRegistry.getFluid(name) : null;
    }
}
