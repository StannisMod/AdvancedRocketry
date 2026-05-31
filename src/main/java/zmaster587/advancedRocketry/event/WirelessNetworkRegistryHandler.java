package zmaster587.advancedRocketry.event;

import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import zmaster587.advancedRocketry.wirelessdata.NetworkRegistry;

public class WirelessNetworkRegistryHandler {

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.getWorld().isRemote && event.getWorld().provider.getDimension() == 0) {
            NetworkRegistry.registerDataNetwork(event.getWorld());
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote && event.getWorld().provider.getDimension() == 0) {
            NetworkRegistry.clear();
        }
    }
}