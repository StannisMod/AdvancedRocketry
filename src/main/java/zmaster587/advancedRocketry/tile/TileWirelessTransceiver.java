package zmaster587.advancedRocketry.tile;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
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
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.api.satellite.IDataHandler;
import zmaster587.advancedRocketry.block.BlockTransceiver;
import zmaster587.advancedRocketry.wirelessdata.DataNetwork;
import zmaster587.advancedRocketry.wirelessdata.HandlerDataNetwork;
import zmaster587.advancedRocketry.wirelessdata.NetworkRegistry;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.inventory.modules.ModuleWirelessBufferBar;
import zmaster587.advancedRocketry.world.util.MultiData;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.interfaces.ILinkableTile;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.IToggleButton;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleText;
import zmaster587.libVulpes.inventory.modules.ModuleToggleSwitch;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.util.INetworkMachine;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class TileWirelessTransceiver extends TileEntity implements INetworkMachine, IModularInventory, ILinkableTile, IDataHandler, ITickable, IToggleButton {

    private static final int DEFAULT_TRANSFER_INTERVAL_TICKS = 20;
    private static final int DEFAULT_BUFFER_CAPACITY = 100;
    private static final int UNLINKED_NETWORK_ID = -1;
    private static final int PACKET_MODE = 0;
    private static final int PACKET_ENABLED = 1;
    private static final int BLOCK_UPDATE_FLAGS = 3;
    private static final EnumFacing NETWORK_SIDE = EnumFacing.UP;

    // Avoid repeated DataType.values() allocations in hot paths.
    // Update this list if the enum changes.
    private static final DataType[] TYPES = {
            DataType.DISTANCE,
            DataType.HUMIDITY,
            DataType.TEMPERATURE,
            DataType.COMPOSITION,
            DataType.ATMOSPHEREDENSITY,
            DataType.MASS
    };

    private final MultiData data = new MultiData();
    private final DataStorage uiBuffer = new DataStorage();

    private final ModuleToggleSwitch modeToggle;
    private final ModuleToggleSwitch enabledToggle;
    private final ModuleText netIdLabel;

    private int transferIntervalTicks = DEFAULT_TRANSFER_INTERVAL_TICKS;
    private int phase = -1;
    private int networkID = UNLINKED_NETWORK_ID;

    private boolean extractMode;
    private boolean enabled;

    public TileWirelessTransceiver() {
        data.setMaxData(DEFAULT_BUFFER_CAPACITY);

        uiBuffer.setMaxData(data.getMaxData());
        uiBuffer.setData(0, DataType.UNDEFINED);

        modeToggle = new ModuleToggleSwitch(
                50, 50, PACKET_MODE,
                LibVulpes.proxy.getLocalizedString("msg.wirelessTransceiver.extract"),
                this,
                TextureResources.buttonGeneric,
                64, 18,
                false
        );

        enabledToggle = new ModuleToggleSwitch(
                160, 5, PACKET_ENABLED,
                "",
                this,
                zmaster587.libVulpes.inventory.TextureResources.buttonToggleImage,
                11, 26,
                true
        );

        netIdLabel = new ModuleText(
                40, 22,
                LibVulpes.proxy.getLocalizedString("msg.wirelessTransceiver.network") + "-",
                0x000000
        );
        netIdLabel.setAlwaysOnTop(true);

        extractMode = modeToggle.getState();
        enabled = enabledToggle.getState();

        syncUiBufferFromMultiData();
        syncWidgetsFromFields();
    }

    public final DataStorage getUiBufferObject() {
        return uiBuffer;
    }

    public boolean isLinkedWireless() {
        return networkID != UNLINKED_NETWORK_ID;
    }

    public int getWirelessNetworkId() {
        return networkID;
    }

    public boolean isEnabledWireless() {
        return enabled;
    }

    public boolean isExtractModeWireless() {
        return extractMode;
    }

    private HandlerDataNetwork nets() {
        return NetworkRegistry.dataNetwork(world);
    }

    private int getEffectiveTransferInterval() {
        return transferIntervalTicks > 0 ? transferIntervalTicks : DEFAULT_TRANSFER_INTERVAL_TICKS;
    }

    private void syncUiBufferFromMultiData() {
        int total = 0;
        int max = data.getMaxData();
        int nonZeroTypes = 0;
        DataType lastType = DataType.UNDEFINED;

        for (DataType type : TYPES) {
            int amount = data.getDataAmount(type);
            if (amount > 0) {
                total += amount;
                nonZeroTypes++;
                lastType = type;
            }
        }

        if (total < 0) total = 0;
        if (total > max) total = max;

        uiBuffer.setMaxData(max);
        uiBuffer.setData(total, nonZeroTypes == 1 ? lastType : DataType.UNDEFINED);
    }

    private void syncWidgetsFromFields() {
        if (modeToggle != null) {
            modeToggle.setToggleState(extractMode);
            modeToggle.setText(LibVulpes.proxy.getLocalizedString(
                    extractMode
                            ? "msg.wirelessTransceiver.extract"
                            : "msg.wirelessTransceiver.insert"
            ));
        }

        if (enabledToggle != null) {
            enabledToggle.setToggleState(enabled);
        }

        if (netIdLabel != null) {
            String label = LibVulpes.proxy.getLocalizedString("msg.wirelessTransceiver.network");
            String value = networkID == UNLINKED_NETWORK_ID
                    ? LibVulpes.proxy.getLocalizedString("msg.wirelessTransceiver.network.unlinked")
                    : Integer.toString(networkID);
            netIdLabel.setText(label + value);
        }
    }

    private void markDirtyAndSyncBlock() {
        if (world == null) return;
        markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), BLOCK_UPDATE_FLAGS);
    }

    private EnumFacing resolveTransferFacing() {
        IBlockState state = world != null ? world.getBlockState(pos) : null;
        if (state == null) return EnumFacing.SOUTH;

        if (state.getBlock() instanceof BlockTransceiver) {
            return BlockTransceiver.getFront(state).getOpposite();
        }

        if (state.getBlock() instanceof zmaster587.libVulpes.block.RotatableBlock) {
            return zmaster587.libVulpes.block.RotatableBlock.getFront(state).getOpposite();
        }

        return EnumFacing.SOUTH;
    }

    private DataNetwork getExistingNetwork() {
        if (world == null || world.isRemote || networkID == UNLINKED_NETWORK_ID) return null;

        HandlerDataNetwork manager = nets();
        if (manager == null) return null;

        int resolvedId = manager.resolveNetworkID(networkID);
        if (resolvedId != networkID) {
            setWirelessNetworkId(resolvedId);
        }

        if (!manager.doesNetworkExist(resolvedId)) return null;
        return manager.getNetwork(resolvedId);
    }

    private DataNetwork getOrCreateNetwork() {
        if (world == null || world.isRemote || networkID == UNLINKED_NETWORK_ID) return null;

        HandlerDataNetwork manager = nets();
        if (manager == null) return null;

        int resolvedId = manager.getNewNetworkID(networkID);
        if (resolvedId != networkID) {
            setWirelessNetworkId(resolvedId);
        }

        return manager.getNetwork(resolvedId);
    }

    private void leaveNetwork() {
        DataNetwork network = getExistingNetwork();
        if (network != null) {
            network.removeFromAll(this);
        }
    }

    private void joinNetwork() {
        DataNetwork network = getOrCreateNetwork();
        if (network == null) return;

        network.removeFromAll(this);
        if (extractMode) {
            network.addSource(this, NETWORK_SIDE);
        } else {
            network.addSink(this, NETWORK_SIDE);
        }
    }

    public void setWirelessNetworkId(int newNetworkId) {
        if (networkID == newNetworkId) {
            return;
        }

        networkID = newNetworkId;
        syncWidgetsFromFields();

        if (world != null && !world.isRemote) {
            markDirtyAndSyncBlock();
        }
    }

    private void resetTransientState() {
        phase = -1;
    }

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        ItemLinker.setMasterCoords(item, getPos());

        if (world.isRemote) {
            player.sendMessage(new TextComponentTranslation("msg.linker.program"));
        }

        return true;
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        BlockPos otherPos = ItemLinker.getMasterCoords(item);
        if (otherPos == null || otherPos.equals(pos) || !world.isBlockLoaded(otherPos)) {
            return false;
        }

        TileEntity otherTile = world.getTileEntity(otherPos);
        if (!(otherTile instanceof TileWirelessTransceiver)) {
            return false;
        }

        if (world.isRemote) {
            player.sendMessage(new TextComponentTranslation("msg.linker.success"));
            return true;
        }

        TileWirelessTransceiver other = (TileWirelessTransceiver) otherTile;
        HandlerDataNetwork manager = nets();

        if (networkID == UNLINKED_NETWORK_ID && other.networkID == UNLINKED_NETWORK_ID) {
            int newId = manager.getNewNetworkID();
            networkID = newId;
            other.networkID = newId;
        } else if (networkID == UNLINKED_NETWORK_ID) {
            networkID = other.networkID;
        } else if (other.networkID == UNLINKED_NETWORK_ID) {
            other.networkID = networkID;
        } else if (networkID != other.networkID) {
            int merged = manager.mergeNetworks(networkID, other.networkID);
            networkID = merged;
            other.networkID = merged;
        }

        joinNetwork();
        other.joinNetwork();

        syncWidgetsFromFields();
        other.syncWidgetsFromFields();

        markDirtyAndSyncBlock();
        other.markDirtyAndSyncBlock();

        ItemLinker.resetPosition(item);
        return true;
    }

    @Override
    public void onChunkUnload() {
        leaveNetwork();

        resetTransientState();

        uiBuffer.setMaxData(data.getMaxData());
        uiBuffer.setData(0, DataType.UNDEFINED);

        super.onChunkUnload();
    }

    @Override
    public void invalidate() {
        leaveNetwork();
        resetTransientState();
        super.invalidate();
    }

    @Override
    public void onLoad() {
        super.onLoad();

        syncUiBufferFromMultiData();
        syncWidgetsFromFields();

        if (world == null || world.isRemote) {
            return;
        }

        phase = (int) Math.floorMod(pos.toLong(), getEffectiveTransferInterval());

        if (networkID != UNLINKED_NETWORK_ID) {
            joinNetwork();
        }
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, writeToNBT(new NBTTagCompound()));
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
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        extractMode = nbt.getBoolean("mode");
        enabled = nbt.getBoolean("enabled");
        networkID = nbt.getInteger("networkID");
        data.readFromNBT(nbt);

        syncUiBufferFromMultiData();
        syncWidgetsFromFields();
    }

    @Override
    @Nonnull
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("mode", extractMode);
        nbt.setBoolean("enabled", enabled);
        nbt.setInteger("networkID", networkID);
        data.writeToNBT(nbt);
        return nbt;
    }

    public boolean canExtract(EnumFacing dir, TileEntity tile) {
        return tile instanceof IDataHandler;
    }

    public boolean canInject(EnumFacing dir, TileEntity tile) {
        return tile instanceof IDataHandler;
    }

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>(4);
        modules.add(modeToggle);
        modules.add(enabledToggle);
        modules.add(netIdLabel);
        modules.add(new ModuleWirelessBufferBar(14, 22, uiBuffer));
        return modules;
    }

    @Override
    public String getModularInventoryName() {
        return "tile.wirelessTransceiver.name";
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == PACKET_MODE) {
            out.writeBoolean(extractMode);
        } else if (id == PACKET_ENABLED) {
            out.writeBoolean(enabled);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        nbt.setBoolean("state", in.readBoolean());
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (!side.isServer()) return;

        boolean state = nbt.getBoolean("state");

        if (id == PACKET_ENABLED) {
            enabled = state;
            syncWidgetsFromFields();
            markDirtyAndSyncBlock();
            return;
        }

        if (id == PACKET_MODE) {
            extractMode = state;
            joinNetwork();
            syncWidgetsFromFields();
            markDirtyAndSyncBlock();
        }
    }

    @Override
    public int extractData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        if (!enabled) return 0;

        int extracted = data.extractData(maxAmount, type, dir, commit);
        if (commit && extracted > 0) {
            syncUiBufferFromMultiData();
        }
        return extracted;
    }

    @Override
    public int addData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        if (!enabled) return 0;

        int added = data.addData(maxAmount, type, dir, commit);
        if (commit && added > 0) {
            syncUiBufferFromMultiData();
        }
        return added;
    }

    @Override
    public void update() {
        if (world == null || world.isRemote || !enabled) {
            return;
        }

        int interval = getEffectiveTransferInterval();
        if (phase < 0) {
            phase = (int) Math.floorMod(pos.toLong(), interval);
        }

        if (((world.getTotalWorldTime() + phase) % interval) != 0) {
            return;
        }

        EnumFacing facing = resolveTransferFacing();
        TileEntity neighborTile = world.getTileEntity(pos.offset(facing));
        if (!(neighborTile instanceof IDataHandler) || neighborTile instanceof TileWirelessTransceiver) {
            return;
        }

        IDataHandler neighbor = (IDataHandler) neighborTile;
        EnumFacing neighborSide = facing.getOpposite();
        boolean changed = false;

        for (DataType type : TYPES) {
            if (extractMode) {
                int room = data.getMaxData() - data.getDataAmount(type);
                if (room <= 0) continue;

                int moved = neighbor.extractData(room, type, neighborSide, true);
                if (moved > 0) {
                    data.addData(moved, type, neighborSide, true);
                    changed = true;
                }
            } else {
                int available = data.getDataAmount(type);
                if (available <= 0) continue;

                int moved = neighbor.addData(available, type, neighborSide, true);
                if (moved > 0) {
                    data.extractData(moved, type, neighborSide, true);
                    changed = true;
                }
            }
        }

        if (changed) {
            syncUiBufferFromMultiData();
            markDirty();
        }
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == PACKET_ENABLED) {
            enabled = enabledToggle.getState();
        } else if (buttonId == PACKET_MODE) {
            extractMode = modeToggle.getState();
        }

        syncWidgetsFromFields();
        PacketHandler.sendToServer(new PacketMachine(this, (byte) buttonId));
    }

    @Override
    public void stateUpdated(ModuleBase module) {
        if (module == enabledToggle) {
            enabled = enabledToggle.getState();
        } else if (module == modeToggle) {
            extractMode = modeToggle.getState();
        }

        syncWidgetsFromFields();

        if (world != null && !world.isRemote) {
            markDirtyAndSyncBlock();
        }
    }
}