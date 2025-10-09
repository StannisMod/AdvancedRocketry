package zmaster587.advancedRocketry.inventory.modules;

import zmaster587.libVulpes.inventory.modules.ModuleContainerPanYOnly;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import net.minecraft.util.ResourceLocation;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;

public class ModuleContainerPanYOnlyWithScrollCache extends ModuleContainerPanYOnly {

    /**
     * Scroll position cache for the observatory asteroid list.
     * - One entry (per GUI layout) per client; cleared on new scan or scroll.
     * - Minimal memory use (single Integer per layout).
     * - Client-side only; no multiplayer or server impact.
     * - Thread-safe for typical GUI use.
     * - If reused, ensure cacheKey is unique per list type / GUI layout.
     */

    // Cache: only stores latest scroll position per key
    private static final Map<String, Integer> scrollCache = new ConcurrentHashMap<>();
    private final String cacheKey;

    public ModuleContainerPanYOnlyWithScrollCache(int offsetX, int offsetY, List<ModuleBase> moduleList, List<ModuleBase> staticModules, ResourceLocation backdrop, int screenSizeX, int screenSizeY, int paddingX, int paddingY, int containerSizeX, int containerSizeY) {
        super(offsetX, offsetY, moduleList, staticModules, backdrop, screenSizeX, screenSizeY, paddingX, paddingY, containerSizeX, containerSizeY);

        // Create unique cache key
        this.cacheKey = "observatory_asteroid_list:" + offsetX + ":" + offsetY + ":" + screenSizeX + ":" + screenSizeY;

        // Restore scroll position if available
        Integer cachedPos = scrollCache.get(cacheKey);
        if (cachedPos != null) {
            super.setOffset2(-cachedPos);
        }
    }

    @Override
    protected void moveContainerInterior(int deltaY) {
        super.moveContainerInterior(deltaY);
        scrollCache.put(cacheKey, super.getScrollY());
    }

    @Override
    public void onScroll(int dwheel) {
        super.onScroll(dwheel);
        scrollCache.put(cacheKey, super.getScrollY());
    }

    // Public method to clear cache (for new asteroid scans)
    public static void clearScrollCache() {
        scrollCache.clear();
    }
}
