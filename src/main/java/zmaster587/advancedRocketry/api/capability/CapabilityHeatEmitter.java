package zmaster587.advancedRocketry.api.capability;

import net.minecraft.nbt.NBTBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;

import javax.annotation.Nullable;

/**
 * The capability holding a machine's {@link IHeatEmitter} waste heat, registered the same way as
 * {@link CapabilityWear}. The host keeps whatever state it needs in its own NBT, so the
 * {@code IStorage} is a no-op.
 * <p>
 * This is the ONE way a coolant loop learns that a machine makes heat — ours and foreign alike.
 * There is deliberately no registry keyed by machine class: such a table can only ever be filled in
 * by us, and the machines the thermal system exists for belong to other mods.
 */
public class CapabilityHeatEmitter {

    @CapabilityInject(IHeatEmitter.class)
    public static Capability<IHeatEmitter> HEAT_EMITTER = null;

    public CapabilityHeatEmitter() {
    }

    /** Convenience: the heat capability on a tile entity, or null if it makes no heat. */
    @Nullable
    public static IHeatEmitter get(@Nullable TileEntity te) {
        if (te == null || te.isInvalid() || HEAT_EMITTER == null) {
            return null;
        }
        return te.getCapability(HEAT_EMITTER, null);
    }

    public static void register() {
        CapabilityManager.INSTANCE.register(IHeatEmitter.class, new Capability.IStorage<IHeatEmitter>() {
            @Override
            public void readNBT(Capability<IHeatEmitter> capability, IHeatEmitter instance, EnumFacing side, NBTBase nbt) {
            }

            @Override
            public NBTBase writeNBT(Capability<IHeatEmitter> capability, IHeatEmitter instance, EnumFacing side) {
                return null;
            }
        }, DefaultHeatEmitter::new);
    }

    /** A backing store for a foreign host that wants one, with the drain contract implemented. */
    public static class DefaultHeatEmitter implements IHeatEmitter {
        private int pending;

        public void add(int heat) {
            pending = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) pending + Math.max(0, heat)));
        }

        @Override
        public int getPendingHeat() {
            return pending;
        }

        @Override
        public int takeHeat(int amount) {
            int taken = Math.max(0, Math.min(amount, pending));
            pending -= taken;
            return taken;
        }
    }
}
