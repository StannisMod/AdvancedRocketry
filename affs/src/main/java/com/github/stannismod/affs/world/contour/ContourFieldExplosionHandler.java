package com.github.stannismod.affs.world.contour;

import com.github.stannismod.affs.te.TileEntityContourInjector;
import com.github.stannismod.affs.world.shield.IShieldNetworkNode;
import com.github.stannismod.affs.world.shield.ShieldNetworkRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import zmaster587.advancedRocketry.api.Constants;

import java.util.*;

@Mod.EventBusSubscriber(modid = Constants.modId)
public final class ContourFieldExplosionHandler {

    private ContourFieldExplosionHandler() {
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }

        Map<TileEntityContourInjector, Set<BlockPos>> injectorBlocks = new LinkedHashMap<>();
        for (IShieldNetworkNode node : ShieldNetworkRegistry.snapshot()) {
            if (!(node instanceof TileEntityContourInjector)) {
                continue;
            }
            TileEntityContourInjector injector = (TileEntityContourInjector) node;
            World nodeWorld = injector.getNodeWorld();
            if (nodeWorld == null || nodeWorld != world || !injector.isFieldActive()) {
                continue;
            }
            for (BlockPos pos : new ArrayList<>(event.getAffectedBlocks())) {
                if (!injector.intersectsField(new net.minecraft.util.math.AxisAlignedBB(pos))) {
                    continue;
                }
                injectorBlocks.computeIfAbsent(injector, ignored -> new HashSet<>()).add(pos);
            }
        }

        if (injectorBlocks.isEmpty()) {
            return;
        }

        for (Map.Entry<TileEntityContourInjector, Set<BlockPos>> entry : injectorBlocks.entrySet()) {
            TileEntityContourInjector injector = entry.getKey();
            if (!injector.tryAbsorbExplosionImpact(world, event.getExplosion(), entry.getValue())) {
                continue;
            }
            filterAffectedBlocks(event, injector);
            filterAffectedEntities(event, injector);
        }
    }

    private static void filterAffectedBlocks(ExplosionEvent.Detonate event, TileEntityContourInjector injector) {
        Iterator<BlockPos> iterator = event.getAffectedBlocks().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (injector.ownsFieldBlock(pos)) {
                iterator.remove();
            }
        }
    }

    private static void filterAffectedEntities(ExplosionEvent.Detonate event, TileEntityContourInjector injector) {
        Iterator<Entity> iterator = event.getAffectedEntities().iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (injector.intersectsField(entity.getEntityBoundingBox())) {
                iterator.remove();
            }
        }
    }
}
