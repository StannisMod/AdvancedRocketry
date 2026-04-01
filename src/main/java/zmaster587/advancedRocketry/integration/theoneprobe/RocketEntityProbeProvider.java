package zmaster587.advancedRocketry.integration.theoneprobe;

import mcjty.theoneprobe.api.IProbeHitEntityData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoEntityProvider;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.integration.dataloaders.AbstractDataContext;
import zmaster587.advancedRocketry.integration.dataloaders.RocketDataLoaderServer;

public class RocketEntityProbeProvider implements IProbeInfoEntityProvider {

    @Override
    public String getID() {
        return "advancedrocketry:rocket_entity";
    }

    @Override
    public void addProbeEntityInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world, Entity entity, IProbeHitEntityData data) {
        if (!(entity instanceof EntityRocket)) {
            return;
        }

        EntityRocket rocket = (EntityRocket) entity;

        AbstractDataContext context = new TOPDataContext(probeInfo);
        RocketDataLoaderServer loader = new RocketDataLoaderServer(rocket);
        loader.addGuidanceInfo(context);

        if (mode == ProbeMode.EXTENDED) {
            loader.addFuelInfo(context);
        }
    }
}