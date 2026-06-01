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
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.EntityRocketBase;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.api.IMission;
import zmaster587.advancedRocketry.block.multiblock.BlockARHatch;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.inventory.modules.ModuleSideSelectorTooltipOverlay;
import zmaster587.advancedRocketry.tile.TileGuidanceComputer;
import zmaster587.advancedRocketry.tile.hatch.TileSatelliteHatch;
import zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.multiblock.hatch.TileInventoryHatch;
import zmaster587.libVulpes.util.INetworkMachine;
import zmaster587.libVulpes.util.ZUtils.RedstoneState;

import javax.annotation.Nonnull;
import java.util.List;

public class TileRocketLoader extends TileInventoryHatch implements IInfrastructure, ITickable, IButtonInventory, INetworkMachine, IGuiCallback {

    private String[] sideStateNames;
    private final static int ALLOW_REDSTONEOUT = 2;
    EntityRocket rocket;
    ModuleRedstoneOutputButton redstoneControl;
    RedstoneState state;
    ModuleRedstoneOutputButton inputRedstoneControl;
    RedstoneState inputstate;
    ModuleBlockSideSelector sideSelectorModule;

    protected static final int TRANSFER_INTERVAL_TICKS = 20;
    protected static final int MAX_TRANSFER_PER_OPERATION = 64;
    protected int transferCooldown = 0;

    // Own wrapper around the EmbeddedInventory from TileInventoryHatch.
    // We DO NOT use the broken capability from LibVulpes for ourselves.
    protected final IItemHandler ownItemHandler = new InvWrapper(this.inventory);

    public TileRocketLoader() {
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, 0, "", this, LibVulpes.proxy.getLocalizedString("msg.rocketLoader.loadingState"));
        state = RedstoneState.ON;
        inputRedstoneControl = new ModuleRedstoneOutputButton(174, 32, 1, "", this, LibVulpes.proxy.getLocalizedString("msg.rocketLoader.allowLoading"));
        inputstate = RedstoneState.OFF;
        inputRedstoneControl.setRedstoneState(inputstate);
        initSideSelector();
    }

    public TileRocketLoader(int size) {
        super(size);
        inventory.setCanInsertSlot(0, true);
        inventory.setCanInsertSlot(1, true);
        inventory.setCanInsertSlot(2, true);
        inventory.setCanInsertSlot(3, true);
        inventory.setCanExtractSlot(0, false);
        inventory.setCanExtractSlot(1, false);
        inventory.setCanExtractSlot(2, false);
        inventory.setCanExtractSlot(3, false);
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, 0, "", this, LibVulpes.proxy.getLocalizedString("msg.rocketLoader.loadingState"));
        state = RedstoneState.ON;
        inputRedstoneControl = new ModuleRedstoneOutputButton(174, 32, 1, "", this, LibVulpes.proxy.getLocalizedString("msg.rocketLoader.allowLoading"));
        inputstate = RedstoneState.OFF;
        inputRedstoneControl.setRedstoneState(inputstate);
        initSideSelector();
    }

    // Used for rocket / other tiles – they SHOULD implement IItemHandler correctly.
    protected IItemHandler getItemHandler(TileEntity tile) {
        if (tile == null || tile.isInvalid())
            return null;

        // Prefer null side
        if (tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            Object cap = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (cap instanceof IItemHandler) {
                return (IItemHandler) cap;
            }
        }

        // Fallback: try all sides
        for (EnumFacing side : EnumFacing.values()) {
            if (tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side)) {
                Object cap = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side);
                if (cap instanceof IItemHandler) {
                    return (IItemHandler) cap;
                }
            }
        }

        return null;
    }


    // For THIS tile only: never go through LibVulpes’ capability (it returns EmbeddedInventory).
    protected IItemHandler getOwnItemHandler() {
        return ownItemHandler;
    }



    private void initSideSelector() {
        sideStateNames = new String[] {
                LibVulpes.proxy.getLocalizedString("msg.rocketLoader.none"),
                LibVulpes.proxy.getLocalizedString("msg.rocketLoader.allowredstoneoutput"),
                LibVulpes.proxy.getLocalizedString("msg.rocketLoader.allowredstoneinput")
        };

        sideSelectorModule = new ModuleBlockSideSelector(90, 15, this, sideStateNames);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (getMasterBlock() instanceof TileRocketAssemblingMachine)
            ((TileRocketAssemblingMachine) getMasterBlock()).removeConnectedInfrastructure(this);
    }

    @Override
    public String getModularInventoryName() {
        return "tile.loader.3.name";
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

    @Override
    public void update() {
        if (world.isRemote || rocket == null)
            return;

        // Throttle: only try to move items every TRANSFER_INTERVAL_TICKS
        if (transferCooldown > 0) {
            transferCooldown--;
            return;
        }

        boolean isAllowedToOperate = (inputstate == RedstoneState.OFF ||
                isStateActive(inputstate, getStrongPowerForSides(world, getPos())));

        IItemHandler ownHandler = getOwnItemHandler();
        if (ownHandler == null || ownHandler.getSlots() == 0) {
            // Nothing to move / no handler -> treat as not doing anything
            setRedstoneState(false);
            return;
        }

        List<TileEntity> tiles = rocket.storage.getInventoryTiles();
        boolean rocketHasCapacity = false; // true if any slot can still take items

        outer:
        for (TileEntity tile : tiles) {
            if (tile instanceof TileGuidanceComputer || tile instanceof TileSatelliteHatch)
                continue;

            IItemHandler rocketHandler = getItemHandler(tile);
            if (rocketHandler == null || rocketHandler.getSlots() == 0)
                continue;

            int rocketSlots = rocketHandler.getSlots();
            int ownSlots = ownHandler.getSlots();

            // Capacity detection for redstone: matches original semantics (any empty slot)
            for (int rocketSlot = 0; rocketSlot < rocketSlots; rocketSlot++) {
                ItemStack rocketStack = rocketHandler.getStackInSlot(rocketSlot);
                if (rocketStack.isEmpty()) {
                    rocketHasCapacity = true;
                    break;
                }
            }

            // If we are not allowed to operate, we only care about capacity for redstone
            if (!isAllowedToOperate)
                continue;

            // Actual transfer: handler-wide insert using ItemHandlerHelper
            for (int ownSlot = 0; ownSlot < ownSlots; ownSlot++) {
                ItemStack sourceStack = ownHandler.getStackInSlot(ownSlot);
                if (sourceStack.isEmpty())
                    continue;

                // Limit per-operation transfer, but DO NOT assume anything about slot max size
                int maxToMove = Math.min(MAX_TRANSFER_PER_OPERATION, sourceStack.getCount());
                if (maxToMove <= 0)
                    continue;

                // Simulate extraction from our inventory
                ItemStack simulatedExtract = ownHandler.extractItem(ownSlot, maxToMove, true);
                if (simulatedExtract.isEmpty())
                    continue;

                // Simulate insertion into the rocket inventory as a whole
                ItemStack simulatedRemainder = ItemHandlerHelper.insertItem(rocketHandler, simulatedExtract, true);
                int accepted = simulatedExtract.getCount() - simulatedRemainder.getCount();
                if (accepted <= 0)
                    continue;

                // Actually extract exactly what the rocket said it will accept
                ItemStack actuallyExtracted = ownHandler.extractItem(ownSlot, accepted, false);
                if (actuallyExtracted.isEmpty())
                    continue;

                // Actually insert into rocket
                ItemStack remainder = ItemHandlerHelper.insertItem(rocketHandler, actuallyExtracted, false);

                // Normally remainder should be empty because we respected 'accepted'.
                // Absolute last-resort fallback for misbehaving handlers: try to put remainder back.
                if (!remainder.isEmpty()) {
                    ItemHandlerHelper.insertItem(ownHandler, remainder, false);
                    // If this still leaves items, they'll effectively vanish, but only
                    // in the case of a broken mod that lied during simulation.
                }

                transferCooldown = TRANSFER_INTERVAL_TICKS;
                markDirty();
                tile.markDirty();
                break outer; // only one transfer per operation
            }
        }

        // Redstone: ON when rocketHasCapacity == false (i.e. no empty slot -> "full" rocket)
        setRedstoneState(!rocketHasCapacity);
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

        ItemLinker.setMasterCoords(item, this.pos);

        if (this.rocket != null) {
            this.rocket.unlinkInfrastructure(this);
            this.unlinkRocket();
        }

        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("%s %s", new TextComponentTranslation("msg.rocketLoader.link"), ": " + getPos().getX() + " " + getPos().getY() + " " + getPos().getZ()));
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
