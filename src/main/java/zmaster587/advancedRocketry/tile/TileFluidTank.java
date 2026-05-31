package zmaster587.advancedRocketry.tile;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.world.util.WorldDummy;
import zmaster587.libVulpes.tile.multiblock.hatch.TileFluidHatch;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TileFluidTank extends TileFluidHatch {

    private static final int MAX_UPDATE = 5;
    private long lastUpdateTime;
    private boolean fluidChanged;

    private boolean removing = false;
    private boolean inColumnOp = false;

    public void setRemoving(boolean removing) { this.removing = removing; }

    public TileFluidTank() {
        super();
        fluidChanged = false;
    }

    public TileFluidTank(int i) {
        super(i);
        fluidChanged = false;
    }

    // Single, reusable delegating handler (column-aware)
    private final net.minecraftforge.fluids.capability.IFluidHandler selfHandler =
        new net.minecraftforge.fluids.capability.IFluidHandler() {
            @Override public int fill(FluidStack r, boolean doFill) { return TileFluidTank.this.fill(r, doFill); }
            @Override public FluidStack drain(FluidStack r, boolean doDrain) { return TileFluidTank.this.drain(r, doDrain); }
            @Override public FluidStack drain(int max, boolean doDrain) { return TileFluidTank.this.drain(max, doDrain); }
            @Override public IFluidTankProperties[] getTankProperties() { return TileFluidTank.this.getTankProperties(); }
        };

    @Override
    public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> cap,
                                @Nullable EnumFacing side) {
        if (cap == net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return true;  // expose our own handler for fluids, all sides
        }
        return super.hasCapability(cap, side);  // items and anything else come from the parent
    }


    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> cap,
                            @Nullable EnumFacing side) {
        if (cap == net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return (T) selfHandler;  // never fall back to parent for fluids
        }
        return super.getCapability(cap, side);  // items etc. still via TileFluidHatch (EmbeddedInventory)
    }




    private void checkForUpdate() {
        if (world == null) return; 
        if (fluidChanged && (world instanceof WorldDummy || world.getTotalWorldTime() - lastUpdateTime > MAX_UPDATE)) {
            markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
            lastUpdateTime = world.getTotalWorldTime();
            fluidChanged = false;
        }
    }

    private boolean enterColumnOp() {
        if (inColumnOp) return false;
        inColumnOp = true;
        return true;
    }
    private void exitColumnOp() { inColumnOp = false; }


    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(getPos(), getBlockMetadata(), getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {

        if (resource == null)
            return 0;

        if (world == null || world.isRemote || removing) return 0;    

        TileFluidTank handler2 = this.getFluidTankInDirection(EnumFacing.UP);

        //Move up, check if we can fill there, do top down
        if (handler2 != null && handler2.canFill(resource)) {
            return handler2.fill(resource, doFill);
        }
        return fillInternal2(resource, doFill);
    }

    private int fillInternal2(FluidStack resource, boolean doFill) {

        TileFluidTank handler = this.getFluidTankInDirection(EnumFacing.DOWN);

        int amt = 0;

        if (handler != null) {
            amt = handler.fillInternal2(resource, doFill);
        }
        //Copy to avoid modifying the passed one
        FluidStack resource2 = resource.copy();
        resource2.amount -= amt;
        if (resource2.amount > 0)
            amt += super.fill(resource2, doFill);

        if (amt > 0 && doFill) {
            fluidChanged = true;
            markDirty();  
        }
        checkForUpdate();

        return amt;
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockPressureTank.getLocalizedName();
    }

    @Nullable
    public FluidStack getOwnContentsCopy() {
        FluidStack f = fluidTank.getFluid();
        return f == null ? null : f.copy();
    }    


    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        if (world == null || world.isRemote || removing) return null;
        
        IFluidHandler handler = this.getFluidTankInDirection(EnumFacing.UP);

        FluidStack fStack = null;
        if (handler != null) {
            IFluidTankProperties[] props = handler.getTankProperties();
            FluidStack contents = (props != null && props.length > 0) ? props[0].getContents() : null;
            if (contents != null &&
                fluidTank.getFluid() != null &&
                fluidTank.getFluid().getFluid() == contents.getFluid()) {
                fStack = handler.drain(maxDrain, doDrain);
            }
        }
        if (fStack != null)
            return fStack;

        FluidStack fStack2 = super.drain(maxDrain, doDrain);

        if (fStack2 != null && doDrain) {
            fluidChanged = true;
            markDirty();
        }
        checkForUpdate();


        return fStack2;
    }

    @Override
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        if (world == null || world.isRemote || removing || resource == null) return null;
        if (this.fluidTank.getFluid() == null || resource.getFluid() != this.fluidTank.getFluid().getFluid())
            return null;

        return this.drain(resource.amount, doDrain);
    }

    public TileFluidTank getFluidTankInDirection(EnumFacing direction) {
        if (world == null) return null;
        TileEntity tile = world.getTileEntity(pos.offset(direction));

        if (tile instanceof TileFluidTank) {
            return ((TileFluidTank) tile);
        }
        return null;
    }

    private boolean canFill(FluidStack fStack) {
        FluidStack fStack2 = fluidTank.getFluid();

        return fStack2 == null || (fStack2.getFluid() == fStack.getFluid());
    }

    @Override
    protected NBTTagCompound writeToNBTHelper(NBTTagCompound nbtTagCompound) {
        super.writeToNBTHelper(nbtTagCompound);
        fluidTank.writeToNBT(nbtTagCompound);
        return nbtTagCompound;
    }

    @Override
    protected void readFromNBTHelper(NBTTagCompound nbtTagCompound) {
        super.readFromNBTHelper(nbtTagCompound);
        fluidTank.readFromNBT(nbtTagCompound);
    }

    @Override
    protected boolean useBucket(int slot, @Nonnull ItemStack stack) {
        boolean bucketUsed = super.useBucket(slot, stack);

        if (bucketUsed) {
            IFluidHandler handler = getFluidTankInDirection(EnumFacing.DOWN);
            if (handler != null) {
                IFluidTankProperties[] props = handler.getTankProperties();
                FluidStack contents = (props != null && props.length > 0) ? props[0].getContents() : null;
                int capacity = (props != null && props.length > 0) ? props[0].getCapacity() : 0;

                // If the tank below is empty or has room, push fluid down
                if (contents == null || (capacity > 0 && contents.amount < capacity)) {
                    FluidStack ours = fluidTank.getFluid();
                    if (ours != null && ours.amount > 0) {
                        int canMove = handler.fill(new FluidStack(ours.getFluid(), ours.amount), false);
                        if (canMove > 0) {
                            FluidStack drained = fluidTank.drain(canMove, true);
                            int filled = handler.fill(drained, true);
                            if (filled < drained.amount) {
                                fluidTank.fill(new FluidStack(drained.getFluid(), drained.amount - filled), true);
                            }
                            fluidChanged = true;
                            markDirty();
                            checkForUpdate();
                        }
                    }
                }
            }
        }

        return bucketUsed;
    }

    @Override
    public void invalidate() {
        removing = true;
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        removing = true;
        super.onChunkUnload();
    }

    public void onAdjacentBlockUpdated(EnumFacing dir) {
        if (world == null || world.isRemote || removing) return;
        if (!enterColumnOp()) return;
        try {
            // If the block BELOW changed, push our fluid down into it and cascade.
            if (dir == EnumFacing.DOWN) {
                TileFluidTank down = getFluidTankInDirection(EnumFacing.DOWN);
                if (down != null) {
                    FluidStack ours = fluidTank.getFluid();
                    if (ours != null && ours.amount > 0) {
                        IFluidTankProperties[] props = down.getTankProperties();
                        FluidStack below = (props != null && props.length > 0) ? props[0].getContents() : null;
                        boolean compatible = (below == null) || (below.getFluid() == ours.getFluid());

                        if (compatible) {
                            int room = down.fluidTank.getCapacity() - down.fluidTank.getFluidAmount();
                            if (room > 0) {
                                int toMove = Math.min(room, ours.amount);

                                // Use capability: simulate then commit
                                IFluidHandler downH = down.getCapability(
                                    net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY,
                                    EnumFacing.UP
                                );
                                if (downH != null) {
                                    int canFill = downH.fill(new FluidStack(ours.getFluid(), toMove), false);
                                    if (canFill > 0) {
                                        FluidStack drained = fluidTank.drain(canFill, true);
                                        int filled = downH.fill(drained, true);
                                        if (filled < drained.amount) {
                                            // Put any remainder back to avoid loss
                                            fluidTank.fill(new FluidStack(drained.getFluid(), drained.amount - filled), true);
                                        }
                                        fluidChanged = true;
                                        markDirty();
                                        checkForUpdate();
                                        down.markDirty();
                                        down.checkForUpdate();

                                        // Cascade to the next tank down
                                        down.onAdjacentBlockUpdated(EnumFacing.DOWN);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // If the block ABOVE changed, pull fluid from the tank above into THIS tank (column-aware).
            else if (dir == EnumFacing.UP) {
                TileFluidTank up = getFluidTankInDirection(EnumFacing.UP);
                if (up != null) {
                    IFluidTankProperties[] props = up.getTankProperties();
                    FluidStack above = (props != null && props.length > 0) ? props[0].getContents() : null;

                    if (above != null) {
                        if (fluidTank.getFluid() == null) {
                            // We're empty: pull directly from the upper tank's internal store
                            FluidStack moved = up.drain(fluidTank.getCapacity(), true);
                            if (moved != null && moved.amount > 0) {
                                fluidTank.fill(moved, true);
                                fluidChanged = true;
                                markDirty();
                                checkForUpdate();
                            }
                        } else if (above.getFluid() == fluidTank.getFluid().getFluid()) {
                            // Same fluid: do a column-aware pull from the upper tank
                            int room = fluidTank.getCapacity() - fluidTank.getFluidAmount();
                            if (room > 0) {
                                FluidStack moved = up.drain(room, true);
                                if (moved != null && moved.amount > 0) {
                                    fluidTank.fill(moved, true);
                                    fluidChanged = true;
                                    markDirty();
                                    checkForUpdate();
                                }
                            }
                        }
                    }
                }
            }

            // Final safety: ensure any state change is persisted/sent
            if (fluidChanged) {
                this.markDirty();
                checkForUpdate();
            }
        } finally {
            exitColumnOp();
        }
    }
}
