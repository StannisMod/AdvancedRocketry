package zmaster587.advancedRocketry.tile.hyperdrive;

import javax.annotation.Nullable;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.EnergyStorage;

import zmaster587.advancedRocketry.hyperdrive.DriveTuning;
import zmaster587.advancedRocketry.tile.TileShipComponent;

/**
 * What stands between a crew and the bulkhead when a jump ends the wrong way.
 *
 * <p>Arriving where you meant to costs nobody anything. A window that collapses mid-flight does not
 * ask: the transit's speed has to go somewhere, and without a dampener it goes into everybody
 * aboard. A powered dampener eats that speed up to what it is built to eat; whatever is left over is
 * what the crew feels.</p>
 *
 * <p>Reach is the second lever, and it is why one dampener is not an answer for every ship. A
 * dampener protects who is near it, so a hull big enough to need several needs several.</p>
 *
 * <p>It holds a small energy buffer rather than drawing per tick: what matters is that it has power
 * at the single instant the window fails, and a buffer is what makes that true for a ship whose
 * generators are on the far side of a battle.</p>
 */
public class TileGravityDampener extends TileShipComponent {

    private static final String NBT_ENERGY = "dampenerEnergy";

    /** Buffer size. Big enough to survive the moment it exists for; not a power system of its own. */
    private static final int BUFFER = 20_000;
    /** Energy below which the dampener is dark and protects nobody. */
    private static final int POWERED_THRESHOLD = 1_000;

    private final EnergyStorage energy = new EnergyStorage(BUFFER);

    /** Whether this dampener has the power to do anything at the moment it is asked. */
    public boolean isPowered() {
        return energy.getEnergyStored() >= POWERED_THRESHOLD;
    }

    /** The exit speed this dampener fully absorbs for everyone it covers. */
    public long absorbedSpeed() {
        return DriveTuning.DAMPENER_ABSORBED_SPEED;
    }

    /** Energy stored here, counted toward what the ship has aboard for a flight. */
    public int storedEnergy() {
        return energy.getEnergyStored();
    }

    /** Charge the buffer directly. Used by fixtures and by creative-mode setup. */
    public void charge(int amount) {
        energy.receiveEnergy(amount, false);
        markDirty();
    }

    @Override
    public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability,
                                 @Nullable EnumFacing facing) {
        return capability == CapabilityEnergy.ENERGY
                || super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,
                               @Nullable EnumFacing facing) {
        if (capability == CapabilityEnergy.ENERGY) {
            return CapabilityEnergy.ENERGY.cast(energy);
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setInteger(NBT_ENERGY, energy.getEnergyStored());
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        int stored = nbt.getInteger(NBT_ENERGY);
        // EnergyStorage has no setter; refill an emptied buffer to the persisted level.
        energy.extractEnergy(Integer.MAX_VALUE, false);
        energy.receiveEnergy(stored, false);
    }
}
