package zmaster587.advancedRocketry.integration.dataloaders;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.tile.TileGuidanceComputer;
import zmaster587.advancedRocketry.util.StationLandingLocation;
import zmaster587.libVulpes.util.Vector3F;

public class RocketDataLoaderServer extends RocketDataLoader {
    EntityRocket rocket;

    public RocketDataLoaderServer(EntityRocket rocket) {
        this.rocket = rocket;
    }

    @Override
    protected EntityRocket getRocket() {
        return rocket;
    }

    @Override
    public ItemStack getGuidanceComputer() {
        TileGuidanceComputer gc = rocket.storage.getGuidanceComputer();
        return gc == null ? null : gc.getStackInSlot(0);
    }

    @Override
    public StationLandingLocation getLandingLocation() {
        TileGuidanceComputer gc = rocket.storage.getGuidanceComputer();
        if (gc != null) {
            int currentDim = rocket.world.provider.getDimension();
            int destDim = rocket.storage.getDestinationDimId(currentDim, (int) rocket.posX, (int) rocket.posZ);

            Vector3F<Float> loc = rocket.storage.getDestinationCoordinates(destDim, false);

            if (destDim == ARConfiguration.getCurrentConfig().spaceDimId) {
                if (loc != null) {
                    ISpaceObject station = SpaceObjectManager.getSpaceManager()
                            .getSpaceStationFromBlockCoords(new BlockPos(loc.x, loc.y, loc.z));

                    if (station != null) {
                        return gc.getLandingLocation(station.getId());
                    }
                }
            }
        }

        return null;
    }

    @Override
    public String getDestinationName() {
        TileGuidanceComputer gc = rocket.storage.getGuidanceComputer();
        if (gc == null) {
            return null;
        }
        int currentDim = rocket.world.provider.getDimension();
        int destDim = rocket.storage.getDestinationDimId(currentDim, (int) rocket.posX, (int) rocket.posZ);
        return gc.getDestinationName(destDim);
    }
}
