package zmaster587.advancedRocketry.tile.cables;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.Block;
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
import zmaster587.advancedRocketry.cable.NetworkRegistry;
import zmaster587.advancedRocketry.inventory.TextureResources;
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
import java.util.LinkedList;
import java.util.List;

public class TileWirelessTransciever extends TileEntity implements INetworkMachine, IModularInventory, ILinkableTile, IDataHandler, ITickable, IToggleButton {

    // How often to transfer data (in ticks)
    private int transferIntervalTicks = 20;
    // Fixed phase per tile to spread load
    private int phase = -1;
    // Show network ID (label)
    private ModuleText netIdLabel;

    // Avoid per-call allocations from DataType.values()
    // needs update if DataType enum changes
    private static final DataType[] TYPES = {
        DataType.DISTANCE, DataType.HUMIDITY, DataType.TEMPERATURE,
        DataType.COMPOSITION, DataType.ATMOSPHEREDENSITY, DataType.MASS
    };

    protected ModuleToggleSwitch toggleSwitch;
    boolean extractMode;
    boolean enabled;
    int networkID;
    MultiData data;
    ModuleToggleSwitch toggle;

    public TileWirelessTransciever() {

        networkID = -1;
        data = new MultiData();
        data.setMaxData(100);
        toggle = new ModuleToggleSwitch(50, 50, 0, LibVulpes.proxy.getLocalizedString("msg.wirelessTransciever.extract"), this, TextureResources.buttonGeneric, 64, 18, false);
        toggleSwitch = new ModuleToggleSwitch(160, 5, 1, "", this, zmaster587.libVulpes.inventory.TextureResources.buttonToggleImage, 11, 26, true);
        
        // Align internal booleans with UI defaults
        extractMode = toggle.getState();         // false initially
        enabled = toggleSwitch.getState();       // true initially    
        updateToggleLabel();
        
        // Network ID label
        netIdLabel = new ModuleText(40, 72, "Network: -", 0x000000);
        netIdLabel.setAlwaysOnTop(true); // optional, so it renders over slots
    }

    private void updateToggleLabel() {
        if (toggle != null) {
            String key = extractMode
                ? "msg.wirelessTransciever.extract"   // pulling from side → network
                : "msg.wirelessTransciever.insert";   // pushing from network → side
            toggle.setText(LibVulpes.proxy.getLocalizedString(key));
        }
    }

    private EnumFacing resolveFront(IBlockState state) {
        if (state == null) return EnumFacing.NORTH;
        if (state.getBlock() instanceof zmaster587.advancedRocketry.block.BlockTransciever) {
            return zmaster587.advancedRocketry.block.BlockTransciever.getFront(state);
        }
        // fallback for legacy blocks if any remain
        if (state.getBlock() instanceof zmaster587.libVulpes.block.RotatableBlock) {
            return zmaster587.libVulpes.block.RotatableBlock.getFront(state);
        }
        return EnumFacing.NORTH;
    }

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {

        ItemLinker.setMasterCoords(item, getPos());

        if (world.isRemote)
            player.sendMessage(new TextComponentTranslation("msg.linker.program"));

        return true;
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (networkID == -1) return;
        if (NetworkRegistry.dataNetwork.doesNetworkExist(networkID))
            NetworkRegistry.dataNetwork.getNetwork(networkID).removeFromAll(this);
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        BlockPos pos = ItemLinker.getMasterCoords(item);
        if (pos == null) return false; // defensive

        if (pos.equals(this.pos)) {
            if (world.isRemote) player.sendMessage(new TextComponentTranslation("msg.linker.sameblock"));
            return false;
        }

        if (!world.isBlockLoaded(pos)) return false;

        TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof TileWirelessTransciever)) return false;

        if (tile instanceof TileWirelessTransciever) {
            if (world.isRemote) {
                player.sendMessage(new TextComponentTranslation("msg.linker.success"));
                return true;
            }

            int otherNetworkId = ((TileWirelessTransciever) tile).networkID;

            if (networkID == -1 && otherNetworkId == -1) {
                networkID = NetworkRegistry.dataNetwork.getNewNetworkID();
                ((TileWirelessTransciever) tile).networkID = networkID;

            } else if (networkID == -1) {
                networkID = otherNetworkId;
            } else if (otherNetworkId == -1) {
                ((TileWirelessTransciever) tile).networkID = networkID;
            } else {
                networkID = NetworkRegistry.dataNetwork.mergeNetworks(otherNetworkId, networkID);
                ((TileWirelessTransciever) tile).networkID = networkID;
            }
            addToNetwork();
            ((TileWirelessTransciever) tile).addToNetwork();


            //SYNC CLIENT UI/STATE FOR BOTH TILES
            this.markDirty();
            world.notifyBlockUpdate(this.pos, world.getBlockState(this.pos), world.getBlockState(this.pos), 3);

            tile.markDirty();
            world.notifyBlockUpdate(tile.getPos(), world.getBlockState(tile.getPos()),
                                    world.getBlockState(tile.getPos()), 3);
           

            ItemLinker.resetPosition(item);

            return true;
        }

        return false;
    }

    private void addToNetwork() {

        if (networkID == -1 || world.isRemote)
            return;
        else if (!NetworkRegistry.dataNetwork.doesNetworkExist(networkID))
            NetworkRegistry.dataNetwork.getNewNetworkID(networkID);

        if (extractMode) {
            NetworkRegistry.dataNetwork.getNetwork(networkID).addSource(this, EnumFacing.UP);
        } else {
            NetworkRegistry.dataNetwork.getNetwork(networkID).addSink(this, EnumFacing.UP);
        }
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound nbt = new NBTTagCompound();
        this.writeToNBT(nbt);

        return new SPacketUpdateTileEntity(this.pos, 0, nbt);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        this.readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    public boolean canExtract(EnumFacing dir, TileEntity e) {

        return e instanceof IDataHandler;
    }


    public boolean canInject(EnumFacing dir, TileEntity e) {
        return e instanceof IDataHandler;
    }

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        LinkedList<ModuleBase> list = new LinkedList<>();

        list.add(toggle);
        list.add(toggleSwitch);
        list.add(netIdLabel);  

        return list;
    }

    @Override
    public String getModularInventoryName() {
        return "tile.wirelessTransciever.name";
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == 0)
            out.writeBoolean(toggle.getState());
        else if (id == 1)
            out.writeBoolean(toggleSwitch.getState());
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        nbt.setBoolean("state", in.readBoolean());

    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (!side.isServer()) return;
        if (id == 1) { // enable/disable toggle doesn’t touch networks
            enabled = nbt.getBoolean("state");
            return;
        }
        if (networkID == -1) return; // not linked yet; ignore network mutations

        if (id == 0) {
            extractMode = nbt.getBoolean("state");
            updateToggleLabel();
            if (NetworkRegistry.dataNetwork.doesNetworkExist(networkID)) {
                NetworkRegistry.dataNetwork.getNetwork(networkID).removeFromAll(this);
                if (extractMode)
                    NetworkRegistry.dataNetwork.getNetwork(networkID).addSource(this, EnumFacing.UP);
                else
                    NetworkRegistry.dataNetwork.getNetwork(networkID).addSink(this, EnumFacing.UP);
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        extractMode = nbt.getBoolean("mode");
        enabled = nbt.getBoolean("enabled");
        networkID = nbt.getInteger("networkID");
        data.readFromNBT(nbt);
        //addToNetwork();

        toggle.setToggleState(extractMode);
        updateToggleLabel();
        toggleSwitch.setToggleState(enabled);
        if (world != null && world.isRemote && netIdLabel != null) {
            netIdLabel.setText("Network: " + networkID);
        }

    }

    @Override
    @Nonnull
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);                 // MOVE: call first
        nbt.setBoolean("mode", extractMode);
        nbt.setBoolean("enabled", enabled);
        nbt.setInteger("networkID", networkID);
        data.writeToNBT(nbt);
        return nbt;
    }


    @Override
    public int extractData(int maxAmount, DataType type, EnumFacing dir,
                           boolean commit) {
        return enabled ? data.extractData(maxAmount, type, dir, commit) : 0;
    }

    @Override
    public int addData(int maxAmount, DataType type, EnumFacing dir,
                       boolean commit) {
        return enabled ? data.addData(maxAmount, type, dir, commit) : 0;
    }

    @Override
    public void onLoad() {
        super.onLoad();

        // Server side only
        if (world == null || world.isRemote) return;

        // Ensure sane interval before using it in modulo
        if (transferIntervalTicks <= 0) transferIntervalTicks = 20;

        // Stable per-tile phase to spread work over time (no persistence needed)
        phase = (int) Math.floorMod(this.pos.toLong(), transferIntervalTicks);

        // (Re)join the data network only if we’re actually linked to one
        if (networkID != -1) {
            if (!NetworkRegistry.dataNetwork.doesNetworkExist(networkID)) {
                // Create (or re-create) this specific network id
                NetworkRegistry.dataNetwork.getNewNetworkID(networkID);
            }

            // Make sure we're not double-registered, then register with the right role
            NetworkRegistry.dataNetwork.getNetwork(networkID).removeFromAll(this);

            if (extractMode) {
                NetworkRegistry.dataNetwork.getNetwork(networkID).addSource(this, EnumFacing.UP);
            } else {
                NetworkRegistry.dataNetwork.getNetwork(networkID).addSink(this, EnumFacing.UP);
            }
        }
    }

    @Override
    public void update() {
        // Server only
        if (world.isRemote) return;

        // Respect front-panel enable switch
        if (!enabled) return;

        // Guard against bad values (e.g., NBT edits)
        if (transferIntervalTicks <= 0) transferIntervalTicks = 20;

        // Initialize a stable phase to spread load across ticks
        if (phase < 0) {
            phase = (int) Math.floorMod(this.pos.toLong(), transferIntervalTicks);
        }

        // Throttle: only run on the tile's assigned tick
        long now = world.getTotalWorldTime();
        if (((now + phase) % transferIntervalTicks) != 0) return;

        IBlockState state = world.getBlockState(getPos());

        // Resolve front for either 6-way or legacy block
        EnumFacing front = resolveFront(state);
        EnumFacing facing = front.getOpposite(); // keep existing IO semantics
        TileEntity neighbor = world.getTileEntity(getPos().offset(facing));
        if (neighbor == null || neighbor instanceof TileWirelessTransciever) return;
        if (!(neighbor instanceof IDataHandler)) return;

        boolean changed = false;

        for (DataType dataType : TYPES) {

            if (!extractMode) {
                // PUSH: from this buffer -> neighbor
                int have = this.data.getDataAmount(dataType);
                if (have <= 0) continue;

                int moved = ((IDataHandler) neighbor).addData(have, dataType, facing.getOpposite(), true);
                if (moved > 0) {
                    this.data.extractData(moved, dataType, facing.getOpposite(), true);
                    changed = true;
                }
            } else {
                // PULL: from neighbor -> this buffer
                int room = this.data.getMaxData() - this.data.getDataAmount(dataType);
                if (room <= 0) continue;

                int moved = ((IDataHandler) neighbor).extractData(room, dataType, facing.getOpposite(), true);
                if (moved > 0) {
                    this.data.addData(moved, dataType, facing.getOpposite(), true);
                    changed = true;
                }
            }
        }


        // Persist changes; a full block update isn't strictly required each tick
        if (changed) {
            this.markDirty();
            // If you want to sync the client meter instantly, you can uncomment:
            // world.notifyBlockUpdate(pos, state, state, 3);
        }
    }


    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == 1) {
            enabled = toggleSwitch.getState();
        } else if (buttonId == 0) {
            extractMode = toggle.getState();
            updateToggleLabel();
        }
        PacketHandler.sendToServer(new PacketMachine(this, (byte) buttonId));
    }

    @Override
    public void stateUpdated(ModuleBase module) {
        if (module == toggleSwitch) {
            enabled = toggleSwitch.getState();
        } else if (module == toggle) {
            extractMode = toggle.getState();
            updateToggleLabel();
        }

        if (!world.isRemote) {
            this.markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }
    @Override
    public void invalidate() {
        // called when the TE is removed because the block changed/broke
        if (!world.isRemote && NetworkRegistry.dataNetwork.doesNetworkExist(networkID)) {
            NetworkRegistry.dataNetwork.getNetwork(networkID).removeFromAll(this);
        }
        super.invalidate();
    }
    
}
