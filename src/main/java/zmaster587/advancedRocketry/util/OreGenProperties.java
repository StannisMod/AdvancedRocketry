package zmaster587.advancedRocketry.util;

import net.minecraft.block.state.IBlockState;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.dimension.DimensionProperties.AtmosphereTypes;
import zmaster587.advancedRocketry.dimension.DimensionProperties.Temps;

import java.util.LinkedList;
import java.util.List;

public class OreGenProperties {

    /**
     * Array of properties for [pressure][temperature]
     *
     * @see DimensionProperties.AtmosphereTypes
     * @see DimensionProperties.Temps
     */
    private static OreGenProperties[][] oreGenPropertyMap = new OreGenProperties[DimensionProperties.AtmosphereTypes.values().length][DimensionProperties.Temps.values().length];
    private List<OreEntry> oreEntries;

    public OreGenProperties() {
        oreEntries = new LinkedList<>();
    }

    /**
     * Sets any planet with temperature temp to use these properties regardless of pressure
     *
     * @param temp       Temperature to set
     * @param properties
     */
    public static void setOresForTemperature(Temps temp, OreGenProperties properties) {
        for (int i = 0; i < AtmosphereTypes.values().length; i++)
            oreGenPropertyMap[i][temp.ordinal()] = properties;
    }

    public static void setOresForPressure(AtmosphereTypes atmType, OreGenProperties properties) {
        for (int i = 0; i < Temps.values().length; i++)
            oreGenPropertyMap[atmType.ordinal()][i] = properties;
    }

    public static void setOresForPressureAndTemp(AtmosphereTypes atmType, Temps temp, OreGenProperties properties) {
        oreGenPropertyMap[atmType.ordinal()][temp.ordinal()] = properties;
    }

    public static OreGenProperties getOresForPressure(AtmosphereTypes atmType, Temps temp) {
        return oreGenPropertyMap[atmType.ordinal()][temp.ordinal()];
    }

    public void addEntry(IBlockState state, int minHeight, int maxHeight, int clumpSize, int chancePerChunk) {
        oreEntries.add(new OreEntry(state, minHeight, maxHeight, clumpSize, chancePerChunk));
    }

    public List<OreEntry> getOreEntries() {
        return oreEntries;
    }

    /**
     * A COPY of this table with the metallic entries scaled by {@code factor} — the parent star's metal
     * content applied to the palette its planet's climate earned.
     *
     * <p>A copy and never a mutation: a table from {@link #getOresForPressure} is shared by every world
     * in that climate cell, so scaling it in place would give one planet's star the ore of all of them.
     * Non-metallic entries (coal, redstone, lapis, diamond, emerald, quartz — and anything the ore
     * dictionary does not call an ore at all) pass through untouched: a metal-poor disk yields the same
     * SORTS of rock with less metal in them, which is what the physics actually says.</p>
     *
     * <p>Both the clump size and the per-chunk chance are scaled, so the effect is on how much metal a
     * world holds rather than on where it hides; each stays at least 1 so a scaling can thin a deposit
     * but never delete it.</p>
     */
    public OreGenProperties withMetalsScaled(double factor) {
        OreGenProperties copy = new OreGenProperties();
        for (OreEntry e : oreEntries) {
            boolean metal = isMetallic(e.getBlockState());
            double f = metal ? Math.max(0.05d, factor) : 1d;
            copy.addEntry(e.getBlockState(), e.getMinHeight(), e.getMaxHeight(),
                    scale(e.getClumpSize(), f), scale(e.getChancePerChunk(), f));
        }
        return copy;
    }

    private static int scale(int value, double factor) {
        return Math.max(1, (int) Math.round(value * factor));
    }

    /** Ore-dictionary names that begin with {@code ore} but are not metals. */
    private static final java.util.Set<String> NON_METAL_ORES = new java.util.HashSet<>(
            java.util.Arrays.asList("orecoal", "oreredstone", "orelapis", "orediamond", "oreemerald",
                    "orequartz", "oresulfur", "oresaltpeter", "orenitre", "oreapatite", "orecertusquartz",
                    "orecharcoal", "oreamber", "oreobsidian"));

    /**
     * Whether a block is a METAL ore, as far as the ore dictionary can say. Unknown blocks answer
     * {@code false} — under-scaling leaves a world with the ore its climate gave it, while over-scaling
     * would quietly rewrite a pack's non-metal deposits.
     */
    static boolean isMetallic(IBlockState state) {
        if (state == null || state.getBlock() == null) {
            return false;
        }
        try {
            net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(state.getBlock(), 1,
                    state.getBlock().getMetaFromState(state));
            for (int id : net.minecraftforge.oredict.OreDictionary.getOreIDs(stack)) {
                String name = net.minecraftforge.oredict.OreDictionary.getOreName(id);
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("ore") && !NON_METAL_ORES.contains(lower)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // No ore dictionary in this context (a headless derivation, or a block with no item form).
            return false;
        }
        return false;
    }

    public static class OreEntry {
        int minHeight;
        int maxHeight;
        int clumpSize;
        int chancePerChunk;
        private IBlockState state;

        public OreEntry(IBlockState state, int minHeight, int maxHeight, int clumpSize, int chancePerChunk) {
            this.state = state;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.clumpSize = clumpSize;
            this.chancePerChunk = chancePerChunk;
        }

        public IBlockState getBlockState() {
            return state;
        }

        public int getMinHeight() {
            return minHeight;
        }

        public int getMaxHeight() {
            return maxHeight;
        }

        public int getClumpSize() {
            return clumpSize;
        }

        public int getChancePerChunk() {
            return chancePerChunk;
        }
    }

}
