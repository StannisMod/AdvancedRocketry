package zmaster587.advancedRocketry.api.capability;

import net.minecraft.nbt.NBTBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;

import javax.annotation.Nullable;

/**
 * Capability holding the {@link IPartWear} wear state of a rocket part.
 * Registered the same way as {@link CapabilitySpaceArmor}. The hosting tile
 * (today always {@link zmaster587.advancedRocketry.tile.TileBrokenPart})
 * persists the stage in its own NBT, so the capability {@code IStorage} is a
 * no-op.
 */
public class CapabilityWear {

    @CapabilityInject(IPartWear.class)
    public static Capability<IPartWear> PART_WEAR = null;

    public CapabilityWear() {
    }

    /** Convenience: the wear capability on a tile entity, or null if absent. */
    @Nullable
    public static IPartWear get(@Nullable TileEntity te) {
        if (te == null || PART_WEAR == null) {
            return null;
        }
        return te.getCapability(PART_WEAR, null);
    }

    public static void register() {
        CapabilityManager.INSTANCE.register(IPartWear.class, new Capability.IStorage<IPartWear>() {
            @Override
            public void readNBT(Capability<IPartWear> capability, IPartWear instance, EnumFacing side, NBTBase nbt) {
            }

            @Override
            public NBTBase writeNBT(Capability<IPartWear> capability, IPartWear instance, EnumFacing side) {
                return null;
            }
        }, DefaultPartWear::new);
    }

    /** Trivial standalone implementation for foreign hosts that want a backing store. */
    public static class DefaultPartWear implements IPartWear {
        private int stage;
        private int maxStage;

        @Override
        public int getStage() {
            return stage;
        }

        @Override
        public int getMaxStage() {
            return maxStage;
        }

        @Override
        public void setStage(int stage) {
            this.stage = stage;
        }

        @Override
        public boolean transition() {
            return false;
        }
    }
}
