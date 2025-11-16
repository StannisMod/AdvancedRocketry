package zmaster587.advancedRocketry.tile.atmosphere;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.AdvancedRocketryFluids;
import zmaster587.advancedRocketry.api.armor.IFillableArmor;
import zmaster587.advancedRocketry.util.ItemAirUtils;
import zmaster587.libVulpes.api.IModularArmor;
import zmaster587.libVulpes.gui.CommonResources;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.tile.TileInventoriedRFConsumerTank;
import zmaster587.libVulpes.util.FluidUtils;
import zmaster587.libVulpes.util.IconResource;
import zmaster587.libVulpes.cap.TeslaHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class TileGasChargePad extends TileInventoriedRFConsumerTank implements IModularInventory {
    private static final int TICK_INTERVAL = 2; 
    // Avoid per-tick AABB allocation: cache lazily
    @Nullable
    private AxisAlignedBB cachedPlayerBox;    
    
    public TileGasChargePad() {
        super(0, 2, 16000);
    }

    // Lazy AABB getter
    private AxisAlignedBB getPlayerBox() {
        if (cachedPlayerBox == null) {
            cachedPlayerBox = new AxisAlignedBB(pos, pos.add(1, 2, 1));
        }
        return cachedPlayerBox;
    }

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[]{};
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        return false;
    }

    @Override
    public boolean canFill(Fluid fluid) {
        return FluidUtils.areFluidsSameType(fluid, AdvancedRocketryFluids.fluidOxygen) || FluidUtils.areFluidsSameType(fluid, AdvancedRocketryFluids.fluidHydrogen);
    }

    @Override
    public int getPowerPerOperation() {
        return 0;
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        // Hide Forge Energy capability
        if (capability == CapabilityEnergy.ENERGY) return false;
        // Hide any Tesla capability the base class would expose
        if (TeslaHandler.hasTeslaCapability(this, capability)) return false;
        return super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        // Don’t provide energy handlers to probes/pipes
        if (capability == CapabilityEnergy.ENERGY) return null;
        if (TeslaHandler.hasTeslaCapability(this, capability)) return null;
        return super.getCapability(capability, facing);
    }

    // Optional (extra safety for mods that query IPower-style methods directly)
    @Override public boolean canConnectEnergy(EnumFacing side) { return false; }
    @Override public boolean canReceive() { return false; }
    @Override public int getEnergyStored(EnumFacing side) { return 0; }
    @Override public int getMaxEnergyStored(EnumFacing side) { return 0; }

    @Override
    public boolean canPerformFunction() {
        if (!world.isRemote) {

            // Throttle: only run every TICK_INTERVAL ticks
            if ((world.getTotalWorldTime() % TICK_INTERVAL) != 0) {
                return false;
            }

            FluidStack tf = this.tank.getFluid();
            if (tf == null || tf.amount <= 0) {
                return false;
            }

            for (EntityPlayer player : this.world.getEntitiesWithinAABB(EntityPlayer.class, getPlayerBox())) {
                ItemStack stack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

                if (!stack.isEmpty()) {
                    IFillableArmor fillable = null;

                    if (stack.getItem() instanceof IFillableArmor)
                        fillable = (IFillableArmor) stack.getItem();
                    else if (ItemAirUtils.INSTANCE.isStackValidAirContainer(stack))
                        fillable = new ItemAirUtils.ItemAirWrapper(stack);

                    //Check for O2 fill
                    if (fillable != null) {
                        int deficit = fillable.getMaxAir(stack) - fillable.getAirRemaining(stack);
                        tf = this.tank.getFluid(); // refresh
                        if (deficit > 0 && tf != null
                                && FluidUtils.areFluidsSameType(tf.getFluid(), AdvancedRocketryFluids.fluidOxygen)
                                && tf.amount > 0) {
                            int toDrain = Math.min(deficit, tf.amount);
                            FluidStack drained = this.drain(toDrain, true);
                            if (drained != null && drained.amount > 0) {
                                fillable.increment(stack, drained.amount);
                                this.markDirty(); // no world.markChunkDirty
                                return true;
                            }
                        }
                    }
                }

                //Check for H2 fill (possibly merge with O2 fill
                //Fix conflict with O2 fill
                if (this.tank.getFluid() != null && !FluidUtils.areFluidsSameType(this.tank.getFluid().getFluid(), AdvancedRocketryFluids.fluidOxygen) && !stack.isEmpty() && stack.getItem() instanceof IModularArmor) {
                    IInventory inv = ((IModularArmor) stack.getItem()).loadModuleInventory(stack);

                    // Create a trial FluidStack up to available amount
                    final int perAttempt = 100 * TICK_INTERVAL;
                    int trialAmt = Math.min(perAttempt, this.tank.getFluid().amount);
                    if (trialAmt > 0) {
                        FluidStack trial = new FluidStack(this.tank.getFluid(), trialAmt);
                        for (int i = 0; i < inv.getSizeInventory(); i++) {

                            if (!((IModularArmor) stack.getItem()).canBeExternallyModified(stack, i))
                                continue;

                            ItemStack module = inv.getStackInSlot(i);
                            if (!FluidUtils.containsFluid(module)) continue; // fast path
                            net.minecraftforge.fluids.capability.IFluidHandlerItem fh =
                                    module.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, EnumFacing.UP);
                            if (fh == null) continue; // null-guard

                            int amtFilled = fh.fill(trial, true);
                            // Accept partial fills: drain exactly what was accepted
                            if (amtFilled > 0) {
                                this.drain(amtFilled, true);
                                this.markDirty(); // no world.markChunkDirty
                                ((IModularArmor) stack.getItem()).saveModuleInventory(stack, inv);
                                return true;
                            }
                         }
                     }
                 }
             }
            // no player matched this tick
            return false;
         }
         return false;
     }

    @Override
    public void performFunction() {

    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        ArrayList<ModuleBase> modules = new ArrayList<>();

        modules.add(new ModuleSlotArray(50, 21, this, 0, 1));
        modules.add(new ModuleSlotArray(50, 57, this, 1, 2));
        if (world.isRemote)
            modules.add(new ModuleImage(49, 38, new IconResource(194, 0, 18, 18, CommonResources.genericBackground)));

        //modules.add(new ModulePower(18, 20, this));
        modules.add(new ModuleLiquidIndicator(32, 20, this));

        //modules.add(toggleSwitch = new ModuleToggleSwitch(160, 5, 0, "", this, TextureResources.buttonToggleImage, 11, 26, getMachineEnabled()));
        return modules;
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockOxygenCharger.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);
        while (useBucket(0, getStackInSlot(0))) ;
    }

    private boolean useBucket(int slot, @Nonnull ItemStack stack) {
        return FluidUtils.attemptDrainContainerIInv(inventory, tank, stack, 0, 1);
    }

    @Override
    public boolean isEmpty() {
        return inventory.isEmpty();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        cachedPlayerBox = null; // drop cached AABB
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        cachedPlayerBox = null; // drop cached AABB
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Ensure cache recomputes from the current pos after NBT load
        cachedPlayerBox = null;
    }    
}
