package zmaster587.advancedRocketry.tile.multiblock.orbitallaserdrill;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * This drill is used if the laserDrillPlanet config option is disabled. It simply conjures ores from nowhere
 */
class VoidDrill extends AbstractDrill {

    private final Random random = new Random();
    private final List<ItemStack> ores = new ArrayList<>();
    private boolean voidCobble; // performance optimization: if true, cobble is not even generated
    private int opCounter = 0; // counts operations when voidCobble is true
    private static final ItemStack[] EMPTY = new ItemStack[0];
    private int sourceDimId = Integer.MIN_VALUE;
    private int cachedDimId = Integer.MIN_VALUE;
    private boolean oreCacheValid = false;


    VoidDrill() {
    }

    void setVoidCobble(boolean voidCobble) {
        this.voidCobble = voidCobble;
    }
    void setSourceDimId(int dimId) {
        if (this.sourceDimId != dimId) {
            this.sourceDimId = dimId;
            this.oreCacheValid = false;
        }
    }
    private static boolean sameOreEntry(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.getItem() != b.getItem()) return false;
        if (a.getMetadata() != b.getMetadata()) return false;
        if (a.getCount() != b.getCount()) return false;

        if (a.getTagCompound() == null) {
            return b.getTagCompound() == null;
        }

        return a.getTagCompound().equals(b.getTagCompound());
    }

    private boolean containsOreEntry(ItemStack stack) {
        for (ItemStack existing : ores) {
            if (sameOreEntry(existing, stack)) {
                return true;
            }
        }
        return false;
    }
    private void rebuildOreList(int dimId) {
        ores.clear();

        List<String> configOres = ARConfiguration.getCurrentConfig().standardLaserDrillOres;
        if (configOres != null) {
            for (String oreDictName : configOres) {
                if (oreDictName == null || oreDictName.isEmpty()) {
                    continue;
                }

                String[] args = oreDictName.split(":");

                List<ItemStack> globalOres = OreDictionary.getOres(args[0]);
                if (globalOres != null && !globalOres.isEmpty()) {
                    int amt = 1;
                    if (args.length > 1) {
                        try {
                            amt = Integer.parseInt(args[1]);
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    ItemStack base = globalOres.get(0);
                    ores.add(new ItemStack(base.getItem(), amt, base.getItemDamage()));
                    continue;
                }

                String[] splitStr = oreDictName.split(":");
                String name;
                try {
                    name = splitStr[0] + ":" + splitStr[1];
                } catch (IndexOutOfBoundsException e) {
                    AdvancedRocketry.logger.warn("Unexpected ore name: \"{}\" during laser drill harvesting", oreDictName);
                    continue;
                }

                int meta = 0;
                int size = 1;
                if (splitStr.length > 2) {
                    try {
                        meta = Integer.parseInt(splitStr[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (splitStr.length > 3) {
                    try {
                        size = Integer.parseInt(splitStr[3]);
                    } catch (NumberFormatException ignored) {
                    }
                }

                ItemStack stack = ItemStack.EMPTY;
                Block block = Block.getBlockFromName(name);
                if (block == null) {
                    Item item = Item.getByNameOrId(name);
                    if (item != null) {
                        stack = new ItemStack(item, size, meta);
                    }
                } else {
                    stack = new ItemStack(block, size, meta);
                }

                if (!stack.isEmpty()) {
                    ores.add(stack);
                }
            }
        }

        if (dimId != Integer.MIN_VALUE) {
            DimensionProperties dimProperties =
                    DimensionManager.getInstance().getDimensionProperties(dimId);

            if (dimProperties != null && dimProperties.laserDrillOres != null) {
                for (ItemStack s : dimProperties.laserDrillOres) {
                    if (s != null && !s.isEmpty() && !containsOreEntry(s)) {
                        ores.add(s.copy());
                    }
                }
            }
        }

        cachedDimId = dimId;
        oreCacheValid = true;
    }

    @Override
    ItemStack[] performOperation() {

        // --- VOID-COBBLE MODE: only ores, every 10th operation ---
        if (voidCobble) {
            if (ores.isEmpty()) {
                // No configured ores -> nothing to give
                return EMPTY;
            }

            opCounter++;
            // 9 out of 10 operations: no items at all
            if (opCounter % 10 != 0) {
                return EMPTY;
            }

            // 10th operation: roll one ore stack
            ItemStack[] result = new ItemStack[1];
            ItemStack template = ores.get(random.nextInt(ores.size()));
            result[0] = template.copy();
            return result;
        }

        // --- NORMAL MODE: 10% ore, 90% cobble (old behavior) ---

        // 10% ore
        boolean produceOre = !ores.isEmpty() && random.nextInt(10) == 0;

        if (produceOre) {
            ItemStack[] result = new ItemStack[1];
            ItemStack template = ores.get(random.nextInt(ores.size()));
            result[0] = template.copy();
            return result;
        }

        // Cobble case
        ItemStack[] result = new ItemStack[1];
        result[0] = new ItemStack(Blocks.COBBLESTONE, 1);
        return result;
    }

    void clearOreCache() {
        ores.clear();
        sourceDimId = Integer.MIN_VALUE;
        cachedDimId = Integer.MIN_VALUE;
        oreCacheValid = false;
        opCounter = 0;
    }

    @Override
    boolean activate(World world, int x, int z) {
        opCounter = 0;

        int dimId = Integer.MIN_VALUE;
        if (world != null) {
            dimId = world.provider.getDimension();
        } else if (sourceDimId != Integer.MIN_VALUE) {
            dimId = sourceDimId;
        }

        if (!oreCacheValid || cachedDimId != dimId) {
            rebuildOreList(dimId);
        }

        return true;
    }


    @Override
    void deactivate() {
        // No state required
    }

    @Override
    boolean isFinished() {
        return false;
    }

    @Override
    boolean needsRestart() {
        return false;
    }
}
