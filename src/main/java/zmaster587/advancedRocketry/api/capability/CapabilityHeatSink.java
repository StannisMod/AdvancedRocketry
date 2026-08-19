package zmaster587.advancedRocketry.api.capability;

import net.minecraft.nbt.NBTBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;

import javax.annotation.Nullable;

/**
 * The capability through which a coolant loop finds a machine that will TAKE its heat, registered
 * exactly like {@link CapabilityHeatEmitter}. The host keeps its own state in its own NBT, so the
 * {@code IStorage} is a no-op.
 * <p>
 * One read path and no {@code instanceof}: a loop does not know what a heat dump is, only that
 * something beside it answers this capability.
 */
public class CapabilityHeatSink {

    @CapabilityInject(IHeatSink.class)
    public static Capability<IHeatSink> HEAT_SINK = null;

    public CapabilityHeatSink() {
    }

    /** Convenience: the sink capability on a tile entity, or null if it takes no heat. */
    @Nullable
    public static IHeatSink get(@Nullable TileEntity te) {
        if (te == null || te.isInvalid() || HEAT_SINK == null) {
            return null;
        }
        return te.getCapability(HEAT_SINK, null);
    }

    public static void register() {
        CapabilityManager.INSTANCE.register(IHeatSink.class, new Capability.IStorage<IHeatSink>() {
            @Override
            public void readNBT(Capability<IHeatSink> capability, IHeatSink instance, EnumFacing side, NBTBase nbt) {
            }

            @Override
            public NBTBase writeNBT(Capability<IHeatSink> capability, IHeatSink instance, EnumFacing side) {
                return null;
            }
        }, () -> new DefaultHeatSink(0L));
    }

    /** A backing store for a foreign host that wants one, with the drain contract implemented. */
    /**
     * The plain implementation for a machine that simply holds what it is given up to a limit - the
     * dump keeps its own, so this exists for anything given the capability from outside.
     */
    public static class DefaultHeatSink implements IHeatSink {
        private final long capacity;
        private long held;

        public DefaultHeatSink(long capacity) {
            this.capacity = Math.max(0L, capacity);
        }

        public long held() {
            return held;
        }

        @Override
        public long getSinkRequestPerTick(double loopKelvin) {
            return Math.max(0L, capacity - held);
        }

        @Override
        public long acceptHeat(long amount) {
            long taken = Math.max(0L, Math.min(amount, capacity - held));
            held += taken;
            return taken;
        }
    }
}
