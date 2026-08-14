package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the physics-substrate boundary: the physics engine is a SWAPPABLE substrate that AR reaches
 * only through its own port, so {@code org.valkyrienskies} types must stay confined to the port
 * package and the mixins that weave into the engine — never leak into AR business logic (space,
 * crew, tiles, events, GUI).
 *
 * <p>Why a test rather than discipline: a stray engine import compiles perfectly and produces no
 * warning, so the violation is SILENT. It is also the invariant that decides how expensive a future
 * engine-version migration is — every business-logic file that names an engine type is a file that
 * migration would have to touch.</p>
 *
 * <p>This is an architectural contract, not an impl detail: it is asserted by ROLE (which packages
 * may name engine types), not by a hardcoded file list, so renaming or splitting a port class is
 * free while a new importer in business code fails.</p>
 */
public class VSBoundaryContainmentTest {

    /** Matched as a prefix, so {@code org.valkyrienskies.*} in any form counts. */
    private static final String ENGINE_IMPORT = "import org.valkyrienskies";

    private static final String PKG = "zmaster587/advancedRocketry/";

    @Test
    public void physicsEngineTypesStayConfinedToThePortAndMixins() throws IOException {
        Path sourceRoot = resolveMainSourceRoot();
        List<String> importers = enginetypeImporters(sourceRoot);

        // Instrument-fire control FIRST: the port itself names engine types, so an empty result
        // means the scan is broken (wrong root, unreadable files) — not that the invariant holds.
        // Without this leg a silently non-functional walk would read as a permanent pass.
        assertFalse("the boundary scan found NO file importing the engine at all, so the scan"
                        + " itself is broken (root=" + sourceRoot.toAbsolutePath() + ");"
                        + " the port class is expected to import it",
                importers.isEmpty());

        List<String> offenders = importers.stream()
                .filter(rel -> !mayNameEngineTypes(rel))
                .collect(Collectors.toList());

        assertTrue("engine types must stay inside the port package and the engine mixins, but "
                        + offenders.size() + " other file(s) import them: " + offenders
                        + " — add the operation to the port instead of reaching for an engine type"
                        + " here (all importers seen: " + importers + ")",
                offenders.isEmpty());
    }

    /**
     * The roles allowed to name engine types: the port package (the bridge + the flight backend),
     * the mixins that weave into engine internals, and the mod entry point that gates/registers the
     * integration.
     */
    private static boolean mayNameEngineTypes(String relativePath) {
        return relativePath.startsWith(PKG + "integration/vs/")
                || relativePath.startsWith(PKG + "mixin/")
                || relativePath.equals(PKG + "AdvancedRocketry.java");
    }

    private static List<String> enginetypeImporters(Path sourceRoot) throws IOException {
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            javaFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        List<String> importers = new ArrayList<>();
        for (Path file : javaFiles) {
            String body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (body.contains(ENGINE_IMPORT)) {
                importers.add(sourceRoot.relativize(file).toString().replace('\\', '/'));
            }
        }
        java.util.Collections.sort(importers);
        return importers;
    }

    /**
     * Locates {@code src/main/java} by walking up from the working directory, so the guard works
     * whether the test runs from the project dir or a nested one. Fails loudly rather than skipping:
     * a guard that quietly does nothing is worse than no guard.
     */
    private static Path resolveMainSourceRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++) {
            Path candidate = dir.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("could not locate src/main/java from working directory "
                + Paths.get("").toAbsolutePath() + "; the boundary guard cannot run");
    }
}
