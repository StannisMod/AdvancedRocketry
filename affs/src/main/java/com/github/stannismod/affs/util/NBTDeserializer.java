package com.github.stannismod.affs.util;

import com.google.gson.internal.LinkedTreeMap;
import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
public class NBTDeserializer {

	public static Map<String, Object> nbtToMap(NBTTagCompound nbt) {
		Map<String, Object> nbtMap = new LinkedTreeMap<>(String::compareTo);
		nbt.getKeySet().forEach(key -> {
			Object value = getValueOf(nbt.getTag(key));
			nbtMap.put(key, value);
		});
		return nbtMap;
	}

	public static Object getValueOf(NBTBase base) {
		Object value = null;
		if (base instanceof NBTTagCompound) {
			value = nbtToMap((NBTTagCompound) base);
		} else if (base instanceof NBTTagDouble) {
			value = ((NBTTagDouble) base).getDouble();
		} else if (base instanceof NBTTagFloat) {
			value = ((NBTTagFloat) base).getFloat();
		} else if (base instanceof NBTTagInt) {
			value = ((NBTTagInt) base).getInt();
		} else if (base instanceof NBTTagLong) {
			value = ((NBTTagLong) base).getLong();
		} else if (base instanceof NBTTagByte) {
			value = ((NBTTagByte) base).getByte();
		} else if (base instanceof NBTTagShort) {
			value = ((NBTTagShort) base).getShort();
		} else if (base instanceof NBTTagString) {
			value = ((NBTTagString) base).getString();
		} else if (base instanceof NBTTagLongArray) {
			value = ((NBTTagLongArray) base).data;
		} else if (base instanceof NBTTagByteArray) {
			value = ((NBTTagByteArray) base).getByteArray();
//			Object[] objs = new Object[bytes.length];
//			for (int i = 0; i < bytes.length; i++) {
//				objs[i] = (int) bytes[i];
//			}
//			value = listHandler(objs);
		} else if (base instanceof NBTTagIntArray) {
			value = ((NBTTagIntArray) base).getIntArray();
//			Object[] objs = new Object[ints.length];
//			for (int i = 0; i < ints.length; i++) {
//				objs[i] = ints[i];
//			}
//			value = listHandler(objs);
		} else if (base instanceof NBTTagList) {
			List<Object> objects = new LinkedList<>();
			((NBTTagList) base).forEach(newBase -> objects.add(getValueOf(newBase)));
			value = objects.toArray();
		}
		return value;
	}

	public static BlockPos blockPos(NBTBase nbt) {
		int[] posArray = (int[]) getValueOf(nbt);
		return new BlockPos(posArray[0], posArray[1], posArray[2]);
	}
}
