package zmaster587.advancedRocketry.tile.satellite;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
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
import zmaster587.advancedRocketry.item.ItemData;
import zmaster587.advancedRocketry.item.ItemSatelliteIdentificationChip;
import zmaster587.advancedRocketry.satellite.SatelliteData;
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
import java.util.LinkedList;
import java.util.List;

public class TileSatelliteTerminal extends TileInventoriedRFConsumer
        implements INetworkMachine, IModularInventory, IButtonInventory, IDataInventory, IDataHandler {

    private DataStorage data;

    public TileSatelliteTerminal() {
        super(10000, 2);
        data = new DataStorage();
        data.setMaxData(1000);
    }

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) { return new int[0]; }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockSatelliteControlCenter.getLocalizedName();
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) { return true; }

    @Override
    public boolean canPerformFunction() {
        return world.getTotalWorldTime() % 16 == 0 && getSatelliteFromSlot(0) != null;
    }

    @Override
    public int getPowerPerOperation() { return 1; }

    @Override
    public void performFunction() {
        // No client push here anymore; module sync handles display updates.
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
        if (id == 0) {
            // store data to item (server handles it inside storeData)
            storeData(0);

        } else if (id == 100) {
            // "Connect" / perform action
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
                    // server mutates the inventory; client will get it via normal container sync
                    setInventorySlotContents(0, stack);
                }
            }
        }
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);
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
        List<ModuleBase> modules = new LinkedList<>();

        modules.add(new ModulePower(18, 20, this.energy) {
            @Override public int numberOfChangesToSend() { return 2; }
        });

        modules.add(new ModuleButton(116, 70, 0,
            LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.connect"),
            this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild));

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
    public void loadData(int id) { }

    @Override
    public void storeData(int id) {
        if (!world.isRemote) {
            ItemStack stack = getStackInSlot(1);
            if (!stack.isEmpty() && stack.getItem() instanceof ItemData && stack.getCount() == 1) {
                ItemData dataItem = (ItemData) stack.getItem();
                data.removeData(dataItem.addData(stack, data.getData(), data.getDataType()), true);
            }
        } else {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
        }
    }

    @Override
    public int extractData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        // 1) Type guard (unchanged)
        if (type != data.getDataType() && data.getDataType() != DataType.UNDEFINED) {
            return 0;
        }

        // 2) Simulation: report local only (don’t guess satellite yield)
        if (!commit) {
            int availableLocal = data.getData();
            return Math.min(maxAmount, availableLocal);
        }

        // 3) Drain LOCAL first, chip or no chip
        int availableLocal = data.getData();
        int toGive = Math.min(maxAmount, availableLocal);
        int removed = 0;
        if (toGive > 0) {
            removed = data.removeData(toGive, true);
        }

        // 4) If we have link+power, auto-download to refill AFTER the pull
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
            sat.performAction(null, world, pos);                // same as GUI Download
            this.energy.extractEnergy(getPowerPerOperation(), false);
            // (No immediate extra removal here; we already served the request.)
        }

        return removed;  // may be 0 if buffer empty and no link/power
    }


    @Override
    public int addData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        int added = data.addData(maxAmount, type, commit);

        return added;
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) { return true; }
}
