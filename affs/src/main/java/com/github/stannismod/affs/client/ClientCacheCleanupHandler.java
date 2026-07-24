package com.github.stannismod.affs.client;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = AdvancedForceFieldSystem.MODID, value = Side.CLIENT)
public final class ClientCacheCleanupHandler {

    private ClientCacheCleanupHandler() {
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world != null && world.isRemote) {
            clearAllCaches();
        }
    }

    @SubscribeEvent
    public static void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        clearAllCaches();
    }

    private static void clearAllCaches() {
        ClientActiveGeneratorCache.clearAll();
        ClientForceFieldRenderCache.clearAll();
        ClientFieldTouchEffectCache.clearAll();
    }
}
