package zmaster587.advancedRocketry.test.integration;

import net.minecraft.init.Biomes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeManager.BiomeEntry;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * §7.4 crater-biome NBT seam — save/wire contract for
 * {@link DimensionProperties#getCraterBiomeWeights()}.
 *
 * <p>The generation biome list {@code allowedBiomes} is persisted by registry
 * NAME ({@code biomeNames}) precisely so it survives biome-ID drift across
 * modpack/version changes; the crater biome list was left on integer IDs. That
 * split (findings C043/C044) means a saved crater biome silently remaps to a
 * different biome on ID drift, or resolves to {@code null} — and the reader had
 * no null guard (so a null {@code BiomeEntry.biome} reaches
 * {@code MapGenCrater.shouldCraterSpawn} and NPEs at world-gen) and no
 * weight-length guard (a short {@code craterWeights} threw
 * {@code ArrayIndexOutOfBoundsException} on load).</p>
 *
 * <p>These tests pin the corrected contract: crater biomes round-trip by
 * registry name ({@code craterBiomeNames}, drift-safe), the legacy integer
 * format still loads for old saves but skips unresolvable IDs instead of
 * poisoning the list with a null biome, and a truncated legacy weight array
 * does not crash the load.</p>
 */
public class CraterBiomeNbtRoundTripTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** starId is package-private; production saves only reference known stars. */
    private static void setStar(DimensionProperties props, int starId) {
        try {
            Field f = DimensionProperties.class.getDeclaredField("starId");
            f.setAccessible(true);
            f.setInt(props, starId);
        } catch (Exception e) {
            throw new AssertionError("reflection failed setting starId", e);
        }
    }

    private static DimensionProperties craterPlanet(int id) {
        DimensionProperties props = new DimensionProperties(id, "CraterWorld");
        setStar(props, 0); // Sol — the only star MinecraftBootstrap registers
        return props;
    }

    /**
     * Contract: crater biomes are persisted by registry NAME (like
     * {@code allowedBiomes}), so the save survives biome-ID drift, and the list
     * round-trips with biome identity + weight preserved.
     */
    @Test
    public void craterBiomesRoundTripByRegistryName() {
        DimensionProperties original = craterPlanet(9601);
        original.addCraterBiomeWeight(Biomes.DESERT, 100);
        original.addCraterBiomeWeight(Biomes.ICE_PLAINS, 60);

        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        // Drift-safe format: names, not integer IDs.
        assertTrue("crater biomes must be saved by registry name (craterBiomeNames)",
                nbt.hasKey("craterBiomeNames"));

        DimensionProperties restored = DimensionProperties.createFromNBT(9601, nbt);

        List<BiomeEntry> entries = restored.getCraterBiomeWeights();
        assertEquals("both crater biomes must round-trip", 2, entries.size());
        assertEquals(Biomes.DESERT, entries.get(0).biome);
        assertEquals(100, entries.get(0).itemWeight);
        assertEquals(Biomes.ICE_PLAINS, entries.get(1).biome);
        assertEquals(60, entries.get(1).itemWeight);
    }

    /**
     * Back-compat: an old save with the legacy integer {@code craterBiomes}
     * array still loads. An unresolvable ID (biome/mod removed) must be SKIPPED,
     * not stored as a {@code BiomeEntry} with a null biome (which NPEs at
     * world-gen).
     */
    @Test
    public void legacyIntegerCraterBiomesSkipUnresolvableId() {
        NBTTagCompound nbt = new NBTTagCompound();
        int desertId = Biome.getIdForBiome(Biomes.DESERT);
        // 30000 resolves to no biome — the drift/removal case.
        nbt.setIntArray("craterBiomes", new int[]{desertId, 30000});
        nbt.setIntArray("craterWeights", new int[]{100, 60});

        DimensionProperties restored = DimensionProperties.createFromNBT(9602, nbt);

        List<BiomeEntry> entries = restored.getCraterBiomeWeights();
        assertEquals("unresolvable legacy crater ID must be skipped, not kept as null",
                1, entries.size());
        BiomeEntry only = entries.get(0);
        assertNotNull("surviving crater entry must have a non-null biome", only.biome);
        assertEquals(Biomes.DESERT, only.biome);
        for (BiomeEntry e : entries) {
            assertFalse("no crater entry may carry a null biome", e.biome == null);
        }
    }

    /**
     * Back-compat robustness: a legacy save whose {@code craterWeights} is
     * shorter than {@code craterBiomes} (truncated / hand-edited) must load
     * without an {@code ArrayIndexOutOfBoundsException}.
     */
    @Test
    public void legacyCraterWeightsShorterThanBiomesDoesNotCrash() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setIntArray("craterBiomes",
                new int[]{Biome.getIdForBiome(Biomes.DESERT), Biome.getIdForBiome(Biomes.PLAINS)});
        nbt.setIntArray("craterWeights", new int[]{100}); // one short

        DimensionProperties restored = DimensionProperties.createFromNBT(9603, nbt);

        List<BiomeEntry> entries = restored.getCraterBiomeWeights();
        assertEquals("both legacy crater biomes must load despite the short weight array",
                2, entries.size());
        for (BiomeEntry e : entries) {
            assertNotNull("no crater entry may carry a null biome", e.biome);
        }
    }
}
