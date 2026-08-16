package zmaster587.advancedRocketry.tile.atmosphere;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;
import zmaster587.advancedRocketry.atmosphere.LifeSupportNetwork;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemSource;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkRegistry;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModulePower;
import zmaster587.libVulpes.inventory.modules.ModuleSlotArray;
import zmaster587.libVulpes.tile.TileInventoriedRFConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier 4: the ship's central regeneration plant. Same Bosch chemistry as the tier-3 recirculator,
 * but it serves every zone reachable through the ventilation network instead of the room it stands
 * in — which is the whole point of the tier, and the reason it can be fed by a single power run.
 * <p>
 * It is a network SOURCE: it offers regeneration work, and the network decides how much of it
 * actually reaches which zone, bounded by the ducts in between. The carbon it removes lands in its
 * own output slot; a full slot backs the plant up rather than deleting the carbon, exactly as it
 * does one tier down.
 */
public class TileLifeSupportPlant extends TileInventoriedRFConsumer implements IModularInventory, ISubsystemSource {

    /** Regeneration work done but not yet worth a whole dust, in µatm·blocks. */
    private int carbonBuffer;

    public TileLifeSupportPlant() {
        super(100000, 1);
    }

    // ─── network node ──────────────────────────────────────────────────

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return LifeSupportNetwork.DOMAIN;
    }

    @Override
    public World getNodeWorld() {
        return world;
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    /**
     * What the plant can convert this tick: its rate, cut by the power it actually holds and by
     * whether there is anywhere to put the carbon. Offering work it cannot pay for would make the
     * network promise a zone air the plant is unable to deliver.
     */
    @Override
    public int getAvailable() {
        if (world == null || world.isRemote || !ARConfiguration.getCurrentConfig().lifeSupportZones)
            return 0;
        if (!hasRoomForDust())
            return 0;
        int rate = LifeSupportNetwork.perTick(ARConfiguration.getCurrentConfig().lifeSupportPlantRate);
        if (rate <= 0)
            return 0;
        int powerPerTick = LifeSupportNetwork.perTick(ARConfiguration.getCurrentConfig().lifeSupportPlantPower);
        if (powerPerTick <= 0)
            return rate;
        // Power is charged in proportion to the work done, so what the buffer holds bounds the
        // offer rather than gating it all-or-nothing.
        long affordable = (long) rate * energy.getUniversalEnergyStored() / powerPerTick;
        return (int) Math.max(0, Math.min(rate, affordable));
    }

    @Override
    public int extract(int amount) {
        if (amount <= 0)
            return 0;
        int rate = LifeSupportNetwork.perTick(ARConfiguration.getCurrentConfig().lifeSupportPlantRate);
        int taken = Math.min(amount, Math.max(0, rate));
        if (taken <= 0)
            return 0;

        int powerPerTick = LifeSupportNetwork.perTick(ARConfiguration.getCurrentConfig().lifeSupportPlantPower);
        if (powerPerTick > 0 && rate > 0) {
            int cost = (int) ((long) powerPerTick * taken / rate);
            energy.extractEnergy(cost, false);
        }

        carbonBuffer += taken;
        emitDust();
        markDirty();
        return taken;
    }

    /** Purely for the readout: production is what it can convert, never what its power buffer holds. */
    @Override
    public int getGenerationPerTick() {
        return getAvailable();
    }

    // ─── the carbon it takes out of the air ────────────────────────────

    private void emitDust() {
        int perDust = Math.max(1, ARConfiguration.getCurrentConfig().lifeSupportPlantCarbonPerDust);
        while (carbonBuffer >= perDust) {
            ItemStack slot = getStackInSlot(0);
            if (slot.isEmpty()) {
                setInventorySlotContents(0, new ItemStack(AdvancedRocketryItems.itemCarbonDust, 1));
            } else if (slot.getCount() < slot.getMaxStackSize()) {
                slot.grow(1);
            } else {
                return;
            }
            carbonBuffer -= perDust;
        }
    }

    private boolean hasRoomForDust() {
        ItemStack slot = getStackInSlot(0);
        return slot.isEmpty()
                || (slot.getItem() == AdvancedRocketryItems.itemCarbonDust && slot.getCount() < slot.getMaxStackSize());
    }

    // ─── tile plumbing ─────────────────────────────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            SubsystemNetworkRegistry.register(this);
            SubsystemNetworkManager.markDirty(LifeSupportNetwork.DOMAIN, world);
        }
    }

    @Override
    public void invalidate() {
        leaveNetwork();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        leaveNetwork();
        super.onChunkUnload();
    }

    private void leaveNetwork() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkRegistry.unregister(this);
            SubsystemNetworkManager.markDirty(LifeSupportNetwork.DOMAIN, world);
        }
    }

    /**
     * The plant does its work through the network solve, not through a machine cycle of its own —
     * the network is what knows which zones exist and what the ducts allow.
     */
    @Override
    public boolean canPerformFunction() {
        return false;
    }

    @Override
    public void performFunction() {
    }

    @Override
    public int getPowerPerOperation() {
        return 0;
    }

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[]{0};
    }

    @Override
    public boolean canInsertItem(int slot, @Nonnull ItemStack stack, @Nullable EnumFacing side) {
        return false;
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        return false;
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockLifeSupportPlant.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>(2);
        modules.add(new ModulePower(18, 20, this.energy));
        modules.add(new ModuleSlotArray(80, 35, this, 0, 1));
        return modules;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        carbonBuffer = nbt.getInteger("carbonBuffer");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setInteger("carbonBuffer", carbonBuffer);
        return nbt;
    }
}
