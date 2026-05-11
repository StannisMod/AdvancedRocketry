package zmaster587.advancedRocketry.client.render;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import zmaster587.advancedRocketry.entity.fx.InverseTrailFx;
import zmaster587.advancedRocketry.entity.fx.RocketFx;

import java.util.ArrayList;
import java.util.List;

public class DelayedParticleRenderingEventHandler {
    public static List<RocketFx> RocketFxParticles = new ArrayList<>();
    public static List<InverseTrailFx> TrailFxParticles = new ArrayList<>();


    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        InverseTrailFx.renderAll(TrailFxParticles);
        RocketFx.renderAll(RocketFxParticles);
    }
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {

        for (RocketFx p: RocketFxParticles){
            p.onUpdate2();
        }
        for (InverseTrailFx p: TrailFxParticles){
            p.onUpdate2();
        }

        RocketFxParticles.removeIf(particle -> !particle.isAlive());
        TrailFxParticles.removeIf(particle -> !particle.isAlive());

    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            return;
        }
        RocketFxParticles.removeIf(particle -> particle.getParticleWorld() == event.getWorld());
        TrailFxParticles.removeIf(particle -> particle.getParticleWorld() == event.getWorld());
    }
}
