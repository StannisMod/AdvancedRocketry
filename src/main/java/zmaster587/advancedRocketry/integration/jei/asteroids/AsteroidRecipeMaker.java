package zmaster587.advancedRocketry.integration.jei.asteroids;

import mezz.jei.api.IJeiHelpers;
import net.minecraftforge.fml.common.Loader;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.util.Asteroid;
import zmaster587.advancedRocketry.util.XMLAsteroidLoader;

import java.io.File;
import java.util.*;

public class AsteroidRecipeMaker {

    private static List<AsteroidWrapper> cached = null;
    private static long cachedMTime = -1L;

    public static List<AsteroidWrapper> getRecipes(IJeiHelpers helpers) {
        // Primary truth: XML in config folder (avoids load-order race)
        File xml = getAsteroidXmlFile();
        long mtime = (xml != null && xml.exists()) ? xml.lastModified() : -1L;

        if (cached != null && mtime == cachedMTime) {
            return cached;
        }

        List<AsteroidWrapper> fromXml = tryLoadFromXml(xml);
        if (fromXml != null && !fromXml.isEmpty()) {
            cached = fromXml;
            cachedMTime = mtime;
            return cached;
        }

        // Fallback: whatever AR already has in memory (better than nothing)
        try {
            Map<String, Asteroid> map = ARConfiguration.getCurrentConfig().asteroidTypes;
            if (map != null && !map.isEmpty()) {
                cached = buildPagedFromMap(map);
                cachedMTime = mtime;
                return cached;
            }
        } catch (Throwable ignored) {}

        cached = Collections.emptyList();
        cachedMTime = mtime;
        return cached;
    }

    private static File getAsteroidXmlFile() {
        try {
            // Most robust in modded: use Forge config dir
            File cfgDir = Loader.instance().getConfigDir();
            String folder = ARConfiguration.configFolder; // AR uses this folder name
            return new File(cfgDir, folder + "/asteroidConfig.xml");
        } catch (Throwable t) {
            // Fallback to run-dir relative path
            try {
                String folder = ARConfiguration.configFolder;
                return new File("./config/" + folder + "/asteroidConfig.xml");
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static List<AsteroidWrapper> tryLoadFromXml(File file) {
        try {
            if (file == null || !file.exists()) {
                AdvancedRocketry.logger.warn("[JEI] asteroidConfig.xml not found: " + (file != null ? file.getAbsolutePath() : "null"));
                return Collections.emptyList();
            }

            XMLAsteroidLoader loader = new XMLAsteroidLoader();
            if (!loader.loadFile(file)) {
                AdvancedRocketry.logger.warn("[JEI] Failed parsing asteroidConfig.xml: " + file.getAbsolutePath());
                return Collections.emptyList();
            }

            List<Asteroid> asteroids = loader.loadPropertyFile();
            if (asteroids == null || asteroids.isEmpty()) {
                AdvancedRocketry.logger.warn("[JEI] asteroidConfig.xml parsed but produced 0 asteroids");
                return Collections.emptyList();
            }

            // Convert list -> map-like key usage
            Map<String, Asteroid> map = new LinkedHashMap<>();
            for (Asteroid a : asteroids) {
                if (a == null) continue;
                String key = (a.ID != null && !a.ID.isEmpty()) ? a.ID : a.getName();
                if (key == null || key.isEmpty()) key = "asteroid";
                map.put(key, a);
            }

            List<AsteroidWrapper> out = buildPagedFromMap(map);
            AdvancedRocketry.logger.info("[JEI] Loaded " + out.size() + " asteroid JEI recipes from XML");
            return out;

        } catch (Throwable t) {
            AdvancedRocketry.logger.warn("[JEI] Exception loading asteroids for JEI", t);
            return Collections.emptyList();
        }
    }

    private static List<AsteroidWrapper> buildPagedFromMap(Map<String, Asteroid> map) {
        List<AsteroidWrapper> out = new ArrayList<>();

        for (Map.Entry<String, Asteroid> e : map.entrySet()) {
            String key = e.getKey();
            Asteroid ast = e.getValue();
            if (ast == null) continue;

            List<ItemStack> all = AsteroidWrapper.collectOutputsFromConfig(ast);
            int total = all.size();
            int pages = Math.max(1, (total + AsteroidWrapper.PAGE_SIZE - 1) / AsteroidWrapper.PAGE_SIZE);

            for (int page = 0; page < pages; page++) {
                int from = page * AsteroidWrapper.PAGE_SIZE;
                int to = Math.min(total, from + AsteroidWrapper.PAGE_SIZE);
                List<ItemStack> slice = (from < to) ? all.subList(from, to) : Collections.emptyList();

                out.add(new AsteroidWrapper(key, ast, page, pages, slice));
            }
        }

        // Sort: asteroid name, then page
        out.sort(Comparator
                .comparing(AsteroidWrapper::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(AsteroidWrapper::getPageIndex));

        return out;
    }

    public static List<AsteroidWrapper> getMachineRecipes(IJeiHelpers helpers, Class<?> ignored) {
        return getRecipes(helpers);
    }

    // Optional: call this if you ever add a config-reload hook
    public static void clearCache() {
        cached = null;
        cachedMTime = -1L;
    }
}
