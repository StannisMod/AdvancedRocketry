package zmaster587.advancedRocketry.inventory.modules;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import zmaster587.advancedRocketry.tile.satellite.TileSatelliteTerminal;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.util.PlanetaryTravelHelper;
import zmaster587.advancedRocketry.satellite.SatelliteData;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.item.ItemSatelliteIdentificationChip;

import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleText;

/**
 * Per-viewer status module for the Satellite Control Center.
 * Forces a sync every 0.5s (10 ticks) while the GUI is open.
 * Sends 4 ints: 0=status, 1=ppt, 2=data, 3=maxdata.
 */
public class ModuleSatelliteTerminal extends ModuleBase {

    private final ModuleText text;
    private final int color;
    private final IInventory inv;                // client: read chip name
    private final TileSatelliteTerminal tile;    // server: compute values

    // {status, ppt, data, max}
    private final int[] vals     = new int[4];


    // Force burst every 10 ticks
    private long lastPushBucket = Long.MIN_VALUE;
    private boolean burstPending = false;


    // Add field to track current chip/satellite identity (server-side)
    private long lastSatId = Long.MIN_VALUE; // or int; use -1 for "no sat"

    private static long getCurrentSatId(TileSatelliteTerminal t) {
        zmaster587.advancedRocketry.api.satellite.SatelliteBase sat = t.getSatelliteFromSlot(0);
        if (sat == null) return -1L;
        return sat.getId(); // adjust if getId() is int; cast/convert as needed
    }

    public ModuleSatelliteTerminal(int x, int y, int color) {
        this(x, y, color, null, null);
    }

    public ModuleSatelliteTerminal(int x, int y, int color, IInventory inv, TileSatelliteTerminal tile) {
        super(x, y);
        this.color = color;
        this.inv   = inv;
        this.tile  = tile;
        this.text  = new ModuleText(x, y, "", color);
        this.text.setText(LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.nolink"));
    }

    @Override
    public void renderForeground(int x, int y, int mouseX, int mouseY, float zLevel,
                                GuiContainer gui, FontRenderer font) {
    }

    @Override
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY,
                                FontRenderer font) {

        text.renderBackground(gui, x, y, mouseX, mouseY, font);
    }

    @Override
    public List<Slot> getSlots(Container container) { return Collections.emptyList(); }

    @Override public int numberOfChangesToSend() { return 4; }

    // Some libVulpes builds use needsUpdate; keep it mapped to our logic.
    @Override
    public boolean needsUpdate(int localId) { return isUpdateRequired(localId); }

    @Override
    public void sendInitialChanges(Container container, IContainerListener listener, int moduleIndex) {
        if (tile != null && !tile.getWorld().isRemote) {
            int[] now = computeStatusFromTile(tile);
            for (int i = 0; i < 4; i++) vals[i] = now[i];
            lastSatId = getCurrentSatId(tile);
            long t = tile.getWorld().getTotalWorldTime();
            lastPushBucket = t / 10L;
        }

        for (int i = 0; i < 4; i++) {
            listener.sendWindowProperty(container, moduleIndex + i, vals[i]);
        }
        burstPending = false; // reset
    }

    @Override
    public boolean isUpdateRequired(int relativeIdx) {
        if (tile != null && !tile.getWorld().isRemote) {
            final long t = tile.getWorld().getTotalWorldTime();
            final long bucket = t / 10L;

            // Detect satellite/chip change
            final long curSatId = getCurrentSatId(tile);
            final boolean satChanged = (curSatId != lastSatId);
            if (satChanged) lastSatId = curSatId;

            // Arm a new burst on bucket edge OR sat change
            if (bucket != lastPushBucket || satChanged) {
                lastPushBucket = bucket;

                // Compute all four, assign immediately so all lanes read the same snapshot
                final int[] now = computeStatusFromTile(tile);
                System.arraycopy(now, 0, vals, 0, 4);

                // One atomic send of all lanes this tick
                burstPending = true;

            }
        }

        // During a burst, ALL lanes return true so container sends 0..3 in one pass.
        return burstPending;
    }

    @Override
    public void sendChanges(Container container, IContainerListener listener,
                            int variableId, int relativeIdx) {
        // 'variableId' IS the global property id. Do NOT add relativeIdx.
        listener.sendWindowProperty(container, variableId, vals[relativeIdx]);

        // Clear the burst only after the last lane goes out
        if (relativeIdx == 3) {
            burstPending = false;
        }
    }



    @Override
    public void onChangeRecieved(int relativeIdx, int value) {
        vals[relativeIdx] = value;
        rebuildClientText();
    }

    // ---- Helpers ----

    private static int[] computeStatusFromTile(TileSatelliteTerminal t) {
        int status = 0, ppt = 0, data = 0, max = 0;

        SatelliteBase sat = t.getSatelliteFromSlot(0);

        // --- Case 1: No chip or invalid satellite ---
        if (!(sat instanceof SatelliteData)) {
            return new int[] { 0, 0, 0, 0 };
        }

        // --- Case 2: Valid satellite ---
        boolean hasPower = t.getUniversalEnergyStored() >= t.getPowerPerOperation();
        int hereDim = DimensionManager.getEffectiveDimId(t.getWorld(), t.getPos()).getId();
        boolean inRange = PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(sat.getDimensionId(), hereDim);

        if (!hasPower) {
            status = 1; // Not enough power
        } else if (!inRange) {
            status = 2; // Out of range
        } else {
            status = 3; // OK and connected

            SatelliteData s = (SatelliteData) sat;
            ppt  = s.getPowerPerTick();      // Power generation rate
            data = s.data.getData();         // Current data amount
            max  = s.data.getMaxData();      // Maximum storage
        }

        // --- Always return all four fields ---
        return new int[] { status, ppt, data, max };
    }


    // Client: rebuild visible text; sat name read locally from chip
    private void rebuildClientText() {
        final int status = vals[0];
        final int ppt    = vals[1];
        final int data   = vals[2];
        final int max    = vals[3];

        String satName = null;
        if (inv != null && inv.getSizeInventory() > 0) {
            ItemStack stack0 = inv.getStackInSlot(0);
            if (!stack0.isEmpty() && stack0.getItem() instanceof ItemSatelliteIdentificationChip) {
                SatelliteBase sat = ItemSatelliteIdentificationChip.getSatellite(stack0);
                if (sat != null) satName = sat.getName();
            }
        }

        String msg;
        if (status == 0) {
            msg = LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.nolink");
        } else if (status == 1) {
            msg = LibVulpes.proxy.getLocalizedString("msg.notenoughpower");
        } else if (status == 2) {
            msg = LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.toofar");
        } else {
            msg = LibVulpes.proxy.getLocalizedString("msg.satctrlcenter.info")
                + "\nPower gen.: " + ppt + "\nData: " + data + "/" + max;
        }

        if (satName != null && !satName.isEmpty()) {
            msg = satName + "\n\n" + msg;
        }

        text.setText(msg);
        text.setColor(color);
    }
}
