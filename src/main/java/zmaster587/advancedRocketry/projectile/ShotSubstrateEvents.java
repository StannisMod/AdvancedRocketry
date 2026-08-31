package zmaster587.advancedRocketry.projectile;

import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import zmaster587.advancedRocketry.api.Constants;

/**
 * When the substrate runs, and nothing else. Every decision it makes lives in {@link ShotSubstrate}
 * and every piece of state it touches belongs to a world's {@link ShotRegistry}; this class answers
 * only "which event, and when".
 *
 * <p>Phase END, so a shot is stepped against the world as the tick leaves it: a shell raised this
 * tick is up when the round arrives, and a ship that moved this tick is tested where it now is
 * rather than where it was. There is no unload handler because there is nothing to clean up — the
 * shots belong to the world's own saved data and go where it goes.</p>
 */
@Mod.EventBusSubscriber(modid = Constants.modId)
public final class ShotSubstrateEvents {

    private ShotSubstrateEvents() {
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        World world = event.world;
        if (world == null || world.isRemote) {
            return;
        }
        ShotSubstrate.tick(world);
    }
}
