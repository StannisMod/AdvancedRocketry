package zmaster587.advancedRocketry.tile.atmosphere;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;
import zmaster587.advancedRocketry.atmosphere.AirState;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.tile.TileInventoriedRFConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tier 3 of the life-support progression: a powered block that regenerates the oxygen of the
 * sealed room it stands in, and ends the era of consumable scrubber cartridges.
 * <p>
 * It serves ONE zone — its own — and has no pipes: power in, solid carbon out. The whole-ship
 * ventilation network belongs to the centralised tier above this one. "Recirculator" names the
 * chemistry rather than any airflow: the Bosch reaction spends hydrogen and electrolysis of the
 * recovered water gives it back, so the hydrogen carrier recirculates inside the machine and the
 * net reaction is carbon dioxide in, oxygen and solid carbon out.
 */
public class TileAirRecirculator extends TileInventoriedRFConsumer implements IModularInventory {

    /** Carbon regenerated but not yet worth a whole item, in the same unit as partial pressure. */
    private int carbonBuffer;

    public TileAirRecirculator() {
        super(10000, 1);
    }

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[]{0};
    }

    @Override
    public boolean canInsertItem(int slot, @Nonnull ItemStack stack, @Nullable EnumFacing side) {
        // Output only: the dust leaves, nothing is fed in. A hopper pointing at this machine must
        // not be able to jam its one slot with something it will never consume.
        return false;
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        return false;
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockAirRecirculator.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(net.minecraft.entity.player.EntityPlayer entity) {
        return true;
    }

    @Override
    public java.util.List<zmaster587.libVulpes.inventory.modules.ModuleBase> getModules(int ID, net.minecraft.entity.player.EntityPlayer player) {
        java.util.List<zmaster587.libVulpes.inventory.modules.ModuleBase> modules = new java.util.ArrayList<>(2);
        modules.add(new zmaster587.libVulpes.inventory.modules.ModulePower(18, 20, this.energy));
        modules.add(new zmaster587.libVulpes.inventory.modules.ModuleSlotArray(80, 35, this, 0, 1));
        return modules;
    }

    @Override
    public int getPowerPerOperation() {
        return ARConfiguration.getCurrentConfig().lifeSupportRecirculatorPower;
    }

    @Override
    public boolean canPerformFunction() {
        return !world.isRemote
                && ARConfiguration.getCurrentConfig().lifeSupportZones
                && world.getTotalWorldTime() % 20 == 0
                && hasCarbonDioxideToProcess()
                && hasRoomForDust();
    }

    @Override
    public void performFunction() {
        AirState air = getZoneAir();
        if (air == null)
            return;

        int regenerated = air.regenerate(ARConfiguration.getCurrentConfig().lifeSupportRecirculatorRate);
        if (regenerated <= 0)
            return;

        carbonBuffer += regenerated;
        emitDust();

        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        if (handler != null)
            handler.refreshDerivedAtmosphereAt(pos);
        markDirty();
    }

    /**
     * Turn buffered carbon into items. The buffer exists because a second of regeneration is worth
     * a fraction of a dust: without it the machine would either drop nothing or round its output
     * up every tick, and the carbon it vents would stop matching the carbon it removed.
     */
    private void emitDust() {
        int perDust = Math.max(1, ARConfiguration.getCurrentConfig().lifeSupportCarbonPerDust);
        while (carbonBuffer >= perDust) {
            ItemStack slot = getStackInSlot(0);
            if (slot.isEmpty()) {
                setInventorySlotContents(0, new ItemStack(AdvancedRocketryItems.itemCarbonDust, 1));
            } else if (slot.getCount() < slot.getMaxStackSize()) {
                slot.grow(1);
            } else {
                // Full. Stop converting rather than deleting the carbon: a blocked output backs
                // the machine up, which is the routing cost this tier is supposed to have.
                return;
            }
            carbonBuffer -= perDust;
        }
    }

    private boolean hasRoomForDust() {
        ItemStack slot = getStackInSlot(0);
        return slot.isEmpty() || (slot.getItem() == AdvancedRocketryItems.itemCarbonDust && slot.getCount() < slot.getMaxStackSize());
    }

    private boolean hasCarbonDioxideToProcess() {
        AirState air = getZoneAir();
        return air != null && air.getCarbonDioxide() > 0;
    }

    @Nullable
    private AirState getZoneAir() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        return handler == null ? null : handler.getAirStateAt(pos);
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
