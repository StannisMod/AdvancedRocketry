package zmaster587.advancedRocketry.inventory.modules;

import zmaster587.libVulpes.inventory.modules.ModuleContainerPanYOnly;
import zmaster587.libVulpes.inventory.modules.ModuleBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class ModuleContainerPanYOnlyWithScrollCache extends ModuleContainerPanYOnly {

    // ===== Single-slot global cache (latest write wins; no memory growth) =====
    private static final int NO_SCROLL = Integer.MIN_VALUE;
    private static final AtomicInteger GLOBAL_SCROLL = new AtomicInteger(NO_SCROLL);

    // ===== Track live instances so the event handler can find "this" without editing GuiModular =====
    @SideOnly(Side.CLIENT)
    private static final CopyOnWriteArrayList<WeakReference<ModuleContainerPanYOnlyWithScrollCache>> LIVE =
            new CopyOnWriteArrayList<>();

    @SideOnly(Side.CLIENT)
    private WeakReference<GuiContainer> lastGui = new WeakReference<>(null);

    @SideOnly(Side.CLIENT)
    private static volatile boolean EVENT_REGISTERED = false;

    // >>> Store GUI origin captured during render to avoid touching protected xSize/ySize
    @SideOnly(Side.CLIENT)
    private volatile int lastGuiLeft = 0, lastGuiTop = 0;

    // ===== Instance state =====
    private int lastSavedY = NO_SCROLL;
    private boolean didRestore = false;

    // Debounce to avoid micro-stutter under heavy input
    private long lastSaveNs = 0L;
    private static final long SAVE_INTERVAL_NS = 30_000_000L; // 30 ms

    public ModuleContainerPanYOnlyWithScrollCache(
            int offsetX, int offsetY,
            List<ModuleBase> moduleList, List<ModuleBase> staticModules,
            ResourceLocation backdrop,
            int screenSizeX, int screenSizeY,
            int paddingX, int paddingY,
            int containerSizeX, int containerSizeY
    ) {
        super(offsetX, offsetY, moduleList, staticModules, backdrop,
              screenSizeX, screenSizeY, paddingX, paddingY,
              containerSizeX, containerSizeY);

        // Register this instance for event routing (client-side only)
        if (Minecraft.getMinecraft() != null) {
            LIVE.add(new WeakReference<>(this));
            maybeRegisterEventHandler();
        }
    }

    // Ensure we don’t leak refs if the module gets disabled/detached
    @Override
    public void setEnabled(boolean state) {
        if (!state && this.isEnabled()) {
            saveScrollIfChangedForce(); // persist last position
        }
        super.setEnabled(state);
        // Optional: prune dead refs occasionally
        pruneDeadRefs();
    }

    // Clamp to base’s legal range [-containerSizeY, 0]
    private int clampScroll(int y) {
        if (y > 0) return 0;
        int min = -this.containerSizeY;
        return (y < min) ? min : y;
    }

    // Debounced save after movement, skipping no-op writes globally and per-instance
    private void saveScrollIfChanged() {
        final int y = clampScroll(super.getScrollY());

        // Per-instance no-op: if we already observed y, don't do anything.
        if (y == lastSavedY) return;

        // Global no-op: if the global cache already holds y, skip the write, but
        // update our lastSavedY so we don't keep re-checking.
        final int global = GLOBAL_SCROLL.get();
        if (global == y) {
            lastSavedY = y;
            return;
        }

        // Keep debounce to avoid bursts during fine-grained drags.
        final long now = System.nanoTime();
        if (now - lastSaveNs < SAVE_INTERVAL_NS) {
            lastSavedY = y;    // remember the new y even if we didn't write globally yet
            return;
        }

        lastSaveNs = now;
        lastSavedY = y;

        // Use lazySet for cheap release write, perfectly fine for a UI cache
        GLOBAL_SCROLL.lazySet(y);

        // DEBUG 
        //System.out.println("[SCROLLER] save y=" + y);
    }


    // Force save (bypass debounce) on close or disable, still skipping no-op
    private void saveScrollIfChangedForce() {
        final int y = clampScroll(super.getScrollY());

        // If both our last and the global already equal y, it's a no-op
        if (y == lastSavedY && GLOBAL_SCROLL.get() == y) return;

        lastSavedY = y;
        lastSaveNs = System.nanoTime();

        GLOBAL_SCROLL.lazySet(y);

        // DEBUG 
        //System.out.println("[SCROLLER] force-save y=" + y);
    }

    // Restore once when bounds are stable
    @Override
    @SideOnly(Side.CLIENT)
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
        // Remember the GUI we’re rendering in so the event handler can filter by current screen
        this.lastGui = new WeakReference<>(gui);

        // >>> Capture guiLeft/guiTop from the parameters
        this.lastGuiLeft = x;
        this.lastGuiTop  = y;

        if (!didRestore) {
            int v = GLOBAL_SCROLL.get();
            if (v != NO_SCROLL) {
                int clamped = clampScroll(v);
                super.setOffset2(-clamped);   // base uses -y
                lastSavedY = clamped;
                //System.out.println("[SCROLLER] restore y=" + clamped); // DEBUG
            }
            didRestore = true;
        }
        super.renderBackground(gui, x, y, mouseX, mouseY, font);
    }

    
    @Override
    @SideOnly(Side.CLIENT)
    public void renderForeground(int guiOffsetX, int guiOffsetY, int mouseX, int mouseY, float zLevel,
                                 GuiContainer gui, FontRenderer font) {
        super.renderForeground(guiOffsetX, guiOffsetY, mouseX, mouseY, zLevel, gui, font);
    }

    // Single save point for any movement
    @Override
    protected void moveContainerInterior(int deltaY) {
        super.moveContainerInterior(deltaY);
        saveScrollIfChanged();
    }

    // Base onScroll calls moveContainerInterior; don’t double-save here
    @Override
    public void onScroll(int dwheel) {
        super.onScroll(dwheel);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onMouseClickedAndDragged(int x, int y, int button, long timeSinceLastClick) {
        super.onMouseClickedAndDragged(x, y, button, timeSinceLastClick);
    }

    // Public clear (e.g., on new scan)
    public static void clearScrollCache() {
        GLOBAL_SCROLL.set(NO_SCROLL);
        //System.out.println("[SCROLLER] clear cache"); // DEBUG
    }

    // ===== Event routing (client-side only) =====

    @SideOnly(Side.CLIENT)
    private static void maybeRegisterEventHandler() {
        if (!EVENT_REGISTERED) {
            MinecraftForge.EVENT_BUS.register(new WheelRouter());
            EVENT_REGISTERED = true;
        }
    }

    @SideOnly(Side.CLIENT)
    private static void pruneDeadRefs() {
        for (WeakReference<ModuleContainerPanYOnlyWithScrollCache> ref : LIVE) {
            if (ref.get() == null) LIVE.remove(ref);
        }
    }

    @SideOnly(Side.CLIENT)
    private boolean isMouseOverThis(int relX, int relY) {
        // relX/relY are GUI-relative to (guiLeft, guiTop)
        int localX = relX - this.offsetX;
        int localY = relY - this.offsetY;
        return localX >= 0 && localX < this.screenSizeX
            && localY >= 0 && localY < this.screenSizeY;
    }

    @SideOnly(Side.CLIENT)
    private boolean isOnThisGui(GuiScreen current) {
        GuiContainer g = lastGui.get();
        return g != null && g == current;
    }

    @SideOnly(Side.CLIENT)
    private static class WheelRouter {
        private static int lastTickDispatched = -1;
        private static int lastScreenId       = 0;
        private static int lastWheelSign      = 0; // -1/+1

        @SubscribeEvent
        public void onMouseInputPre(GuiScreenEvent.MouseInputEvent.Pre evt) throws IOException {
            GuiScreen screen = evt.getGui();
            if (!(screen instanceof GuiContainer)) return;

            int d = org.lwjgl.input.Mouse.getEventDWheel();
            if (d == 0) return;

            Minecraft mc = Minecraft.getMinecraft();
            int tick = (mc.ingameGUI != null) ? mc.ingameGUI.getUpdateCounter() : 0;
            int screenId = System.identityHashCode(screen);
            int sign = Integer.signum(d);

            // Coalesce: same screen + same tick + same direction => treat as duplicate
            if (tick == lastTickDispatched && screenId == lastScreenId && sign == lastWheelSign) {
                evt.setCanceled(true);
                return;
            }

            int scaledW = screen.width, scaledH = screen.height;
            int mouseX = org.lwjgl.input.Mouse.getX() * scaledW / mc.displayWidth;
            int mouseY = scaledH - org.lwjgl.input.Mouse.getY() * scaledH / mc.displayHeight - 1;

            boolean handled = false;
            for (int i = LIVE.size() - 1; i >= 0; i--) {
                WeakReference<ModuleContainerPanYOnlyWithScrollCache> ref = LIVE.get(i);
                ModuleContainerPanYOnlyWithScrollCache mod = ref.get();
                if (mod == null) { LIVE.remove(i); continue; }
                if (!mod.getVisible() || !mod.isEnabled()) continue;
                if (!mod.isOnThisGui(screen)) continue;

                int relX = mouseX - mod.lastGuiLeft;
                int relY = mouseY - mod.lastGuiTop;
                if (!mod.isMouseOverThis(relX, relY)) continue;

                mod.onScroll(d);     // will call moveContainerInterior -> save
                handled = true;
                break;
            }

            if (handled) {
                lastTickDispatched = tick;
                lastScreenId       = screenId;
                lastWheelSign      = sign;
                evt.setCanceled(true);
            }
        }
    }
}
