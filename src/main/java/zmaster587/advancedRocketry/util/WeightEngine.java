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
import zmaster587.advancedRocketry.api.damage.ImpactKind;
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
 * Resolves the weight (in kN, the unit the rocket maths uses) of any block, item or fluid.
 *
 * Resolution chain for a stack (first hit wins, per single item, before multiplying by count):
 *   1. {@code individual}  — explicit per-registry-name override from weights.json
 *   2. {@code byRegex}     — first matching regex over the registry name
 *   3. AR component specifics (motor / tank / pressure tank / guidance / loader)
 *   4. {@code materials}   — by the block's {@link Material}, scaled by weightMaterialScale
 *   5. {@code fallback}    — global default, scaled by weightMaterialScale
 *
 * Only steps 4-5 are scaled by {@code weightMaterialScale}; explicit overrides and AR
 * component values are intentional absolutes and are left untouched.
 */
public enum WeightEngine {
    INSTANCE("config/advRocketry/weights.json");

    // AR component defaults (kN) — heavy, purpose-built parts that should not fall back to material.
    private static final double TANK_WEIGHT = 0.2;
    private static final double MOTOR_WEIGHT = 2;
    private static final double GUIDANCE_COMPUTER_WEIGHT = 1.8;
    private static final double PRESSURE_TANK_WEIGHT = 5;
    private static final double SATELLITE_HATCH_WEIGHT = 5;

    private final String file;

    // Persisted, player-editable tables.
    private Map<String, Double> individual = new HashMap<>();
    private Map<String, Double> byRegex = new LinkedHashMap<>();
    private Map<String, Double> fluids = new HashMap<>();
    private Map<String, Double> materials = new HashMap<>();
    private double fallback = 0.1;
    private double fluidFallback = 0.001;

    // Toughness — a second column over the same keys, resolved by the same chain (individual ->
    // byRegex -> material -> fallback) and living in the same file. It answers "how much does it cost
    // to damage this block", where weight answers "how much does it mass"; the two are correlated but
    // are not the same question, which is why anvils are not simply heavy glass.
    //
    // CALIBRATION, and a bet worth stating out loud: these numbers are spent against a budget
    // denominated in the SAME unit as shield impact energy. The muzzle-side damage->energy factor
    // therefore scales BOTH what a shot costs a shield and what it costs a hull — anyone retuning it
    // is retuning hull lethality at the same time, in the same direction.
    private Map<String, Double> toughnessIndividual = new HashMap<>();
    private Map<String, Double> toughnessByRegex = new LinkedHashMap<>();
    private Map<String, Double> toughnessMaterials = new HashMap<>();
    private double toughnessFallback = 2.0;
    /**
     * The ablation column: how much energy this block costs per unit of volume BOILED AWAY, as opposed
     * to pushed through. Same units and the same resolution chain as toughness, and deliberately
     * sparse — a block with no row here has its ablation derived from its toughness, so the table only
     * ever has to name the materials whose two channels genuinely disagree.
     */
    private Map<String, Double> ablationIndividual = new HashMap<>();
    private Map<String, Double> ablationByRegex = new LinkedHashMap<>();

    // Transient runtime caches (not persisted; cleared on load()).
    private final Map<String, Float> resolvedItemCache = new HashMap<>();
    private final Map<String, Pattern> compiledRegex = new HashMap<>();

    WeightEngine(String file) {
        this.file = file;
        load();
    }

    private static double scale() {
        return ARConfiguration.getCurrentConfig().weightMaterialScale;
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
                return (float) TANK_WEIGHT;
            }
            if (block instanceof BlockRocketMotor || block instanceof BlockBipropellantRocketMotor) {
                return (float) MOTOR_WEIGHT;
            }
            if (block instanceof BlockPressurizedFluidTank) {
                return (float) PRESSURE_TANK_WEIGHT;
            }
            if (key.equals("advancedrocketry:guidancecomputer")) {
                return (float) GUIDANCE_COMPUTER_WEIGHT;
            }
            if (key.equals("advancedrocketry:loader")) {
                return (float) SATELLITE_HATCH_WEIGHT;
            }

            Double materialWeight = materials.get(materialName(block.getDefaultState().getMaterial()));
            if (materialWeight != null) {
                return (float) (materialWeight * scale());
            }
        }
        return (float) (fallback * scale());
    }

    private Double matchRegex(String key) {
        return matchRegex(byRegex, key);
    }

    /** First matching regex rule of {@code table}, or null. The compiled-pattern cache is shared:
     *  the same pattern string means the same pattern whichever column it rules. */
    private Double matchRegex(Map<String, Double> table, String key) {
        for (Map.Entry<String, Double> e : table.entrySet()) {
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

    /**
     * How hard the block at {@code pos} is to damage. Same resolution chain as weight, over the same
     * registry names, so a pack that has already tuned one has half the work done for the other.
     * Air and anything unrecognised resolve to the fallback rather than to zero: a block that costs
     * nothing to break would let one shot walk an entire hull.
     */
    public float getToughness(World world, BlockPos pos) {
        return world == null || pos == null ? (float) toughnessFallback
                : getToughness(world.getBlockState(pos).getBlock());
    }

    public float getToughness(Block block) {
        if (block == null || block.getRegistryName() == null) {
            return (float) toughnessFallback;
        }
        String key = block.getRegistryName().toString();

        Double override = toughnessIndividual.get(key);
        if (override != null) {
            return override.floatValue();
        }
        Double regex = matchRegex(toughnessByRegex, key);
        if (regex != null) {
            return regex.floatValue();
        }
        Double byMaterial = toughnessMaterials.get(materialName(block.getDefaultState().getMaterial()));
        return byMaterial != null ? byMaterial.floatValue() : (float) toughnessFallback;
    }

    /** Register an explicit per-registry-name toughness (highest precedence). */
    public void setIndividualToughness(String registryName, double toughness) {
        toughnessIndividual.put(registryName, toughness);
    }

    /**
     * How much this block resists ONE KIND of arrival, in the same units as toughness.
     *
     * <h3>Two channels of one law, not two mechanics</h3>
     * <p>A slug is pushed through material; a beam boils it away. Both are "how much energy this stuff
     * costs per unit of volume removed", so the law is the same and only the constant differs by kind.
     * That is what makes a ceramic which shrugs off a beam and shatters under a slug two ROWS of one
     * table rather than two special cases.</p>
     *
     * <h3>A block nobody wrote a row for</h3>
     * <p>...keeps exactly today's single toughness for the mechanical kinds, so the plain hull the
     * whole game is built out of behaves precisely as it did. Its ablation figure is DERIVED from that
     * toughness by a single factor, because the alternative — defaulting the two columns equal — would
     * quietly declare that a joule of laser digs as much hull as a joule of shell, which is both wrong
     * and the opposite of the game being built.</p>
     */
    public float getResistance(Block block, ImpactKind kind) {
        float mechanical = getToughness(block);
        if (kind == null || !isThermalChannel(kind)) {
            return mechanical;
        }
        String key = block == null || block.getRegistryName() == null
                ? null : block.getRegistryName().toString();
        if (key != null) {
            Double override = ablationIndividual.get(key);
            if (override != null) {
                return override.floatValue();
            }
            Double regex = matchRegex(ablationByRegex, key);
            if (regex != null) {
                return regex.floatValue();
            }
        }
        return (float) (mechanical * ARConfiguration.getCurrentConfig().ablationResistanceFactor);
    }

    /** The same question at a position. */
    public float getResistance(World world, BlockPos pos, ImpactKind kind) {
        return world == null || pos == null
                ? (float) toughnessFallback
                : getResistance(world.getBlockState(pos).getBlock(), kind);
    }

    /**
     * Is this block METAL? Asked of the same material the toughness table already resolves by, so
     * "is this metal" is a question that is already answered for every block in the game, vanilla
     * ones included, and no new classification machinery is invented to ask it.
     */
    public boolean isMetal(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return world.getBlockState(pos).getMaterial() == Material.IRON;
    }

    /** Which kinds arrive as heat to be conducted away rather than as something to be pushed through. */
    public static boolean isThermalChannel(ImpactKind kind) {
        return kind == ImpactKind.THERMAL || kind == ImpactKind.BEAM;
    }

    /** Register an explicit per-registry-name ablation resistance (highest precedence). */
    public void setIndividualAblation(String registryName, double resistance) {
        ablationIndividual.put(registryName, resistance);
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
        try (Reader r = new FileReader(file)) {
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            JsonObject root = gson.fromJson(r, JsonObject.class);
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

            toughnessIndividual = readMap(gson, root, "toughnessIndividual", mapType);
            ablationIndividual = readMap(gson, root, "ablationIndividual", mapType);
            ablationByRegex = readMap(gson, root, "ablationByRegex", mapType);
            toughnessByRegex = readMap(gson, root, "toughnessByRegex", linkedType);
            toughnessMaterials = readMap(gson, root, "toughnessMaterials", mapType);
            if (toughnessMaterials.isEmpty()) {
                toughnessMaterials = defaultToughnessMaterials();
            }
            if (root.has("toughnessFallback")) {
                toughnessFallback = root.get("toughnessFallback").getAsDouble();
            }
        } catch (Exception e) {
            e.printStackTrace();
            seedDefaults();
            System.out.println("The weight config was wrong, could not be read, was broken, not there or something else! Defaults will be used");
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
        fallback = 0.1;
        fluidFallback = 0.001;
        toughnessIndividual = new HashMap<>();
        toughnessByRegex = new LinkedHashMap<>();
        toughnessMaterials = defaultToughnessMaterials();
        toughnessFallback = 2.0;
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

    public void save() {
        File parent = new File(file).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileWriter w = new FileWriter(file)) {
            Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
            JsonObject json = new JsonObject();
            json.add("individual", gson.toJsonTree(individual));
            json.add("byRegex", gson.toJsonTree(byRegex));
            json.add("fluids", gson.toJsonTree(fluids));
            json.add("materials", gson.toJsonTree(materials));
            json.addProperty("fallback", fallback);
            json.addProperty("fluidFallback", fluidFallback);
            json.add("toughnessIndividual", gson.toJsonTree(toughnessIndividual));
            json.add("toughnessByRegex", gson.toJsonTree(toughnessByRegex));
            json.add("toughnessMaterials", gson.toJsonTree(toughnessMaterials));
            json.addProperty("toughnessFallback", toughnessFallback);
            w.write(gson.toJson(json));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---- Material table -----------------------------------------------------

    private static Map<String, Double> defaultMaterials() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("AIR", 0.0);
        m.put("CLOTH", 0.05);
        m.put("CARPET", 0.05);
        m.put("WEB", 0.02);
        m.put("PLANTS", 0.02);
        m.put("VINE", 0.02);
        m.put("LEAVES", 0.02);
        m.put("CACTUS", 0.05);
        m.put("GOURD", 0.1);
        m.put("SNOW", 0.05);
        m.put("CRAFTED_SNOW", 0.1);
        m.put("SAND", 0.2);
        m.put("GROUND", 0.2);
        m.put("GRASS", 0.2);
        m.put("CLAY", 0.25);
        m.put("WOOD", 0.15);
        m.put("GLASS", 0.1);
        m.put("ICE", 0.15);
        m.put("PACKED_ICE", 0.2);
        m.put("CORAL", 0.2);
        m.put("CAKE", 0.05);
        m.put("CIRCUITS", 0.3);
        m.put("REDSTONE_LIGHT", 0.3);
        m.put("TNT", 0.3);
        m.put("ROCK", 0.4);
        m.put("IRON", 1.0);
        m.put("ANVIL", 1.5);
        return m;
    }

    /**
     * Toughness by material — how much a block of this stuff resists being damaged. Ordered so the
     * ratios read at a glance: glass is not armour, rock is a wall, iron is a hull, an anvil is a
     * deliberate outlier. Every one of these is tunable and none is pinned by a test; what IS meant to
     * survive retuning is the ordering, because that is what a player perceives when a shot goes
     * through a window and stops in the plating.
     */
    private static Map<String, Double> defaultToughnessMaterials() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("AIR", 0.0);
        m.put("CLOTH", 0.2);
        m.put("CARPET", 0.2);
        m.put("WEB", 0.1);
        m.put("PLANTS", 0.1);
        m.put("VINE", 0.1);
        m.put("LEAVES", 0.1);
        m.put("CACTUS", 0.2);
        m.put("GOURD", 0.3);
        m.put("SNOW", 0.2);
        m.put("CRAFTED_SNOW", 0.4);
        m.put("SAND", 0.8);
        m.put("GROUND", 0.8);
        m.put("GRASS", 0.8);
        m.put("CLAY", 1.0);
        m.put("WOOD", 1.2);
        m.put("GLASS", 0.5);
        m.put("ICE", 0.6);
        m.put("PACKED_ICE", 0.9);
        m.put("CORAL", 0.6);
        m.put("CAKE", 0.1);
        m.put("CIRCUITS", 1.0);
        m.put("REDSTONE_LIGHT", 1.0);
        m.put("TNT", 0.5);
        m.put("ROCK", 3.0);
        m.put("IRON", 6.0);
        m.put("ANVIL", 9.0);
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
