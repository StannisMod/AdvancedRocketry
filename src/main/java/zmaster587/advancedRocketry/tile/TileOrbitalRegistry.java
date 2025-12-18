package zmaster587.advancedRocketry.tile;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.api.satellite.SatelliteProperties;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.item.ItemOreScanner;
import zmaster587.advancedRocketry.item.ItemSatelliteIdentificationChip;
import zmaster587.advancedRocketry.item.ItemStationChip;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.advancedRocketry.util.StationLandingLocation;

import zmaster587.libVulpes.client.util.ProgressBarImage;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.gui.CommonResources;
import zmaster587.libVulpes.inventory.GuiHandler;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.multiblock.TileMultiPowerConsumer;
import zmaster587.libVulpes.util.EmbeddedInventory;
import zmaster587.libVulpes.util.IconResource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;


/**
 * Orbital Registry: satellites + stations
 *
 * Two tabs:
 *   0 - Satellites
 *   1 - Stations
 *
 * Both tabs:
 *   - Left window: scrollable list of objects (buttons)
 *   - Right window: detail view for the selected object
 *   - Slot 0: input chip (sat or station chip)
 *   - Slot 1: output written chip
 *   - "Scan" button to populate/refresh the list from server state
 */
public class TileOrbitalRegistry extends TileMultiPowerConsumer
        implements IModularInventory, IButtonInventory, IGuiCallback, IInventory {

    // Simple 1x2 structure, same pattern as TileSatelliteBuilder
    public static final Object[][][] structure = new Object[][][] {
            { { 'c' } }
    };

    // Inventory slots
    private static final int SLOT_CHIP_IN  = 0;
    private static final int SLOT_CHIP_OUT = 1;

    // Tabs
    private static final int TAB_SATELLITES = 0;
    private static final int TAB_STATIONS   = 1;

    // Left list: only slightly wider
    private static final int OBS_LIST_BASE_X  = 5;
    private static final int OBS_LIST_BASE_Y  = 32;
    private static final int OBS_LIST_SIZE_X  = 120;
    private static final int OBS_LIST_SIZE_Y  = 46;

    // Keep a small gap, give the rest of the width to the detail pane
    private static final int OBS_DETAIL_BASE_X = 135;  
    private static final int OBS_DETAIL_BASE_Y = 32;
    private static final int OBS_DETAIL_SIZE_X = 110;
    private static final int OBS_DETAIL_SIZE_Y = 46;

    // Chip IO area (same as Observatory asteroid tab)
    private static final int OBS_CHIP_X = 5;
    private static final int OBS_CHIP_Y = 120;

    // GUI button IDs (client-side)
    private static final int GUI_BUTTON_WRITE = 0;
    private static final int GUI_BUTTON_SCAN  = 1;

    // GUI list offsets (client-side)
    private static final short SAT_LIST_OFFSET     = 100;
    private static final short STATION_LIST_OFFSET = 200;

    // Network IDs (PacketMachine)
    private static final byte NET_TAB_SWITCH          = 10;
    private static final byte NET_BUTTON_SELECT_SAT   = 11;
    private static final byte NET_BUTTON_WRITE_CHIP   = 12;
    private static final byte NET_BUTTON_SCAN         = 13;
    private static final byte NET_BUTTON_SELECT_STAT  = 14;

    // Inventory
    private final EmbeddedInventory inv;

    // Dimension whose satellites we show (usually effective dim of this tile)
    private int satDimId = 0;

    // Selection / last pressed list button
    private int  lastSatButton     = -1;
    private long selectedSatId     = -1L;

    private int  lastStationButton = -1;
    private int  selectedStationId = -1;

    // Cached scan results (server-side authoritative, synced to client)
    private final List<SatEntry>     satCache     = new ArrayList<>();
    private final List<StationEntry> stationCache = new ArrayList<>();

    // Tab module (0 = satellites, 1 = stations)
    private final ModuleTab tabModule;

    private static class SatEntry {
        long   id;
        int    dimId;
        String registryKey;   // satellite type / registry id / dataType
        int    powerGen;
        int    powerStorage;
        long   maxData;
    }

    private static class StationEntry {
        int    id;
        int    dimId;
        int    orbitingBodyId;
        boolean anchored;
        boolean hasWarpCore;
        int    freePads;
    }

    public TileOrbitalRegistry() {
        this.inv = new EmbeddedInventory(2);
        this.powerPerTick = 0;           // mostly passive
        this.completionTime = 0;

        this.tabModule = new ModuleTab(
                4, 0, 0,
                this,
                2,
                new String[] {
                        LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.tab.satellites"),
                        LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.tab.stations")
                },
                new net.minecraft.util.ResourceLocation[][] {
                        TextureResources.tabData,
                        TextureResources.tabAsteroid
                }
        );
    }

    /* ------------------------------------------------------------------------
     *  Multiblock basics
     * --------------------------------------------------------------------- */

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public boolean completeStructure(net.minecraft.block.state.IBlockState state) {
        boolean result = super.completeStructure(state);
        ((zmaster587.libVulpes.block.multiblock.BlockMultiblockMachine)
                world.getBlockState(pos).getBlock())
                .setBlockState(world, world.getBlockState(pos), pos, result);
        return result;
    }

    @Override
    public String getMachineName() {
        return LibVulpes.proxy.getLocalizedString("tile.orbitalRegistry.name");
    }

    /* ------------------------------------------------------------------------
     *  Helpers / scans
     * --------------------------------------------------------------------- */

    private int getEffectiveSatDim() {
        if (world == null) {
            return satDimId;
        }
        int eff = DimensionManager.getEffectiveDimId(world, pos).getId();
        if (satDimId == 0) {
            satDimId = eff;
        }
        return satDimId;
    }

    // Blacklist for "satellites" that should not appear in the orbital registry
    private static final java.util.Set<String> SAT_BLACKLIST =
            java.util.Collections.unmodifiableSet(
                    new java.util.HashSet<>(java.util.Arrays.asList(
                            "asteroidMiner",
                            "gasMining"
                    ))
            );
                

    /**
     * Build satellite cache from DimensionProperties.
     * Only called when Scan is pressed on the satellites tab.
     */

    private void rescanSatellites() {
        satCache.clear();

        int dimId = getEffectiveSatDim();
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(dimId);
        if (props == null) {
            selectedSatId = -1L;
            lastSatButton = -1;
            return;
        }

        java.util.Collection<SatelliteBase> raw = props.getAllSatellites();
        if (raw == null) raw = java.util.Collections.emptyList();

        List<SatelliteBase> sats = new ArrayList<>(raw);
        sats.sort(Comparator.comparingLong(SatelliteBase::getId));

        for (SatelliteBase sat : sats) {
            SatEntry entry = new SatEntry();
            entry.id    = sat.getId();
            entry.dimId = sat.getDimensionId();

            entry.registryKey  = "";
            entry.powerGen     = 0;
            entry.powerStorage = 0;
            entry.maxData      = 0;

            try {
                zmaster587.advancedRocketry.api.satellite.SatelliteProperties sProps = sat.getProperties();
                if (sProps != null) {
                    String type = sProps.getSatelliteType();
                    if (type != null && !type.isEmpty()) {
                        entry.registryKey = type;
                    }
                    entry.powerGen     = sProps.getPowerGeneration();
                    entry.powerStorage = sProps.getPowerStorage();
                    entry.maxData      = sProps.getMaxDataStorage();
                }
            } catch (Throwable ignored) {}

            // Fallback: derive registry key from the satellite class if properties didn't give one
            if (entry.registryKey == null || entry.registryKey.isEmpty()) {
                try {
                    String key = SatelliteRegistry.getKey(sat.getClass());
                    if (key != null && !"poo".equals(key)) { // "poo" is the error sentinel in SatelliteRegistry
                        entry.registryKey = key;
                    }
                } catch (Throwable ignored) {}
            }

            // Absolute last-resort fallback so we always have "something"
            if (entry.registryKey == null || entry.registryKey.isEmpty()) {
                entry.registryKey = sat.getClass().getSimpleName().toLowerCase();
            }

            if (SAT_BLACKLIST.contains(entry.registryKey)) {
                continue;
            }
            /* UNCOMMENT TO EXCLUDE MISSIONS FROM ORBITAL REGISTRY
            BLACKLIST OVER SHOULD HOLD FOR NOW
            if (sat instanceof zmaster587.advancedRocketry.api.IMission) {
                continue;
            }
            */

            satCache.add(entry);
        }

        // Keep selection if possible
        long prevSelected = selectedSatId;
        selectedSatId = -1L;
        lastSatButton = -1;

        if (prevSelected >= 0L) {
            for (int i = 0; i < satCache.size(); i++) {
                if (satCache.get(i).id == prevSelected) {
                    selectedSatId = prevSelected;
                    lastSatButton = SAT_LIST_OFFSET + i;
                    break;
                }
            }
        }
    }

    /**
     * Build station cache from SpaceObjectManager.
     * Only called when Scan is pressed on the stations tab.
     *
     * NOTE: This assumes SpaceObjectManager exposes a way to iterate space objects.
     * If your API is different (e.g. getSpaceStations(), getSpaceObjects()),
     * adjust the iteration inside this method.
     */
    
    private void rescanStations() {
        stationCache.clear();

        final SpaceObjectManager manager = SpaceObjectManager.getSpaceManager();
        if (manager == null) {
            selectedStationId = -1;
            lastStationButton = -1;
            return;
        }

        final Iterable<ISpaceObject> objects = manager.getSpaceObjects();
        if (objects == null) {
            selectedStationId = -1;
            lastStationButton = -1;
            return;
        }

        for (ISpaceObject obj : objects) {
            if (obj == null) continue; // ultra-defensive, cheap

            StationEntry entry = new StationEntry();
            entry.id             = obj.getId();
            entry.orbitingBodyId = obj.getOrbitingPlanetId();
            entry.anchored       = obj.isAnchored();

            entry.dimId       = -1;
            entry.hasWarpCore = false;
            entry.freePads    = 0;

            if (entry.orbitingBodyId == zmaster587.advancedRocketry.api.Constants.INVALID_PLANET
                    || entry.orbitingBodyId == SpaceObjectManager.WARPDIMID) {
                entry.dimId = -1;
            } else {
                entry.dimId = entry.orbitingBodyId;
            }

            if (obj instanceof SpaceStationObject) {
                SpaceStationObject station = (SpaceStationObject) obj;
                entry.hasWarpCore = station.hasWarpCores;

                int free = 0;
                for (StationLandingLocation pad : station.getLandingPads()) {
                    if (!pad.getOccupied()) {
                        free++;
                    }
                }
                entry.freePads = free;
            }

            stationCache.add(entry);
        }

        stationCache.sort(Comparator.comparingInt(e -> e.id));

        int prevSelected = selectedStationId;
        selectedStationId = -1;
        lastStationButton = -1;

        if (prevSelected >= 0) {
            for (int i = 0; i < stationCache.size(); i++) {
                if (stationCache.get(i).id == prevSelected) {
                    selectedStationId = prevSelected;
                    lastStationButton = STATION_LIST_OFFSET + i;
                    break;
                }
            }
        }
    }


    private void handleSatelliteSelectionFromButton(int buttonId) {
        lastSatButton = buttonId;
        int idx = lastSatButton - SAT_LIST_OFFSET;

        if (idx >= 0 && idx < satCache.size()) {
            selectedSatId = satCache.get(idx).id;
        } else {
            selectedSatId = -1L;
        }
    }

    private void handleStationSelectionFromButton(int buttonId) {
        lastStationButton = buttonId;
        int idx = lastStationButton - STATION_LIST_OFFSET;

        if (idx >= 0 && idx < stationCache.size()) {
            selectedStationId = stationCache.get(idx).id;
        } else {
            selectedStationId = -1;
        }
    }

    /**
     * Returns a localized display name for a satellite based on its raw name.
     *
     * - Builds a lang key of the form:
     *     msg.orbitalregistry.sat.name.<normalized_name>
     * - If no translation exists (returned string == key), falls back to the raw name.
     * - If name is null/empty, uses a generic "unnamed" key.
     */
        
    private String getLocalizedSatName(SatEntry sat) {
        String baseKey = sat.registryKey;
        if (baseKey == null || baseKey.isEmpty()) {
            baseKey = "unknown";
        }

        // Lang key you will define in the lang file:
        // msg.orbitalregistry.sat.name.<registryKey>
        String langKey = "msg.orbitalregistry.sat.name." + baseKey;
        String localized = LibVulpes.proxy.getLocalizedString(langKey);

        // If missing, LibVulpes usually returns the key itself → then we just show the registryKey as text.
        if (localized == null || localized.isEmpty() || localized.equals(langKey)) {
            return baseKey;
        }
        return localized;
    }

    private static class WriteCheck {
        final boolean ok;
        final String tooltipKey;

        WriteCheck(boolean ok, String tooltipKey) {
            this.ok = ok;
            this.tooltipKey = tooltipKey;
        }

        static WriteCheck fail(String key) {
            return new WriteCheck(false, key);
        }

        static WriteCheck ok(String key) {
            return new WriteCheck(true, key);
        }
    }

    private WriteCheck checkWrite() {
        int tab = tabModule.getTab();

        ItemStack in  = getStackInSlot(SLOT_CHIP_IN);
        ItemStack out = getStackInSlot(SLOT_CHIP_OUT);

        // Output blocking reason is universal
        if (!out.isEmpty()) {
            return WriteCheck.fail("msg.orbitalregistry.writechip.hint.output");
        }

        // No input: avoid negative phrasing
        if (in.isEmpty() || in.getCount() != 1) {
            return WriteCheck.fail("msg.orbitalregistry.writechip.hint.insert");
        }

        // SATELLITES TAB
        if (tab == TAB_SATELLITES) {

            // maybe:
            // if (satCache.isEmpty()) {
            //     return WriteCheck.fail("msg.orbitalregistry.writechip.hint.scan");
            // }

            if (selectedSatId < 0L) {
                return WriteCheck.fail("msg.orbitalregistry.writechip.hint.select");
            }

            SatelliteBase sat = DimensionManager.getInstance().getSatellite(selectedSatId);
            if (sat == null) {
                // Optional: could be hint.scan instead of select
                return WriteCheck.fail("msg.orbitalregistry.writechip.hint.select");
            }

            boolean isIdChip     = in.getItem() instanceof ItemSatelliteIdentificationChip;
            boolean isOreScanner = in.getItem() instanceof ItemOreScanner;

            if (!isIdChip && !isOreScanner) {
                return WriteCheck.fail("msg.orbitalregistry.writechip.hint.sat.or.idchip");
            }

            if (isOreScanner) {
                if (!(sat instanceof zmaster587.advancedRocketry.satellite.SatelliteOreMapping)) {
                    return WriteCheck.fail("msg.orbitalregistry.writechip.hint.sat.orescanner.only");
                }
                return WriteCheck.ok("msg.orbitalregistry.writechip.ok");
            }

            // ID-chip path
            if (!sat.isAcceptableControllerItemStack(in)) {
                return WriteCheck.fail("msg.orbitalregistry.writechip.hint.sat.badcontroller");
            }

            return WriteCheck.ok("msg.orbitalregistry.writechip.ok");
        }

        // STATIONS TAB

        // maybe:
        // if (stationCache.isEmpty()) {
        //     return WriteCheck.fail("msg.orbitalregistry.writechip.hint.scan");
        // }

        if (!(in.getItem() instanceof ItemStationChip)) {
            return WriteCheck.fail("msg.orbitalregistry.writechip.hint.sat.or.stationchip");
        }

        if (selectedStationId < 0) {
            return WriteCheck.fail("msg.orbitalregistry.writechip.hint.select");
        }

        StationEntry selected = null;
        for (StationEntry e : stationCache) {
            if (e.id == selectedStationId) {
                selected = e;
                break;
            }
        }

        if (selected == null) {
            return WriteCheck.fail("msg.orbitalregistry.writechip.hint.select");
        }

        if (selected.orbitingBodyId == zmaster587.advancedRocketry.api.Constants.INVALID_PLANET) {
            return WriteCheck.fail("msg.orbitalregistry.writechip.hint.station.unlaunched");
        }

        return WriteCheck.ok("msg.orbitalregistry.writechip.ok");
    }


    /* ------------------------------------------------------------------------
     *  Chip writing
     * --------------------------------------------------------------------- */


    private void writeSatelliteChipFromSelection() {
        if (world == null || world.isRemote) return;

        SatelliteBase sat = DimensionManager.getInstance().getSatellite(selectedSatId);
        if (sat == null) return;

        ItemStack source = decrStackSize(SLOT_CHIP_IN, 1);
        if (source.isEmpty()) return;

        if (source.getItem() instanceof ItemOreScanner) {
            ItemOreScanner scanner = (ItemOreScanner) source.getItem();

            if (!(sat instanceof zmaster587.advancedRocketry.satellite.SatelliteOreMapping)) {
                setInventorySlotContents(SLOT_CHIP_IN, source);
                return;
            }

            scanner.setSatelliteID(source, selectedSatId);
            setInventorySlotContents(SLOT_CHIP_OUT, source);

            markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
            return;
        }

        SatelliteProperties props = sat.getProperties();
        if (props == null) {
            setInventorySlotContents(SLOT_CHIP_IN, source);
            return;
        }

        ItemStack programmed = sat.getControllerItemStack(source, props);
        setInventorySlotContents(SLOT_CHIP_OUT, programmed);

        markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
    }
    
    private boolean canWriteChipForCurrentTab() {
        return checkWrite().ok;
    }

    private void writeChipForCurrentTab() {
        if (!checkWrite().ok) return;

        if (tabModule.getTab() == TAB_SATELLITES) {
            writeSatelliteChipFromSelection();
        } else {
            writeStationChipFromSelection();
        }
    }

    /**
     * Writes a station chip for the selected space station.
     */
    private void writeStationChipFromSelection() {
        if (world == null || world.isRemote) return;

        ItemStack sourceChip = decrStackSize(SLOT_CHIP_IN, 1);
        if (sourceChip.isEmpty() || !(sourceChip.getItem() instanceof ItemStationChip)) {
            return;
        }

        ItemStationChip chipItem = (ItemStationChip) sourceChip.getItem();
        chipItem.setUUID(sourceChip, selectedStationId);

        setInventorySlotContents(SLOT_CHIP_OUT, sourceChip);

        markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
    }

    /* ------------------------------------------------------------------------
     *  Core tick / processing
     * --------------------------------------------------------------------- */

    @Override
    protected void processComplete() {
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    /* ------------------------------------------------------------------------
     *  GUI modules
     * --------------------------------------------------------------------- */

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();

        // --- Extra right-hand backdrop: stretch main GUI full height with 3 slices ---
        if (world != null && world.isRemote) {

            final int extX       = 173;
            final int guiTopY    = 0;
            final int guiBottomY = 168;
            final int extWidth   = 78;

            // TOP: the 86px slice that already lined up with the main GUI top
            modules.add(new ModuleImage(
                    extX, guiTopY,
                    new IconResource(
                            98, 0,
                            extWidth, 86,
                            CommonResources.genericBackground
                    )
            ));

            // MIDDLE: fill from y=86 down to y=168,
            modules.add(new ModuleImage(
                    extX, guiTopY + 86,
                    new IconResource(
                            98, 3,
                            extWidth, guiBottomY - 86,
                            CommonResources.genericBackground
                    )
            ));

            // BOTTOM: the 3px strip that already lined up with the main GUI bottom
            modules.add(new ModuleImage(
                    extX, guiBottomY,
                    new IconResource(
                            98, 168,
                            extWidth, 3,
                            CommonResources.genericBackground
                    )
            ));
        }

        modules.add(tabModule);
        //no powerbar
        //modules.add(new ModulePower(18, 20, getBatteries()));

        final int tab = tabModule.getTab();

        // ----- CHIP IO + BUTTONS (bottom, same as Observatory) -----
        // Same layout as TileObservatory asteroid tab: (5,120) / (45,120) / 25 / 100
        modules.add(new ModuleTexturedSlotArray(
                OBS_CHIP_X, OBS_CHIP_Y,
                this,
                SLOT_CHIP_IN, SLOT_CHIP_IN + 1,
                TextureResources.idChip));

        modules.add(new ModuleOutputSlotArray(
                OBS_CHIP_X + 40, OBS_CHIP_Y,
                this,
                SLOT_CHIP_OUT, SLOT_CHIP_OUT + 1));

        // Progress bar behind the write button
        modules.add(new ModuleProgress(
                OBS_CHIP_X + 20, OBS_CHIP_Y,
                0,
                new ProgressBarImage(
                        217, 0, 17, 17,
                        234, 0,
                        EnumFacing.DOWN,
                        TextureResources.progressBars
                ),
                this
        ));

        ModuleButton writeBtn = new ModuleButton(
                OBS_CHIP_X + 20, OBS_CHIP_Y,
                GUI_BUTTON_WRITE,
                "",
                this,
                zmaster587.libVulpes.inventory.TextureResources.buttonNull,
                LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.writechip"),
                17, 17
        );
        WriteCheck wc = checkWrite();
        writeBtn.setToolTipText(LibVulpes.proxy.getLocalizedString(wc.tooltipKey));


        modules.add(writeBtn);

        ModuleButton scanBtn = new ModuleButton(
                110, OBS_CHIP_Y,
                GUI_BUTTON_SCAN,
                LibVulpes.proxy.getLocalizedString("msg.observetory.scan.button"),
                this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.scan.tooltip"),
                64, 18
        );
        modules.add(scanBtn);

        // ----- WINDOWS (left list + right detail)  -----

        final int listBaseX = OBS_LIST_BASE_X;
        final int listBaseY = OBS_LIST_BASE_Y;
        final int listSizeX = OBS_LIST_SIZE_X;
        final int listSizeY = OBS_LIST_SIZE_Y;

        final int detailBaseX = OBS_DETAIL_BASE_X;
        final int detailBaseY = OBS_DETAIL_BASE_Y;
        final int detailSizeX = OBS_DETAIL_SIZE_X;
        final int detailSizeY = OBS_DETAIL_SIZE_Y;

        if (world != null && world.isRemote) {
            // Left window frame
            modules.add(new ModuleScaledImage(
                    listBaseX - 3, listBaseY - 3,
                    3, listBaseY + listSizeY + 6,
                    TextureResources.verticalBar));
            modules.add(new ModuleScaledImage(
                    listBaseX + listSizeX, listBaseY - 3,
                    -3, listBaseY + listSizeY + 6,
                    TextureResources.verticalBar));
            modules.add(new ModuleScaledImage(
                    listBaseX, listBaseY - 3,
                    listSizeX, 3,
                    TextureResources.horizontalBar));
            modules.add(new ModuleScaledImage(
                    listBaseX, 2 * listBaseY + listSizeY,
                    listSizeX, -3,
                    TextureResources.horizontalBar));

            // Right window frame
            modules.add(new ModuleScaledImage(
                    detailBaseX - 3, detailBaseY - 3,
                    3, detailBaseY + detailSizeY + 6,
                    TextureResources.verticalBar));
            modules.add(new ModuleScaledImage(
                    detailBaseX + detailSizeX, detailBaseY - 3,
                    -3, detailBaseY + detailSizeY + 6,
                    TextureResources.verticalBar));
            modules.add(new ModuleScaledImage(
                    detailBaseX, detailBaseY - 3,
                    detailSizeX, 3,
                    TextureResources.horizontalBar));
            modules.add(new ModuleScaledImage(
                    detailBaseX, 2 * detailBaseY + detailSizeY,
                    detailSizeX, -3,
                    TextureResources.horizontalBar));
        }

        // Title positions
        if (tab == TAB_SATELLITES) {
            modules.add(new ModuleText(
                    10, 18,
                    LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.satellites"),
                    0x2d2d2d
            ));
            modules.add(new ModuleText(
                    OBS_DETAIL_BASE_X - 5,
                    18,
                    LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.details"),
                    0x2d2d2d
            ));

            buildSatelliteListWindow(modules, listBaseX, listBaseY, listSizeX, listSizeY);
            buildSatelliteDetailWindow(modules, detailBaseX + 3, detailBaseY + 3);

        } else {
            modules.add(new ModuleText(
                    10, 18,
                    LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.stations"),
                    0x2d2d2d
            ));
            modules.add(new ModuleText(
                    OBS_DETAIL_BASE_X - 5,
                    18,
                    LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.details"),
                    0x2d2d2d
            ));

            buildStationListWindow(modules, listBaseX, listBaseY, listSizeX, listSizeY);
            buildStationDetailWindow(modules, detailBaseX + 3, detailBaseY + 3);
        }

        return modules;
    }
            
    private void buildSatelliteListWindow(List<ModuleBase> modules,
                                        int baseX, int baseY, int sizeX, int sizeY) {

        List<ModuleBase> satButtons = new LinkedList<>();

        for (int i = 0; i < satCache.size(); i++) {
            SatEntry sat = satCache.get(i);

            int buttonId    = SAT_LIST_OFFSET + i;
            String displayName = getLocalizedSatName(sat);
            String label       = String.format("ID %d %s", sat.id, displayName);

            ModuleButton button = new ModuleButton(
                    0,
                    i * 18,
                    buttonId,
                    label,
                    this,
                    TextureResources.buttonAsteroid,
                    OBS_LIST_SIZE_X, 18
            );

            if (sat.id == selectedSatId) {
                button.setColor(0xFFFF00);
            }

            satButtons.add(button);
        }

        if (!satButtons.isEmpty()) {
            modules.add(AdvancedRocketry.proxy.createScrollListPan(
                    baseX, baseY,
                    satButtons,
                    sizeX, sizeY
            ));
        }
    }

    private void buildSatelliteDetailWindow(List<ModuleBase> modules, int startX, int startY) {
    int x = startX;
    int y = startY;

    if (selectedSatId < 0L || satCache.isEmpty()) {
        modules.add(new ModuleText(
                x, y,
                LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.nosel"),
                0xAA0000
        ));
        return;
    }

    SatEntry selected = null;
    for (SatEntry e : satCache) {
        if (e.id == selectedSatId) {
            selected = e;
            break;
        }
    }

    if (selected == null) {
        modules.add(new ModuleText(
                x, y,
                LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.notfound"),
                0xAA0000
        ));
        return;
    }

    // ----- ID: <id> -----
    String idLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.id"); // "ID:"
    String idLine  = idLabel + " " + selected.id;
    modules.add(new ModuleText(x, y, idLine, 0x2d2d2d));
    y += 10;

    // ----- Type: localized satellite name (from registryKey) -----
    String typeLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.type"); // "Type:"
    String typeLine  = typeLabel + " " + getLocalizedSatName(selected);
    modules.add(new ModuleText(x, y, typeLine, 0x2d2d2d));
    y += 10;

    // ----- Dim: <raw dim id> -----
    String dimLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.dimid"); // "Dim:"
    String dimLine  = dimLabel + " " + selected.dimId;
    modules.add(new ModuleText(x, y, dimLine, 0x2d2d2d));
    y += 10;

    // ----- Orbiting: <resolved dimension/planet name or 'None'> -----
    String orbitLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.orbit"); // "Orbiting:"

    String orbitName;
    DimensionProperties bodyProps =
            DimensionManager.getInstance().getDimensionProperties(selected.dimId);
    if (bodyProps != null) {
        orbitName = bodyProps.getName();
    } else {
        orbitName = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.dimid.none");
    }

    String orbitLine = orbitLabel + " " + orbitName;
    modules.add(new ModuleText(x, y, orbitLine, 0x2d2d2d));
    y += 10;

    // ----- Power + data -----
    if (selected.powerGen != 0 || selected.powerStorage != 0 || selected.maxData != 0) {
        String pwrGenLabel   = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.sat.pwrgen");
        String pwrStoreLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.sat.pwrstore");
        String maxDataLabel  = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.sat.maxdata");

        modules.add(new ModuleText(x, y, pwrGenLabel + " " + selected.powerGen, 0x2d2d2d));
        y += 10;
        modules.add(new ModuleText(x, y, pwrStoreLabel + " " + selected.powerStorage, 0x2d2d2d));
        y += 10;
        modules.add(new ModuleText(x, y, maxDataLabel + " " + selected.maxData, 0x2d2d2d));
        y += 10;
    }
}
   


    private void buildStationListWindow(List<ModuleBase> modules,
                                        int baseX, int baseY, int sizeX, int sizeY) {

        List<ModuleBase> stationButtons = new LinkedList<>();

        for (int i = 0; i < stationCache.size(); i++) {
            StationEntry st = stationCache.get(i);

            int buttonId = STATION_LIST_OFFSET + i;

        // Short type text (localized)
        String typeShort = st.hasWarpCore
                ? LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.type.starshiplist")
                : LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.type.station");

        // "ID" label from lang, then "ID <id> <Type>"
        String listPrefix = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.listentry");
        String label      = listPrefix + " " + st.id + " " + typeShort;


            ModuleButton button = new ModuleButton(
                    0,
                    i * 18,
                    buttonId,
                    label,
                    this,
                    TextureResources.buttonAsteroid,
                    OBS_LIST_SIZE_X, 18
            );

            if (st.id == selectedStationId) {
                button.setColor(0xFFFF00);
            }

            stationButtons.add(button);
        }

        if (!stationButtons.isEmpty()) {
            modules.add(AdvancedRocketry.proxy.createScrollListPan(
                    baseX, baseY,
                    stationButtons,
                    sizeX, sizeY
            ));
        }
    }
      
    private void buildStationDetailWindow(List<ModuleBase> modules, int startX, int startY) {
        int x = startX;
        int y = startY;

        if (selectedStationId < 0 || stationCache.isEmpty()) {
            modules.add(new ModuleText(
                    x, y,
                    LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.nosel"),
                    0xAA0000
            ));
            return;
        }

        StationEntry selected = null;
        for (StationEntry e : stationCache) {
            if (e.id == selectedStationId) {
                selected = e;
                break;
            }
        }

        if (selected == null) {
            modules.add(new ModuleText(
                    x, y,
                    LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.notfound"),
                    0xAA0000
            ));
            return;
        }

        // ----- ID: <id> -----
        String idLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.id"); // e.g. "ID:"
        String idLine  = idLabel + " " + selected.id;
        modules.add(new ModuleText(x, y, idLine, 0x2d2d2d));
        y += 10;

        // ----- Type: <Starship/Station> -----
        String typeLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.type"); // e.g. "Type:"
        String typeKey   = selected.hasWarpCore
                ? LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.type.starship")
                : LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.type.station");
        String typeLine  = typeLabel + " " + typeKey;
        modules.add(new ModuleText(x, y, typeLine, 0x2d2d2d));
        y += 10;

        // ----- DimID: <dim id under the station, or None if not orbiting> -----
        String dimText;
        if (selected.orbitingBodyId == zmaster587.advancedRocketry.api.Constants.INVALID_PLANET
                || selected.orbitingBodyId == SpaceObjectManager.WARPDIMID) {
            // No real body below → None
            dimText = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.dimid.none");
        } else {
            dimText = Integer.toString(selected.dimId);
        }
        String dimLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.dimid"); // e.g. "DimID:"
        String dimLine  = dimLabel + " " + dimText;
        modules.add(new ModuleText(x, y, dimLine, 0x2d2d2d));
        y += 10;

        // ----- Orbiting: <resolved name / unlaunched> -----
        String orbitName;
        String systemName;

        if (selected.orbitingBodyId == zmaster587.advancedRocketry.api.Constants.INVALID_PLANET
                || selected.orbitingBodyId == SpaceObjectManager.WARPDIMID) {
            // Treat as unlaunched / no system
            orbitName  = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.orbit.unlaunched");
            systemName = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.system.none"); // e.g. "None"
        } else {
            DimensionProperties bodyProps =
                    zmaster587.advancedRocketry.dimension.DimensionManager
                            .getInstance().getDimensionProperties(selected.orbitingBodyId);

            if (bodyProps != null) {
                orbitName = bodyProps.getName();

                // Try to get star/system name
                if (bodyProps.getStar() != null && bodyProps.getStar().getName() != null) {
                    systemName = bodyProps.getStar().getName();
                } else {
                    systemName = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.system.unknown");
                }
            } else {
                // Fallback: raw ID, unknown system
                orbitName  = Integer.toString(selected.orbitingBodyId);
                systemName = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.system.unknown");
            }
        }

        String orbitLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.orbit"); // "Orbiting:"
        String orbitLine  = orbitLabel + " " + orbitName;
        modules.add(new ModuleText(x, y, orbitLine, 0x2d2d2d));
        y += 10;

        // ----- System: <Starname> -----  (NEW)
        String systemLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.system"); // "System:"
        String systemLine  = systemLabel + " " + systemName;
        modules.add(new ModuleText(x, y, systemLine, 0x2d2d2d));
        y += 10;


        // ----- Free landingpads: <#freepads> -----
        String padsLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.freepads"); // "Free landingpads:"
        String padsLine  = padsLabel + " " + selected.freePads;
        modules.add(new ModuleText(x, y, padsLine, 0x2d2d2d));
        y += 10;

        // ----- Anchored: yes/no -----
        String anchoredLabel = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.anchored"); // "Anchored:"
        String anchoredYes   = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.anchored.yes");
        String anchoredNo    = LibVulpes.proxy.getLocalizedString("msg.orbitalregistry.text.anchored.no");
        String anchoredVal   = selected.anchored ? anchoredYes : anchoredNo;
        String anchoredLine  = anchoredLabel + " " + anchoredVal;
        modules.add(new ModuleText(x, y, anchoredLine, 0x2d2d2d));
        y += 10;
    }

    /* ------------------------------------------------------------------------
     *  Button handling
     * --------------------------------------------------------------------- */

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        // Client → server via PacketMachine
        if (world != null && world.isRemote) {
            if (buttonId == GUI_BUTTON_SCAN) {
                // Reset scroll immediately on the client
                AdvancedRocketry.proxy.clearScrollCache();
                PacketHandler.sendToServer(new PacketMachine(this, NET_BUTTON_SCAN));
                return;
            }

            if (buttonId == GUI_BUTTON_WRITE) {
                PacketHandler.sendToServer(new PacketMachine(this, NET_BUTTON_WRITE_CHIP));
                return;
            } else if (buttonId >= SAT_LIST_OFFSET && buttonId < STATION_LIST_OFFSET) {
                // NEW: update client-side selection immediately
                lastSatButton = buttonId;
                handleSatelliteSelectionFromButton(buttonId);

                PacketHandler.sendToServer(new PacketMachine(this, NET_BUTTON_SELECT_SAT));

            } else if (buttonId >= STATION_LIST_OFFSET) {
                // NEW: update client-side selection immediately
                lastStationButton = buttonId;
                handleStationSelectionFromButton(buttonId);

                PacketHandler.sendToServer(new PacketMachine(this, NET_BUTTON_SELECT_STAT));
            }
            return;
        }

        // Server-side fallback (normally PacketMachine + useNetworkData)
        if (buttonId == GUI_BUTTON_WRITE) {
            writeChipForCurrentTab();
        } else if (buttonId == GUI_BUTTON_SCAN) {
            if (tabModule.getTab() == TAB_SATELLITES) {
                rescanSatellites();
            } else {
                rescanStations();
            }
            markDirty();
        } else if (buttonId >= SAT_LIST_OFFSET && buttonId < STATION_LIST_OFFSET) {
            handleSatelliteSelectionFromButton(buttonId);
        } else if (buttonId >= STATION_LIST_OFFSET) {
            handleStationSelectionFromButton(buttonId);
        }
    }


    @Override
    public void onModuleUpdated(ModuleBase module) {
        // Tab switched; tell server to update and reopen GUI
        PacketHandler.sendToServer(new PacketMachine(this, NET_TAB_SWITCH));
    }

    /* ------------------------------------------------------------------------
     *  INetworkMachine: custom packets
     * --------------------------------------------------------------------- */

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        super.writeDataToNetwork(out, id);

        if (id == NET_TAB_SWITCH) {
            out.writeShort(tabModule.getTab());
        } else if (id == NET_BUTTON_SELECT_SAT) {
            out.writeShort(lastSatButton);
        } else if (id == NET_BUTTON_SELECT_STAT) {
            out.writeShort(lastStationButton);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        super.readDataFromNetwork(in, packetId, nbt);

        if (packetId == NET_TAB_SWITCH) {
            nbt.setShort("tab", in.readShort());
        } else if (packetId == NET_BUTTON_SELECT_SAT) {
            nbt.setShort("buttonSat", in.readShort());
        } else if (packetId == NET_BUTTON_SELECT_STAT) {
            nbt.setShort("buttonStation", in.readShort());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        super.useNetworkData(player, side, id, nbt);

        if (!world.isRemote) {
            if (id == NET_TAB_SWITCH) {
                tabModule.setTab(nbt.getShort("tab"));
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());

            } else if (id == NET_BUTTON_SELECT_SAT) {
                int btn = nbt.getShort("buttonSat");
                handleSatelliteSelectionFromButton(btn);
                markDirty();
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());

            } else if (id == NET_BUTTON_SELECT_STAT) {
                int btn = nbt.getShort("buttonStation");
                handleStationSelectionFromButton(btn);
                markDirty();
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());

            } else if (id == NET_BUTTON_WRITE_CHIP) {
                writeChipForCurrentTab();
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());

            } else if (id == NET_BUTTON_SCAN) {
                if (tabModule.getTab() == TAB_SATELLITES) {
                    rescanSatellites();
                } else {
                    rescanStations();
                }

                markDirty();
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
                player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());
            }
        }
    }

    /* ------------------------------------------------------------------------
     *  Persistent state (world save + client sync)
     * --------------------------------------------------------------------- */

    @Override
    protected void writeNetworkData(NBTTagCompound nbt) {
        super.writeNetworkData(nbt);
        writeCommonNBT(nbt);
    }

    @Override
    protected void readNetworkData(NBTTagCompound nbt) {
        super.readNetworkData(nbt);
        readCommonNBT(nbt);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        inv.writeToNBT(nbt);
        writeCommonNBT(nbt);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        inv.readFromNBT(nbt);
        readCommonNBT(nbt);
    }

    private void writeCommonNBT(NBTTagCompound nbt) {
        nbt.setInteger("satDimId", satDimId);
        nbt.setInteger("lastSatButton", lastSatButton);
        nbt.setLong("selectedSatId", selectedSatId);
        nbt.setInteger("lastStationButton", lastStationButton);
        nbt.setInteger("selectedStationId", selectedStationId);

        // Satellite cache
        NBTTagList satList = new NBTTagList();
        for (SatEntry e : satCache) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("id", e.id);
            tag.setInteger("dimId", e.dimId);
            tag.setString("registryKey", e.registryKey == null ? "" : e.registryKey);
            tag.setInteger("powerGen", e.powerGen);
            tag.setInteger("powerStorage", e.powerStorage);
            tag.setLong("maxData", e.maxData);
            satList.appendTag(tag);
        }
        nbt.setTag("satCache", satList);



        // Station cache
        NBTTagList stationList = new NBTTagList();
        for (StationEntry e : stationCache) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("id", e.id);
            tag.setInteger("dimId", e.dimId);
            tag.setInteger("orbitingBodyId", e.orbitingBodyId);
            tag.setBoolean("anchored", e.anchored);
            tag.setBoolean("hasWarpCore", e.hasWarpCore);
            tag.setInteger("freePads", e.freePads);
            stationList.appendTag(tag);
        }
        nbt.setTag("stationCache", stationList);

    }

    private void readCommonNBT(NBTTagCompound nbt) {
        satDimId         = nbt.getInteger("satDimId");
        lastSatButton    = nbt.getInteger("lastSatButton");
        selectedSatId    = nbt.getLong("selectedSatId");
        lastStationButton = nbt.getInteger("lastStationButton");
        selectedStationId = nbt.getInteger("selectedStationId");

        satCache.clear();
        if (nbt.hasKey("satCache")) {
            NBTTagList satList = nbt.getTagList("satCache", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < satList.tagCount(); i++) {
                NBTTagCompound tag = satList.getCompoundTagAt(i);
                SatEntry e = new SatEntry();
                e.id          = tag.getLong("id");
                e.dimId       = tag.getInteger("dimId");
                e.registryKey = tag.getString("registryKey");
                e.powerGen    = tag.getInteger("powerGen");
                e.powerStorage= tag.getInteger("powerStorage");
                e.maxData     = tag.getLong("maxData");
                satCache.add(e);
            }
        }


        stationCache.clear();
        if (nbt.hasKey("stationCache")) {
            NBTTagList stationList = nbt.getTagList("stationCache", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < stationList.tagCount(); i++) {
                NBTTagCompound tag = stationList.getCompoundTagAt(i);
                StationEntry e = new StationEntry();
                e.id             = tag.getInteger("id");
                e.dimId          = tag.getInteger("dimId");                 // NEW
                e.orbitingBodyId = tag.getInteger("orbitingBodyId");
                e.anchored       = tag.getBoolean("anchored");
                e.hasWarpCore    = tag.getBoolean("hasWarpCore");           // NEW
                e.freePads       = tag.getInteger("freePads");              // NEW
                stationCache.add(e);
            }
        }
    }

    /* ------------------------------------------------------------------------
     *  Inventory plumbing (2 slots)
     * --------------------------------------------------------------------- */

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
        return inv.hasCustomName();
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean isUsableByPlayer(@Nullable EntityPlayer player) {
        return player != null && player.getDistanceSq(pos) < 4096;
    }

    @Override
    public void openInventory(EntityPlayer player) {
        inv.openInventory(player);
    }

    @Override
    public void closeInventory(EntityPlayer player) {
        inv.closeInventory(player);
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        if (slot == SLOT_CHIP_IN) {
            if (stack.getCount() != 1) return false;
            Item item = stack.getItem();

            return (item instanceof ItemSatelliteIdentificationChip)
                    || (item instanceof ItemStationChip)
                    || (item instanceof ItemOreScanner);
        }
        return false;
    }


    @Override
    @Nonnull
    public ItemStack removeStackFromSlot(int index) {
        return inv.removeStackFromSlot(index);
    }

    @Override
    public int getField(int id) {
        return inv.getField(id);
    }

    @Override
    public void setField(int id, int value) {
        inv.setField(id, value);
    }

    @Override
    public int getFieldCount() {
        return inv.getFieldCount();
    }

    @Override
    public void clear() {
        inv.clear();
    }

    @Override
    public boolean isEmpty() {
        return inv.isEmpty();
    }

    @Override
    @Nullable
    public String getName() {
        return null;
    }

    @Override
    public void invalidate() {
        super.invalidate();

        // Optional but nice to keep state sane
        satCache.clear();
        stationCache.clear();
        selectedSatId = -1;
        selectedStationId = -1;
        lastSatButton = -1;
        lastStationButton = -1;

        // Critical: reset static scroll cache so containers don't reuse old offsets
        if (world != null && world.isRemote) {
            AdvancedRocketry.proxy.clearScrollCache();
        }

    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (world != null && world.isRemote) {
            AdvancedRocketry.proxy.clearScrollCache();
        }
    }
}
