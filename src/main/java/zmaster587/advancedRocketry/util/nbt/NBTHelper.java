package zmaster587.advancedRocketry.util.nbt;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.nbt.*;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tool for NBT serializing and deserializing collections and other stuff
 */
@SuppressWarnings("unchecked")
public class NBTHelper {

    public static NBTBase NBT_NULL = new NBTTagString("null");

    public static void writeCollection(String name, NBTTagCompound compound, Collection<? extends INBTSerializable<? extends NBTBase>> collection) {
        compound.setTag(name, collectionToNBT(collection));
    }

    public static <T> void writeCollection(String name, NBTTagCompound compound, Collection<T> collection, ParametrizedFactory<T, ? extends NBTBase> serializer) {
        compound.setTag(name, collectionToNBT(collection, serializer));
    }

    public static <T, NBT_T extends NBTBase, C extends Collection<T>> C readCollection(String name, NBTTagCompound compound, Factory<C> collectionFactory, ParametrizedFactory<NBT_T, T> deserializer) {
        return collectionFromNBT(getTagList(name, compound), collectionFactory, deserializer);
    }

    public static NBTTagList collectionToNBT(Collection<? extends INBTSerializable<? extends NBTBase>> collection) {
        NBTTagList tag = new NBTTagList();
        collection.stream().map(INBTSerializable::serializeNBT).forEach(tag::appendTag);
        return tag;
    }

    public static <T> NBTTagList collectionToNBT(Collection<T> collection, ParametrizedFactory<T, ? extends NBTBase> serializer) {
        NBTTagList tag = new NBTTagList();
        collection.stream().map(serializer::create).forEach(tag::appendTag);
        return tag;
    }

    public static <T, NBT_T extends NBTBase, C extends Collection<T>> C collectionFromNBT(NBTTagList tag, Factory<C> collectionFactory, ParametrizedFactory<NBT_T, T> deserializer) {
        C collection = collectionFactory.create();
        tag.iterator().forEachRemaining(item -> collection.add(deserializer.create((NBT_T) item)));
        return collection;
    }

    public static void writeMap(String name, NBTTagCompound compound, Map<?, ? extends INBTSerializable<? extends NBTBase>> map) {
        compound.setTag(name, mapToNBT(map));
    }

    public static <V> void writeMap(String name, NBTTagCompound compound, Map<?, V> map, ParametrizedFactory<V, ? extends NBTBase> serializer) {
        compound.setTag(name, mapToNBT(map, serializer));
    }

    public static <V, NBT_T extends NBTBase> Map<String, V> readMap(String name, NBTTagCompound compound, ParametrizedFactory<NBT_T, V> valueDeserializer) {
        return readMap(name, compound, Objects::toString, valueDeserializer);
    }

    public static <K, V, NBT_T extends NBTBase> Map<K, V> readMap(String name, NBTTagCompound compound, ParametrizedFactory<String, K> keyDeserializer, ParametrizedFactory<NBT_T, V> valueDeserializer) {
        return mapFromNBT(compound.getCompoundTag(name), HashMap::new, keyDeserializer, valueDeserializer);
    }

    public static NBTTagCompound mapToNBT(Map<?, ? extends INBTSerializable<? extends NBTBase>> map) {
        NBTTagCompound compound = new NBTTagCompound();
        map.forEach((key, value) -> write(key.toString(), value, compound));
        return compound;
    }

    public static <T> NBTTagCompound mapToNBT(Map<?, T> map, ParametrizedFactory<T, ? extends NBTBase> serializer) {
        NBTTagCompound compound = new NBTTagCompound();
        map.forEach((key, value) -> write(key.toString(), serializer.create(value), compound));
        return compound;
    }

    public static <V, NBT_T extends NBTBase> Map<String, V> mapFromNBT(NBTTagCompound compound, ParametrizedFactory<NBT_T, V> valueDeserializer) {
        return mapFromNBT(compound, Objects::toString, valueDeserializer);
    }

    public static <K, V, NBT_T extends NBTBase> Map<K, V> mapFromNBT(NBTTagCompound compound, ParametrizedFactory<String, K> keyDeserializer, ParametrizedFactory<NBT_T, V> valueDeserializer) {
        return mapFromNBT(compound, HashMap::new, keyDeserializer, valueDeserializer);
    }

    public static <K, V, NBT_T extends NBTBase> Map<K, V> mapFromNBT(NBTTagCompound compound, Factory<Map<K, V>> mapFactory, ParametrizedFactory<String, K> keyDeserializer, ParametrizedFactory<NBT_T, V> valueDeserializer) {
        Map<K, V> map = mapFactory.create();
        compound.getKeySet().forEach(key -> map.put(keyDeserializer.create(key), read(key, compound, valueDeserializer)));
        return map;
    }

    public static void write(String key, INBTSerializable<? extends NBTBase> value, NBTTagCompound compound) {
        compound.setTag(key, value.serializeNBT());
    }

    public static void write(String key, Object value, NBTTagCompound compound) {
        if (value instanceof Integer) {
            compound.setInteger(key, (Integer) value);
        } else if (value instanceof Long) {
            compound.setLong(key, (Long) value);
        } else if (value instanceof String) {
            compound.setString(key, (String) value);
        } else if (value instanceof Boolean) {
            compound.setBoolean(key, (Boolean) value);
        } else if (value instanceof Float) {
            compound.setFloat(key, (Float) value);
        } else if (value instanceof Double) {
            compound.setDouble(key, (Double) value);
        } else if (value instanceof Byte) {
            compound.setByte(key, (Byte) value);
        } else if (value instanceof NBTBase) {
            compound.setTag(key, (NBTBase) value);
        } else if (value instanceof byte[]) {
            compound.setByteArray(key, (byte[]) value);
        } else if (value instanceof int[]) {
            compound.setIntArray(key, (int[]) value);
        }
    }

    public static <T, NBT_T> T read(String key, NBTTagCompound compound, ParametrizedFactory<NBT_T, T> factory) {
        return factory.create((NBT_T) compound.getTag(key));
    }

    public static Object read(String key, NBTTagCompound compound) {
        NBTBase tag = compound.getTag(key);
        if (tag instanceof NBTTagInt) {
            return ((NBTTagInt) tag).getInt();
        } else if (tag instanceof NBTTagLong) {
            return ((NBTTagLong) tag).getLong();
        } else if (tag instanceof NBTTagString) {
            return ((NBTTagString) tag).getString();
        } else if (tag instanceof NBTTagByte) {
            return ((NBTTagByte) tag).getByte();
        } else if (tag instanceof NBTTagFloat) {
            return ((NBTTagFloat) tag).getFloat();
        } else if (tag instanceof NBTTagDouble) {
            return ((NBTTagDouble) tag).getDouble();
        } else if (tag instanceof NBTTagByteArray) {
            return ((NBTTagByteArray) tag).getByteArray();
        } else if (tag instanceof NBTTagIntArray) {
            return ((NBTTagIntArray) tag).getIntArray();
        }
        return null;
    }

    public static NBTTagList getTagList(String name, NBTTagCompound compound) {
        NBTBase nbt = compound.tagMap.get(name);
        if (!(nbt instanceof NBTTagList)) {
            throw new IllegalArgumentException("Tag got by name " + name + "isn't NBTTagList!");
        }
        return (NBTTagList) nbt;
    }

    public static NBTBase writeBlockPos(BlockPos pos) {
        return new NBTTagLong(pos.toLong());
    }

    public static BlockPos readBlockPos(NBTBase nbt) {
        return BlockPos.fromLong(((NBTTagLong) nbt).getLong());
    }

    public static void writeBlockPos(String key, BlockPos pos, NBTTagCompound compound) {
        compound.setLong(key, pos.toLong());
    }

    public static BlockPos readBlockPos(String key, NBTTagCompound compound) {
        return BlockPos.fromLong(compound.getLong(key));
    }

    public static NBTBase writeState(IBlockState state) {
        if (state == null) {
            return NBT_NULL;
        }
        NBTTagCompound compound = new NBTTagCompound();
        compound.setString("name", state.getBlock().getRegistryName().toString());
        compound.setShort("meta", (short) state.getBlock().getMetaFromState(state));
        return compound;
    }

    public static IBlockState readState(NBTBase nbt) {
        if (nbt.equals(NBT_NULL)) {
            return null;
        }
        NBTTagCompound compound = (NBTTagCompound) nbt;
        String blockName = compound.getString("name");
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(blockName));
        return block.getStateFromMeta(compound.getShort("meta"));
    }

    public static void writeAABB(String key, NBTTagCompound compound, AxisAlignedBB aabb) {
        compound.setTag(key, NBTTagCompoundBuilder
                    .create()
                    .setDouble("minX", aabb.minX)
                    .setDouble("minY", aabb.minY)
                    .setDouble("minZ", aabb.minZ)
                    .setDouble("maxX", aabb.maxX)
                    .setDouble("maxY", aabb.maxY)
                    .setDouble("maxZ", aabb.maxZ)
                    .build());
    }

    public static AxisAlignedBB readAABB(String key, NBTTagCompound compound) {
        NBTTagCompound tag = compound.getCompoundTag(key);
        return new AxisAlignedBB(
                tag.getDouble("minX"),
                tag.getDouble("minY"),
                tag.getDouble("minZ"),
                tag.getDouble("maxX"),
                tag.getDouble("maxY"),
                tag.getDouble("maxZ")
        );
    }

    public static NBTBase writeTileEntity(final TileEntity tileEntity) {
        if (tileEntity == null) {
            return NBT_NULL;
        }

        final NBTTagCompound compound = new NBTTagCompound();
        tileEntity.writeToNBT(compound);
        return compound;
    }

    public static TileEntity readTileEntity(final NBTBase compound) {
        return readTileEntity(compound, null);
    }

    public static TileEntity readTileEntity(final NBTBase nbt, final World world) {
        if (nbt.equals(NBT_NULL)) {
            return null;
        }
        NBTTagCompound compound = (NBTTagCompound) nbt;
        return TileEntity.create(world, compound);
    }

    public static NBTTagCompound writeEntityToCompound(final Entity entity) {
        final NBTTagCompound entityCompound = new NBTTagCompound();
        if (entity.writeToNBTOptional(entityCompound)) {
            return entityCompound;
        }

        return null;
    }

    public static Entity readEntityFromCompound(final NBTTagCompound nbtTagCompound, final World world) {
        return EntityList.createEntityFromNBT(nbtTagCompound, world);
    }
}
