package zmaster587.advancedRocketry.api.capability;

import net.minecraft.nbt.NBTBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;

import zmaster587.advancedRocketry.api.damage.DamageOccurrence;
import zmaster587.advancedRocketry.api.damage.IDamageAware;

import javax.annotation.Nullable;

/**
 * Capability carrying {@link IDamageAware} — a unit's willingness to be told what broke it.
 * Registered exactly as {@link CapabilityWear} is.
 *
 * <p><b>There is no storage.</b> An occurrence is news, not state: it is true at one moment and has no
 * persistent form, so there is nothing to write to NBT and nothing to read back. A unit that turns an
 * occurrence into durable state — a tripped breaker, a scrammed reactor — persists THAT in its own
 * tile, where it belongs, exactly as a worn part persists its stage.</p>
 */
public class CapabilityDamageAware {

    @CapabilityInject(IDamageAware.class)
    public static Capability<IDamageAware> DAMAGE_AWARE = null;

    public CapabilityDamageAware() {
    }

    /** The capability on a tile, or null when it has none — which is the ordinary case. */
    @Nullable
    public static IDamageAware get(@Nullable TileEntity te) {
        if (te == null || DAMAGE_AWARE == null) {
            return null;
        }
        return te.getCapability(DAMAGE_AWARE, null);
    }

    public static void register() {
        CapabilityManager.INSTANCE.register(IDamageAware.class, new Capability.IStorage<IDamageAware>() {
            @Override
            public void readNBT(Capability<IDamageAware> capability, IDamageAware instance,
                                EnumFacing side, NBTBase nbt) {
            }

            @Override
            public NBTBase writeNBT(Capability<IDamageAware> capability, IDamageAware instance,
                                    EnumFacing side) {
                return null;
            }
        }, DeafUnit::new);
    }

    /**
     * The default the capability system requires: a unit that hears and does nothing.
     *
     * <p>It exists because {@code CapabilityManager.register} demands a factory, not because anybody
     * should attach one. A unit with no reaction is better served by not carrying the capability at
     * all — then nothing is even looked up for it.</p>
     */
    public static class DeafUnit implements IDamageAware {
        @Override
        public void onDamage(DamageOccurrence occurrence) {
        }
    }
}
