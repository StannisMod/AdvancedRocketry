package zmaster587.advancedRocketry.util.nbt;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagList;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

public class NBTTagListCollector implements Collector<NBTBase, NBTTagList, NBTTagList> {

    static final Set<Characteristics> CH_UNORDERED_ID
            = Collections.unmodifiableSet(EnumSet.of(Characteristics.UNORDERED,
            Characteristics.IDENTITY_FINISH));

    @Override
    public Supplier<NBTTagList> supplier() {
        return NBTTagList::new;
    }

    @Override
    public BiConsumer<NBTTagList, NBTBase> accumulator() {
        return NBTTagList::appendTag;
    }

    @Override
    public BinaryOperator<NBTTagList> combiner() {
        return (o1, o2) -> {
            o2.forEach(o1::appendTag);
            return o1;
        };
    }

    @Override
    public Function<NBTTagList, NBTTagList> finisher() {
        return Function.identity();
    }

    @Override
    public Set<Characteristics> characteristics() {
        return CH_UNORDERED_ID;
    }
}
