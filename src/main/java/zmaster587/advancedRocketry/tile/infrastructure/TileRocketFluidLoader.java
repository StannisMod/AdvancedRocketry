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
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.EntityRocketBase;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.api.IMission;
import zmaster587.advancedRocketry.block.multiblock.BlockARHatch;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.inventory.modules.ModuleSideSelectorTooltipOverlay;
import zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.multiblock.hatch.TileFluidHatch;
import zmaster587.libVulpes.util.INetworkMachine;
import zmaster587.libVulpes.util.ZUtils.RedstoneState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;

public class TileRocketFluidLoader extends TileFluidHatch implements IInfrastructure, ITickable, IButtonInventory, INetworkMachine, IGuiCallback {

    private final static int ALLOW_REDSTONEOUT = 2;
    EntityRocket rocket;
    ModuleRedstoneOutputButton redstoneControl;
    RedstoneState state;
    ModuleRedstoneOutputButton inputRedstoneControl;
    RedstoneState inputstate;
    private String[] sideStateNames;
    ModuleBlockSideSelector sideSelectorModule;
    protected static final int TRANSFER_INTERVAL_TICKS = 10;
    protected int transferCooldown = 0;

    public TileRocketFluidLoader() {
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, 0, "", this,
                LibVulpes.proxy.getLocalizedString("msg.fluidLoader.loadingState"));
        state = RedstoneState.ON;

        inputRedstoneControl = new ModuleRedstoneOutputButton(174, 32, 1, "", this,
                LibVulpes.proxy.getLocalizedString("msg.fluidLoader.allowLoading"));
        inputstate = RedstoneState.OFF;
        inputRedstoneControl.setRedstoneState(inputstate);

        initSideSelector();
    }

    public TileRocketFluidLoader(int size) {
        super(size);
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, 0, "", this,
                LibVulpes.proxy.getLocalizedString("msg.fluidLoader.loadingState"));
        state = RedstoneState.ON;

        inputRedstoneControl = new ModuleRedstoneOutputButton(174, 32, 1, "", this,
                LibVulpes.proxy.getLocalizedString("msg.fluidLoader.allowLoading"));
        inputstate = RedstoneState.OFF;
        inputRedstoneControl.setRedstoneState(inputstate);

        initSideSelector();
    }


    @Override
    public void invalidate() {
        super.invalidate();
        if (getMasterBlock() instanceof TileRocketAssemblingMachine)
            ((TileRocketAssemblingMachine) getMasterBlock()).removeConnectedInfrastructure(this);
    }

    private void initSideSelector() {
        sideStateNames = new String[] {
                LibVulpes.proxy.getLocalizedString("msg.fluidLoader.none"),
                LibVulpes.proxy.getLocalizedString("msg.fluidLoader.allowredstoneoutput"),
                LibVulpes.proxy.getLocalizedString("msg.fluidLoader.allowredstoneinput")
        };
        sideSelectorModule = new ModuleBlockSideSelector(90, 15, this, sideStateNames);
    }

    @Override
    public String getModularInventoryName() {
        return "tile.loader.5.name";
    }

    @Override
    public boolean allowRedstoneOutputOnSide(EnumFacing facing) {
        return sideSelectorModule.getStateForSide(facing.getOpposite()) == 1;
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> list = super.getModules(ID, player);
        list.add(redstoneControl);
        list.add(inputRedstoneControl);
        list.add(sideSelectorModule);

        if (FMLCommonHandler.instance().getSide().isClient()) {
            list.add(new ModuleSideSelectorTooltipOverlay(90, 15, sideSelectorModule, sideStateNames));
        }

        return list;
    }


    protected boolean getStrongPowerForSides(World world, BlockPos pos) {
        for (int i = 0; i < 6; i++) {
            if (sideSelectorModule.getStateForSide(i) == ALLOW_REDSTONEOUT && world.getRedstonePower(pos.offset(EnumFacing.VALUES[i]), EnumFacing.VALUES[i]) > 0)
                return true;
        }
        return false;
    }

    @Nullable
    protected static IFluidHandler getFluidHandlerAnySide(TileEntity te) {
        IFluidHandler h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        if (h != null) return h;

        for (EnumFacing f : EnumFacing.VALUES) {
            h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, f);
            if (h != null) return h;
        }
        return null;
    }
    @Nullable
    protected static IFluidHandler getBestFillHandler(TileEntity te, @Nonnull FluidStack toInsert) {
        // Try null side first
        IFluidHandler h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        if (h != null) {
            FluidStack probe = toInsert.copy();
            if (h.fill(probe, false) > 0) return h;
        }

        // Then try all faces
        for (EnumFacing f : EnumFacing.VALUES) {
            h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, f);
            if (h == null) continue;

            FluidStack probe = toInsert.copy();
            if (h.fill(probe, false) > 0) return h;
        }

        return null;
    }

    @Nullable
    protected static IFluidHandler getBestDrainHandler(TileEntity te) {
        // For draining, side rules also exist; "null then faces" is usually OK.
        IFluidHandler h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        if (h != null) return h;

        for (EnumFacing f : EnumFacing.VALUES) {
            h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, f);
            if (h != null) return h;
        }
        return null;
    }


    @Override
    public void update() {
        if (world.isRemote || rocket == null) return;

        if (transferCooldown > 0) {
            transferCooldown--;
            return;
        }
        transferCooldown = TRANSFER_INTERVAL_TICKS;

        boolean isAllowToOperate = (inputstate == RedstoneState.OFF
                || isStateActive(inputstate, getStrongPowerForSides(world, getPos())));

        List<TileEntity> tiles = rocket.storage.getFluidTiles();
        boolean rocketHasFillCapacitySomewhere = false;
        boolean doupdate = false;

        for (TileEntity tile : tiles) {
            if (tile == null || tile.isInvalid()) continue;

            IFluidHandler drainHandler = getBestDrainHandler(tile);
            if (drainHandler == null) continue;

            // --- redstone probe (keep your current semantics for now)
            FluidStack probe = drainHandler.drain(1, false);
            if (probe != null && probe.amount > 0) {
                IFluidHandler fillProbeHandler = getBestFillHandler(tile, probe);
                if (fillProbeHandler != null && fillProbeHandler.fill(probe, false) > 0) {
                    rocketHasFillCapacitySomewhere = true;
                }
            }

            if (!isAllowToOperate) continue;

            FluidStack fromLoader = fluidTank.drain(fluidTank.getCapacity(), false);
            if (fromLoader == null || fromLoader.amount <= 0) continue;

            IFluidHandler fillHandler = getBestFillHandler(tile, fromLoader);
            if (fillHandler == null) continue;

            int accepted = fillHandler.fill(fromLoader, true);
            if (accepted > 0) {
                fluidTank.drain(accepted, true);
                doupdate = true;
                break;
            }
        }

        if (doupdate) {
            PacketHandler.sendToNearby(new PacketEntity(rocket, (byte) 9987),
                    world.provider.getDimension(), getPos(), 128);
            markDirty();
        }

        setRedstoneState(!rocketHasFillCapacitySomewhere);
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

    protected void setRedstoneState(boolean condition) {
        condition = isStateActive(state, condition);
        ((BlockARHatch) AdvancedRocketryBlocks.blockLoader).setRedstoneState(world, world.getBlockState(pos), pos, condition);

    }

    protected boolean isStateActive(RedstoneState state, boolean condition) {
        if (state == RedstoneState.INVERTED)
            return !condition;
        else if (state == RedstoneState.OFF)
            return false;
        return condition;
    }

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity,
                               EntityPlayer player, World world) {

        ItemLinker.setMasterCoords(item, this.getPos());

        if (this.rocket != null) {
            this.rocket.unlinkInfrastructure(this);
            this.unlinkRocket();
        }

        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("%s %s", new TextComponentTranslation("msg.fluidLoader.link"), ": " + getPos().getX() + " " + getPos().getY() + " " + getPos().getZ()));
        return true;
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity,
                                  EntityPlayer player, World world) {
        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("msg.linker.error.firstMachine"));
        return false;
    }

    @Override
    public void unlinkRocket() {
        rocket = null;
        ((BlockARHatch) AdvancedRocketryBlocks.blockLoader).setRedstoneState(world, world.getBlockState(pos), pos, false);
        //On unlink prevent the tile from ticking anymore

        //if(!worldObj.isRemote)
        //worldObj.loadedTileEntityList.remove(this);
    }

    @Override
    public boolean disconnectOnLiftOff() {
        return true;
    }

    @Override
    public boolean linkRocket(EntityRocketBase rocket) {
        //On linked allow the tile to tick
        //if(!worldObj.isRemote)
        //worldObj.loadedTileEntityList.add(this);
        this.rocket = (EntityRocket) rocket;
        return true;
    }

    @Override
    public boolean canUpdate() {
        return true;
    }

    @Override
    public boolean linkMission(IMission mission) {
        return false;
    }

    @Override
    public void unlinkMission() {

    }

    @Override
    public int getMaxLinkDistance() {
        return 32;
    }

    public boolean canRenderConnection() {
        return true;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        state = RedstoneState.values()[nbt.getByte("redstoneState")];
        redstoneControl.setRedstoneState(state);


        inputstate = RedstoneState.values()[nbt.getByte("inputRedstoneState")];
        inputRedstoneControl.setRedstoneState(inputstate);

        sideSelectorModule.readFromNBT(nbt);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setByte("redstoneState", (byte) state.ordinal());
        nbt.setByte("inputRedstoneState", (byte) inputstate.ordinal());
        sideSelectorModule.writeToNBT(nbt);
        return nbt;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == 0)
            state = redstoneControl.getState();
        if (buttonId == 1)
            inputstate = inputRedstoneControl.getState();
        PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        out.writeByte(state.ordinal());
        out.writeByte(inputstate.ordinal());
        for (int i = 0; i < 6; i++)
            out.writeByte(sideSelectorModule.getStateForSide(i));
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        nbt.setByte("state", in.readByte());
        nbt.setByte("inputstate", in.readByte());

        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++)
            bytes[i] = in.readByte();
        nbt.setByteArray("bytes", bytes);
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        state = RedstoneState.values()[nbt.getByte("state")];
        inputstate = RedstoneState.values()[nbt.getByte("inputstate")];

        byte[] bytes = nbt.getByteArray("bytes");
        for (int i = 0; i < 6; i++)
            sideSelectorModule.setStateForSide(i, bytes[i]);

        if (rocket == null)
            setRedstoneState(state == RedstoneState.INVERTED);


        markDirty();
        world.markChunkDirty(getPos(), this);
    }

    @Override
    public void onModuleUpdated(ModuleBase module) {
        PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
    }
}
