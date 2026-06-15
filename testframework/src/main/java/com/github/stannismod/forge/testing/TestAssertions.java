package com.github.stannismod.forge.testing;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class TestAssertions {

    private TestAssertions() {
    }

    public static ByteBuf newBuffer() {
        return Unpooled.buffer();
    }

    public static <T> T roundTrip(T value, BufferWriter<T> writer, BufferReader<T> reader) {
        ByteBuf buffer = newBuffer();
        writer.write(value, buffer);
        return reader.read(buffer);
    }

    public static void assertFullyConsumed(ByteBuf buffer) {
        if (buffer.isReadable()) {
            throw new AssertionError("Expected buffer to be fully consumed but still had " + buffer.readableBytes() + " readable bytes");
        }
    }

    public interface BufferWriter<T> {
        void write(T value, ByteBuf buffer);
    }

    public interface BufferReader<T> {
        T read(ByteBuf buffer);
    }
}

