package zmaster587.advancedRocketry.util;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

public class InventoryUtil {

    public static boolean hasItemInInventories(Iterable<IInventory> invs, String substr, boolean consume) {
        for (IInventory inv : invs) {
            if (hasItemInInventory(inv, substr, consume)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasItemInInventory(IInventory inv, String substr, boolean consume) {
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (inv.getStackInSlot(i).getUnlocalizedName().toLowerCase().contains(substr.toLowerCase())) {
                if (consume) {
                    inv.setInventorySlotContents(i, ItemStack.EMPTY);
                }
                return true;
            }
        }
        return false;
    }

    public static boolean addItemToOneOfTheInventories(Iterable<IInventory> invs, ItemStack stack) {
        for (IInventory inv : invs) {
            if (addItemToInventory(inv, stack)) {
                return true;
            }
        }
        return false;
    }

    public static boolean addItemToInventory(IInventory inv, ItemStack stack) {
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (inv.getStackInSlot(i).isEmpty()) {
                inv.setInventorySlotContents(i, stack);
                return true;
            }
        }
        return false;
    }
}
