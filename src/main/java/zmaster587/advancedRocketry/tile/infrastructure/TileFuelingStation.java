package zmaster587.advancedRocketry.tile.infrastructure;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.*;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;
import zmaster587.advancedRocketry.block.BlockTileRedstoneEmitter;
import zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.gui.CommonResources;
import zmaster587.libVulpes.interfaces.ILinkableTile;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.IMultiblock;
import zmaster587.libVulpes.tile.TileInventoriedRFConsumerTank;
import zmaster587.libVulpes.util.FluidUtils;
import zmaster587.libVulpes.util.HashedBlockPosition;
import zmaster587.libVulpes.util.INetworkMachine;
import zmaster587.libVulpes.util.IconResource;
import zmaster587.libVulpes.util.ZUtils.RedstoneState;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TileFuelingStation extends TileInventoriedRFConsumerTank implements IModularInventory, IMultiblock, IInfrastructure, ILinkableTile, INetworkMachine, IButtonInventory {

    private EntityRocketBase linkedRocket;
    private HashedBlockPosition masterBlock;
    private ModuleRedstoneOutputButton redstoneControl;
    private RedstoneState state;

    // Tune cadence: Ticks between operations
    private static final int OP_THROTTLE_TICKS = 5;

    // Stop polling after full for current link/fluid
    private boolean fuelingActive = false;

    // Cache last emitted redstone to avoid duplicate updates
    private Boolean lastRs = null;

    // Small perf win: cache resolved fluids from rocket stats
    private String lastFuelStr = null, lastOxStr = null, lastWorkStr = null;
    private Fluid cachedFuelFluid = null, cachedOxFluid = null, cachedWorkFluid = null;

    public TileFuelingStation() {
        super(1000, 3, 5000);
        masterBlock = new HashedBlockPosition(0, -1, 0);
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, 0, "", this);
        state = RedstoneState.ON;
    }

    private void syncTE() {
        markDirty();
        net.minecraft.block.state.IBlockState s = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, s, s, 3);
    }

    @Override
    public int getMaxLinkDistance() { return 10; }

    // ---- redstone emission with duplicate suppression ----
    private void setRedstoneState(boolean condition) {
        if (world == null || world.isRemote) return;

        if (state == RedstoneState.INVERTED)      condition = !condition;
        else if (state == RedstoneState.OFF)      condition = false;

        if (lastRs != null && lastRs == condition) return;
        lastRs = condition;

        net.minecraft.block.state.IBlockState s = world.getBlockState(pos);
        if (AdvancedRocketryBlocks.blockFuelingStation instanceof BlockTileRedstoneEmitter) {
            ((BlockTileRedstoneEmitter) AdvancedRocketryBlocks.blockFuelingStation)
                    .setRedstoneState(world, s, pos, condition);
        }
        markDirty();
    }


    // ---- small cache to avoid repeated FluidRegistry lookups per tick ----
    private void refreshFluidCachesIfNeeded() {
        if (linkedRocket == null || linkedRocket.stats == null) return;
        String f = linkedRocket.stats.getFuelFluid();
        String o = linkedRocket.stats.getOxidizerFluid();
        String w = linkedRocket.stats.getWorkingFluid();

        if (!Objects.equals(f, lastFuelStr)) {
            lastFuelStr = f;
            cachedFuelFluid = (f == null || "null".equals(f) || f.isEmpty()) ? null : FluidRegistry.getFluid(f);
        }
        if (!Objects.equals(o, lastOxStr)) {
            lastOxStr = o;
            cachedOxFluid = (o == null || "null".equals(o) || o.isEmpty()) ? null : FluidRegistry.getFluid(o);
        }
        if (!Objects.equals(w, lastWorkStr)) {
            lastWorkStr = w;
            cachedWorkFluid = (w == null || "null".equals(w) || w.isEmpty()) ? null : FluidRegistry.getFluid(w);
        }
    }

    private boolean isStationFluidForThisRocket(Fluid current) {
        refreshFluidCachesIfNeeded();
        if (current == null || linkedRocket == null) return false;

        // --- compare by fluid name, not by instance ---
        final String currentName = current.getName();

        // If a specific fluid was already chosen, match directly by name.
        if (lastFuelStr != null && !"null".equals(lastFuelStr) && !lastFuelStr.isEmpty() && currentName.equals(lastFuelStr)) {
            return true;
        }
        if (lastOxStr != null && !"null".equals(lastOxStr) && !lastOxStr.isEmpty() && currentName.equals(lastOxStr)) {
            return true;
        }
        if (lastWorkStr != null && !"null".equals(lastWorkStr) && !lastWorkStr.isEmpty() && currentName.equals(lastWorkStr)) {
            return true;
        }

        // Allow first-time lock-in when rocket hasn't chosen a fluid yet,
        // but DOES have capacity for the corresponding tank and "current" is valid for that type.
        if ("null".equals(linkedRocket.stats.getFuelFluid())) {
            if ((linkedRocket.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT) > 0 &&
                FuelRegistry.instance.isFuel(FuelType.LIQUID_MONOPROPELLANT, current)) ||
                (linkedRocket.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT) > 0 &&
                FuelRegistry.instance.isFuel(FuelType.LIQUID_BIPROPELLANT, current))) {
                return true;
            }
        }

        if ("null".equals(linkedRocket.stats.getOxidizerFluid())) {
            if (linkedRocket.getFuelCapacity(FuelType.LIQUID_OXIDIZER) > 0 &&
                FuelRegistry.instance.isFuel(FuelType.LIQUID_OXIDIZER, current)) {
                return true;
            }
        }

        if ("null".equals(linkedRocket.stats.getWorkingFluid())) {
            if (linkedRocket.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID) > 0 &&
                FuelRegistry.instance.isFuel(FuelType.NUCLEAR_WORKING_FLUID, current)) {
                return true;
            }
        }

        return false;
    }


    @Override
    public void update() {
        if (world.isRemote) return;

        // Lightweight bucket poll every 10 ticks (automation/hoppers)
        if ((world.getTotalWorldTime() % 10L) == 0L) {
            ItemStack in = inventory.getStackInSlot(0);
            if (!in.isEmpty() && useBucket(0, in)) {
                syncTE(); // only when something actually changed
            }
        }

        super.update(); // IMPORTANT: preserve parent RF/ticking pipeline
    }


    @Override
    public void performFunction() {
        if (world.isRemote) return; // server-only

        if (!fuelingActive) {
            FluidStack fs = tank.getFluid();
            boolean relevant = false, room = false;

            if (linkedRocket != null && fs != null) {
                Fluid f = fs.getFluid();
                // relevant if this fluid matches what this rocket can actually use
                relevant = isStationFluidForThisRocket(f);
                // room if the matching logical tank has capacity
                room = relevant && canRocketFitFluid(f);
            }

            setRedstoneState(relevant && !room); // emit when relevant but full
            fuelingActive = room;                // arm when relevant and there’s room
            if (!fuelingActive) return;
        }

        // from here: only do rocket-facing work when it's worth it...
        if (linkedRocket == null) {
            fuelingActive = false;
            setRedstoneState(false);
            return;
        }

        // Stop all work once full (until unlink/relink)
        if (!fuelingActive) {
            FluidStack fs = tank.getFluid();
            if (fs != null && canRocketFitFluid(fs.getFluid())) {
                fuelingActive = true;   // resume fueling after reload
                // don’t run expensive work this tick; we’ll catch it on the next throttled pass
            } else {
                // already full or no relevant fluid — keep RS accurate
                setRedstoneState(fs != null && isStationFluidForThisRocket(fs.getFluid())   && !canRocketFitFluid(fs.getFluid()));
            }
            return;
        }

        // Throttle only the expensive fueling/redstone path
        if ((world.getTotalWorldTime() % OP_THROTTLE_TICKS) != 0L) return;

        FluidStack currentFluidStack = tank.getFluid();
        if (currentFluidStack == null) {
            // No fluid to offer; keep fuelingActive so we’ll retry when fluid arrives
            setRedstoneState(false);
            return;
        }

        final Fluid currentFluid = currentFluidStack.getFluid();

        // Lock rocket to specific fluids if unset
        if ("null".equals(linkedRocket.stats.getFuelFluid())) {
            if ((FuelRegistry.instance.isFuel(FuelType.LIQUID_MONOPROPELLANT, currentFluid) && linkedRocket.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT) > 0)
             || (FuelRegistry.instance.isFuel(FuelType.LIQUID_BIPROPELLANT, currentFluid) && linkedRocket.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT) > 0)) {
                linkedRocket.stats.setFuelFluid(currentFluid.getName());
            }
        }
        if ("null".equals(linkedRocket.stats.getOxidizerFluid())) {
            if (FuelRegistry.instance.isFuel(FuelType.LIQUID_OXIDIZER, currentFluid)) {
                linkedRocket.stats.setOxidizerFluid(currentFluid.getName());
            }
        }
        if ("null".equals(linkedRocket.stats.getWorkingFluid())) {
            if (FuelRegistry.instance.isFuel(FuelType.NUCLEAR_WORKING_FLUID, currentFluid)) {
                linkedRocket.stats.setWorkingFluid(currentFluid.getName());
            }
        }

        // Update caches after potential stat change
        refreshFluidCachesIfNeeded();

        // If station fluid isn't relevant for this rocket, we can't help
        if (!isStationFluidForThisRocket(currentFluid)) {
            setRedstoneState(false);
            fuelingActive = false;    // go fully idle if station fluid not relevant
            return;
        }

        if (!canRocketFitFluid(currentFluid)) {
            setRedstoneState(true);
            fuelingActive = false;  // early-return above- next pass
            markDirty();
            return;
        }

        // Determine which tank to fill
        final FuelType typeToFill;
        if (FuelRegistry.instance.isFuel(FuelType.LIQUID_OXIDIZER, currentFluid)
            && linkedRocket.getFuelCapacity(FuelType.LIQUID_OXIDIZER) > linkedRocket.getFuelAmount(FuelType.LIQUID_OXIDIZER)) {
            typeToFill = FuelType.LIQUID_OXIDIZER;

        } else if (FuelRegistry.instance.isFuel(FuelType.LIQUID_BIPROPELLANT, currentFluid)
            && linkedRocket.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT) > linkedRocket.getFuelAmount(FuelType.LIQUID_BIPROPELLANT)) {
            typeToFill = FuelType.LIQUID_BIPROPELLANT;

        } else if (FuelRegistry.instance.isFuel(FuelType.LIQUID_MONOPROPELLANT, currentFluid)
            && linkedRocket.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT) > linkedRocket.getFuelAmount(FuelType.LIQUID_MONOPROPELLANT)) {
            typeToFill = FuelType.LIQUID_MONOPROPELLANT;

        } else if (FuelRegistry.instance.isFuel(FuelType.NUCLEAR_WORKING_FLUID, currentFluid)
            && linkedRocket.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID) > linkedRocket.getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID)) {
            typeToFill = FuelType.NUCLEAR_WORKING_FLUID;

        } else {
            // not relevant or no room
            setRedstoneState(false);
            fuelingActive = false;
            return;
        }

        // Bounded transfer scaled by throttle; drain exactly the delta that actually landed
        int step = ARConfiguration.getCurrentConfig().fuelPointsPer10Mb;
        int toOffer = Math.min(step * OP_THROTTLE_TICKS, tank.getFluidAmount());
        if (toOffer > 0) {
            final int before = linkedRocket.getFuelAmount(typeToFill);
            final int ret    = linkedRocket.addFuelAmount(typeToFill, toOffer);

            // Be robust to either contract:
            //  - if ret == new total -> delta = ret - before
            //  - if ret == accepted  -> delta = ret (clamped to toOffer)
            int delta = Math.max(0, ret - before);
            if (delta == 0) {
                // Assume ret is "accepted amount"
                delta = Math.min(toOffer, Math.max(0, ret));
            }

            if (delta > 0) {
                tank.drain(delta, true);

                int baseRate = linkedRocket.stats.getBaseFuelRate(typeToFill);
                if (baseRate > 0) {
                    int multRate = (int)(FuelRegistry.instance.getMultiplier(typeToFill, currentFluid) * baseRate);
                    if (multRate > 0) {
                        linkedRocket.setFuelConsumptionRate(typeToFill, multRate);
                    }
                }
            }
        }


        // Re-evaluate full; if full now, stop within this link
        boolean fullNow = !canRocketFitFluid(currentFluid);
        setRedstoneState(fullNow);
        if (fullNow) {
            fuelingActive = false;
            markDirty();
        }
    }

    @Override
    public int getPowerPerOperation() {
        return 30;
    }


    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, getBlockMetadata(), getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public boolean canPerformFunction() {
        if (world.isRemote) return false;
        if (linkedRocket == null) return false;

        FluidStack fs = tank.getFluid();
        if (fs == null || fs.amount <= 9) return false;

        // Only draw power when the rocket can actually take this fluid
        return canRocketFitFluid(fs.getFluid());
    }

    @Override
    public boolean canFill(Fluid fluid) {
        if (fluid == null) return false;
        return FuelRegistry.instance.isFuel(FuelType.LIQUID_MONOPROPELLANT, fluid)
            || FuelRegistry.instance.isFuel(FuelType.NUCLEAR_WORKING_FLUID, fluid)
            || FuelRegistry.instance.isFuel(FuelType.LIQUID_BIPROPELLANT, fluid)
            || FuelRegistry.instance.isFuel(FuelType.LIQUID_OXIDIZER, fluid);
    }

    /**
     * @param fluid the fluid to check whether the rocket has space for it
     * @return boolean on whether the rocket can accept the fluid
     */
    private boolean canRocketFitFluid(Fluid f) {
        if (f == null || linkedRocket == null) return false;

        boolean fits = false;

        // Check every type the fluid qualifies for; OR the results.
        if (FuelRegistry.instance.isFuel(FuelType.LIQUID_OXIDIZER, f)) {
            fits |= linkedRocket.getFuelAmount(FuelType.LIQUID_OXIDIZER) < linkedRocket.getFuelCapacity(FuelType.LIQUID_OXIDIZER);
        }
        if (FuelRegistry.instance.isFuel(FuelType.LIQUID_BIPROPELLANT, f)) {
            fits |= linkedRocket.getFuelAmount(FuelType.LIQUID_BIPROPELLANT) < linkedRocket.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT);
        }
        if (FuelRegistry.instance.isFuel(FuelType.LIQUID_MONOPROPELLANT, f)) {
            fits |= linkedRocket.getFuelAmount(FuelType.LIQUID_MONOPROPELLANT) < linkedRocket.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT);
        }
        if (FuelRegistry.instance.isFuel(FuelType.NUCLEAR_WORKING_FLUID, f)) {
            fits |= linkedRocket.getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID) < linkedRocket.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID);
        }

        return fits;
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockFuelingStation.getLocalizedName();
    }

    // keep original claim of custom name, but return non-null to avoid GUI NPEs
    @Override
    public boolean hasCustomName() { return true; }

    @Override
    public String getName() {
        return AdvancedRocketryBlocks.blockFuelingStation.getLocalizedName();
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);
        if (!world.isRemote) {
            boolean changed = false;
            while (useBucket(0, getStackInSlot(0))) changed = true; // drain all at once
            if (changed) syncTE();  // one sync if anything changed
        }
    }

    /**
     * Handles internal bucket tank interaction
     *
     * @param slot  integer slot to insert into
     * @param stack the itemstack to work fluid handling on
     * @return boolean on whether the fluid stack was successfully filled from or not, returns false if the stack cannot be extracted from or has no fluid left
     */
    private boolean useBucket(int slot, @Nonnull ItemStack stack) {
        return FluidUtils.attemptDrainContainerIInv(inventory, tank, stack, 0, 1);
    }


    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        if (!stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, EnumFacing.UP)) return false;
        IFluidHandler cap = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, EnumFacing.UP);
        if (cap == null) return false;
        IFluidTankProperties[] props = cap.getTankProperties();
        if (props == null || props.length == 0) return false;
        FluidStack fstack = props[0].getContents();
        return fstack != null && canFill(fstack.getFluid());
    }

    @Override
    public void unlinkRocket() {
        this.linkedRocket = null;
        this.fuelingActive = false;
        this.lastRs = null;
        lastFuelStr = lastOxStr = lastWorkStr = null;
        cachedFuelFluid = cachedOxFluid = cachedWorkFluid = null;
        ((BlockTileRedstoneEmitter) AdvancedRocketryBlocks.blockFuelingStation)
            .setRedstoneState(world, world.getBlockState(pos), pos, false);
        markDirty();
    }

    @Override
    public boolean disconnectOnLiftOff() { return true; }

    @Override
    public boolean linkRocket(EntityRocketBase rocket) {
        this.linkedRocket = rocket;
        this.lastRs = null;
        refreshFluidCachesIfNeeded();

        boolean room = false;

        if (tank.getFluid() != null) {
            Fluid f = tank.getFluid().getFluid();
            boolean relevant = isStationFluidForThisRocket(f);
            room = relevant && canRocketFitFluid(f);
            setRedstoneState(relevant && !room);
        } else {
            setRedstoneState(false);
        }

        // Arm fueling only if there’s actually room for the current fluid
        this.fuelingActive = room;

        syncTE();
        return true;
    }

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity,
                               EntityPlayer player, World world) {

        ItemLinker.setMasterCoords(item, pos);

        if (this.linkedRocket != null) {
            this.linkedRocket.unlinkInfrastructure(this);
            this.unlinkRocket();
        }

        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(
                new TextComponentString(LibVulpes.proxy.getLocalizedString("msg.fuelingStation.link") +
                    ": " + this.pos.getX() + " " + this.pos.getY() + " " + this.pos.getZ()));
        return true;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (getMasterBlock() instanceof TileRocketAssemblingMachine)
            ((TileRocketAssemblingMachine)getMasterBlock()).removeConnectedInfrastructure(this);

        if (linkedRocket != null)
            linkedRocket.unlinkInfrastructure(this);

        // Clear caches
        lastFuelStr = lastOxStr = lastWorkStr = null;
        cachedFuelFluid = cachedOxFluid = cachedWorkFluid = null;
        lastRs = null;
        fuelingActive = false;

        if (world != null && AdvancedRocketryBlocks.blockFuelingStation instanceof BlockTileRedstoneEmitter) {
            ((BlockTileRedstoneEmitter) AdvancedRocketryBlocks.blockFuelingStation)
                    .setRedstoneState(world, world.getBlockState(pos), pos, false);
        }

        markDirty();
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity,
                                  EntityPlayer player, World world) {
        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(
                new TextComponentTranslation("msg.linker.error.firstMachine"));
        return false;
    }

    @Override
    @Nonnull
    public int[] getSlotsForFace(EnumFacing side) {
        if (side == EnumFacing.DOWN)
            return new int[]{1};
        return new int[]{0};
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> list = new ArrayList<>();

        list.add(new ModulePower(156, 12, this));
        list.add(new ModuleSlotArray(45, 18, this, 0, 1));
        list.add(new ModuleSlotArray(45, 54, this, 1, 2));
        list.add(redstoneControl);

        if (world.isRemote)
            list.add(new ModuleImage(44, 35, new IconResource(194, 0, 18, 18, CommonResources.genericBackground)));
        list.add(new ModuleLiquidIndicator(27, 18, this));

        return list;
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) { return true; }

    @Override
    public boolean linkMission(IMission mission) { return false; }

    @Override
    public void unlinkMission() { }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setByte("redstoneState", (byte) state.ordinal());
        if (hasMaster()) {
            nbt.setIntArray("masterPos", new int[]{masterBlock.x, masterBlock.y, masterBlock.z});
        }
        // fuelingActive not persisted on purpose to match original continuous behavior after reload
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        state = RedstoneState.values()[nbt.getByte("redstoneState")];
        redstoneControl.setRedstoneState(state);

        if (nbt.hasKey("masterPos")) {
            int[] pos = nbt.getIntArray("masterPos");
            setMasterBlock(new BlockPos(pos[0], pos[1], pos[2]));
        }
        // lastRs/fuelingActive intentionally not restored; link events will reset as needed
    }

    @Override
    public boolean hasMaster() { return masterBlock.y > -1; }

    @Override
    public TileEntity getMasterBlock() {
        return world.getTileEntity(new BlockPos(masterBlock.x, masterBlock.y, masterBlock.z));
    }

    @Override
    public void setMasterBlock(BlockPos pos) { masterBlock = new HashedBlockPosition(pos); }

    @Override
    public void setComplete(BlockPos pos) { }

    @Override
    public void setIncomplete() { masterBlock.y = -1; }

    public boolean canRenderConnection() { return true; }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        state = redstoneControl.getState();
        PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        out.writeByte(state.ordinal());
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        nbt.setByte("state", in.readByte());
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        state = RedstoneState.values()[nbt.getByte("state")];
        markDirty();
        if (side == Side.SERVER && linkedRocket != null) {
            FluidStack fs = tank.getFluid();
            if (fs == null) { setRedstoneState(false); return; }
            Fluid f = fs.getFluid();
            boolean relevant = isStationFluidForThisRocket(f);
            boolean room = relevant && canRocketFitFluid(f);
            setRedstoneState(relevant && !room);
        }
    }


    @Override
    public void onLoad() {
        if (world.isRemote) return;
        lastRs = null; // allow first emit
        refreshFluidCachesIfNeeded();

        boolean emit = false;
        if (linkedRocket != null) {
            FluidStack fs = tank.getFluid();
            if (fs != null) {
                Fluid f = fs.getFluid();
                emit = isStationFluidForThisRocket(f) && !canRocketFitFluid(f);
                // also re-arm fueling if we actually can fit
                if (!emit && isStationFluidForThisRocket(f) && canRocketFitFluid(f)) {
                    fuelingActive = true;
                }
            }
        }
        setRedstoneState(emit);
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (world == null || world.isRemote) return;

        // Clear caches
        lastFuelStr = lastOxStr = lastWorkStr = null;
        cachedFuelFluid = cachedOxFluid = cachedWorkFluid = null;
        lastRs = null;
        fuelingActive = false;
        if (AdvancedRocketryBlocks.blockFuelingStation instanceof BlockTileRedstoneEmitter) {
            ((BlockTileRedstoneEmitter) AdvancedRocketryBlocks.blockFuelingStation)
                    .setRedstoneState(world, world.getBlockState(pos), pos, false);
        }
        markDirty();
    }

    @Override
    public boolean isEmpty() { return inventory.isEmpty(); }
}
