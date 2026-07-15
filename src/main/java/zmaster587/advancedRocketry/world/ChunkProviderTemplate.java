package zmaster587.advancedRocketry.world;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biome.SpawnListEntry;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.IChunkGenerator;

import java.util.List;

/**
 * Void gap-filler generator for TEMPLATE planets. It produces empty chunks so that any region files
 * pre-dropped on disk load verbatim - Minecraft only invokes {@link #generateChunk} for chunks that are
 * absent from disk, so imported chunks are never overwritten and chunks outside the template become air.
 * Biomes for the void gaps come from the world's own biome provider so the per-dimension features that
 * key off biome stay consistent with the rest of the planet.
 */
public class ChunkProviderTemplate implements IChunkGenerator {

    private final World world;

    public ChunkProviderTemplate(World world) {
        this.world = world;
    }

    @Override
    public Chunk generateChunk(int x, int z) {
        Chunk chunk = new Chunk(this.world, new ChunkPrimer(), x, z);
        byte[] biomeArray = chunk.getBiomeArray();
        Biome[] biomes = this.world.getBiomeProvider().getBiomes(new Biome[biomeArray.length], x << 4, z << 4, 16, 16);
        for (int i = 0; i < biomeArray.length; i++) {
            biomeArray[i] = (byte) Biome.getIdForBiome(biomes[i]);
        }
        chunk.generateSkylightMap();
        return chunk;
    }

    @Override
    public void populate(int x, int z) {
    }

    @Override
    public boolean generateStructures(Chunk chunkIn, int x, int z) {
        return false;
    }

    @Override
    public List<SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos) {
        return null;
    }

    @Override
    public void recreateStructures(Chunk chunkIn, int x, int z) {
    }

    @Override
    public BlockPos getNearestStructurePos(World worldIn, String structureName, BlockPos position, boolean findUnexplored) {
        return null;
    }

    @Override
    public boolean isInsideStructure(World worldIn, String structureName, BlockPos pos) {
        return false;
    }
}
