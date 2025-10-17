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


public class TileSatelliteTerminal extends TileInventoriedRFConsumer implements INetworkMachine, IModularInventory, IButtonInventory, IDataInventory, IDataHandler {

    // Subscribers: players who currently have this GUI open (server-side only)
    private final java.util.Set<java.util.UUID> subscribers = new java.util.HashSet<>();


    //private ModuleText satelliteText;
    private SatelliteBase satellite;
    private ModuleText moduleText;
    private DataStorage data;

    public TileSatelliteTerminal() {
        super(10000, 2);

        data = new DataStorage();
        data.setMaxData(1000);
    }

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[0];
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockSatelliteControlCenter.getLocalizedName();
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        return true;
    }

    @Override
    public boolean canPerformFunction() {
        return world.getTotalWorldTime() % 16 == 0 && getSatelliteFromSlot(0) != null;
    }

    @Override
    public int getPowerPerOperation() {
        return 1;
    }

    @Override
    public void performFunction() {
        if (world.isRemote)
            updateInventoryInfo();
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte packetId) {
        if (packetId == (byte) 22) {
            satellite = getSatelliteFromSlot(0);
            if (satellite != null && satellite instanceof SatelliteData) {
                if (getUniversalEnergyStored() < getPowerPerOperation()) {
                    out.writeInt(1); // no power
                } else {
                    if (!PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(satellite.getDimensionId(), DimensionManager.getEffectiveDimId(world, pos).getId())) {
                        out.writeInt(2);//out of range
                    } else {
                        out.writeInt(3);
                        out.writeInt(((SatelliteData) satellite).getPowerPerTick());
                        out.writeInt(((SatelliteData) satellite).data.getData());
                        out.writeInt(((SatelliteData) satellite).data.getMaxData());
                    }
                }
            } else {
                out.writeInt(0); // no link
            }
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == (byte) 22) {
            int status = in.readInt();
            if (status == 3){
                nbt.setInteger("ppt", in.readInt());
                nbt.setInteger("data", in.readInt());
                nbt.setInteger("maxdata", in.readInt());
            }
            nbt.setInteger("status", status);
        }
    }

    @Override
    public void update() {
        super.update();
        if (world.isRemote) return;

        if ((world.getTotalWorldTime() % 20) == 0 && !subscribers.isEmpty()) {
            PacketMachine pkt = new PacketMachine(this, (byte)22);
            java.util.Set<java.util.UUID> stale = new java.util.HashSet<>();

            for (java.util.UUID id : subscribers) {
                net.minecraft.entity.player.EntityPlayerMP mp =
                    (net.minecraft.entity.player.EntityPlayerMP) world.getPlayerEntityByUUID(id);
                if (mp == null || !mp.isEntityAlive()) {
                    stale.add(id);
                    continue;
                }
                zmaster587.libVulpes.network.PacketHandler.sendToPlayer(pkt, mp);
            }
            if (!stale.isEmpty()) subscribers.removeAll(stale);
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (id == 0) {
            storeData(0);

        } else if (id == 100) {
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
                    // perform action and pay the RF cost, just like extractData(commit=true)
                    sat.performAction(player, world, pos);
                    this.energy.extractEnergy(getPowerPerOperation(), false);
                }

                // Push a fresh status payload either way so the UI reflects current state
                if (player instanceof net.minecraft.entity.player.EntityPlayerMP) {
                    zmaster587.libVulpes.network.PacketHandler.sendToPlayer(
                        new PacketMachine(this, (byte)22),
                        (net.minecraft.entity.player.EntityPlayerMP) player
                    );
                }
            }

        } else if (id == 101) {
            onInventoryButtonPressed(id - 100);
        }

        if (id == 22) {
            if (world.isRemote) { // 22 should never arrive at the server
                int status = nbt.getInteger("status");
                satellite = getSatelliteFromSlot(0);
                if (moduleText != null) {
                    if (status != 0 && satellite != null) {
                        if (status == 1) {
                            moduleText.setText(LibVulpes.proxy.getLocalizedString("msg.notenoughpower"));
                        } else if (status == 2) {
                            moduleText.setText(satellite.getName() + "\n\n" + LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.toofar"));
                        } else if (status == 3) {
                            moduleText.setText(satellite.getName() + "\n\n" + LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.info") + "\n" +
                                    "Power gen.: " + nbt.getInteger("ppt") + "\n" +
                                    "Data: " + nbt.getInteger("data") + "/" + nbt.getInteger("maxdata"));
                        }
                    } else {
                        moduleText.setText(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.nolink"));
                    }
                }
            }
        }
    }


    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        super.setInventorySlotContents(slot, stack);
        satellite = getSatelliteFromSlot(0);
        updateInventoryInfo();
    }

    public void updateInventoryInfo() {

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

        // Ensure the server registers the viewer and sends immediate state
        if (!world.isRemote && player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            java.util.UUID uid = player.getUniqueID();
            if (!subscribers.contains(uid)) {
                subscribers.add(uid);
            }
            // immediate payload so UI doesn’t show "no link" for up to 1s
            zmaster587.libVulpes.network.PacketHandler.sendToPlayer(
                    new PacketMachine(this, (byte)22),
                    (net.minecraft.entity.player.EntityPlayerMP) player
            );
        }        

        List<ModuleBase> modules = new LinkedList<>();
        modules.add(new ModulePower(18, 20, this.energy));
        modules.add(new ModuleButton(116, 70, 0, LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.connect"), this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild));
        modules.add(new ModuleButton(173, 3, 1, "", this, TextureResources.buttonKill, LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.destroysat"), 24, 24));
        modules.add(new ModuleData(28, 20, 1, this, data));
        ModuleSatellite moduleSatellite = new ModuleSatellite(152, 10, this, 0);
        moduleSatellite.setSatellite(satellite);
        modules.add(moduleSatellite);

        //Try to assign a satellite ASAP
        //moduleSatellite.setSatellite(getSatelliteFromSlot(0));

        moduleText = new ModuleText(60, 20, LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.nolink"), 0x404040);
        modules.add(moduleText);

        updateInventoryInfo();
        return modules;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {

        if (buttonId == 0) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) (100 + buttonId)));

        } else if (buttonId == 1) {
            ItemStack stack = getStackInSlot(0);

            if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
                ItemSatelliteIdentificationChip idchip = (ItemSatelliteIdentificationChip) stack.getItem();

                SatelliteBase satellite = idchip.getSatellite(stack);

                //Somebody might want to erase the chip of an already existing satellite
                if (satellite != null)
                    DimensionManager.getInstance().getDimensionProperties(satellite.getDimensionId()).removeSatellite(satellite.getId());

                idchip.erase(stack);
                setInventorySlotContents(0, stack);
                PacketHandler.sendToServer(new PacketMachine(this, (byte) (100 + buttonId)));
            }
        }

    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        NBTTagCompound data = new NBTTagCompound();

        this.data.writeToNBT(data);
        nbt.setTag("data", data);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        NBTTagCompound data = nbt.getCompoundTag("data");
        this.data.readFromNBT(data);
    }

    @Override
    public void loadData(int id) {
    }

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
        SatelliteBase sat = getSatelliteFromSlot(0);

        // Link + range + power gates (same as UI)
        boolean inRange = false;
        if (sat != null) {
            int satDim = sat.getDimensionId();
            int hereDim = DimensionManager.getEffectiveDimId(world, pos).getId();
            inRange = PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(satDim, hereDim);
        }
        boolean hasLink  = (sat instanceof SatelliteData) && inRange;
        boolean hasPower = getUniversalEnergyStored() >= getPowerPerOperation();

        if (!(hasLink && hasPower)) {
            return 0;
        }

        if (!commit) {
            // Simulate: do NOT pull from satellite; just report current availability
            if (type != data.getDataType() && data.getDataType() != DataType.UNDEFINED) return 0;
            int available = data.getData();
            return Math.min(maxAmount, available);
        }

        // COMMIT path: first pull fresh data from the satellite into our buffer
        sat.performAction(null, world, pos);

        // Now validate type and figure out how much we can remove
        if (type != data.getDataType() && data.getDataType() != DataType.UNDEFINED) return 0;

        int removable = Math.min(maxAmount, data.getData());
        if (removable <= 0) return 0;

        // Consume RF only if we're actually removing data
        this.energy.extractEnergy(getPowerPerOperation(), false);

        return data.removeData(removable, true);
    }

    @Override
    public int addData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {

        return data.addData(maxAmount, type, commit);
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }


    // Subscribe/unsubscribe when GUI (container) opens/closes
    @Override
    public void openInventory(EntityPlayer player) {
        super.openInventory(player);
        if (!world.isRemote && player != null) {
            subscribers.add(player.getUniqueID());
            // immediate first payload so UI populates without waiting a tick
            if (player instanceof net.minecraft.entity.player.EntityPlayerMP) {
                zmaster587.libVulpes.network.PacketHandler.sendToPlayer(new PacketMachine(this, (byte)22),
                        (net.minecraft.entity.player.EntityPlayerMP) player);
            }
        }
    }

    @Override
    public void closeInventory(EntityPlayer player) {
        super.closeInventory(player);
        if (!world.isRemote && player != null) {
            subscribers.remove(player.getUniqueID());
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (!world.isRemote) {
            subscribers.clear();
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (!world.isRemote) {
            subscribers.clear();
        }
    }

}
