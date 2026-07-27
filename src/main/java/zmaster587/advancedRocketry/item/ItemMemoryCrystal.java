package zmaster587.advancedRocketry.item;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.libVulpes.LibVulpes;

/**
 * A memory crystal — the physical form of a ship's galactic knowledge.
 *
 * <p>Its capacity is effectively unlimited: a crystal records at enormous density, so a single one can
 * hold every address a campaign will ever discover. Crystals are copied and carried because knowledge
 * is worth trading and worth losing, not because any of them fills up.</p>
 */
public class ItemMemoryCrystal extends Item {

    /** Marks a crystal that has already been given its starter addresses. */
    private static final String KEY_SEEDED = "navSeeded";

    public ItemMemoryCrystal() {
        setMaxStackSize(1); // each crystal carries its own addresses: stacking would merge identities
    }

    /** Whether {@code stack} is a memory crystal at all. */
    public static boolean isCrystal(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemMemoryCrystal;
    }

    /** The addresses on {@code stack}; an empty memory for a blank crystal or a non-crystal. */
    public static CrystalMemory memoryOf(ItemStack stack) {
        if (!isCrystal(stack) || !stack.hasTagCompound()) {
            return new CrystalMemory();
        }
        return CrystalMemory.readFromNBT(stack.getTagCompound());
    }

    /** Write {@code memory} onto {@code stack}, replacing whatever it held. */
    public static void writeMemory(ItemStack stack, CrystalMemory memory) {
        if (!isCrystal(stack) || memory == null) {
            return;
        }
        NBTTagCompound nbt = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        memory.writeToNBT(nbt);
        stack.setTagCompound(nbt);
    }

    /**
     * Give a crystal its starter addresses the first time anyone uses it, and mark it so this never
     * happens twice — a crystal a player deliberately blanked must stay blank.
     *
     * <p>Seeding here rather than at crafting time covers every way a crystal can be acquired: crafted,
     * spawned in creative, or handed over by another player.</p>
     */
    public static void ensureSeeded(ItemStack stack, net.minecraft.world.World world) {
        if (!isCrystal(stack) || world == null || world.isRemote) {
            return;
        }
        NBTTagCompound nbt = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        if (nbt.getBoolean(KEY_SEEDED)) {
            return;
        }
        nbt.setBoolean(KEY_SEEDED, true);
        stack.setTagCompound(nbt);
        CrystalMemory memory = memoryOf(stack);
        memory.copyFrom(zmaster587.advancedRocketry.navigation.CrystalSeeding.starterFor(world));
        writeMemory(stack, memory);
    }

    /** Add one address to {@code stack}, merging by freshness. {@code true} if the crystal changed. */
    public static boolean record(ItemStack stack, CrystalEntry entry) {
        if (!isCrystal(stack)) {
            return false;
        }
        CrystalMemory memory = memoryOf(stack);
        if (!memory.record(entry)) {
            return false;
        }
        writeMemory(stack, memory);
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
                               ITooltipFlag flag) {
        int count = memoryOf(stack).size();
        tooltip.add(LibVulpes.proxy.getLocalizedString("msg.memorycrystal.addresses") + " " + count);
    }
}
