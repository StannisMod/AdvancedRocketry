package zmaster587.advancedRocketry.subsystem.heat;

import net.minecraft.world.World;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;

/**
 * The second half of "a machine is not a network node": a chunk arriving can put a heat-making
 * machine next to a loop that is already built and already knows its neighbours.
 * <p>
 * The loop block's own neighbour notification covers everything that is PLACED, whoever placed it.
 * It does not cover a machine that was already there and simply came back with its chunk, because
 * loading a chunk notifies nobody. So a chunk load invalidates the cached neighbours, and the loop
 * works them out again on its next tick.
 * <p>
 * This costs nothing where nothing uses it: with no heat node registered anywhere the domain is
 * never ticked, so the flag this sets is never read. Where it does apply, a rebuild is coalesced to
 * at most one per tick however many chunks arrive.
 */
@Mod.EventBusSubscriber(modid = Constants.modId)
public final class HeatNetworkWatcher {

    private HeatNetworkWatcher() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        SubsystemNetworkManager.markDirty(HeatNetwork.DOMAIN, world);
    }
}
