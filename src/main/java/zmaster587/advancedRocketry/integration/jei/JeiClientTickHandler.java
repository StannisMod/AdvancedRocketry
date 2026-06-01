package zmaster587.advancedRocketry.integration.jei;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class JeiClientTickHandler {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ARPlugin.hasQueuedGasGiantRefresh()) return;

        ARPlugin.tryApplyQueuedGasGiantRefresh();
    }
}