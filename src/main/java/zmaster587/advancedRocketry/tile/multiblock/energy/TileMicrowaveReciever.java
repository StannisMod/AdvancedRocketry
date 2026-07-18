package zmaster587.advancedRocketry.tile.multiblock.energy;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.client.TooltipInjector;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.item.ItemSatelliteIdentificationChip;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.advancedRocketry.util.PlanetaryTravelHelper;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.api.IUniversalEnergyTransmitter;
import zmaster587.libVulpes.block.BlockMeta;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleText;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.multiblock.TilePlaceholder;
import zmaster587.libVulpes.tile.multiblock.hatch.TileInventoryHatch;
import zmaster587.libVulpes.tile.multiblock.TileMultiBlock;
import zmaster587.libVulpes.tile.multiblock.TileMultiPowerProducer;
import zmaster587.libVulpes.util.Vector3F;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


public class TileMicrowaveReciever extends TileMultiPowerProducer implements ITickable {

    // key: BlockPos.toLong(), value: saved non-empty stacks for that hatch (slot order preserved)
    private final Map<Long, NonNullList<ItemStack>> savedHatchInv = new HashMap<>();

    static final BlockMeta iron_block = new BlockMeta(AdvancedRocketryBlocks.blockSolarPanel);
    static final Object[][][] structure = new Object[][][]{
            {
                    {iron_block, '*', '*', '*', iron_block},
                    {'*', iron_block, iron_block, iron_block, '*'},
                    {'*', iron_block, 'c', iron_block, '*'},
                    {'*', iron_block, iron_block, iron_block, '*'},
                    {iron_block, '*', '*', '*', iron_block},
            }};

    List<Long> connectedSatellites;
    boolean initialCheck;
    double insolationPowerMultiplier;
    int powerSourceDimensionID;
    int powerMadeLastTick, prevPowerMadeLastTick;
    ModuleText textModule;

    public TileMicrowaveReciever() {
        connectedSatellites = new LinkedList<>();
        initialCheck = false;
        insolationPowerMultiplier = 0;
        textModule = new ModuleText(40, 20, LibVulpes.proxy.getLocalizedString("msg.microwaverec.notgenerating"), 0x2b2b2b);
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);

        modules.add(textModule);

        return modules;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return super.getRenderBoundingBox().grow(0, 2000, 0).offset(0, 1000, 0);
    }

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
        return false;
    }

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public List<BlockMeta> getAllowableWildCardBlocks() {
        List<BlockMeta> blocks = super.getAllowableWildCardBlocks();

        blocks.addAll(TileMultiBlock.getMapping('I'));
        blocks.add(iron_block);
        blocks.addAll(TileMultiBlock.getMapping('p'));

        return blocks;
    }

    @Override
    public String getMachineName() {
        return AdvancedRocketryBlocks.blockMicrowaveReciever.getLocalizedName();
    }

    public int getPowerMadeLastTick() {
        return powerMadeLastTick;
    }

    @Override
    public void onInventoryUpdated() {
        super.onInventoryUpdated();

        List<Long> list = new LinkedList<>();

        if (itemInPorts != null) {
            for (IInventory inv : itemInPorts) {
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
                        list.add(SatelliteRegistry.getSatelliteId(stack));
                    }
                }
            }
        }
        connectedSatellites = new LinkedList<>(new LinkedHashSet<>(list));
    }

    private List<Long> getConnectedSatellitesLive() {
        if (itemInPorts == null) return java.util.Collections.emptyList();

        // refresh TE references (libVulpes replaces TEs during multiblock build/load)
        List<IInventory> ports = getItemInPorts();

        java.util.LinkedHashSet<Long> set = new java.util.LinkedHashSet<>();
        for (IInventory inv : ports) {
            if (inv == null) continue;
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.isEmpty() && stack.getItem() instanceof ItemSatelliteIdentificationChip) {
                    set.add(SatelliteRegistry.getSatelliteId(stack));
                }
            }
        }
        return new java.util.ArrayList<>(set);
    }

    @Override
    public boolean attemptCompleteStructure(IBlockState state) {
        if (!world.isRemote) {
            // Snapshot BEFORE formation (real hatches)
            snapshotHatchInventories();
        }
        boolean ok = super.attemptCompleteStructure(state);

        if (!world.isRemote) {
            if (ok) {
                // Formation succeeded -> push snapshot into placeholders (alive state)
                writeSnapshotIntoPlaceholders();
            } else {
                // Formation failed -> discard
                savedHatchInv.clear();
            }
        }
        return ok;
    }


    @Override
    public void deconstructMultiBlock(World worldIn, BlockPos destroyedPos, boolean blockBroken, IBlockState state) {
        if (!worldIn.isRemote) {
            snapshotFromPlaceholders();
        }

        super.deconstructMultiBlock(worldIn, destroyedPos, blockBroken, state);

        if (!worldIn.isRemote) {
            restoreHatchInventories();
        }
    }


    @Override
    public void update() {

        if (!initialCheck && !world.isRemote) {
            completeStructure = attemptCompleteStructure(world.getBlockState(pos));
            onInventoryUpdated();
            initialCheck = true;
        }

        //Checks whenever a station changes dimensions or when the multiblock is intialized - ie any time the multipler could concieveably change
        final int curDim = world.provider.getDimension();
        final int spaceDim = ARConfiguration.getCurrentConfig().spaceDimId;

        // Cache station once; can be null
        final zmaster587.advancedRocketry.stations.SpaceStationObject station =
            (curDim == spaceDim)
                ? (zmaster587.advancedRocketry.stations.SpaceStationObject)
                    zmaster587.advancedRocketry.stations.SpaceObjectManager.getSpaceManager()
                        .getSpaceStationFromBlockCoords(this.pos)
                : null;

        // Recompute when uninitialized OR (in space AND orbiting planet changed and station exists)
        final boolean needRecompute =
            (insolationPowerMultiplier == 0)
            || (curDim == spaceDim && station != null && powerSourceDimensionID != station.getOrbitingPlanetId());

        if (needRecompute) {
            if (curDim == spaceDim && station != null) {
                insolationPowerMultiplier = station.getInsolationMultiplier();
                powerSourceDimensionID = station.getOrbitingPlanetId();
            } else {
                final zmaster587.advancedRocketry.dimension.DimensionProperties props =
                    zmaster587.advancedRocketry.dimension.DimensionManager.getInstance()
                        .getDimensionProperties(curDim);
                insolationPowerMultiplier = (props != null)
                    ? props.getPeakInsolationMultiplierWithoutAtmosphere()
                    : 1.0; // safe fallback
                powerSourceDimensionID = curDim;
            }
        }
        // If we're in space but station==null (early ticks), keep previous multiplier and carry on.

        if (!isComplete())
            return;

        //Periodically check for obstructing blocks above the panel
        if (!world.isRemote && getPowerMadeLastTick() > 0 && world.getTotalWorldTime() % 100 == 0) {
            Vector3F<Integer> offset = getControllerOffset(getStructure());


            List<Entity> entityList = world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(this.getPos().getX() - offset.x, this.getPos().getY(), this.getPos().getZ() - offset.z, this.getPos().getX() - offset.x + getStructure()[0][0].length, 256, this.getPos().getZ() - offset.z + getStructure()[0].length));

            for (Entity e : entityList) {
                e.setFire(powerMadeLastTick / 10);
            }

            for (int x = 0; x < getStructure()[0][0].length; x++) {
                for (int z = 0; z < getStructure()[0].length; z++) {

                    BlockPos pos2;
                    IBlockState state = world.getBlockState(pos2 = (world.getHeight(pos.add(x - offset.x, 128, z - offset.z)).add(0, -1, 0)));

                    if (pos2.getY() > this.getPos().getY()) {
                        if (!world.isAirBlock(pos2.add(0, 1, 0))) {
                            world.setBlockToAir(pos2);
                            world.playSound(pos2.getX(), pos2.getY(), pos2.getZ(), new SoundEvent(new ResourceLocation("fire.fire")), SoundCategory.BLOCKS, 1f, 3f, false);
                        }
                    }
                }
            }
        }

        final int dimId = world.provider.getDimension();
        final boolean dimOk = DimensionManager.getInstance().isDimensionCreated(dimId) || dimId == 0;

        if (!world.isRemote && dimOk) {
            // If we’re on a station, prefer its orbiting planet; otherwise use the local dim props
            final SpaceStationObject stationHere = (dimId == ARConfiguration.getCurrentConfig().spaceDimId)
                    ? (SpaceStationObject) SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.pos)
                    : null;

            final DimensionProperties props = (stationHere != null)
                    ? stationHere.getOrbitingPlanet()
                    : DimensionManager.getInstance().getDimensionProperties(dimId);

            int energyReceived = 0;

            final List<Long> sats = enabled && props != null ? getConnectedSatellitesLive() : java.util.Collections.emptyList();

            if (!sats.isEmpty()) {
                for (long lng : sats) {
                    final SatelliteBase sat = props.getSatellite(lng);
                    if (sat == null) continue;

                    final int satDim = sat.getDimensionId();
                    final int hereDim = DimensionManager.getEffectiveDimId(world, pos).getId();
                    if (!PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(satDim, hereDim)) continue;

                    if (sat instanceof IUniversalEnergyTransmitter) {
                        energyReceived += ((IUniversalEnergyTransmitter) sat).transmitEnergy(EnumFacing.UP, false);
                    }
                }

                // scale by insolation (your existing logic)
                energyReceived = (int)Math.round(energyReceived * (2 * insolationPowerMultiplier));
            }


            powerMadeLastTick = energyReceived;

            if (powerMadeLastTick != prevPowerMadeLastTick) {
                prevPowerMadeLastTick = powerMadeLastTick;
                PacketHandler.sendToNearby(new PacketMachine(this, (byte) 1),
                        world.provider.getDimension(), pos, 128);
            }
            producePower(powerMadeLastTick);
        }

        if (world.isRemote) {
            textModule.setText(
                LibVulpes.proxy.getLocalizedString("msg.microwaverec.generating") + " " +
                powerMadeLastTick + " " +
                LibVulpes.proxy.getLocalizedString("msg.powerunit.rfpertick"));
        }
    }    


    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setBoolean("canRender", canRender);
        nbt.setInteger("amtPwr", powerMadeLastTick);
        writeNetworkData(nbt);
        return new SPacketUpdateTileEntity(pos, 0, nbt);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        NBTTagCompound nbt = pkt.getNbtCompound();

        canRender = nbt.getBoolean("canRender");
        powerMadeLastTick = nbt.getInteger("amtPwr");
        readNetworkData(nbt);
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setBoolean("canRender", canRender);
        nbt.setInteger("amtPwr", powerMadeLastTick);
        writeToNBT(nbt);
        return nbt;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound nbt) {
        powerMadeLastTick = nbt.getInteger("amtPwr");
        canRender = nbt.getBoolean("canRender");
        readNetworkData(nbt);
    }


    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        super.writeDataToNetwork(out, id);

        if (id == 1) {
            out.writeInt(powerMadeLastTick);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        super.readDataFromNetwork(in, packetId, nbt);

        if (packetId == 1) {
            nbt.setInteger("amtPwr", in.readInt());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        super.useNetworkData(player, side, id, nbt);

        if (id == 1) {
            powerMadeLastTick = nbt.getInteger("amtPwr");
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        // ---- saved hatch inventories ----
        NBTTagList hatchList = new NBTTagList();
        if (savedHatchInv != null && !savedHatchInv.isEmpty()) {
            for (Map.Entry<Long, NonNullList<ItemStack>> e : savedHatchInv.entrySet()) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setLong("pos", e.getKey());

                NBTTagList items = new NBTTagList();
                NonNullList<ItemStack> arr = e.getValue();
                for (int slot = 0; slot < arr.size(); slot++) {
                    ItemStack s = arr.get(slot);
                    if (s.isEmpty()) continue;
                    NBTTagCompound it = new NBTTagCompound();
                    it.setInteger("slot", slot);
                    s.writeToNBT(it);
                    items.appendTag(it);
                }
                entry.setTag("items", items);
                hatchList.appendTag(entry);
            }
        }
        nbt.setTag("savedHatchInv", hatchList);

        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        // ---- saved hatch inventories ----
        savedHatchInv.clear(); 

        NBTTagList hatchList = nbt.getTagList("savedHatchInv", 10);
        for (int i = 0; i < hatchList.tagCount(); i++) {
            NBTTagCompound entry = hatchList.getCompoundTagAt(i);
            long posKey = entry.getLong("pos");
            NBTTagList items = entry.getTagList("items", 10);

            int maxSlot = -1;
            for (int j = 0; j < items.tagCount(); j++) {
                int slot = items.getCompoundTagAt(j).getInteger("slot");
                if (slot > maxSlot) maxSlot = slot;
            }
            NonNullList<ItemStack> arr = NonNullList.withSize(Math.max(maxSlot + 1, 1), ItemStack.EMPTY);

            for (int j = 0; j < items.tagCount(); j++) {
                NBTTagCompound it = items.getCompoundTagAt(j);
                int slot = it.getInteger("slot");
                arr.set(slot, new ItemStack(it));
            }
            savedHatchInv.put(posKey, arr);
        }
    }

    // Push the pre-formation snapshot into the placeholders' replaced inventories (after formation)
    private void writeSnapshotIntoPlaceholders() {
        if (world == null || savedHatchInv.isEmpty()) return;

        final Object[][][] struct = getStructure();
        if (struct == null) return;

        final Vector3F<Integer> off = getControllerOffset(struct);
        final EnumFacing front = getFrontDirection(world.getBlockState(pos));

        for (int y = 0; y < struct.length; y++) {
            for (int z = 0; z < struct[0].length; z++) {
                for (int x = 0; x < struct[0][0].length; x++) {
                    if (struct[y][z][x] == null) continue;

                    int gx = pos.getX() + (x - off.x) * front.getFrontOffsetZ() - (z - off.z) * front.getFrontOffsetX();
                    int gy = pos.getY() - y + off.y;
                    int gz = pos.getZ() - (x - off.x) * front.getFrontOffsetX() - (z - off.z) * front.getFrontOffsetZ();
                    BlockPos bp = new BlockPos(gx, gy, gz);

                    TileEntity te = world.getTileEntity(bp);
                    if (!(te instanceof TilePlaceholder)) continue;

                    NonNullList<ItemStack> snapshot = savedHatchInv.get(bp.toLong());
                    if (snapshot == null || snapshot.isEmpty()) continue;

                    TileEntity rep = ((TilePlaceholder) te).getReplacedTileEntity();
                    if (!(rep instanceof IInventory)) continue;

                    IInventory inv = (IInventory) rep;

                    // First try to restore to original slots
                    for (int i = 0; i < snapshot.size(); i++) {
                        ItemStack src = snapshot.get(i);
                        if (src.isEmpty()) continue;
                        ItemStack cur = (i < inv.getSizeInventory()) ? inv.getStackInSlot(i) : ItemStack.EMPTY;
                        if (i < inv.getSizeInventory() && cur.isEmpty()) {
                            inv.setInventorySlotContents(i, src.copy());
                            snapshot.set(i, ItemStack.EMPTY);
                        }
                    }
                    // Then merge leftovers
                    for (int i = 0; i < snapshot.size(); i++) {
                        ItemStack left = snapshot.get(i);
                        if (left.isEmpty()) continue;

                        ItemStack rem = left.copy();
                        // merge into existing stacks
                        for (int slot = 0; slot < inv.getSizeInventory() && !rem.isEmpty(); slot++) {
                            ItemStack dst = inv.getStackInSlot(slot);
                            if (dst.isEmpty()) continue;
                            if (ItemStack.areItemsEqual(dst, rem) && ItemStack.areItemStackTagsEqual(dst, rem)) {
                                int can = Math.min(inv.getInventoryStackLimit(), dst.getMaxStackSize()) - dst.getCount();
                                if (can > 0) {
                                    int move = Math.min(can, rem.getCount());
                                    dst.grow(move);
                                    rem.shrink(move);
                                    inv.setInventorySlotContents(slot, dst);
                                }
                            }
                        }
                        // fill empties
                        for (int slot = 0; slot < inv.getSizeInventory() && !rem.isEmpty(); slot++) {
                            if (inv.getStackInSlot(slot).isEmpty()) {
                                int put = Math.min(inv.getInventoryStackLimit(), rem.getMaxStackSize());
                                ItemStack putStack = rem.splitStack(put);
                                inv.setInventorySlotContents(slot, putStack);
                            }
                        }
                        // any remainder stays in snapshot (shouldn’t normally happen)
                        snapshot.set(i, rem.isEmpty() ? ItemStack.EMPTY : rem);
                    }
                    inv.markDirty();
                }
            }
        }

        // After pushing into placeholders, discard snapshot
        savedHatchInv.clear();
    }

    // Pull current contents back out of placeholders' replaced inventories (before teardown)
    private void snapshotFromPlaceholders() {
        savedHatchInv.clear();
        if (world == null) return;

        final Object[][][] struct = getStructure();
        if (struct == null) return;

        final Vector3F<Integer> off = getControllerOffset(struct);
        final EnumFacing front = getFrontDirection(world.getBlockState(pos));

        for (int y = 0; y < struct.length; y++) {
            for (int z = 0; z < struct[0].length; z++) {
                for (int x = 0; x < struct[0][0].length; x++) {
                    if (struct[y][z][x] == null) continue;

                    int gx = pos.getX() + (x - off.x) * front.getFrontOffsetZ() - (z - off.z) * front.getFrontOffsetX();
                    int gy = pos.getY() - y + off.y;
                    int gz = pos.getZ() - (x - off.x) * front.getFrontOffsetX() - (z - off.z) * front.getFrontOffsetZ();
                    BlockPos bp = new BlockPos(gx, gy, gz);

                    TileEntity te = world.getTileEntity(bp);

                    // Prefer the underlying hatch if this position is a placeholder
                    IInventory inv = null;
                    if (te instanceof TilePlaceholder) {
                        TileEntity rep = ((TilePlaceholder) te).getReplacedTileEntity();
                        if (rep instanceof TileInventoryHatch) inv = (IInventory) rep;
                    } else if (te instanceof TileInventoryHatch) {
                        // Real multiblock component hatch (hidden block), still a live TE
                        inv = (IInventory) te;
                    }

                    if (inv != null) {
                        NonNullList<ItemStack> copy = NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
                        boolean any = false;
                        for (int i = 0; i < inv.getSizeInventory(); i++) {
                            ItemStack s = inv.getStackInSlot(i);
                            if (!s.isEmpty()) {
                                copy.set(i, s.copy());
                                any = true;
                            }
                        }
                        if (any) savedHatchInv.put(bp.toLong(), copy);
                    }
                }
            }
        }
    }




    private void snapshotHatchInventories() {
        savedHatchInv.clear();
        final Object[][][] struct = getStructure();
        if (struct == null || world == null) return;

        final Vector3F<Integer> off = getControllerOffset(struct);
        final EnumFacing front = getFrontDirection(world.getBlockState(pos));

        for (int y = 0; y < struct.length; y++) {
            for (int z = 0; z < struct[0].length; z++) {
                for (int x = 0; x < struct[0][0].length; x++) {
                    if (struct[y][z][x] == null) continue;

                    int gx = pos.getX() + (x - off.x) * front.getFrontOffsetZ() - (z - off.z) * front.getFrontOffsetX();
                    int gy = pos.getY() - y + off.y;
                    int gz = pos.getZ() - (x - off.x) * front.getFrontOffsetX() - (z - off.z) * front.getFrontOffsetZ();
                    BlockPos bp = new BlockPos(gx, gy, gz);

                    if (!world.getChunkFromBlockCoords(bp).isLoaded()) continue;

                    TileEntity te = world.getTileEntity(bp);

                    // If already replaced, pull from the placeholder’s replaced tile
                    if (te instanceof TilePlaceholder) te = ((TilePlaceholder) te).getReplacedTileEntity();

                    if (te instanceof IInventory) {
                        IInventory inv = (IInventory) te;
                        NonNullList<ItemStack> copy = NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
                        boolean any = false;
                        for (int i = 0; i < inv.getSizeInventory(); i++) {
                            ItemStack s = inv.getStackInSlot(i);
                            if (!s.isEmpty()) {
                                copy.set(i, s.copy());
                                any = true;
                            }
                        }
                        if (any) savedHatchInv.put(bp.toLong(), copy);
                    }
                }
            }
        }
    }

    private void restoreHatchInventories() {
        if (world == null || savedHatchInv.isEmpty()) return;

        for (Map.Entry<Long, NonNullList<ItemStack>> e : savedHatchInv.entrySet()) {
            BlockPos bp = BlockPos.fromLong(e.getKey());
            TileEntity te = world.getTileEntity(bp);

            // If placeholder is still present for any reason, restore into the underlying replaced tile
            if (te instanceof TilePlaceholder) te = ((TilePlaceholder) te).getReplacedTileEntity();

            if (te instanceof IInventory) {
                IInventory inv = (IInventory) te;
                NonNullList<ItemStack> items = e.getValue();

                // naive merge: try to put stacks back in their original slots first, then merge to any slot
                // 1) original slots
                for (int i = 0; i < items.size(); i++) {
                    ItemStack src = items.get(i);
                    if (src.isEmpty()) continue;
                    ItemStack cur = inv.getStackInSlot(i);
                    if (cur.isEmpty()) {
                        inv.setInventorySlotContents(i, src.copy());
                        items.set(i, ItemStack.EMPTY);
                    }
                }
                // 2) merge leftovers anywhere they fit, otherwise drop
                for (int i = 0; i < items.size(); i++) {
                    ItemStack left = items.get(i);
                    if (left.isEmpty()) continue;

                    ItemStack rem = left.copy();
                    // try merging into existing stacks
                    for (int slot = 0; slot < inv.getSizeInventory() && !rem.isEmpty(); slot++) {
                        ItemStack dst = inv.getStackInSlot(slot);
                        if (dst.isEmpty()) continue;
                        if (ItemStack.areItemsEqual(dst, rem) && ItemStack.areItemStackTagsEqual(dst, rem)) {
                            int can = Math.min(inv.getInventoryStackLimit(), dst.getMaxStackSize()) - dst.getCount();
                            if (can > 0) {
                                int move = Math.min(can, rem.getCount());
                                dst.grow(move);
                                rem.shrink(move);
                                inv.setInventorySlotContents(slot, dst);
                            }
                        }
                    }
                    // fill empty slots
                    for (int slot = 0; slot < inv.getSizeInventory() && !rem.isEmpty(); slot++) {
                        if (inv.getStackInSlot(slot).isEmpty()) {
                            int put = Math.min(inv.getInventoryStackLimit(), rem.getMaxStackSize());
                            ItemStack putStack = rem.splitStack(put);
                            inv.setInventorySlotContents(slot, putStack);
                        }
                    }
                    // drop remainder to world
                    if (!rem.isEmpty()) {
                        world.spawnEntity(new EntityItem(world, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, rem));
                    }
                    items.set(i, ItemStack.EMPTY);
                }
                inv.markDirty();
            } else {
                // no inventory to restore into -> drop all
                for (ItemStack s : e.getValue()) {
                    if (!s.isEmpty()) {
                        world.spawnEntity(new EntityItem(world, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, s.copy()));
                    }
                }
            }
        }
        savedHatchInv.clear();
    }


}
