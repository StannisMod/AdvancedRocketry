package zmaster587.advancedRocketry.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.block.BlockBipropellantRocketMotor;
import zmaster587.advancedRocketry.block.BlockFuelTank;
import zmaster587.advancedRocketry.block.BlockPressurizedFluidTank;
import zmaster587.advancedRocketry.block.BlockRocketMotor;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Resolves the MASS, in kilograms, of any block, item or fluid. One block is one cubic metre,
 * so a table entry is also that material's density in kg/m³; fluid entries are kilograms per
 * millibucket. Nothing here is a weight: gravity is applied where the force is needed, never
 * baked into the table.
 *
 * Resolution chain for a stack (first hit wins, per single item, before multiplying by count):
 *   1. {@code individual}  — explicit per-registry-name override from weights.json
 *   2. {@code byRegex}     — first matching regex over the registry name
 *   3. AR component specifics (motor / tank / pressure tank / guidance / loader)
 *   4. {@code materials}   — by the block's {@link Material}
 *   5. {@code fallback}    — global default
 */
public enum WeightEngine {
    INSTANCE("config/advRocketry/weights.json");

    /**
     * Schema version of weights.json. Bumped to 2 when the tables moved from a dimensionless
     * rating to kilograms; a file without a matching version is set aside and reseeded, because
     * reading pre-kilogram numbers as kilograms makes every hull ~5000x too light.
     */
    private static final int FORMAT_VERSION = 2;

    // AR component defaults (kg) — heavy, purpose-built parts that should not fall back to material.
    private static final double TANK_MASS = 1000;
    private static final double MOTOR_MASS = 10000;
    private static final double GUIDANCE_COMPUTER_MASS = 9000;
    private static final double PRESSURE_TANK_MASS = 25000;
    private static final double SATELLITE_HATCH_MASS = 25000;

    private final String file;

    // Persisted, player-editable tables.
    private Map<String, Double> individual = new HashMap<>();
    private Map<String, Double> byRegex = new LinkedHashMap<>();
    private Map<String, Double> fluids = new HashMap<>();
    private Map<String, Double> materials = new HashMap<>();
    private double fallback = 500;
    private double fluidFallback = 5;

    // Transient runtime caches (not persisted; cleared on load()).
    private final Map<String, Float> resolvedItemCache = new HashMap<>();
    private final Map<String, Pattern> compiledRegex = new HashMap<>();

    WeightEngine(String file) {
        this.file = file;
        load();
    }

    public float getWeight(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem().getRegistryName() == null) {
            return 0;
        }
        String key = stack.getItem().getRegistryName().toString();
        return resolveUnitWeight(key, stack) * stack.getCount();
    }

    /** Weight of a single item (count == 1), memoised by registry name. */
    private float resolveUnitWeight(String key, ItemStack stack) {
        Float cached = resolvedItemCache.get(key);
        if (cached != null) {
            return cached;
        }

        float weight;
        Double override = individual.get(key);
        if (override != null) {
            weight = override.floatValue();
        } else {
            Double regex = matchRegex(key);
            if (regex != null) {
                weight = regex.floatValue();
            } else {
                weight = componentOrMaterialWeight(key, stack);
            }
        }

        resolvedItemCache.put(key, weight);
        return weight;
    }

    private float componentOrMaterialWeight(String key, ItemStack stack) {
        if (stack.getItem() instanceof ItemBlock) {
            Block block = ((ItemBlock) stack.getItem()).getBlock();

            if (block instanceof BlockFuelTank) {
                return (float) TANK_MASS;
            }
            if (block instanceof BlockRocketMotor || block instanceof BlockBipropellantRocketMotor) {
                return (float) MOTOR_MASS;
            }
            if (block instanceof BlockPressurizedFluidTank) {
                return (float) PRESSURE_TANK_MASS;
            }
            if (key.equals("advancedrocketry:guidancecomputer")) {
                return (float) GUIDANCE_COMPUTER_MASS;
            }
            if (key.equals("advancedrocketry:loader")) {
                return (float) SATELLITE_HATCH_MASS;
            }

            Double materialMass = materials.get(materialName(block.getDefaultState().getMaterial()));
            if (materialMass != null) {
                return materialMass.floatValue();
            }
        }
        return (float) fallback;
    }

    private Double matchRegex(String key) {
        for (Map.Entry<String, Double> e : byRegex.entrySet()) {
            Pattern p = compiledRegex.get(e.getKey());
            if (p == null) {
                try {
                    p = Pattern.compile(e.getKey());
                } catch (PatternSyntaxException ex) {
                    continue;
                }
                compiledRegex.put(e.getKey(), p);
            }
            if (p.matcher(key).matches()) {
                return e.getValue();
            }
        }
        return null;
    }

    public float getWeight(Collection<ItemStack> stacks) {
        return stacks.stream().map(this::getWeight).reduce(0.0F, Float::sum);
    }

    public float getWeight(World world, BlockPos pos) {
        return getWeight(world.getTileEntity(pos), world.getBlockState(pos).getBlock());
    }

    public float getWeight(FluidStack stack) {
        return getWeight(stack.getFluid(), stack.amount);
    }

    public float getWeight(Fluid fluid, float amount) {
        double perMb = fluids.getOrDefault(fluid.getName(), fluidFallback);
        return (float) (perMb * amount * ARConfiguration.getCurrentConfig().fuelMassScale);
    }

    public float getTEWeight(TileEntity te) {
        if (!ARConfiguration.getCurrentConfig().advancedWeightSystemInventories) return 0;

        float weight = 0;

        if (te == null) {
            return weight;
        }

        IItemHandler capability = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if (capability != null) {
            for (int i = 0; i < capability.getSlots(); i++) {
                weight += getWeight(capability.getStackInSlot(i));
            }
        }

        IFluidHandler fluidHandler = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        if (fluidHandler != null) {
            for (IFluidTankProperties info : fluidHandler.getTankProperties()) {
                if (info != null && info.getContents() != null) {
                    weight += getWeight(info.getContents());
                }
            }
        }

        return weight;
    }

    public float getWeight(TileEntity te, Block blk) {
        if (blk == null) {
            // if block is null, TE should be not null
            blk = te.getBlockType();
        }
        float weight = getWeight(new ItemStack(blk));

        return weight + getTEWeight(te);
    }

    public float getWeight(World world, Collection<BlockPos> poses) {
        return poses.stream().map(pos -> getWeight(world, pos)).reduce(0.0F, Float::sum);
    }

    public void load() {
        resolvedItemCache.clear();
        compiledRegex.clear();
        File f = new File(file);
        if (!f.exists()) {
            seedDefaults();
            save();
            return;
        }
        boolean incompatibleSchema = false;
        try (Reader r = new FileReader(file)) {
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            JsonObject root = gson.fromJson(r, JsonObject.class);
            if (root == null || !root.has("formatVersion")
                    || root.get("formatVersion").getAsInt() != FORMAT_VERSION) {
                // Do NOT retire the file here: the reader above is still open, and Windows refuses
                // to rename an open file. Flag it and act once the resource has closed.
                incompatibleSchema = true;
            } else {
                Type mapType = new TypeToken<HashMap<String, Double>>() {}.getType();
                Type linkedType = new TypeToken<LinkedHashMap<String, Double>>() {}.getType();

                individual = readMap(gson, root, "individual", mapType);
                byRegex = readMap(gson, root, "byRegex", linkedType);
                fluids = readMap(gson, root, "fluids", mapType);
                materials = readMap(gson, root, "materials", mapType);
                if (materials.isEmpty()) {
                    materials = defaultMaterials();
                }
                if (root.has("fallback")) {
                    fallback = root.get("fallback").getAsDouble();
                }
                if (root.has("fluidFallback")) {
                    fluidFallback = root.get("fluidFallback").getAsDouble();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            seedDefaults();
            System.out.println("The weight config was wrong, could not be read, was broken, not there or something else! Defaults will be used");
            return;
        }
        if (incompatibleSchema) {
            retireIncompatibleFile();
        }
    }

    private static <T extends Map<String, Double>> T readMap(Gson gson, JsonObject root, String name, Type type) {
        if (root.has(name) && root.get(name).isJsonObject()) {
            T parsed = gson.fromJson(root.getAsJsonObject(name), type);
            if (parsed != null) {
                return parsed;
            }
        }
        return gson.fromJson("{}", type);
    }

    private void seedDefaults() {
        individual = new HashMap<>();
        byRegex = new LinkedHashMap<>();
        fluids = new HashMap<>();
        materials = defaultMaterials();
        fallback = 500;
        fluidFallback = 5;
    }

    /**
     * Move a weights.json written against another schema aside and write fresh defaults. The old
     * file is kept next to it so a player's hand-tuned numbers can be carried over by hand — its
     * values cannot be converted automatically, because an entry may be either a material default
     * or a deliberate absolute.
     */
    private void retireIncompatibleFile() {
        File current = new File(file);
        File retired = new File(file + ".v" + (FORMAT_VERSION - 1) + ".bak");
        if (retired.exists() && !retired.delete()) {
            System.out.println("Could not replace " + retired + "; leaving weights.json in place and using defaults in memory");
            seedDefaults();
            return;
        }
        if (!current.renameTo(retired)) {
            System.out.println("Could not set aside " + current + "; using default weights in memory");
            seedDefaults();
            return;
        }
        System.out.println("weights.json predates the move to kilograms; kept as " + retired.getName() + " and reseeded with defaults");
        seedDefaults();
        save();
    }

    // ---- Runtime / test mutation hooks --------------------------------------

    /** Reset every table to the built-in defaults and drop all caches. */
    public void resetTables() {
        seedDefaults();
        resolvedItemCache.clear();
        compiledRegex.clear();
    }

    /** Drop the memoised per-item resolutions (call after changing scale config). */
    public void clearResolveCache() {
        resolvedItemCache.clear();
    }

    /** Register an explicit per-registry-name weight (highest precedence). */
    public void setIndividual(String registryName, double weight) {
        individual.put(registryName, weight);
        resolvedItemCache.clear();
    }

    /** Register a regex rule matched against the registry name (below individual). */
    public void setRegex(String pattern, double weight) {
        byRegex.put(pattern, weight);
        compiledRegex.clear();
        resolvedItemCache.clear();
    }

    /** Test accessor: raw individual-override value, or null if none. */
    public Double rawIndividual(String registryName) {
        return individual.get(registryName);
    }

    /** Test accessor: number of material entries currently loaded. */
    public int materialCount() {
        return materials.size();
    }

    /**
     * The table's own fallback mass, kilograms — what a block resolves to when nothing more specific
     * matches. Exposed for the one caller that must answer for a block with no item form and so cannot
     * go through the registry-name chain at all; it takes this rather than some other table's default,
     * because a second default is a second mass model.
     */
    public double fallbackMass() {
        return fallback;
    }

    public void save() {
        File parent = new File(file).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileWriter w = new FileWriter(file)) {
            Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
            JsonObject json = new JsonObject();
            json.addProperty("formatVersion", FORMAT_VERSION);
            json.add("individual", gson.toJsonTree(individual));
            json.add("byRegex", gson.toJsonTree(byRegex));
            json.add("fluids", gson.toJsonTree(fluids));
            json.add("materials", gson.toJsonTree(materials));
            json.addProperty("fallback", fallback);
            json.addProperty("fluidFallback", fluidFallback);
            w.write(gson.toJson(json));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---- Material table -----------------------------------------------------

    /**
     * Mass of one block of each material, in kilograms — a block is a cubic metre, so these are
     * also densities. An ordinary block lands at 500 kg/m³ (the density of wood), stone at 2000
     * and iron at 5000: a hollow structural block rather than a solid billet.
     */
    private static Map<String, Double> defaultMaterials() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("AIR", 0.0);
        m.put("CLOTH", 250.0);
        m.put("CARPET", 250.0);
        m.put("WEB", 100.0);
        m.put("PLANTS", 100.0);
        m.put("VINE", 100.0);
        m.put("LEAVES", 100.0);
        m.put("CACTUS", 250.0);
        m.put("GOURD", 500.0);
        m.put("SNOW", 250.0);
        m.put("CRAFTED_SNOW", 500.0);
        m.put("SAND", 1000.0);
        m.put("GROUND", 1000.0);
        m.put("GRASS", 1000.0);
        m.put("CLAY", 1250.0);
        m.put("WOOD", 750.0);
        m.put("GLASS", 500.0);
        m.put("ICE", 750.0);
        m.put("PACKED_ICE", 1000.0);
        m.put("CORAL", 1000.0);
        m.put("CAKE", 250.0);
        m.put("CIRCUITS", 1500.0);
        m.put("REDSTONE_LIGHT", 1500.0);
        m.put("TNT", 1500.0);
        m.put("ROCK", 2000.0);
        m.put("IRON", 5000.0);
        m.put("ANVIL", 7500.0);
        return m;
    }

    private static final Map<Material, String> MATERIAL_NAMES = buildMaterialNames();

    private static Map<Material, String> buildMaterialNames() {
        Map<Material, String> m = new HashMap<>();
        m.put(Material.AIR, "AIR");
        m.put(Material.GRASS, "GRASS");
        m.put(Material.GROUND, "GROUND");
        m.put(Material.WOOD, "WOOD");
        m.put(Material.ROCK, "ROCK");
        m.put(Material.IRON, "IRON");
        m.put(Material.ANVIL, "ANVIL");
        m.put(Material.WATER, "WATER");
        m.put(Material.LAVA, "LAVA");
        m.put(Material.LEAVES, "LEAVES");
        m.put(Material.PLANTS, "PLANTS");
        m.put(Material.VINE, "VINE");
        m.put(Material.SPONGE, "SPONGE");
        m.put(Material.CLOTH, "CLOTH");
        m.put(Material.FIRE, "FIRE");
        m.put(Material.SAND, "SAND");
        m.put(Material.CIRCUITS, "CIRCUITS");
        m.put(Material.CARPET, "CARPET");
        m.put(Material.GLASS, "GLASS");
        m.put(Material.REDSTONE_LIGHT, "REDSTONE_LIGHT");
        m.put(Material.TNT, "TNT");
        m.put(Material.CORAL, "CORAL");
        m.put(Material.ICE, "ICE");
        m.put(Material.PACKED_ICE, "PACKED_ICE");
        m.put(Material.SNOW, "SNOW");
        m.put(Material.CRAFTED_SNOW, "CRAFTED_SNOW");
        m.put(Material.CACTUS, "CACTUS");
        m.put(Material.CLAY, "CLAY");
        m.put(Material.GOURD, "GOURD");
        m.put(Material.DRAGON_EGG, "DRAGON_EGG");
        m.put(Material.PORTAL, "PORTAL");
        m.put(Material.CAKE, "CAKE");
        m.put(Material.WEB, "WEB");
        m.put(Material.PISTON, "PISTON");
        m.put(Material.BARRIER, "BARRIER");
        m.put(Material.STRUCTURE_VOID, "STRUCTURE_VOID");
        return m;
    }

    private static String materialName(Material material) {
        return MATERIAL_NAMES.getOrDefault(material, "UNKNOWN");
    }
}
