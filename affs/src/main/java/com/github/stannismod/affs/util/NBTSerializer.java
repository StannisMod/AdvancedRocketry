package com.github.stannismod.affs.util;

import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

public class NBTSerializer {

    @SuppressWarnings("unchecked")
    public static NBTBase getNBTOf(Object obj) {
        if (obj instanceof Map) {
            return map((Map<String, Object>) obj);
        } else if (obj instanceof Double) {
            return new NBTTagDouble((Double) obj);
        } else if (obj instanceof Float) {
            return new NBTTagFloat((Float) obj);
        } else if (obj instanceof Integer) {
            return new NBTTagInt((Integer) obj);
        } else if (obj instanceof Short) {
            return new NBTTagShort((Short) obj);
        } else if (obj instanceof Byte) {
            return new NBTTagByte((Byte) obj);
        } else if (obj instanceof Long) {
            return new NBTTagLong((Long) obj);
        } else if (obj instanceof String) {
            return new NBTTagString((String) obj);
        } else if (obj instanceof int[]) {
            return new NBTTagIntArray((int[]) obj);
        } else if (obj instanceof byte[]) {
            return new NBTTagByteArray((byte[]) obj);
        } else if (obj instanceof long[]) {
            return new NBTTagLongArray((long[]) obj);
        } else if (obj instanceof List) {
            NBTTagList lst = new NBTTagList();
            ((List) obj).forEach(o -> lst.appendTag(getNBTOf(o)));
            return lst;
        } else {
            return null;
        }
    }

    public static NBTTagCompound map(Map<String, Object> map) {
        NBTTagCompound compound = new NBTTagCompound();
        map.forEach((k, v) -> compound.setTag(k, getNBTOf(v)));
        return compound;
    }

    public static NBTBase blockPos(BlockPos pos) {
        return getNBTOf(new int[]{ pos.getX(), pos.getY(), pos.getZ() });
    }
}
