package zmaster587.advancedRocketry.api.capability;

import net.minecraft.nbt.NBTBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;

import javax.annotation.Nullable;

/**
 * The capability holding a machine's {@link IHeatPump} lift, registered the way the others are. The
 * host keeps its own state in its own NBT, so the {@code IStorage} is a no-op.
 * <p>
 * A capability rather than an interface for the same reason as the emitter seam: another mod's
 * cooling machine should be able to raise a coolant loop's radiating temperature without us knowing
 * its class, and our own chiller reads through exactly the same call.
 */
public class CapabilityHeatPump {

    @CapabilityInject(IHeatPump.class)
    public static Capability<IHeatPump> HEAT_PUMP = null;

    public CapabilityHeatPump() {
    }

    /** Convenience: the pump capability on a tile entity, or null if it is not one. */
    @Nullable
    public static IHeatPump get(@Nullable TileEntity te) {
        if (te == null || te.isInvalid() || HEAT_PUMP == null) {
            return null;
        }
        return te.getCapability(HEAT_PUMP, null);
    }

    public static void register() {
        CapabilityManager.INSTANCE.register(IHeatPump.class, new Capability.IStorage<IHeatPump>() {
            @Override
            public void readNBT(Capability<IHeatPump> capability, IHeatPump instance, EnumFacing side, NBTBase nbt) {
            }

            @Override
            public NBTBase writeNBT(Capability<IHeatPump> capability, IHeatPump instance, EnumFacing side) {
                return null;
            }
        }, DefaultHeatPump::new);
    }

    /** A do-nothing default: shifts nothing and pays nothing, so an unimplemented host is inert. */
    public static class DefaultHeatPump implements IHeatPump {
        @Override
        public int getThroughputPerTick() {
            return 0;
        }

        @Override
        public boolean drawsFrom(net.minecraft.util.math.BlockPos loopMemberPos) {
            return false;
        }

        @Override
        public net.minecraft.util.math.BlockPos getHotSideAnchor() {
            return null;
        }

        @Override
        public long payWork(long work) {
            return 0L;
        }
    }
}
