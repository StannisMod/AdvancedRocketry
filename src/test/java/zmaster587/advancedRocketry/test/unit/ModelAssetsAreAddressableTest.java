package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Every model asset AR ships must be reachable by the name the game asks for.
 *
 * <p>A block or item model is looked up through a {@code ResourceLocation}
 * built from the registry name, and 1.12.2's {@code ResourceLocation} lowercases
 * its path on construction. A registry name may therefore be written
 * {@code mirrorPlatingAluminium} — the lookup that follows is for
 * {@code advancedrocketry:mirrorplatingaluminium}. In a development run the
 * assets are loose files on a case-insensitive Windows filesystem and a
 * camelCase file answers that lookup anyway; inside a built jar the entry names
 * are case-sensitive and the same file answers nothing, so the block ships with
 * no model at all.</p>
 *
 * <p>The trap has been walked into twice. Once through a toughness regex written
 * in the case the block was declared in, which then matched nothing and was
 * indistinguishable from a table that was simply not needed; and once through
 * five blockstates and one item model shipped camelCase, found by a review
 * rather than by anything mechanical. Both times the dev client looked correct.
 * This test is the mechanical half.</p>
 *
 * <p><b>What it cannot see.</b> Only the three directories whose file names are
 * derived from a registry name. It does not check that a needed asset EXISTS —
 * a block with no blockstate file at all passes here — and it says nothing about
 * textures, sounds or recipes, whose names come from string literals rather than
 * from the registry. The {@code models/**}{@code /models/} subdirectories are
 * excluded: those OBJ/MTL files are named verbatim by a field inside the JSON
 * that references them and are resolved by libVulpes' own loader, not by a
 * lowercased registry name.</p>
 */
public class ModelAssetsAreAddressableTest {

    private static final Path ASSETS =
            Paths.get("src", "main", "resources", "assets", "advancedrocketry");

    /** The directories whose file names must equal a lowercased registry name. */
    private static final String[] REGISTRY_NAMED = {"blockstates", "models/block", "models/item"};

    @Test
    public void everyModelAssetIsNamedInTheCaseTheLookupUses() throws IOException {
        List<String> offenders = new ArrayList<String>();

        for (String dir : REGISTRY_NAMED) {
            final Path root = ASSETS.resolve(dir.replace("/", java.io.File.separator));
            assertTrue("asset directory is missing, so this test is measuring nothing: " + root,
                    Files.isDirectory(root));

            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path candidate, BasicFileAttributes attrs) {
                    // The nested models/ subdirectory holds OBJ/MTL geometry named by a
                    // JSON field, not by a registry name — outside this contract.
                    if (!candidate.equals(root) && "models".equals(candidate.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (!name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
                        offenders.add(ASSETS.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        assertTrue("These model assets carry an uppercase letter, so the lowercased "
                        + "ResourceLocation the game builds from the registry name will not find "
                        + "them inside a jar — the block or item ships with no model: " + offenders,
                offenders.isEmpty());
    }

    /**
     * A block registered with an inventory item has a blockstate file to be drawn from.
     *
     * <p>The one-argument {@code LibVulpesBlocks.registerBlock} is the form that also builds an
     * {@code ItemBlock} and registers its item states, so the block reaches both the world and the
     * creative inventory and needs a model in each. Nothing refuses a registration that has no
     * blockstate: the block registers, crafts, places and renders as the purple-and-black missing
     * model. A whole gun part shipped that way — registered, tuned, and with no blockstate, no name
     * and no recipe, while its six siblings had all three.</p>
     *
     * <p><b>What it cannot see.</b> It reads the registration source rather than the live registry,
     * so a block registered anywhere other than {@code AdvancedRocketry.java}'s
     * {@code registerBlock(…setRegistryName("…"))} lines is invisible to it. It does not check
     * models, textures, lang names or recipes — only that the blockstate the lookup asks for is
     * present. The three-argument form is deliberately out of scope: it is how the fluid blocks are
     * registered, with a null ItemBlock, and their model comes from the custom
     * {@code FluidStateMapper} the client proxy installs rather than from a blockstate file.</p>
     */
    @Test
    public void everyBlockRegisteredWithAnItemHasABlockstate() throws IOException {
        String source = new String(Files.readAllBytes(
                Paths.get("src", "main", "java", "zmaster587", "advancedRocketry",
                        "AdvancedRocketry.java")), StandardCharsets.UTF_8);

        // The one-argument overload only: a closing paren straight after setRegistryName's, which
        // the three-argument fluid form (", null, false)") does not match.
        Matcher m = Pattern.compile(
                "registerBlock\\(\\s*AdvancedRocketryBlocks\\.\\w+\\s*\\.setRegistryName\\(\"([^\"]+)\"\\)\\s*\\)")
                .matcher(source);

        Path blockstates = ASSETS.resolve("blockstates");
        List<String> unmodelled = new ArrayList<String>();
        int scanned = 0;
        while (m.find()) {
            scanned++;
            String expected = m.group(1).toLowerCase(java.util.Locale.ROOT) + ".json";
            if (!Files.isRegularFile(blockstates.resolve(expected))) {
                unmodelled.add(m.group(1) + " (wants blockstates/" + expected + ")");
            }
        }

        assertTrue("the registration scan matched nothing, so this test is measuring nothing",
                scanned > 50);
        assertTrue("these blocks are registered with an inventory item and have no blockstate, so "
                + "they place and stack as the missing-model checkerboard: " + unmodelled,
                unmodelled.isEmpty());
    }
}
