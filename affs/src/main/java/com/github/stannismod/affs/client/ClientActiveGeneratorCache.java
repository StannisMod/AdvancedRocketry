package com.github.stannismod.affs.client;

import com.github.stannismod.affs.network.PacketSyncActiveGenerators;
import com.github.stannismod.affs.world.FieldSource;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientActiveGeneratorCache {

    private static final Map<Integer, List<PacketSyncActiveGenerators.Entry>> GENERATORS_BY_DIMENSION = new ConcurrentHashMap<>();

    private ClientActiveGeneratorCache() {
    }

    public static void replace(int dimension, List<PacketSyncActiveGenerators.Entry> entries) {
        GENERATORS_BY_DIMENSION.put(dimension, Collections.unmodifiableList(new ArrayList<>(entries)));
    }

    public static List<? extends FieldSource> get(World world) {
        if (world == null) {
            return Collections.emptyList();
        }
        List<PacketSyncActiveGenerators.Entry> entries = GENERATORS_BY_DIMENSION.get(world.provider.getDimension());
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        return entries;
    }

    public static void clearAll() {
        GENERATORS_BY_DIMENSION.clear();
    }
}
