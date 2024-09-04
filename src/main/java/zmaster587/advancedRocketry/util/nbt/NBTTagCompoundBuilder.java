package zmaster587.advancedRocketry.util.nbt;

import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.INBTSerializable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * An utility class to functionally construct NBTTagCompound.
 * Potentially can provide the advanced serializing to it.
 */
public class NBTTagCompoundBuilder {

    private final NBTTagCompound instance;

    private NBTTagCompoundBuilder(NBTTagCompound instance) {
        this.instance = instance;
    }

    public static NBTTagCompoundBuilder create() {
        return new NBTTagCompoundBuilder(new NBTTagCompound());
    }

    public static NBTTagCompoundBuilder create(NBTTagCompound instance) {
        return new NBTTagCompoundBuilder(instance);
    }

    public NBTTagCompoundBuilder setString(String key, String value) {
        instance.setString(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setInt(String key, int value) {
        instance.setInteger(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setShort(String key, short value) {
        instance.setShort(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setLong(String key, long value) {
        instance.setLong(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setFloat(String key, float value) {
        instance.setFloat(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setDouble(String key, double value) {
        instance.setDouble(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setBoolean(String key, boolean value) {
        instance.setBoolean(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setIntArray(String key, int[] value) {
        instance.setIntArray(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setByteArray(String key, byte[] value) {
        instance.setByteArray(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setUniqueID(String key, UUID value) {
        instance.setUniqueId(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setSerializable(String key, INBTSerializable<?> serializable) {
        return setTag(key, serializable.serializeNBT());
    }

    public NBTTagCompoundBuilder setBlockPos(String key, BlockPos value) {
        NBTHelper.writeBlockPos(key, value, instance);
        return this;
    }

    public NBTTagCompoundBuilder setAABB(String key, AxisAlignedBB value) {
        NBTHelper.writeAABB(key, instance, value);
        return this;
    }

    public NBTTagCompoundBuilder setTag(String key, NBTBase value) {
        instance.setTag(key, value);
        return this;
    }

    public NBTTagCompoundBuilder setCollection(String key, Collection<? extends INBTSerializable<? extends NBTBase>> collection) {
        NBTHelper.writeCollection(key, instance, collection);
        return this;
    }

    public NBTTagCompoundBuilder setStringCollection(String key, Collection<String> collection) {
        NBTHelper.writeCollection(key, instance, collection, NBTTagString::new);
        return this;
    }

    public NBTTagCompoundBuilder setIntegerCollection(String key, Collection<Integer> collection) {
        NBTHelper.writeCollection(key, instance, collection, NBTTagInt::new);
        return this;
    }

    public NBTTagCompoundBuilder setFloatCollection(String key, Collection<Float> collection) {
        NBTHelper.writeCollection(key, instance, collection, NBTTagFloat::new);
        return this;
    }

    public NBTTagCompoundBuilder setDoubleCollection(String key, Collection<Double> collection) {
        NBTHelper.writeCollection(key, instance, collection, NBTTagDouble::new);
        return this;
    }

    public NBTTagCompoundBuilder setByteCollection(String key, Collection<Byte> collection) {
        NBTHelper.writeCollection(key, instance, collection, NBTTagByte::new);
        return this;
    }

    public <T> NBTTagCompoundBuilder setCollection(String key, Collection<T> collection, ParametrizedFactory<T, ? extends NBTBase> serializer) {
        NBTHelper.writeCollection(key, instance, collection, serializer);
        return this;
    }

    public NBTTagCompoundBuilder setResourceLocation(String key, ResourceLocation value) {
        instance.setString(key, value.toString());
        return this;
    }

    public NBTTagCompoundBuilder setMap(String key, Map<?, ? extends INBTSerializable<? extends NBTBase>> map) {
        NBTHelper.writeMap(key, instance, map);
        return this;
    }

    public <T> NBTTagCompoundBuilder setMap(String key, Map<?, T> map, ParametrizedFactory<T, ? extends NBTBase> serializer) {
        NBTHelper.writeMap(key, instance, map, serializer);
        return this;
    }

    public NBTTagCompoundBuilder execute(Consumer<NBTTagCompound> action) {
        action.accept(instance);
        return this;
    }

    public NBTTagCompound build() {
        return instance;
    }
}
