package zmaster587.advancedRocketry.event;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import zmaster587.advancedRocketry.wirelessdata.HandlerDataNetwork;
import zmaster587.advancedRocketry.wirelessdata.NetworkRegistry;

public class WirelessDataTickHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        HandlerDataNetwork nets = NetworkRegistry.dataNetwork();
        if (nets != null) {
            nets.tickAllNetworks();
        }
    }
}