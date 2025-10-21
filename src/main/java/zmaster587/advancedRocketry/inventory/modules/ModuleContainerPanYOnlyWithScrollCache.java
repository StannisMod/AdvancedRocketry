package zmaster587.advancedRocketry.inventory.modules;

import zmaster587.libVulpes.inventory.modules.ModuleContainerPanYOnly;
import zmaster587.libVulpes.inventory.modules.ModuleBase;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ModuleContainerPanYOnlyWithScrollCache extends ModuleContainerPanYOnly {

    /**
     * Single global scroll position cache for the client.
     * Stores one Integer (legal range [-containerSizeY, 0]) for the *most recently used* pane.
     * Latest write always wins (old overwrites new; there is only one slot).
     */
    private static final AtomicReference<Integer> GLOBAL_SCROLL = new AtomicReference<>(null);

    // Lazy, one-time restore (containerSizeY may not be final at ctor time)
    private boolean didRestore = false;
    private Integer pendingCachedPos = null;

    // ===== Constructors =====
    public ModuleContainerPanYOnlyWithScrollCache(
            int offsetX, int offsetY,
            List<ModuleBase> moduleList, List<ModuleBase> staticModules,
            ResourceLocation backdrop,
            int screenSizeX, int screenSizeY,
            int paddingX, int paddingY,
            int containerSizeX, int containerSizeY
    ) {
        super(offsetX, offsetY, moduleList, staticModules, backdrop, screenSizeX, screenSizeY,
              paddingX, paddingY, containerSizeX, containerSizeY);

        // Snapshot current global scroll to restore later (after layout stabilizes)
        this.pendingCachedPos = GLOBAL_SCROLL.get();
    }

    // ===== Clamping & persistence =====
    private int clampScroll(int y) {
        if (y > 0) return 0;
        int min = -this.containerSizeY;
        return (y < min) ? min : y;
    }

    private void saveScrollIfChanged() {
        int y = clampScroll(super.getScrollY());
        Integer prev = GLOBAL_SCROLL.get();
        if (prev != null && prev.intValue() == y) return; // no write if unchanged
        GLOBAL_SCROLL.set(y); // single slot: old key always overwritten by new write
    }

    // One-time lazy restore when bounds should be final
    @Override
    @SideOnly(Side.CLIENT)
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
        if (!didRestore) {
            if (pendingCachedPos != null) {
                int clamped = clampScroll(pendingCachedPos);
                // Base setOffset2 sets currentPosY = -y, so pass -clamped
                super.setOffset2(-clamped);
            }
            didRestore = true;
        }
        super.renderBackground(gui, x, y, mouseX, mouseY, font);
    }

    // Capture all movement paths (wheel, drag, programmatic move, close)
    @Override
    protected void moveContainerInterior(int deltaY) {
        super.moveContainerInterior(deltaY);
        saveScrollIfChanged();
    }

    @Override
    public void onScroll(int dwheel) {
        super.onScroll(dwheel);
        saveScrollIfChanged();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onMouseClickedAndDragged(int x, int y, int button, long timeSinceLastClick) {
        super.onMouseClickedAndDragged(x, y, button, timeSinceLastClick);
        saveScrollIfChanged();
    }

    @Override
    public void setEnabled(boolean state) {
        if (!state && this.isEnabled()) {
            // Persist on GUI disable/close
            saveScrollIfChanged();
        }
        super.setEnabled(state);
    }

    // Persist programmatic jumps too
    @Override
    protected void setOffset2(int y) {
        super.setOffset2(y);
        saveScrollIfChanged();
    }

    // external centralized wheel dispatcher 
    @SideOnly(Side.CLIENT)
    public void acceptExternalScroll(int dwheel, int mouseX, int mouseY) {
        if (dwheel == 0 || !this.isEnabled()) return;
        if (isMouseInBoundsForThis(mouseX, mouseY)) {
            super.onScroll(dwheel);
            saveScrollIfChanged();
        }
    }

    // Corrected bounds check (local space; include edges)
    private boolean isMouseInBoundsForThis(int mouseX, int mouseY) {
        int localX = mouseX - this.offsetX;
        int localY = mouseY - this.offsetY;
        return localX >= 0 && localX < this.screenSizeX
            && localY >= 0 && localY < this.screenSizeY;
    }

    // ===== Cache management helpers =====
    /** Clear the single cached position (e.g., on new scan). */
    public static void clearScrollCache() {
        GLOBAL_SCROLL.set(null);
    }
}
