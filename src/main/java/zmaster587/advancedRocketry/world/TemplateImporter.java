package zmaster587.advancedRocketry.world;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.minecraft.world.World;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.dimension.TerrainSource;

/**
 * Copies pre-generated WorldPainter / pregen region files into a {@link TerrainSource#TEMPLATE} planet's
 * on-disk region folder once, before its chunks are first generated. Runs at world-load, server side only.
 *
 * <p>Idempotent: a marker file next to the region folder guards against re-copying (so the imported terrain
 * is thereafter loaded verbatim). Sandboxed: a template name resolves under {@code config/advRocketry/templates/}
 * and any path escaping that root (traversal or an absolute path) is rejected, since the name is pack-authored.
 * Never throws - a missing or invalid source simply yields a void world.
 */
public final class TemplateImporter {

    private static final String MARKER = ".ar_template_imported";
    private static final String TEMPLATES_DIR = "templates";

    private TemplateImporter() {}

    /** Root that TEMPLATE names resolve under: {@code ./config/advRocketry/templates/}. */
    private static File templatesRoot() {
        return new File("." + File.separator + "config" + File.separator
                + ARConfiguration.configFolder + File.separator + TEMPLATES_DIR);
    }

    /**
     * If {@code world}'s dimension is a not-yet-imported TEMPLATE planet, copy its region files in.
     * A no-op for remote worlds, non-TEMPLATE dimensions, or an already-imported dimension.
     */
    public static void importIfNeeded(World world, DimensionProperties props) {
        if (world == null || world.isRemote || props == null || props.getTerrainSource() != TerrainSource.TEMPLATE)
            return;

        int dimId = world.provider.getDimension();
        File saveRoot = net.minecraftforge.common.DimensionManager.getCurrentSaveRootDirectory();
        if (saveRoot == null) {
            AdvancedRocketry.logger.warn("TEMPLATE dimension " + dimId + ": no save root directory available, skipping import");
            return;
        }

        // Mirrors WorldProviderPlanet.getSaveFolder() == "advRocketry/DIM<id>".
        File dimFolder = new File(saveRoot, "advRocketry" + File.separator + "DIM" + dimId);
        File marker = new File(dimFolder, MARKER);
        if (marker.exists())
            return; // already imported - verbatim from now on

        File source = resolveSource(props.getTerrainTemplate());
        if (source == null) {
            AdvancedRocketry.logger.warn("TEMPLATE dimension " + dimId + ": template '" + props.getTerrainTemplate()
                    + "' is invalid or escapes the templates directory; generating a void world");
            writeMarker(marker);
            return;
        }

        File sourceRegion = new File(source, "region");
        if (!sourceRegion.isDirectory()) {
            AdvancedRocketry.logger.warn("TEMPLATE dimension " + dimId + ": no region folder at "
                    + sourceRegion.getPath() + "; generating a void world");
            writeMarker(marker);
            return;
        }

        try {
            copyRegionFiles(sourceRegion, new File(dimFolder, "region"));
            writeMarker(marker);
            AdvancedRocketry.logger.info("TEMPLATE dimension " + dimId + ": imported region files from " + sourceRegion.getPath());
        } catch (IOException e) {
            // Do NOT write the marker: a partial copy must be retried on the next load.
            AdvancedRocketry.logger.error("TEMPLATE dimension " + dimId + ": failed to import region files from "
                    + sourceRegion.getPath(), e);
        }
    }

    /** Resolves a template name against {@link #templatesRoot()}, rejecting any path that escapes it. */
    private static File resolveSource(String template) {
        if (template == null || template.trim().isEmpty())
            return null;
        File root = templatesRoot();
        File candidate = new File(root, template.trim());
        try {
            Path rootPath = root.getCanonicalFile().toPath();
            Path candidatePath = candidate.getCanonicalFile().toPath();
            if (!candidatePath.startsWith(rootPath))
                return null; // traversal or absolute-path escape
            return candidatePath.toFile();
        } catch (IOException e) {
            return null;
        }
    }

    private static void copyRegionFiles(File sourceRegion, File targetRegion) throws IOException {
        if (!targetRegion.exists() && !targetRegion.mkdirs())
            throw new IOException("could not create " + targetRegion.getPath());
        File[] files = sourceRegion.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".mca") || name.endsWith(".mcr");
            }
        });
        if (files == null)
            return;
        for (File f : files) {
            Files.copy(f.toPath(), new File(targetRegion, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeMarker(File marker) {
        try {
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            marker.createNewFile();
        } catch (IOException e) {
            AdvancedRocketry.logger.warn("TEMPLATE: could not write import marker " + marker.getPath(), e);
        }
    }
}
