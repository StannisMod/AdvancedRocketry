package zmaster587.advancedRocketry.tile.multiblock;

import java.util.HashSet;
import java.util.Set;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.network.PacketDimInfo;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.inventory.modules.ModuleData;
import zmaster587.advancedRocketry.inventory.modules.ModuleItemSlotButton;
import zmaster587.advancedRocketry.item.IDataItem;
import zmaster587.advancedRocketry.item.ItemAsteroidChip;
import zmaster587.advancedRocketry.item.ItemMemoryCrystal;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.tile.hatch.TileDataBus;
import zmaster587.advancedRocketry.universe.RegionScan;
import zmaster587.advancedRocketry.universe.TelescopeScan;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.advancedRocketry.util.Asteroid;
import zmaster587.advancedRocketry.util.Asteroid.StackEntry;
import zmaster587.advancedRocketry.util.IDataInventory;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.api.LibVulpesBlocks;
import zmaster587.libVulpes.block.BlockMeta;
import zmaster587.libVulpes.block.multiblock.BlockMultiblockMachine;
import zmaster587.libVulpes.client.util.ProgressBarImage;
import zmaster587.libVulpes.inventory.GuiHandler;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.multiblock.TileMultiBlock;
import zmaster587.libVulpes.tile.multiblock.TileMultiPowerConsumer;
import zmaster587.libVulpes.tile.multiblock.TilePlaceholder;
import zmaster587.libVulpes.util.EmbeddedInventory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Map;

public class TileObservatory extends TileMultiPowerConsumer implements IModularInventory, IDataInventory, IGuiCallback {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("AdvancedRocketry|Observatory");

    private final java.util.Map<Long, NBTTagCompound> savedDataBusNbt = new java.util.HashMap<>();
    final static int openTime = 100;
    final static int observationTime = 1000;
    private static final Block[] lens = {AdvancedRocketryBlocks.blockLens, Blocks.GLASS};
    private static final Object[][][] structure = new Object[][][]{
    
            {{null, null, null, null, null},
                    {null, LibVulpesBlocks.blockStructureBlock, lens, LibVulpesBlocks.blockStructureBlock, null},
                    {null, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, null},
                    {null, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, null},
                    {null, null, null, null, null}},

            {{null, null, null, null, null},
                    {null, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, null},
                    {null, LibVulpesBlocks.blockStructureBlock, lens, LibVulpesBlocks.blockStructureBlock, null},
                    {null, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, null},
                    {null, null, null, null, null}},

            {{null, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, null},
                    {LibVulpesBlocks.blockStructureBlock, Blocks.AIR, Blocks.AIR, Blocks.AIR, LibVulpesBlocks.blockStructureBlock},
                    {LibVulpesBlocks.blockStructureBlock, Blocks.AIR, Blocks.AIR, Blocks.AIR, LibVulpesBlocks.blockStructureBlock},
                    {LibVulpesBlocks.blockStructureBlock, Blocks.AIR, lens, Blocks.AIR, LibVulpesBlocks.blockStructureBlock},
                    {null, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, null}},

            {{null, '*', 'c', '*', null},
                    {'*', LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, '*'},
                    {'*', LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, '*'},
                    {'*', LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, '*'},
                    {null, '*', '*', '*', null}},

            {{null, '*', '*', '*', null},
                    {'*', AdvancedRocketryBlocks.blockStructureTower, AdvancedRocketryBlocks.blockStructureTower, AdvancedRocketryBlocks.blockStructureTower, '*'},
                    {'*', AdvancedRocketryBlocks.blockStructureTower, LibVulpesBlocks.motors, AdvancedRocketryBlocks.blockStructureTower, '*'},
                    {'*', AdvancedRocketryBlocks.blockStructureTower, AdvancedRocketryBlocks.blockStructureTower, AdvancedRocketryBlocks.blockStructureTower, '*'},
                    {null, '*', '*', '*', null}}};
    private static final byte TAB_SWITCH = 10;
    private static final byte BUTTON_PRESS = 11;
    private static final short LIST_OFFSET = 100;
    private static final byte PROCESS_CHIP = 12;
    private static final byte SEED_CHANGE = 13;
    private static final byte SYNC_PRINTED = 14;
    private static final byte REQUEST_REOPEN = 15;
    private static final byte SYNC_SEED = 16;
    private static final byte PICK_DIRECTION = 17;
    private static final byte PICK_DISTANCE = 18;
    private static final byte START_SCAN = 19;
    private static final byte ABORT_SCAN = 20;
    private static final byte PASSIVE_SWEEP = 21;
    private static final byte TOGGLE_WHOLE_SYSTEM = 22;
    private static final byte UPLOAD_CRYSTAL = 23;
    /** Progress id of the region-scan bar; the machine's own bar keeps id 0. */
    private static final int PROGRESS_SCAN = 1;
    /**
     * The directions a region scan can be aimed, in the order the pick button cycles them. Six axes
     * rather than a free vector: a telescope is pointed at a patch of sky, and a patch is what the
     * sector box already is.
     */
    private static final int[][] SCAN_DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
    private static final String[] SCAN_DIRECTION_NAMES = {"+X", "-X", "+Z", "-Z", "+Y", "-Y"};
    private final int dataConsumedPerRefresh = 100; // Distance data consumed per scan
    private boolean pendingReopenAfterSeedSync = false;
    // Dont allow duplicate chipwrites for the same seed + button
    private java.util.HashSet<Integer> printedButtonsThisSeed = new java.util.HashSet<>();
    private long printedSetSeed = -1; // track which seed the set belongs to
    /** The slot a memory crystal goes in, so a region scan has somewhere to write what it resolves. */
    public static final int SLOT_CRYSTAL = 5;
    int openProgress;
    EmbeddedInventory inv = new EmbeddedInventory(6);
    private int viewDistance;
    private int lastButton;
    private long lastSeed;
    private String lastType;
    private LinkedList<TileDataBus> dataCables;
    private HashMap<Integer, String> buttonType = new HashMap<>();
    private boolean isOpen;
    private ModuleTab tabModule;
    /** The observation in flight, or {@code null} when the instrument is idle. */
    private RegionScan activeScan;
    /** How many addresses the last finished scan wrote — what the operator gets told he learned. */
    private int lastScanDiscoveries;
    /**
     * How many of the last scan's looks a cloud stood in the way of. The crystal still gained their
     * coordinates; what it did NOT gain is what is at them, and the operator is told which.
     */
    private int lastScanObscured;
    /** Which way the operator has the instrument pointed, as an index into {@link #SCAN_DIRECTIONS}. */
    private int scanDirection;
    /** How far out, in STEPS, he has it aimed. Clamped to the configured reach when it is used. */
    private int scanDistance = 1;
    /**
     * What one step of aim is worth in light years — how the operator's pick is turned into a length
     * he can recognise. Derived from the SERVER's galaxy generator and synced, never computed on the
     * client: a client attached to a pack whose star spacing it does not hold would quote its own.
     */
    private double stepLightYears;
    /** Client-side only: which way the distance button just pressed wants to move the aim. */
    private int pendingDistanceDelta;
    /** Watching the neighbourhood rather than a distant patch. The two modes are exclusive. */
    private boolean passive;
    /**
     * Whether a detection is followed all the way to the system's BODIES, or only its address is
     * written down.
     *
     * <p>An operational choice with a cost, so it lives on the instrument rather than in the
     * configuration: characterising every find fills a crystal many times faster and is what an
     * operator wants over known sky, while a deep pointing into sky nobody has been to is a list of
     * places worth flying to. Default on, because that is what the survey did before it could tell
     * the two questions apart.</p>
     */
    private boolean characteriseWholeSystem = true;

    public TileObservatory() {
        openProgress = 0;
        viewDistance = 0;
        lastButton = -1;
        lastSeed = -1;
        completionTime = observationTime;
        dataCables = new LinkedList<>();
        tabModule = new ModuleTab(4, 0, 0, this, 3, new String[]{
                LibVulpes.proxy.getLocalizedString("msg.tooltip.data"),
                LibVulpes.proxy.getLocalizedString("msg.tooltip.asteroidselection"),
                LibVulpes.proxy.getLocalizedString("msg.tooltip.regionscan")},
                new ResourceLocation[][]{TextureResources.tabData, TextureResources.tabAsteroid,
                        TextureResources.tabPlanet});
    }

    public float getOpenProgress() {
        return openProgress / (float) openTime;
    }

    private void snapshotDataBusesBeforeTeardown() {
        savedDataBusNbt.clear();

        final Object[][][] struct = getStructure();
        if (struct == null || world == null) return;

        final zmaster587.libVulpes.util.Vector3F<Integer> off = getControllerOffset(struct);
        final EnumFacing front = getFrontDirection(world.getBlockState(pos));

        for (int y = 0; y < struct.length; y++) {
            for (int z = 0; z < struct[0].length; z++) {
                for (int x = 0; x < struct[0][0].length; x++) {
                    if (struct[y][z][x] == null) continue;

                    int gx = pos.getX() + (x - off.x) * front.getFrontOffsetZ()
                                    - (z - off.z) * front.getFrontOffsetX();
                    int gy = pos.getY() - y + off.y;
                    int gz = pos.getZ() - (x - off.x) * front.getFrontOffsetX()
                                    - (z - off.z) * front.getFrontOffsetZ();
                    BlockPos bp = new BlockPos(gx, gy, gz);

                    TileEntity te = world.getTileEntity(bp);
                    if (te instanceof zmaster587.libVulpes.tile.multiblock.TilePlaceholder) {
                        te = ((zmaster587.libVulpes.tile.multiblock.TilePlaceholder) te).getReplacedTileEntity();
                    }

                    if (te instanceof zmaster587.advancedRocketry.tile.hatch.TileDataBus) {
                        NBTTagCompound tag = new NBTTagCompound();
                        te.writeToNBT(tag);
                        savedDataBusNbt.put(bp.toLong(), tag);
                    }
                }
            }
        }
    }

    private void restoreDataBusesAfterTeardown() {
        if (world == null || savedDataBusNbt.isEmpty()) return;

        try {
            for (Map.Entry<Long, NBTTagCompound> e : savedDataBusNbt.entrySet()) {
                BlockPos bp = BlockPos.fromLong(e.getKey());
                TileEntity te = world.getTileEntity(bp);

                if (te instanceof TilePlaceholder) {
                    te = ((TilePlaceholder) te).getReplacedTileEntity();
                }

                if (te instanceof TileDataBus) {
                    TileDataBus bus = (TileDataBus) te;
                    bus.readFromNBT(e.getValue());
                    bus.lockData(null);
                    bus.markDirty();
                    world.notifyBlockUpdate(bp, world.getBlockState(bp), world.getBlockState(bp), 3);
                }
            }
        } finally {
            savedDataBusNbt.clear();
        }
    }


    @Override
    protected void integrateTile(TileEntity tile) {
        super.integrateTile(tile);

        if (tile instanceof TileDataBus) {
            TileDataBus bus = (TileDataBus) tile;
            dataCables.add(bus);

            DataType type = bus.getDataObject().getDataType();

            // If bus already has a meaningful type, preserve it.
            if (type != null && type != DataType.UNDEFINED) {
                bus.lockData(type);
            } else {
                // Default untyped buses to DISTANCE
                bus.lockData(DataType.DISTANCE);
            }
        }
    }


    @Override
    public void deconstructMultiBlock(World worldIn, BlockPos destroyedPos,
                                    boolean blockBroken, IBlockState state) {

        if (!worldIn.isRemote) {
            snapshotDataBusesBeforeTeardown();
        }

        super.deconstructMultiBlock(worldIn, destroyedPos, blockBroken, state);

        if (!worldIn.isRemote) {
            restoreDataBusesAfterTeardown();
        }

        viewDistance = 0;
    }


    @Override
    protected void replaceStandardBlock(BlockPos newPos, IBlockState state,
                                        TileEntity tile) {

        Block block = state.getBlock();

        if (block == AdvancedRocketryBlocks.blockLens) {
            viewDistance += 5;
        } else if (block == LibVulpesBlocks.blockMotor) {
            viewDistance += 25;
        } else if (block == LibVulpesBlocks.blockAdvancedMotor) {
            viewDistance += 50;
        } else if (block == LibVulpesBlocks.blockEnhancedMotor) {
            viewDistance += 100;
        } else if (block == LibVulpesBlocks.blockEliteMotor) {
            viewDistance += 175;
        }

        super.replaceStandardBlock(newPos, state, tile);
    }

    @Override
    public void update() {

        //Freaky jenky crap to make sure the multiblock loads on chunkload etc
        if (timeAlive == 0) {
            attemptCompleteStructure(world.getBlockState(pos));
            timeAlive = 0x1;
        }

        if (!world.isRemote) {
            // Once per load: the stride is the installed generator's, and that is fixed for a world.
            if (stepLightYears <= 0d) {
                stepLightYears = zmaster587.advancedRocketry.universe.UniverseScale
                        .lightYearsForCells(RegionScan.Tuning.fromConfig().strideCells());
                markDirty();
            }
            completeRegionScanIfDue();
        }

        if ((world.isRemote && isOpen) || (!world.isRemote && isRunning() && getMachineEnabled() && ((!world.isRaining() && world.canBlockSeeSky(pos.add(0, 1, 0)) && !world.isDaytime()) || world.provider.getDimension() == ARConfiguration.getCurrentConfig().spaceDimId))) {

            if (!isOpen) {
                isOpen = true;

                markDirty();
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            }

            if (openProgress < openTime)
                openProgress++;
        } else if (openProgress > 0) {

            if (isOpen) {
                isOpen = false;

                markDirty();
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            }

            openProgress--;
        }
    }

    //Always running if enabled
    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    protected void processComplete() {
    }

    @Override
    public void resetCache() {
        super.resetCache();
        dataCables.clear();
    }

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    @Nonnull
    public AxisAlignedBB getRenderBoundingBox() {

        return new AxisAlignedBB(pos.add(-5, -3, -5), pos.add(5, 3, 5));
    }

    @Override
    public List<BlockMeta> getAllowableWildCardBlocks() {
        List<BlockMeta> list = super.getAllowableWildCardBlocks();

        list.add(new BlockMeta(Blocks.IRON_BLOCK, BlockMeta.WILDCARD));
        list.addAll(TileMultiBlock.getMapping('P'));
        list.addAll(TileMultiBlock.getMapping('D'));
        return list;
    }

    @Override
    protected void writeNetworkData(NBTTagCompound nbt) {
        super.writeNetworkData(nbt);
        nbt.setInteger("openProgress", openProgress);
        nbt.setBoolean("isOpen", isOpen);

        nbt.setInteger("viewableDist", viewDistance);
        nbt.setLong("lastSeed", lastSeed);
        nbt.setInteger("lastButton", lastButton);
        if (lastType != null && !lastType.isEmpty())
            nbt.setString("lastType", lastType);

        nbt.setLong("printedSetSeed", printedSetSeed);
        if (!printedButtonsThisSeed.isEmpty()) {
            int[] arr = printedButtonsThisSeed.stream().mapToInt(Integer::intValue).toArray();
            nbt.setIntArray("printedButtons", arr);
        }

        // The scan travels to the client whole, so the tab computes its bar and its text from the
        // same object and the same arithmetic the server uses — never from a second, drifting copy.
        if (activeScan != null) {
            NBTTagCompound scan = new NBTTagCompound();
            activeScan.writeToNBT(scan);
            nbt.setTag("regionScan", scan);
        }
        nbt.setInteger("lastScanDiscoveries", lastScanDiscoveries);
        nbt.setInteger("lastScanObscured", lastScanObscured);
        nbt.setInteger("scanDirection", scanDirection);
        nbt.setInteger("scanDistance", scanDistance);
        nbt.setDouble("scanStepLy", stepLightYears);
        nbt.setBoolean("scanPassive", passive);
        nbt.setBoolean("scanWholeSystem", characteriseWholeSystem);
    }

    @Override
    protected void readNetworkData(NBTTagCompound nbt) {
        long prevSeed = this.lastSeed;
        super.readNetworkData(nbt);
        openProgress = nbt.getInteger("openProgress");
        isOpen = nbt.getBoolean("isOpen");
        viewDistance = nbt.getInteger("viewableDist");
        lastSeed = nbt.getLong("lastSeed");
        lastButton = nbt.getInteger("lastButton");
        lastType = nbt.getString("lastType");

        printedSetSeed = nbt.getLong("printedSetSeed");
        printedButtonsThisSeed.clear();
        int[] arr = nbt.getIntArray("printedButtons");
        if (arr != null) for (int v : arr) printedButtonsThisSeed.add(v);
        activeScan = nbt.hasKey("regionScan") ? RegionScan.readFromNBT(nbt.getCompoundTag("regionScan")) : null;
        lastScanDiscoveries = nbt.getInteger("lastScanDiscoveries");
        lastScanObscured = nbt.getInteger("lastScanObscured");
        scanDirection = nbt.getInteger("scanDirection");
        scanDistance = Math.max(1, nbt.getInteger("scanDistance"));
        stepLightYears = nbt.getDouble("scanStepLy");
        passive = nbt.getBoolean("scanPassive");
        characteriseWholeSystem = !nbt.hasKey("scanWholeSystem") || nbt.getBoolean("scanWholeSystem");

        if (world != null && world.isRemote && prevSeed != lastSeed) {
            zmaster587.advancedRocketry.AdvancedRocketry.proxy.clearObservatoryScrollCache();
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        inv.writeToNBT(nbt);

        nbt.setLong("printedSetSeed", printedSetSeed);
        if (!printedButtonsThisSeed.isEmpty()) {
            int[] arr = printedButtonsThisSeed.stream().mapToInt(Integer::intValue).toArray();
            nbt.setIntArray("printedButtons", arr);
        }

        if (activeScan != null) {
            NBTTagCompound scan = new NBTTagCompound();
            activeScan.writeToNBT(scan);
            nbt.setTag("regionScan", scan);
        }
        nbt.setInteger("lastScanDiscoveries", lastScanDiscoveries);
        nbt.setInteger("lastScanObscured", lastScanObscured);
        nbt.setInteger("scanDirection", scanDirection);
        nbt.setInteger("scanDistance", scanDistance);
        nbt.setBoolean("scanPassive", passive);
        nbt.setBoolean("scanWholeSystem", characteriseWholeSystem);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        inv.readFromNBT(nbt);

        printedSetSeed = nbt.getLong("printedSetSeed");
        printedButtonsThisSeed.clear();
        int[] arr = nbt.getIntArray("printedButtons");
        if (arr != null) for (int v : arr) printedButtonsThisSeed.add(v);

        activeScan = nbt.hasKey("regionScan") ? RegionScan.readFromNBT(nbt.getCompoundTag("regionScan")) : null;
        lastScanDiscoveries = nbt.getInteger("lastScanDiscoveries");
        lastScanObscured = nbt.getInteger("lastScanObscured");
        scanDirection = nbt.getInteger("scanDirection");
        scanDistance = Math.max(1, nbt.getInteger("scanDistance"));
        passive = nbt.getBoolean("scanPassive");
        characteriseWholeSystem = !nbt.hasKey("scanWholeSystem") || nbt.getBoolean("scanWholeSystem");
    }


    public LinkedList<TileDataBus> getDataBus() {
        return dataCables;
    }

    private int getDataAmt(DataType type) {
        int data = 0;
        for (TileDataBus tile : getDataBus()) {
            if (tile.getDataObject().getDataType() == type)
                data += tile.getDataObject().getData();
        }
        return data;
    }

    @Override
    public boolean completeStructure(IBlockState state) {
        boolean result = super.completeStructure(state);
        ((BlockMultiblockMachine) world.getBlockState(pos).getBlock()).setBlockState(world, world.getBlockState(pos), pos, result);


        completionTime = observationTime;
        return result;
    }

    @Override
    public String getMachineName() {
        return AdvancedRocketryBlocks.blockObservatory.getLocalizedName();
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();

        modules.add(tabModule);

        if (tabModule.getTab() == 1) {

            //ADD io slots
            modules.add(new ModuleTexturedSlotArray(5, 120, this, 1, 2, TextureResources.idChip));
            modules.add(new ModuleOutputSlotArray(45, 120, this, 2, 3));

            ModuleButton scanButton = new ModuleButton(100, 120, 2, LibVulpes.proxy.getLocalizedString("msg.observetory.scan.button"), this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild, LibVulpes.proxy.getLocalizedString("msg.observetory.scan.tooltip"), 64, 18);
            scanButton.setColor(extractData(dataConsumedPerRefresh, DataType.DISTANCE, EnumFacing.DOWN, false) == dataConsumedPerRefresh ? 0x00ff00 : 0xff0000);
            modules.add(scanButton);

            modules.add(new ModuleProgress(25, 120, 0, new ProgressBarImage(217, 0, 17, 17, 234, 0, EnumFacing.DOWN, TextureResources.progressBars), this));
            ModuleButton processBtn = new ModuleButton(
                25, 120, 1, "",
                this,
                zmaster587.libVulpes.inventory.TextureResources.buttonNull,
                LibVulpes.proxy.getLocalizedString("msg.observetory.text.processdiscovery"),
                17, 17
            );

            boolean alreadyPrinted = (lastButton != -1) && printedButtonsThisSeed.contains(lastButton);

            if (!isOpen) {
                processBtn.setToolTipText(LibVulpes.proxy.getLocalizedString("msg.observetory.req.open"));
            } else if (alreadyPrinted) {
                processBtn.setToolTipText(LibVulpes.proxy.getLocalizedString("msg.observetory.print.already"));
            } else {
                processBtn.setToolTipText(LibVulpes.proxy.getLocalizedString("msg.observetory.text.processdiscovery"));
            }

            modules.add(processBtn);

            List<ModuleBase> list2 = new LinkedList<>();
            List<ModuleBase> buttonList = new LinkedList<>();
            buttonType.clear();

            int g = 0;
            Asteroid asteroidSmol;
            if (lastButton != -1 && lastType != null && !lastType.isEmpty() && (asteroidSmol = ARConfiguration.getCurrentConfig().asteroidTypes.get(lastType)) != null) {
                List<StackEntry> harvestList = asteroidSmol.getHarvest(lastSeed + lastButton, Math.max(1 - ((Math.min(getDataAmt(DataType.COMPOSITION), 2000) + Math.min(getDataAmt(DataType.MASS), 2000)) / 4000f), 0));
                for (StackEntry entry : harvestList) {
                    ItemStack s = entry.stack;
                    String tip = entry.midpoint + " +/-  " + entry.variablility;

                    int sx = (g % 2) * 24 + 1;
                    int sy = 24 * (g / 2) + 1;

                    // If stack is empty, still show a slot button (optional), but don't crash.
                    if (!s.isEmpty() && Block.getBlockFromItem(s.getItem()) != Blocks.AIR) {
                        buttonList.add(new ModuleSlotButton(sx, sy, -2, this, s, tip, getWorld()));
                    } else {
                        buttonList.add(new ModuleItemSlotButton(sx, sy, -2, this, s, tip));
                    }

                    buttonList.add(new ModuleText(sx, sy,
                            entry.midpoint + "\n+/- " + entry.variablility,
                            0xFFFFFF, 0.5f));

                    g++;
                }

                float time = asteroidSmol.timeMultiplier;

                String timeLabel = LibVulpes.proxy.getLocalizedString("msg.observetory.text.time");
                buttonList.add(new ModuleText(
                        0,
                        24 * (1 + (g / 2)),
                        String.format("%s\n%.2fx", timeLabel, time),
                        0x2f2f2f
                ));
            }

            //Calculate Types
            int totalAmountAllowed = 10;
            float totalWeight = 0;

            List<String> keys = new ArrayList<>(ARConfiguration.getCurrentConfig().asteroidTypes.keySet());
            Collections.sort(keys);

            List<Asteroid> viableTypes = new LinkedList<>();
            for (String str : keys) {
                Asteroid asteroid = ARConfiguration.getCurrentConfig().asteroidTypes.get(str);
                if (asteroid != null && asteroid.distance <= getMaxDistance()) {
                    totalWeight += asteroid.getProbability();
                    viableTypes.add(asteroid);
                }
            }

            //Yeah, eww
            List<Asteroid> finalList = new LinkedList<>();
            Random rand = new Random(lastSeed);
            for (Asteroid asteroid : viableTypes) {
                for (int i = 0; i < totalAmountAllowed; i++) {
                    if (asteroid.getProbability() / totalWeight >= rand.nextFloat())
                        finalList.add(asteroid);
                }
            }

            for (int i = 0; i < finalList.size(); i++) {
                Asteroid asteroid = finalList.get(i);

                ModuleButton button = new ModuleButton(0, i * 18, LIST_OFFSET + i, asteroid.getName(), this, TextureResources.buttonAsteroid, 112, 18);

                if (lastButton - LIST_OFFSET == i) {
                    button.setColor(0xFFFF00);
                }

                list2.add(button);
                buttonType.put(i, asteroid.getName());
            }

            modules.add(new ModuleText(10, 18, LibVulpes.proxy.getLocalizedString("msg.observetory.text.asteroids"), 0x2d2d2d));
            modules.add(new ModuleText(105, 18, LibVulpes.proxy.getLocalizedString("msg.observetory.text.composition"), 0x2d2d2d));

            //Ore display
            int baseX = 122;
            int baseY = 32;
            int sizeX = 52;
            int sizeY = 46;
            if (world.isRemote) {
                // Border for RIGHT composition pane (unchanged)
                modules.add(new ModuleScaledImage(baseX - 3, baseY - 3, 3, baseY + sizeY + 6, TextureResources.verticalBar));
                modules.add(new ModuleScaledImage(baseX + sizeX, baseY - 3, -3, baseY + sizeY + 6, TextureResources.verticalBar));
                modules.add(new ModuleScaledImage(baseX, baseY - 3, sizeX, 3, TextureResources.horizontalBar));
                modules.add(new ModuleScaledImage(baseX, 2 * baseY + sizeY, sizeX, -3, TextureResources.horizontalBar));
            }

            // Preserve RIGHT pane coords before reusing baseX/baseY for the LEFT pane
            final int compX = baseX;
            final int compY = baseY;
            final int compScreenX = 40;  // same as original
            final int compScreenY = 48;  // same as original

            // ---- LEFT pane (asteroid list) border
            baseX = 5;
            baseY = 32;
            sizeX = 112;
            sizeY = 46;
            if (world.isRemote) {
                // Border for LEFT asteroid list
                modules.add(new ModuleScaledImage(baseX - 3, baseY - 3, 3, baseY + sizeY + 6, TextureResources.verticalBar));
                modules.add(new ModuleScaledImage(baseX + sizeX, baseY - 3, -3, baseY + sizeY + 6, TextureResources.verticalBar));
                modules.add(new ModuleScaledImage(baseX, baseY - 3, sizeX, 3, TextureResources.horizontalBar));
                modules.add(new ModuleScaledImage(baseX, 2 * baseY + sizeY, sizeX, -3, TextureResources.horizontalBar));
            }

            // ---- LEFT asteroid list: wheel-enabled + cached
            if (lastSeed != -1) {
                modules.add(zmaster587.advancedRocketry.AdvancedRocketry.proxy
                    .createObservatoryAsteroidListPan(baseX, baseY, list2, sizeX, sizeY));
            }



            // ---- RIGHT composition pane: parent class (drag-only; wheel will be 0 after left consumes it)
            ModuleContainerPanYOnly panRight = new ModuleContainerPanYOnly(
                compX, compY,
                buttonList, new LinkedList<>(),
                null,
                compScreenX, compScreenY,
                0, 0,
                0, 72
            );
            modules.add(panRight);


        } else if (tabModule.getTab() == 2) {
            // The region scan: where to point, how far out, the crystal it writes to, and how far
            // along the current look is. Everything shown here is read from the tile's own state, so
            // the client draws what the server actually has rather than a local guess.
            modules.add(new ModuleSlotArray(8, 18, this, SLOT_CRYSTAL, SLOT_CRYSTAL + 1));
            modules.add(new ModuleText(30, 22,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.crystal"), 0x2d2d2d, false));

            modules.add(new ModuleText(8, 46,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.direction")
                            + " " + SCAN_DIRECTION_NAMES[scanDirectionIndex()], 0x2d2d2d, false));
            modules.add(new ModuleButton(120, 42, 3, SCAN_DIRECTION_NAMES[scanDirectionIndex()], this,
                    zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.direction.tooltip"), 40, 18));

            modules.add(new ModuleText(8, 70,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.distance")
                            + " " + scanDistance + aimInLightYears(), 0x2d2d2d, false));
            modules.add(new ModuleButton(100, 66, 4, "-", this,
                    zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.distance.tooltip"), 18, 18));
            modules.add(new ModuleButton(142, 66, 5, "+", this,
                    zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.distance.tooltip"), 18, 18));

            modules.add(new ModuleProgress(8, 94, PROGRESS_SCAN, new ProgressBarImage(217, 0, 17, 17,
                    234, 0, EnumFacing.DOWN, TextureResources.progressBars), this));
            ModuleButton scanRegion = new ModuleButton(100, 94, 6,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.region"), this,
                    zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.region.tooltip"), 64, 18);
            scanRegion.setColor(activeScan == null ? 0x00ff00 : 0xffff00);
            modules.add(scanRegion);

            if (activeScan != null) {
                modules.add(new ModuleButton(166, 94, 7,
                        LibVulpes.proxy.getLocalizedString("msg.observetory.scan.abort"), this,
                        zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                        LibVulpes.proxy.getLocalizedString("msg.observetory.scan.abort.tooltip"), 40, 18));
            }
            // Reading a crystal INTO this world, the reverse of the survey that fills one. It costs
            // no power and no data: the knowledge already exists, it is being put down here.
            modules.add(new ModuleButton(100, 142, 10,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.upload.button"), this,
                    zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.upload.tooltip"), 64, 18));
            modules.add(new ModuleButton(166, 42, 8,
                    LibVulpes.proxy.getLocalizedString(passive
                            ? "msg.observetory.scan.mode.passive" : "msg.observetory.scan.mode.active"),
                    this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.mode.tooltip"), 40, 18));

            // What a detection is followed up with. An operational choice with a cost, so it is a
            // control on the instrument and not a setting in a file: over known sky an operator wants
            // every body named, and into sky nobody has visited he wants a list of places to fly to.
            modules.add(new ModuleButton(166, 66, 9,
                    LibVulpes.proxy.getLocalizedString(characteriseWholeSystem
                            ? "msg.observetory.scan.detail.full" : "msg.observetory.scan.detail.coords"),
                    this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.detail.tooltip"), 40, 18));

            modules.add(new ModuleText(8, 116, scanStatusText(), 0x2d2d2d, false));
            modules.add(new ModuleText(8, 128,
                    LibVulpes.proxy.getLocalizedString("msg.observetory.scan.keepcrystal"),
                    0x8d2d2d, false));

        } else if (tabModule.getTab() == 0) {
            modules.add(new ModulePower(18, 20, getBatteries()));
            modules.add(toggleSwitch = new ModuleToggleSwitch(160, 5, 0, "", this, zmaster587.libVulpes.inventory.TextureResources.buttonToggleImage, 11, 26, getMachineEnabled()));

            List<DataStorage> distanceStorage = new LinkedList<>();
            List<DataStorage> compositionStorage = new LinkedList<>();
            List<DataStorage> massStorage = new LinkedList<>();
            for (TileDataBus dataCable : dataCables) {

                DataStorage storage = dataCable.getDataObject();
                DataType type = dataCable.getDataObject().getDataType();
                if (type == DataType.COMPOSITION)
                    compositionStorage.add(storage);
                else if (type == DataType.DISTANCE)
                    distanceStorage.add(storage);
                else if (type == DataType.MASS)
                    massStorage.add(storage);
            }

            if (distanceStorage.size() > 0) {
                modules.add(new ModuleData(40, 20, 0, this, distanceStorage.toArray(new DataStorage[0])));
            }

            if (compositionStorage.size() > 0) {
                modules.add(new ModuleData(80, 20, 3, this, compositionStorage.toArray(new DataStorage[0])));
            }

            if (massStorage.size() > 0) {
                modules.add(new ModuleData(120, 20, 4, this, massStorage.toArray(new DataStorage[0])));
            }

            modules.add(new ModuleText(10, 90, LibVulpes.proxy.getLocalizedString("msg.observetory.text.observabledistance") + " " + getMaxDistance(), 0x2d2d2d, false));
        }

		/*DataStorage data[] = new DataStorage[dataCables.size()];

		if(data.length > 0)
			modules.add(new ModuleData(40, 20, 0, this, data));*/
        //modules.add(new ModuleProgress(120, 30, 0, TextureResources.progressScience, this));

        return modules;
    }

    public int getMaxDistance() {
        return viewDistance + 10;
    }

    /**
     * Aim the instrument at a region and start looking. Server side; one observation at a time.
     *
     * <p>The distance is in STEPS — one step is one star's territory — and is clamped to the
     * configured reach rather than refused: asking to see farther than the instrument can gets you
     * the instrument's reach.</p>
     *
     * @return {@code false} when the machine is already looking somewhere, or when it does not know
     *         where it is standing and so has nothing to aim FROM
     */
    public boolean beginRegionScan(int dirX, int dirY, int dirZ, int distanceSteps) {
        if (world == null || world.isRemote) {
            return false;
        }
        GalacticCoord origin = scanOrigin();
        if (origin == null) {
            return false;
        }
        // Re-aiming mid-sweep is allowed and costs only the cell in flight: every cell already
        // resolved is already written to the crystal, so there is nothing else to lose.
        RegionScan aimed = buildScan(() -> RegionScan.directed(origin, dirX, dirY, dirZ, distanceSteps,
                world.getTotalWorldTime(), RegionScan.Tuning.fromConfig()));
        if (aimed == null) {
            return false;
        }
        activeScan = aimed;
        passive = false;
        lastScanDiscoveries = 0;
        lastScanObscured = 0;
        markDirty();
        return true;
    }

    /**
     * Build a survey, or refuse to start one — a configuration that describes a region no survey can
     * walk is reported and declined, never started half-way.
     *
     * <p>{@code RegionScan} refuses such a region rather than clamping its look count, because a
     * clamped count reports the sweep complete with most of the region never visited. Here that
     * refusal has to become an operator-visible "the machine did not start" plus a line in the log
     * naming the setting, since the alternative is a tile that throws out of a GUI action.</p>
     */
    private RegionScan buildScan(java.util.function.Supplier<RegionScan> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException refused) {
            LOGGER.error("the observatory at " + pos + " cannot start a survey: "
                    + refused.getMessage() + " Check the telescopeScan* settings.");
            return null;
        }
    }

    /** Stop looking. Free — an aim the operator regrets must not have to be waited out. */
    public boolean abortRegionScan() {
        if (world == null || world.isRemote || activeScan == null) {
            return false;
        }
        activeScan = null;
        markDirty();
        return true;
    }

    /**
     * Point the instrument at its own neighbourhood instead of at a distant patch — a local radar
     * with its data ready, growing outward from the cell the observatory stands in.
     *
     * <p>Passive and active are one mode at a time: an observatory staring into deep space genuinely
     * cannot watch what is close, and a second set of scanners is the expensive cure.</p>
     */
    public boolean beginPassiveSweep() {
        if (world == null || world.isRemote) {
            return false;
        }
        GalacticCoord origin = scanOrigin();
        if (origin == null) {
            return false;
        }
        int radius = Math.max(0, ARConfiguration.getCurrentConfig().telescopePassiveRadiusSteps);
        RegionScan sweep = buildScan(() -> RegionScan.local(origin, radius,
                world.getTotalWorldTime(), RegionScan.Tuning.fromConfig()));
        if (sweep == null) {
            return false;
        }
        activeScan = sweep;
        passive = true;
        lastScanDiscoveries = 0;
        lastScanObscured = 0;
        markDirty();
        return true;
    }

    /**
     * Teach the world this observatory stands on what the survey just made out.
     *
     * <p>Server side only, and only what has a dimension. The set is a body's own, additive over the
     * global known-set rather than a replacement for it, so a pack that authored its known planets
     * keeps them and a world merely adds what it has learned since. Syncing goes through the same
     * channel a beacon uses, because this is the same kind of fact.</p>
     */
    private void teachThisBody(Set<Integer> discovered) {
        if (discovered.isEmpty() || world == null || world.isRemote) {
            return;
        }
        DimensionProperties here = DimensionManager.getInstance()
                .getDimensionPropertiesOrNull(world.provider.getDimension());
        if (here == null) {
            return; // a world the planet layer does not own - nothing here can learn
        }
        boolean learned = false;
        for (int dimId : discovered) {
            if (!here.isPlanetKnownHere(dimId)) {
                here.discoverPlanet(dimId);
                learned = true;
            }
        }
        if (learned) {
            PacketHandler.sendToAll(new PacketDimInfo(here.getId(), here));
        }
    }

    /** The observation in flight, or {@code null}. */
    @Nullable
    public RegionScan getActiveScan() {
        return activeScan;
    }

    /** Which of {@link #SCAN_DIRECTIONS} the operator has selected, always in range. */
    public int scanDirectionIndex() {
        return Math.floorMod(scanDirection, SCAN_DIRECTIONS.length);
    }

    /** How far out the operator has the instrument aimed, in steps of one star's territory. */
    public int getScanDistance() {
        return scanDistance;
    }

    /** Whether a detection is followed all the way to the system's bodies, or only its address. */
    public boolean isCharacterisingWholeSystem() {
        return characteriseWholeSystem;
    }

    /** How far the current aim reaches, in light years, or zero before the server has said. */
    public double getAimLightYears() {
        return scanDistance * stepLightYears;
    }

    /**
     * The aim as a length, in brackets — because a bare "3" says nothing about the sky. Empty until
     * the server has told this tile what a step is worth, so the client never invents the number.
     */
    private String aimInLightYears() {
        double ly = getAimLightYears();
        if (ly <= 0d) {
            return "";
        }
        // The unit travels with the translation: a bracket reading "ly" is English, and this GUI
        // already speaks two languages.
        return String.format(LibVulpes.proxy.getLocalizedString("msg.observetory.scan.lightyears"), ly);
    }

    /** Whether the instrument is watching its own neighbourhood rather than a distant patch. */
    public boolean isPassive() {
        return passive;
    }

    /**
     * How many of the looks in this batch a cloud stands in the way of.
     *
     * <p>Counted beside the resolve rather than inside it, because what the OPERATOR is owed and what
     * the CRYSTAL is written with are different things: the crystal gains an address either way, and
     * the operator needs to know the difference between "there is nothing out that way" and "I cannot
     * see through that".</p>
     */
    private static int countObscured(UniverseRegistry registry, GalacticCoord origin, RegionScan scan,
                                     int from, int count) {
        if (registry == null || origin == null || scan == null) {
            return 0;
        }
        int obscured = 0;
        double limit = TelescopeScan.limitMagnitude();
        for (int index = from; index < from + count && index < scan.totalCells(); index++) {
            // The DETECTIONS and not the cells: empty sky is not a hidden sky, and neither is a star
            // the instrument never registered. What the operator is being told about is the band in
            // between - bright enough to see, too dim through the dust to make anything out.
            for (TelescopeScan.Detection hit
                    : TelescopeScan.detect(registry, scan.cellAt(index), origin, limit)) {
                if (TelescopeScan.isObscuredAt(hit.extinctionMagnitudes())) {
                    obscured++;
                }
            }
        }
        return obscured;
    }

    /** What the tab tells the operator the instrument is doing right now. */
    private String scanStatusText() {
        if (activeScan != null) {
            return LibVulpes.proxy.getLocalizedString("msg.observetory.scan.looking")
                    + " " + activeScan.cellsDone() + "/" + activeScan.totalCells();
        }
        // The dust is reported BEFORE the count of what was found: a survey that came back with
        // coordinates and no bodies has a reason, and an operator who is not told it reads the
        // instrument as broken.
        if (lastScanObscured > 0) {
            return LibVulpes.proxy.getLocalizedString("msg.observetory.scan.obscured")
                    + " " + lastScanObscured;
        }
        if (lastScanDiscoveries > 0) {
            return LibVulpes.proxy.getLocalizedString("msg.observetory.scan.found")
                    + " " + lastScanDiscoveries;
        }
        return LibVulpes.proxy.getLocalizedString("msg.observetory.scan.idle");
    }

    @Override
    public int getProgress(int id) {
        if (id != PROGRESS_SCAN) {
            return super.getProgress(id);
        }
        return activeScan == null ? 0 : activeScan.cellsDone();
    }

    @Override
    public int getTotalProgress(int id) {
        if (id != PROGRESS_SCAN) {
            return super.getTotalProgress(id);
        }
        return activeScan == null ? 0 : activeScan.totalCells();
    }

    @Override
    public float getNormallizedProgress(int id) {
        if (id != PROGRESS_SCAN) {
            return super.getNormallizedProgress(id);
        }
        // Cells resolved, not ticks elapsed: what the operator is owed is how much of the sky has
        // been read, and a step that takes longer at distance must not make the bar lie about it.
        return activeScan == null ? 0f : activeScan.progress();
    }

    /** How many addresses the last finished observation added to the crystal. */
    public int getLastScanDiscoveries() {
        return lastScanDiscoveries;
    }

    /** Where this observatory stands, in galactic terms, or {@code null} if its world has no address. */
    @Nullable
    public GalacticCoord scanOrigin() {
        UniverseRegistry registry = UniverseRegistry.get(world);
        if (registry == null) {
            return null;
        }
        return registry.coordForPlanet(world.provider.getDimension()).orElse(null);
    }

    /**
     * Write a finished observation onto the crystal in the machine.
     *
     * <p>A finished scan with no crystal to write to is <b>kept</b>, not discarded: the light has
     * arrived and the instrument is holding the picture, so slotting a crystal afterwards still gets
     * you what was seen. Losing an hour's observation to a forgotten crystal would be a punishment
     * for nothing.</p>
     */
    private void completeRegionScanIfDue() {
        if (activeScan == null) {
            return;
        }
        ItemStack crystal = getStackInSlot(SLOT_CRYSTAL);
        if (!ItemMemoryCrystal.isCrystal(crystal)) {
            return; // the picture is held until there is something to write it on
        }
        long now = world.getTotalWorldTime();

        // Without the research master switch, an observation is not a matter of time: what the
        // instrument can reach, it has already resolved. With it on, the sweep advances a bounded
        // number of cells per step and the time curve is the mechanic.
        boolean instant = !ARConfiguration.getCurrentConfig().planetsMustBeDiscovered;
        int cells = instant
                ? activeScan.totalCells() - activeScan.cellsDone()
                : activeScan.cellsDueAt(now);
        if (cells <= 0) {
            return;
        }

        // A step is paid for in distance data, the same currency the asteroid scan spends. An
        // instrument with too little waits instead of resolving — it stalls rather than working for
        // free, and the deadline is not moved, so it resumes the moment it is fed.
        int cost = Math.max(0, ARConfiguration.getCurrentConfig().telescopeSurveyDataPerStep);
        if (cost > 0) {
            if (extractData(cost, DataType.DISTANCE, EnumFacing.UP, false) < cost) {
                return;
            }
            extractData(cost, DataType.DISTANCE, EnumFacing.UP, true);
        }

        // The look is resolved FROM here, so a cloud standing between this instrument and what it is
        // aimed at can cost the look its detail. Counted while we are at it: an operator whose
        // survey came back with coordinates and no bodies must be told it was the dust, or the
        // feature is indistinguishable from an instrument that found nothing.
        GalacticCoord origin = scanOrigin();
        UniverseRegistry registry = UniverseRegistry.get(world);
        lastScanObscured += countObscured(registry, origin, activeScan, activeScan.cellsDone(), cells);
        // WHERE the instrument stands is what it teaches. A survey writes the crystal the operator
        // will carry away, and it also teaches the body underneath: a launch pad here may afterwards
        // be aimed at what this telescope made out, while a pad on the next world may not. Only a
        // NAMED body can be taught - a bare address has no world to fly to.
        Set<Integer> taughtHere = new HashSet<>();
        lastScanDiscoveries += TelescopeScan.resolveBatch(registry, activeScan,
                activeScan.cellsDone(), cells, crystal, now, TelescopeScan.dimensionNames(), origin,
                characteriseWholeSystem, taughtHere::add);
        teachThisBody(taughtHere);
        activeScan = instant ? activeScan.completed(now) : activeScan.advanced(now, cells);
        if (activeScan.isComplete()) {
            activeScan = null;
        }
        markDirty();
        IBlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 2);
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        super.onInventoryButtonPressed(buttonId);

        if (buttonId == 1) {
            // Client: prevent packet spam
            if (world != null && world.isRemote) {
                boolean alreadyPrinted = (lastButton != -1) && printedButtonsThisSeed.contains(lastButton);
                if (!isOpen || alreadyPrinted || lastButton == -1) {
                    return;
                }
            }

            // Server-side protection is in PROCESS_CHIP 
            PacketHandler.sendToServer(new PacketMachine(this, PROCESS_CHIP));
        }

        if (buttonId >= LIST_OFFSET) {
            lastButton = buttonId;
            lastType = buttonType.get(lastButton - LIST_OFFSET);
            PacketHandler.sendToServer(new PacketMachine(this, BUTTON_PRESS));
        }
        if (buttonId == 2) {
            if (world != null && world.isRemote) {
                pendingReopenAfterSeedSync = true;
            }
            PacketHandler.sendToServer(new PacketMachine(this, SEED_CHANGE));

        }

        // The region-scan tab. Each press is a request to the SERVER — the pick lives on the tile,
        // so what the operator chose survives him closing the GUI and is what the scan actually uses.
        if (buttonId == 3) {
            PacketHandler.sendToServer(new PacketMachine(this, PICK_DIRECTION));
        }
        if (buttonId == 4 || buttonId == 5) {
            pendingDistanceDelta = buttonId == 4 ? -1 : 1;
            PacketHandler.sendToServer(new PacketMachine(this, PICK_DISTANCE));
        }
        if (buttonId == 6) {
            PacketHandler.sendToServer(new PacketMachine(this, START_SCAN));
        }
        if (buttonId == 7) {
            PacketHandler.sendToServer(new PacketMachine(this, ABORT_SCAN));
        }
        if (buttonId == 8) {
            PacketHandler.sendToServer(new PacketMachine(this, PASSIVE_SWEEP));
        }
        if (buttonId == 9) {
            PacketHandler.sendToServer(new PacketMachine(this, TOGGLE_WHOLE_SYSTEM));
        }
        if (buttonId == 10) {
            PacketHandler.sendToServer(new PacketMachine(this, UPLOAD_CRYSTAL));
        }
    }

    /**
     * Read the crystal in the machine into the KNOWLEDGE OF THIS BODY: what somebody carried here
     * becomes what a launch pad standing here may be aimed at.
     *
     * <p>This is the one sanctioned crossing between the two discovery systems, and it goes one way.
     * A crystal deposits into a body's set; a body's set never feeds a crystal, a console, or a jump
     * target.</p>
     *
     * <p><b>An address without a world is skipped, and the count says so.</b> A procedural body
     * carries no dimension until somebody descends to it, so a survey of unvisited space writes
     * addresses that tier-1 has nothing to fly to yet - and an upload that silently did nothing
     * would be indistinguishable from a broken button. Re-surveying such a system after somebody has
     * landed there records the world it now has.</p>
     *
     * @return {@code {landed, total}}
     */
    private int[] uploadCrystalHere() {
        ItemStack crystal = getStackInSlot(SLOT_CRYSTAL);
        if (!ItemMemoryCrystal.isCrystal(crystal)) {
            return new int[] {0, 0};
        }
        List<CrystalEntry> entries = ItemMemoryCrystal.memoryOf(crystal).list();
        Set<Integer> landed = new HashSet<>();
        for (CrystalEntry entry : entries) {
            if (entry.namesBody()) {
                landed.add(entry.dimId());
            }
        }
        teachThisBody(landed);
        return new int[] {landed.size(), entries.size()};
    }


    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        super.useNetworkData(player, side, id, nbt);

        if (id == SYNC_PRINTED && world != null && world.isRemote) {
            printedSetSeed = nbt.getLong("ps");

            printedButtonsThisSeed.clear();
            for (int v : nbt.getIntArray("pb")) printedButtonsThisSeed.add(v);

            lastSeed = nbt.getLong("ls");
            lastButton = nbt.getInteger("lb");
            isOpen = nbt.getBoolean("io");
            return;
        }
        if (id == SYNC_SEED && world != null && world.isRemote) {
            lastSeed = nbt.getLong("ls");
            lastButton = nbt.getInteger("lb");
            lastType = ""; // since scan resets it
            isOpen = nbt.getBoolean("io");

            zmaster587.advancedRocketry.AdvancedRocketry.proxy.clearObservatoryScrollCache();

            if (pendingReopenAfterSeedSync) {
                pendingReopenAfterSeedSync = false;
                PacketHandler.sendToServer(new PacketMachine(this, REQUEST_REOPEN));
            }
            return;
        }

        if (id == -1) { storeData(-1); return; }
        if (id == -2) { loadData(-2);  return; }

        // --- server-side handlers only ---
        if (!world.isRemote) {

            if (id == TAB_SWITCH) {
                tabModule.setTab(nbt.getShort("tab"));
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());
            }
            else if (id == BUTTON_PRESS) {
                lastButton = nbt.getShort("button");
                lastType = buttonType.get(lastButton - LIST_OFFSET);
                markDirty();
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());
            }
            else if (id == PICK_DIRECTION || id == PICK_DISTANCE) {
                if (id == PICK_DIRECTION) {
                    scanDirection = (scanDirectionIndex() + 1) % SCAN_DIRECTIONS.length;
                } else {
                    int reach = RegionScan.Tuning.fromConfig().maxRangeSteps();
                    scanDistance = Math.max(1, Math.min(reach, scanDistance + nbt.getInteger("d")));
                }
                markDirty();
                IBlockState st = world.getBlockState(pos);
                world.notifyBlockUpdate(pos, st, st, 2);
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());
            }
            else if (id == TOGGLE_WHOLE_SYSTEM) {
                characteriseWholeSystem = !characteriseWholeSystem;
                markDirty();
                IBlockState st = world.getBlockState(pos);
                world.notifyBlockUpdate(pos, st, st, 2);
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());
            }
            else if (id == UPLOAD_CRYSTAL) {
                int[] result = uploadCrystalHere();
                player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
                        "msg.observetory.upload.result", result[0], result[1]));
            }
            else if (id == START_SCAN || id == ABORT_SCAN || id == PASSIVE_SWEEP) {
                if (id == ABORT_SCAN) {
                    abortRegionScan();
                } else if (id == PASSIVE_SWEEP) {
                    beginPassiveSweep();
                } else {
                    int[] aim = SCAN_DIRECTIONS[scanDirectionIndex()];
                    beginRegionScan(aim[0], aim[1], aim[2], scanDistance);
                }
                IBlockState st = world.getBlockState(pos);
                world.notifyBlockUpdate(pos, st, st, 2);
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());
            }
            else if (id == SEED_CHANGE) {
                if (extractData(dataConsumedPerRefresh, DataType.DISTANCE, EnumFacing.UP, false) >= dataConsumedPerRefresh) {
                    lastSeed = world.getTotalWorldTime() / 100;
                    lastButton = -1;
                    lastType = "";
                    
                    printedButtonsThisSeed.clear();
                    printedSetSeed = lastSeed;
                    PacketHandler.sendToPlayer(new PacketMachine(this, SYNC_SEED), player);
                    extractData(dataConsumedPerRefresh, DataType.DISTANCE, EnumFacing.UP, true);
                    markDirty();
                    IBlockState st = world.getBlockState(pos);
                    world.notifyBlockUpdate(pos, st, st, 2);
                }
            }
            else if (id == PROCESS_CHIP) {

                // Keep printed set aligned with current seed
                if (printedSetSeed != lastSeed) {
                    printedButtonsThisSeed.clear();
                    printedSetSeed = lastSeed;
                }

                // Hard block duplicates for this scan
                if (lastButton != -1 && printedButtonsThisSeed.contains(lastButton)) {
                    // No status message; just refresh UI so tooltip updates
                    world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
                    markDirty();
                    if (player != null) {
                        player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                            getWorld(), pos.getX(), pos.getY(), pos.getZ());
                    }
                    return;
                }

                if (inv.getStackInSlot(2).isEmpty() && isOpen && hasEnergy(500) && lastButton != -1) {
                    ItemStack stack = inv.decrStackSize(1, 1);
                    if (stack != ItemStack.EMPTY && stack.getItem() instanceof ItemAsteroidChip) {
                        ((ItemAsteroidChip)(stack.getItem())).setUUID(stack, lastSeed + lastButton);
                        ((ItemAsteroidChip)(stack.getItem())).setType(stack, lastType);
                        ((ItemAsteroidChip)(stack.getItem())).setMaxData(stack, 1000);
                        inv.setInventorySlotContents(2, stack);

                        useEnergy(500);

                        // Mark this selection as consumed for this seed
                        printedButtonsThisSeed.add(lastButton);
                        PacketHandler.sendToPlayer(new PacketMachine(this, SYNC_PRINTED), player);

                        markDirty();
                        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);

                        // reopen 1 tick later to avoid cross-channel ordering race
                        ((WorldServer) world).addScheduledTask(() -> player.openGui(
                                LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                                getWorld(), pos.getX(), pos.getY(), pos.getZ()
                        ));
                    }
                }
            }
            else if (id == REQUEST_REOPEN) {
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                    getWorld(), pos.getX(), pos.getY(), pos.getZ());
            }
        }
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        super.writeDataToNetwork(out, id);

        if (id == TAB_SWITCH)
            out.writeShort(tabModule.getTab());
        else if (id == BUTTON_PRESS)
            out.writeShort(lastButton);

        if (id == SYNC_PRINTED) {
            out.writeLong(printedSetSeed);
            out.writeInt(printedButtonsThisSeed.size());
            for (int b : printedButtonsThisSeed) out.writeInt(b);
            out.writeLong(lastSeed);
            out.writeInt(lastButton);
            out.writeBoolean(isOpen);
        }  
        if (id == SYNC_SEED) {
            out.writeLong(lastSeed);
            out.writeInt(lastButton);
            out.writeBoolean(isOpen);
            out.writeBoolean(getMachineEnabled());
            // lastType optional; you set it "" on scan, so you can skip it
        }
        if (id == PICK_DISTANCE) {
            out.writeInt(pendingDistanceDelta);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        super.readDataFromNetwork(in, packetId, nbt);

        if (packetId == TAB_SWITCH)
            nbt.setShort("tab", in.readShort());
        else if (packetId == BUTTON_PRESS)
            nbt.setShort("button", in.readShort());

        if (packetId == SYNC_PRINTED) {
            nbt.setLong("ps", in.readLong());
            int n = in.readInt();
            int[] arr = new int[n];
            for (int i=0;i<n;i++) arr[i] = in.readInt();
            nbt.setIntArray("pb", arr);
            nbt.setLong("ls", in.readLong());
            nbt.setInteger("lb", in.readInt());
            nbt.setBoolean("io", in.readBoolean());
        }
        if (packetId == SYNC_SEED) {
            nbt.setLong("ls", in.readLong());
            nbt.setInteger("lb", in.readInt());
            nbt.setBoolean("io", in.readBoolean());
            nbt.setBoolean("en", in.readBoolean());
        }
        if (packetId == PICK_DISTANCE) {
            nbt.setInteger("d", in.readInt());
        }
    }

    @Override
    public int getSizeInventory() {
        return inv.getSizeInventory();
    }

    @Override
    @Nonnull
    public ItemStack getStackInSlot(int slot) {
        return inv.getStackInSlot(slot);
    }

    @Override
    @Nonnull
    public ItemStack decrStackSize(int slot, int amount) {
        return inv.decrStackSize(slot, amount);
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        inv.setInventorySlotContents(slot, stack);
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean isUsableByPlayer(@Nullable EntityPlayer player) {
        return true;
    }

    @Override
    public boolean isEmpty() {
        return inv.isEmpty();
    }

    @Override
    public void openInventory(EntityPlayer player) {

    }

    @Override
    public void closeInventory(EntityPlayer player) {
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        if (stack.isEmpty()) return false;

        // data chip slots
        if (slot == 0 || slot == 3 || slot == 4) {
            return stack.getItem() instanceof IDataItem;
        }

        // asteroid chip input slot (if that's your intended one)
        if (slot == 1) {
            return stack.getItem() instanceof ItemAsteroidChip;
        }

        // output slot(s)
        if (slot == 2) {
            return false;
        }

        if (slot == SLOT_CRYSTAL) {
            return ItemMemoryCrystal.isCrystal(stack);
        }

        return true;
    }


    @Override
    public int extractData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        int amt = 0;
        for (TileDataBus tile : getDataBus()) {
            int dataAmt = tile.extractData(maxAmount, type, dir, commit);
            amt += dataAmt;
            maxAmount -= dataAmt;
        }
        return amt;
    }

    @Override
    public int addData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        return 0;
    }

    @Override
    public void loadData(int id) {
        int chipSlot = !inv.getStackInSlot(0).isEmpty() ? 0
                : !inv.getStackInSlot(3).isEmpty() ? 3
                : 4;

        ItemStack dataChip = !inv.getStackInSlot(0).isEmpty() ? inv.getStackInSlot(0)
                : !inv.getStackInSlot(3).isEmpty() ? inv.getStackInSlot(3)
                : inv.getStackInSlot(4);

        if (!dataChip.isEmpty() && dataChip.getItem() instanceof IDataItem && dataChip.getCount() == 1) {

            IDataItem dataItem = (IDataItem) dataChip.getItem();
            DataStorage chipData = dataItem.getDataStorage(dataChip);
            DataType chipType = chipData.getDataType();

            if (doesSlotIndexMatchDataType(chipType, chipSlot)) {

                for (TileDataBus tile : dataCables) {

                    // Only push into buses that match the chip's type by design
                    if (tile.getDataObject().getDataType() != chipType &&
                            tile.getDataObject().getDataType() != DataType.UNDEFINED) {
                        continue;
                    }

                    int remaining = chipData.getData();
                    if (remaining <= 0) break;

                    int space = tile.getDataObject().getMaxData() - tile.getData();
                    if (space <= 0) continue;

                    int toMove = Math.min(space, remaining);

                    int accepted = tile.addData(toMove, chipType, EnumFacing.UP, true);
                    if (accepted > 0) {
                        // IMPORTANT: decrement the LOCAL chipData so the next bus
                        // doesn't see the original amount
                        chipData.removeData(accepted, true);
                    }
                }

                // Write the final state back to the item once
                dataItem.setData(dataChip, chipData.getData(), chipData.getDataType());
            }
        }

        if (world.isRemote) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) -2));
        }
    }


    @Override
    public void storeData(int id) {
        int chipSlot = !inv.getStackInSlot(0).isEmpty() ? 0
                : !inv.getStackInSlot(3).isEmpty() ? 3
                : 4;

        ItemStack dataChip = !inv.getStackInSlot(0).isEmpty() ? inv.getStackInSlot(0)
                : !inv.getStackInSlot(3).isEmpty() ? inv.getStackInSlot(3)
                : inv.getStackInSlot(4);

        if (!dataChip.isEmpty() && dataChip.getItem() instanceof IDataItem && dataChip.getCount() == 1) {

            IDataItem dataItem = (IDataItem) dataChip.getItem();
            DataStorage chipData = dataItem.getDataStorage(dataChip);

            for (TileDataBus tile : dataCables) {
                DataType busType = tile.getDataObject().getDataType();

                if (!doesSlotIndexMatchDataType(busType, chipSlot)) continue;

                int remainingCap = chipData.getMaxData() - chipData.getData();
                if (remainingCap <= 0) break;

                int pulled = tile.extractData(remainingCap, chipData.getDataType(), EnumFacing.UP, true);
                if (pulled > 0) {
                    chipData.addData(pulled, busType, true);
                }
            }

            dataItem.setData(dataChip, chipData.getData(), chipData.getDataType());
        }

        if (world.isRemote) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) -1));
        }
    }


    @Override
    @Nullable
    public String getName() {
        return null;
    }

    @Override
    @Nonnull
    public ItemStack removeStackFromSlot(int index) {
        return inv.removeStackFromSlot(index);
    }

    @Override
    public int getField(int id) {
        return 0;
    }

    @Override
    public void setField(int id, int value) {

    }

    @Override
    public int getFieldCount() {
        return 0;
    }

    @Override
    public void clear() {

    }

    @Override
    public void invalidate() {
        super.invalidate();
        dataCables.clear();
        buttonType.clear();
        printedButtonsThisSeed.clear();
        printedSetSeed = -1;
        lastSeed = -1;
        lastButton = -1;
        lastType = "";
        if (world != null && world.isRemote) {
            zmaster587.advancedRocketry.AdvancedRocketry.proxy.clearObservatoryScrollCache();
        }


        savedDataBusNbt.clear();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        dataCables.clear();
        if (world != null && world.isRemote) {
            zmaster587.advancedRocketry.AdvancedRocketry.proxy.clearObservatoryScrollCache();
        }


        savedDataBusNbt.clear();
    }

    @Override
    public void onModuleUpdated(ModuleBase module) {
        //ReopenUI on server
        PacketHandler.sendToServer(new PacketMachine(this, TAB_SWITCH));
    }

    private boolean doesSlotIndexMatchDataType(DataType type, int slotIndex) {
        return (type == DataType.DISTANCE && slotIndex == 0) || (type == DataType.COMPOSITION && slotIndex == 3) || (type == DataType.MASS && slotIndex == 4);
    }
}
