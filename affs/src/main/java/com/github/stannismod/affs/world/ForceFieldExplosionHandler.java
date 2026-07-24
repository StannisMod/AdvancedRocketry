package com.github.stannismod.affs.world;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

@Mod.EventBusSubscriber(modid = AdvancedForceFieldSystem.MODID)
public final class ForceFieldExplosionHandler {

    private ForceFieldExplosionHandler() {
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }

        List<TileEntityFieldGenerator> generators = FieldSurfaceMath.getActiveGenerators(world);
        if (generators.isEmpty()) {
            return;
        }

        for (TileEntityFieldGenerator generator : generators) {
            if (generator == null || generator.isInvalid() || !generator.isFieldPowered()) {
                continue;
            }
            if (!generator.tryAbsorbExplosionImpact(world, event.getExplosion(), event.getAffectedBlocks())) {
                continue;
            }
            filterAffectedBlocks(event, generator);
            filterAffectedEntities(event, generator);
        }
    }

    private static void filterAffectedBlocks(ExplosionEvent.Detonate event, TileEntityFieldGenerator generator) {
        Iterator<BlockPos> iterator = event.getAffectedBlocks().iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (generator.protects(pos)) {
                iterator.remove();
            }
        }
    }

    private static void filterAffectedEntities(ExplosionEvent.Detonate event, TileEntityFieldGenerator generator) {
        Iterator<Entity> iterator = event.getAffectedEntities().iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (FieldSurfaceMath.intersectsCompositeShell(Collections.singletonList(generator), entity.getEntityBoundingBox())) {
                iterator.remove();
            }
        }
    }
}
