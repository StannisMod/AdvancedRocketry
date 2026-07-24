package com.github.stannismod.affs.util;

import com.github.stannismod.affs.item.ItemCodeDevice;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class CodeUtils {

    public static final String CODE_TAG = "affs_code";

    private CodeUtils() {
    }

    public static String normalize(String code) {
        if (code == null) {
            return "";
        }
        String normalized = code.trim();
        if (normalized.length() > 32) {
            normalized = normalized.substring(0, 32);
        }
        return normalized;
    }

    public static String getCode(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTagCompound()) {
            return "";
        }
        return normalize(stack.getTagCompound().getString(CODE_TAG));
    }

    public static void setCode(ItemStack stack, String code) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        NBTTagCompound tag = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        tag.setString(CODE_TAG, normalize(code));
        stack.setTagCompound(tag);
    }

    public static boolean entityHasMatchingCode(Entity entity, String expectedCode) {
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }

        String normalizedExpected = normalize(expectedCode);
        if (normalizedExpected.isEmpty()) {
            return false;
        }

        EntityPlayer living = (EntityPlayer) entity;
        for (ItemStack stack : living.getHeldEquipment()) {
            if (stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemCodeDevice) {
                if (normalizedExpected.equals(getCode(stack))) {
                    return true;
                }
            }
        }

        for (ItemStack stack : living.inventory.mainInventory) {
            if (stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemCodeDevice) {
                if (normalizedExpected.equals(getCode(stack))) {
                    return true;
                }
            }
        }

        return false;
    }
}
