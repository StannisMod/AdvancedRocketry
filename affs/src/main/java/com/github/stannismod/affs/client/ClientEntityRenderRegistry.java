package com.github.stannismod.affs.client;

import com.github.stannismod.affs.entity.EntityLaserBolt;
import net.minecraftforge.fml.client.registry.RenderingRegistry;

public final class ClientEntityRenderRegistry {

    private ClientEntityRenderRegistry() {
    }

    public static void init() {
        RenderingRegistry.registerEntityRenderingHandler(EntityLaserBolt.class, RenderLaserBolt::new);
    }
}
