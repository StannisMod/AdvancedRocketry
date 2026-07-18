package zmaster587.advancedRocketry.tile.satellite;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.api.satellite.IDataHandler;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.inventory.modules.ModuleData;
import zmaster587.advancedRocketry.inventory.modules.ModuleSatellite;
import zmaster587.advancedRocketry.item.IDataItem;
import zmaster587.advancedRocketry.item.ItemSatelliteIdentificationChip;
import zmaster587.advancedRocketry.satellite.SatelliteData;
import zmaster587.advancedRocketry.tile.TileWirelessTransceiver;
import zmaster587.advancedRocketry.util.IDataInventory;
import zmaster587.advancedRocketry.util.PlanetaryTravelHelper;

import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.TileInventoriedRFConsumer;
import zmaster587.libVulpes.util.INetworkMachine;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class TileSatelliteTerminal extends TileInventoriedRFConsumer
        implements INetworkMachine, IModularInventory, IButtonInventory, IDataInventory, IDataHandler {

    private DataStorage data;

    // Auto-download polling with exponential backoff
    private static final int AUTO_DL_BASE_INTERVAL_TICKS = 64;   // min interval
    private static final int AUTO_DL_MAX_INTERVAL_TICKS  = 512;  // cap
    private int autoDlInterval = AUTO_DL_BASE_INTERVAL_TICKS;    // current interval
    private long nextAutoDlTick = 0L;                             // worldTime gate


    public TileSatelliteTerminal() {
        super(10000, 2);
        data = new DataStorage();
        data.setMaxData(1000);
    }

    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

    private boolean hasExtractPlugAdjacent() {
        if (world == null) return false;
        for (EnumFacing f : EnumFacing.values()) {
            mpos.setPos(pos.getX() + f.getFrontOffsetX(),
                        pos.getY() + f.getFrontOffsetY(),
                        pos.getZ() + f.getFrontOffsetZ());
            if (!world.isBlockLoaded(mpos)) continue;

            TileEntity te = world.getTileEntity(mpos);
            if (te instanceof TileWirelessTransceiver) {
                TileWirelessTransceiver w =
                    (TileWirelessTransceiver) te;
                if (w.isEnabledWireless() && w.isExtractModeWireless()) return true;
            }
        }
        return false;
    }

    public final DataStorage getDataObject() {
        return data;
    }

    // Link+power check using an already-looked-up satellite
    private boolean hasLinkAndPower(@Nonnull SatelliteBase sat) {
        // must be a data satellite
        if (!(sat instanceof zmaster587.advancedRocketry.satellite.SatelliteData)) return false;

        // check range
        final int hereDim = zmaster587.advancedRocketry.dimension.DimensionManager
                .getEffectiveDimId(world, pos).getId();
        final int satDim  = sat.getDimensionId();

        final boolean inRange = zmaster587.advancedRocketry.util.PlanetaryTravelHelper
                .isTravelAnywhereInPlanetarySystem(satDim, hereDim);
        if (!inRange) return false;

        // check power
        return getUniversalEnergyStored() >= getPowerPerOperation();
    }

    // Keep convenience overload
    private void maybeAutoDownloadFromSatellite() { 
        maybeAutoDownloadFromSatellite(false); 
    }

    private void maybeAutoDownloadFromSatellite(boolean force) {
        if (world == null || world.isRemote) return;

        final long now = world.getTotalWorldTime();

        if (force) {
            autoDlInterval = AUTO_DL_BASE_INTERVAL_TICKS;
            nextAutoDlTick = now + autoDlInterval;  // avoid same-tick multi-pulls
        } else if (now < nextAutoDlTick) {
            return;
        }

        // Buffer full -> just schedule next check
        if (data.getData() >= data.getMaxData()) {
            nextAutoDlTick = now + autoDlInterval;
            return;
        }

        // No eligible plug -> back off (unless forced)
        if (!hasExtractPlugAdjacent()) {
            if (!force) autoDlInterval = Math.min(autoDlInterval << 1, AUTO_DL_MAX_INTERVAL_TICKS);
            nextAutoDlTick = now + autoDlInterval;
            return;
        }

        // Resolve satellite fresh from the chip each poll
        SatelliteBase sat = resolveSatelliteFresh();
        if (sat == null) {
            if (!force) autoDlInterval = Math.min(autoDlInterval << 1, AUTO_DL_MAX_INTERVAL_TICKS);
            nextAutoDlTick = now + autoDlInterval;
            return;
        }
        if (!hasLinkAndPower(sat)) {
            if (!force) autoDlInterval = Math.min(autoDlInterval << 1, AUTO_DL_MAX_INTERVAL_TICKS);
            nextAutoDlTick = now + autoDlInterval;
            return;
        }

        // Do the pull
        sat.performAction(null, world, pos);
        this.energy.extractEnergy(getPowerPerOperation(), false);

        // Success -> reset interval
        autoDlInterval = AUTO_DL_BASE_INTERVAL_TICKS;
        nextAutoDlTick = now + autoDlInterval;
    }


    private static final int[] NO_SLOTS = new int[0];

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) { return NO_SLOTS; }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockSatelliteControlCenter.getLocalizedName();
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (slot == 0) return stack.getItem() instanceof ItemSatelliteIdentificationChip;
        if (slot == 1) return stack.getItem() instanceof IDataItem;
        return false;
    }



    @Override
    public boolean canPerformFunction() {
        if (world == null) return false;
        final long now = world.getTotalWorldTime();
        return (now % 16 == 0) && (now >= nextAutoDlTick);
    }

    @Override
    public int getPowerPerOperation() { return 1; }

    @Override
    public void performFunction() {
        if (world == null || world.isRemote) return;
        maybeAutoDownloadFromSatellite(false);
    }



    // Old custom packet not used anymore; keep empty to satisfy INetworkMachine
    @Override
    public void writeDataToNetwork(ByteBuf out, byte packetId) { }

    // Old custom packet not used anymore; keep empty to satisfy INetworkMachine
    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) { }

    // Tick: nothing needed; the module polls the tile every 9 tick while GUI is open
    //@Override
    //public void update() {
    //    super.update();
        // no status pushing needed
    //}

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {

        if (id == -1) {
            storeData(1);
            return;
        } else if (id == -2) {
            loadData(1);
            return;
        } else if (id == 100) {
            // connect logic
            if (!world.isRemote) {
                SatelliteBase sat = getSatelliteFromSlot(0);

                boolean inRange = false;
                if (sat != null) {
                    int satDim = sat.getDimensionId();
                    int hereDim = DimensionManager.getEffectiveDimId(world, pos).getId();
                    inRange = PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(satDim, hereDim);
                }

                boolean hasLink  = (sat instanceof SatelliteData) && inRange;
                boolean hasPower = getUniversalEnergyStored() >= getPowerPerOperation();

                if (hasLink && hasPower) {
                    sat.performAction(player, world, pos);
                    this.energy.extractEnergy(getPowerPerOperation(), false);
                }
            }
            return;

        } else if (id == 101) {
            if (!world.isRemote) {
                ItemStack stack = getStackInSlot(0);
                if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
                    ItemSatelliteIdentificationChip idchip = (ItemSatelliteIdentificationChip) stack.getItem();

                    SatelliteBase sat = idchip.getSatellite(stack);
                    if (sat != null) {
                        DimensionManager.getInstance()
                                .getDimensionProperties(sat.getDimensionId())
                                .removeSatellite(sat.getId());
                    }

                    idchip.erase(stack);
                    setInventorySlotContents(0, stack);
                }
            }
            return;
        }
    }

    
    @Nullable
    private SatelliteBase resolveSatelliteFresh() {
        ItemStack s0 = getStackInSlot(0);
        return (!s0.isEmpty() && s0.getItem() instanceof ItemSatelliteIdentificationChip)
                ? ItemSatelliteIdentificationChip.getSatellite(s0)
                : null;
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);
        if (!world.isRemote && slot == 0) {
            maybeAutoDownloadFromSatellite(true); // force reset to base
        }
    }

    public SatelliteBase getSatelliteFromSlot(int slot) {
        ItemStack stack = getStackInSlot(slot);
        if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
            return ItemSatelliteIdentificationChip.getSatellite(stack);
        }
        return null;
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>(6);

        modules.add(new ModulePower(18, 20, this.energy) {
            @Override public int numberOfChangesToSend() { return 2; }
        });

        modules.add(new ModuleButton(
            116, 70, 0,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.connect"),
            this,
            zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.autodl_hint") // tooltip
        ));


        modules.add(new ModuleButton(173, 3, 1, "",
            this, TextureResources.buttonKill,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.destroysat"), 24, 24));

        modules.add(new ModuleData(28, 20, 1, this, data) {
            @Override public int numberOfChangesToSend() { return 2; }
        });

        modules.add(new ModuleSatellite(152, 10, this, 0) {
            @Override public int numberOfChangesToSend() { return 0; }
        });

        // Add status module last; no need to keep a field reference
        modules.add(new zmaster587.advancedRocketry.inventory.modules.ModuleSatelliteTerminal(
            60, 20, 0x404040, this, this));

        return modules;
    }


    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == 0) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) (100 + buttonId))); // id 100
        } else if (buttonId == 1) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) (100 + buttonId))); // id 101
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        NBTTagCompound dataTag = new NBTTagCompound();
        this.data.writeToNBT(dataTag);
        nbt.setTag("data", dataTag);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        NBTTagCompound dataTag = nbt.getCompoundTag("data");
        this.data.readFromNBT(dataTag);
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public void loadData(int slotId) {
        if (world == null || world.isRemote) {
            // Client triggers server action
            PacketHandler.sendToServer(new PacketMachine(this, (byte) -2));
            return;
        }

        ItemStack stack = getStackInSlot(slotId);
        if (stack.isEmpty() || !(stack.getItem() instanceof IDataItem)) return;

        IDataItem dataItem = (IDataItem) stack.getItem();
        DataStorage itemStore = dataItem.getDataStorage(stack);

        int available = itemStore.getData();
        if (available <= 0) return;

        DataType type = itemStore.getDataType();

        // How much room does the terminal buffer have?
        int room = data.getMaxData() - data.getData();
        if (room <= 0) return;

        int toMove = Math.min(available, room);

        // Add to terminal first (authoritative return value)
        int added = data.addData(toMove, type, true);

        if (added > 0) {
            // Remove only what actually got accepted
            dataItem.removeData(stack, added, type);
            setInventorySlotContents(slotId, stack);
            markDirty();
        }
    }

    @Override
    public void storeData(int slotId) {
        if (world == null || world.isRemote) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) -1));
            return;
        }

        ItemStack stack = getStackInSlot(slotId);
        if (stack.isEmpty() || !(stack.getItem() instanceof IDataItem)) return;

        if (data.getData() <= 0 || data.getDataType() == DataType.UNDEFINED) return;

        IDataItem dataItem = (IDataItem) stack.getItem();

        int moved = dataItem.addData(stack, data.getData(), data.getDataType());

        if (moved > 0) {
            data.removeData(moved, true);
            setInventorySlotContents(slotId, stack);
            markDirty();
        }
    }



    @Override
    public int extractData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        // 1) Type guard
        if (type != data.getDataType() && data.getDataType() != DataType.UNDEFINED) return 0;

        // 2) Simulation
        if (!commit) return Math.min(maxAmount, data.getData());

        // 3) Drain LOCAL once
        int toGive = Math.min(maxAmount, data.getData());
        int removed = (toGive > 0) ? data.removeData(toGive, true) : 0;

        // 4) Opportunistic refill (cheap guard inside function)
        if (removed > 0) {
            maybeAutoDownloadFromSatellite(true);
        }
        return removed;
    }


    @Override
    public int addData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        int added = data.addData(maxAmount, type, commit);

        return added;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!world.isRemote) {
            // Reset backoff scheduler to a sane base state
            autoDlInterval = AUTO_DL_BASE_INTERVAL_TICKS;
            long now = world.getTotalWorldTime();
            // Warm-up so neighbors/registries settle (e.g. 80 ticks ~ 4s)
            nextAutoDlTick = now + 80;
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        // Hard-clear any references and scheduling
        autoDlInterval = AUTO_DL_BASE_INTERVAL_TICKS;
        nextAutoDlTick = 0L;
    }

    @Override
    public void invalidate() {
        super.invalidate();
    }


    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) { return true; }
}
