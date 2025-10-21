package zmaster587.advancedRocketry.item;

import com.mojang.realmsclient.gui.ChatFormatting;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import zmaster587.libVulpes.LibVulpes;

import javax.annotation.Nonnull;
import java.util.List;

public class ItemAsteroidChip extends ItemMultiData {

    private static final String uuidIdentifier = "UUID";
    private static final String astType = "astype";

    public ItemAsteroidChip() {
    }

    @Override
    public boolean isDamageable() {
        return false;
    }


    /**
     * Removes any Information and reset the stack to a default state
     *
     * @param stack stack to erase
     */
    public void erase(@Nonnull ItemStack stack) {
        stack.setTagCompound(null);
    }

    public Long getUUID(@Nonnull ItemStack stack) {
        if (stack.hasTagCompound())
            return stack.getTagCompound().getLong(uuidIdentifier);
        return null;
    }

    public void setUUID(@Nonnull ItemStack stack, long uuid) {
        NBTTagCompound nbt;
        if (stack.hasTagCompound())
            nbt = stack.getTagCompound();
        else
            nbt = new NBTTagCompound();

        nbt.setLong(uuidIdentifier, uuid);
        stack.setTagCompound(nbt);
    }

    public String getType(@Nonnull ItemStack stack) {
        if (stack.hasTagCompound())
            return stack.getTagCompound().getString(astType);
        return null;
    }

    public void setType(@Nonnull ItemStack stack, String type) {
        NBTTagCompound nbt;
        if (stack.hasTagCompound())
            nbt = stack.getTagCompound();
        else
            nbt = new NBTTagCompound();

        nbt.setString(astType, type);
        stack.setTagCompound(nbt);
    }
    // SplitMix64 mixer: great diffusion, tiny cost
    // Make Unique ID from UUID and type (looks random, but is deterministic)
    // Only for tooltip display purposes. Actual NBT untouched.
    private static long mix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // Deterministic display id from UUID and type (no world dependence)
    private static long makeDisplayId(Long uuid, String type) {
        long base = (uuid == null) ? 0L : uuid;
        long th   = (type == null) ? 0L : Integer.toUnsignedLong(type.hashCode());
        return mix64(base ^ (th << 1)); // fold in type so same UUID/different types look different
    }

    @Override
    public void addInformation(@Nonnull ItemStack stack, World world, List<String> list, ITooltipFlag flag) {
        if (!stack.hasTagCompound()) {
            list.add(LibVulpes.proxy.getLocalizedString("msg.unprogrammed"));
            return;
        }
        if (stack.getItemDamage() == 0) {
            Long id = getUUID(stack);
            String type = getType(stack);

            if (type != null && !type.isEmpty()) {
                list.add(LibVulpes.proxy.getLocalizedString("msg.asteroidChip.type") + ": "
                        + ChatFormatting.AQUA + type);
            }

            // Tooltip-only, random-looking but deterministic
            final long disp = makeDisplayId(id, type);
            final String hex = Long.toUnsignedString(disp, 16).toUpperCase();

            // Fixed-length visual tag (avoid lookalikes by using N=6 chars)
            final int N = 6;
            final String shortHex = (hex.length() > N) ? hex.substring(hex.length() - N) : hex;

            list.add(LibVulpes.proxy.getLocalizedString("msg.asteroidChip.asteroid") + ": "
                    + ChatFormatting.DARK_GREEN + shortHex);

            super.addInformation(stack, world, list, flag);
        }
    }

}
