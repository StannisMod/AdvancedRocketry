package com.github.stannismod.affs.util;

import io.netty.buffer.ByteBuf;

/**
 * Extended utilities to operate with ByteBuf
 */
public class ByteBufUtils extends net.minecraftforge.fml.common.network.ByteBufUtils {

    public static int[] readIntArray(ByteBuf buf) {
        int size = buf.readInt();
        int[] result = new int[size];
        for (int i = 0; i < size; i++) {
            result[i] = buf.readInt();
        }
        return result;
    }

    public static long[] readLongArray(ByteBuf buf) {
        int size = buf.readInt();
        long[] result = new long[size];
        for (int i = 0; i < size; i++) {
            result[i] = buf.readLong();
        }
        return result;
    }

    public static void writeLongArray(ByteBuf buf, long[] array) {
        buf.writeInt(array.length);
        for (long l : array) {
            buf.writeLong(l);
        }
    }

    public static void writeIntArray(ByteBuf buf, int[] array) {
        buf.writeInt(array.length);
        for (int i : array) {
            buf.writeInt(i);
        }
    }
}
