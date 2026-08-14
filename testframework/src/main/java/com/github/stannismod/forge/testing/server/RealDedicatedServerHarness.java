package com.github.stannismod.forge.testing.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class RealDedicatedServerHarness implements AutoCloseable {

    private final Path root;
    private final int port;
    private final TestClient client;
    private final Thread readerThread;
    private final boolean cleanupOnClose;

    private RealDedicatedServerHarness(Path root, int port, TestClient client, Thread readerThread,
                                       boolean cleanupOnClose) {
        this.root = root;
        this.port = port;
        this.client = client;
        this.readerThread = readerThread;
        this.cleanupOnClose = cleanupOnClose;
    }

    /**
     * Starts a fresh server in a temporary work directory. The directory is
     * deleted recursively when {@link #close()} is called — use this for
     * scenarios that don't need to inspect or reuse the world after close.
     */
    public static RealDedicatedServerHarness start() throws IOException, InterruptedException {
        Path root = Files.createTempDirectory("forge-dedicated-server-");
        return startInternal(root, /*bootstrap=*/true, /*cleanupOnClose=*/true,
                /*flatTerrain=*/false);
    }

    /**
     * The same, on a FLAT world whose surface is exactly y=64 everywhere ({@link #FLAT_PRESET}).
     *
     * <p>Opt-in, and it was tried as the default on 2026-08-14 — see the preset's own javadoc for
     * what that measured. Short version: it removes the landscape variable, and it costs a bigger
     * server heap, ~15 % more wall clock at that heap, and three reds nobody has explained. Reach
     * for it when a test genuinely wants a world with no landscape in it, not as a general cure.</p>
     */
    public static RealDedicatedServerHarness startWithFlatTerrain()
            throws IOException, InterruptedException {
        Path root = Files.createTempDirectory("forge-dedicated-server-");
        return startInternal(root, /*bootstrap=*/true, /*cleanupOnClose=*/true,
                /*flatTerrain=*/true);
    }

    /**
     * Starts a server using the supplied work directory.
     *
     * <p>Useful for persistence-restart scenarios: start a fresh server, mutate
     * world state, close it, then start again with the same {@code root} to
     * verify the state survived save/load.</p>
     *
     * @param root           directory to use as the server's gameDir / world root.
     *                       If empty, framework files (eula.txt, server.properties)
     *                       are bootstrapped automatically. If it contains a
     *                       {@code server.properties} from a previous run, the
     *                       file is rewritten with a fresh port; the rest of the
     *                       directory (world, config, mods) is preserved.
     * @param cleanupOnClose if {@code true}, recursively deletes {@code root} on
     *                       {@link #close()}. Pass {@code false} when you intend
     *                       to restart against the same dir.
     */
    public static RealDedicatedServerHarness startWith(Path root, boolean cleanupOnClose)
            throws IOException, InterruptedException {
        return startWith(root, cleanupOnClose, /*flatTerrain=*/false);
    }

    /** @see #startWith(Path, boolean) and {@link #startWithFlatTerrain()} */
    public static RealDedicatedServerHarness startWith(Path root, boolean cleanupOnClose,
                                                       boolean flatTerrain)
            throws IOException, InterruptedException {
        Files.createDirectories(root);
        boolean bootstrap = !Files.exists(root.resolve("eula.txt"));
        return startInternal(root, bootstrap, cleanupOnClose, flatTerrain);
    }

    private static RealDedicatedServerHarness startInternal(Path root, boolean bootstrap,
                                                            boolean cleanupOnClose,
                                                            boolean flatTerrain)
            throws IOException, InterruptedException {
        if (bootstrap) {
            writeEula(root);
        }
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_PORT_BIND_ATTEMPTS; attempt++) {
            int port = reservePort();
            // Rewrite server.properties with the freshly reserved port on every
            // attempt — needed both for the first iteration and for retries
            // after a child JVM lost the TOCTOU race to bind it.
            Files.write(root.resolve("server.properties"),
                    buildServerProperties(port, flatTerrain).getBytes(StandardCharsets.UTF_8));
            Process process = launchServer(root, port);
            List<String> transcript = new ArrayList<>();
            Thread readerThread = startReader(process, transcript);
            TestClient client = new TestClient(process, TestClient.newWriter(process), transcript);
            BootOutcome outcome;
            try {
                // Load-scaled: N concurrent modded boots contend on disk + CPU.
                outcome = awaitReadyOrBindFailure(process, transcript,
                        com.github.stannismod.forge.testing.TestTimeouts.scaled(Duration.ofMinutes(3)));
            } catch (RuntimeException | InterruptedException failure) {
                destroyAndJoin(process, readerThread);
                throw failure;
            }
            if (outcome == BootOutcome.READY) {
                return new RealDedicatedServerHarness(root, port, client, readerThread, cleanupOnClose);
            }
            destroyAndJoin(process, readerThread);
            lastFailure = new IOException("BindException on port " + port
                    + " (attempt " + attempt + " of " + MAX_PORT_BIND_ATTEMPTS + ")");
        }
        throw new IOException("Failed to start dedicated server after "
                + MAX_PORT_BIND_ATTEMPTS + " port-bind attempts", lastFailure);
    }

    private static final int MAX_PORT_BIND_ATTEMPTS = 3;

    /** Fixed world seed, so harness terrain is identical from run to run. See {@link #levelSeed()}. */
    private static final String DEFAULT_LEVEL_SEED = "forge-test-framework";

    private enum BootOutcome { READY, BIND_FAILED }

    private static BootOutcome awaitReadyOrBindFailure(Process process, List<String> transcript,
                                                       Duration timeout) throws InterruptedException {
        final String readyMarker = "For help, type \"help\" or \"?\"";
        final String bindMarker = "BindException";
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        int index = 0;
        while (System.nanoTime() < deadlineNanos) {
            synchronized (transcript) {
                while (index < transcript.size()) {
                    String line = transcript.get(index++);
                    if (line.contains(readyMarker)) {
                        return BootOutcome.READY;
                    }
                    if (line.contains(bindMarker)) {
                        return BootOutcome.BIND_FAILED;
                    }
                }
                if (!process.isAlive()) {
                    // Child exited without printing the ready marker. If a bind
                    // failure is visible in the tail, treat the attempt as a
                    // port collision and let the caller retry; otherwise this
                    // is a real crash and we surface it as before.
                    for (int i = transcript.size() - 1;
                         i >= Math.max(0, transcript.size() - 50); i--) {
                        if (transcript.get(i).contains(bindMarker)) {
                            return BootOutcome.BIND_FAILED;
                        }
                    }
                    throw new AssertionError("Server process exited (code="
                            + process.exitValue() + ") before becoming ready. Recent output: "
                            + tailOf(transcript));
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                transcript.wait(Math.min(waitMillis, 250L));
            }
        }
        throw new AssertionError("Timed out waiting for server to become ready. Recent output: "
                + tailOf(transcript));
    }

    private static String tailOf(List<String> transcript) {
        synchronized (transcript) {
            int from = Math.max(0, transcript.size() - 25);
            StringBuilder builder = new StringBuilder();
            for (int i = from; i < transcript.size(); i++) {
                if (i > from) {
                    builder.append(System.lineSeparator());
                }
                builder.append(transcript.get(i));
            }
            return builder.toString();
        }
    }

    private static void destroyAndJoin(Process process, Thread readerThread) {
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
        try {
            readerThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public Path root() {
        return root;
    }

    public int port() {
        return port;
    }

    public TestClient client() {
        return client;
    }

    @Override
    public void close() throws IOException {
        try {
            client.close();
        } finally {
            try {
                readerThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            // Preserve the server log at a stable location BEFORE the temp dir is wiped. The
            // client harness has done this for its side for a while; the server side had nothing,
            // so every diagnostic the SERVER writes — the mod's own ERROR/WARN reports about a
            // crossing, a reseat, a tick that gave up — died with the directory, and a failing
            // client e2e could only ever be read from the client's half of the story. `close()`
            // has already stopped the child (TestClient.close sends `stop` and waits), so the log
            // is flushed by the time we copy.
            try {
                Path serverLog = root.resolve("logs").resolve("latest.log");
                if (Files.isRegularFile(serverLog)) {
                    Files.copy(serverLog, preservedLogPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // No log4j file appender in this launcher layout: fall back to the console
                    // transcript we have been reading all along, so the preserved path is never
                    // silently empty (an absent file reads as "the server said nothing").
                    Files.write(preservedLogPath(), client.transcriptSnapshot(),
                            StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
                // Best-effort only — never block close on preserve failure.
            }
            if (cleanupOnClose) {
                deleteRecursively(root);
            }
        }
    }

    /**
     * The stable post-mortem location of the last server log, one file PER TEST-JVM (the PID
     * suffix), mirroring the client harness's {@code forge-test-client-last-pid*.log}: a single
     * fixed name would let concurrent forks clobber each other's diagnostics, exactly when
     * parallel runs make failures interesting.
     *
     * <p>Last-boot-wins within one JVM. A class whose failing test is not the last one to run
     * therefore loses its log — filter to the single method when the log is the evidence.</p>
     */
    public static Path preservedLogPath() {
        String jvm = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = jvm.indexOf('@');
        String pid = at > 0 ? jvm.substring(0, at) : jvm;
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "forge-test-server-last-pid" + pid + ".log");
    }

    /**
     * System property naming the launcher main class. Default {@code GradleStartServer}
     * (RFG / FG4 layout). Set to e.g. {@code net.minecraftforge.legacydev.MainServer}
     * for ForgeGradle 6 projects.
     */
    public static final String PROP_LAUNCHER_CLASS = "forge.test.launcher.class.server";

    /**
     * System property naming the assets dir passed via {@code --assetsDir}. Default
     * resolves to {@code <gradle-user-home>/caches/retro_futura_gradle/assets} for
     * RFG. Set to {@code <gradle-user-home>/caches/forge_gradle/assets} for FG6.
     * Ignored when {@link #PROP_LEGACY_ARGS} is {@code false}.
     */
    public static final String PROP_ASSETS_DIR = "forge.test.assets.dir";

    /**
     * System property toggling the RFG-style {@code --version / --assetsDir / --username / ...}
     * arg list. Default {@code true} (RFG behavior). Set to {@code false} for
     * launchers that take no args (e.g. FG6's {@code MainServer} which reads cwd).
     */
    public static final String PROP_LEGACY_ARGS = "forge.test.launcher.legacyArgs";

    private static Process launchServer(Path root, int port) throws IOException {
        String javaExe = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String javaName = windows ? "java.exe" : "java";
        Path javaBinary = javaExe == null
                ? Paths.get(javaName)
                : Paths.get(javaExe, "bin", javaName);
        String launcherClass = System.getProperty(PROP_LAUNCHER_CLASS, "GradleStartServer");
        boolean legacyArgs = Boolean.parseBoolean(System.getProperty(PROP_LEGACY_ARGS, "true"));

        List<String> command = new ArrayList<>();
        command.add(javaBinary.toString());
        // Cap the child heap: without an explicit -Xmx, Java 8 ergonomics grant EACH server child
        // ~1/4 of physical RAM, which is what makes concurrent forks memory-infeasible.
        //
        // 1g fits a modded 1.12 dedicated server on generated terrain, which is what this harness
        // hands out. A FLAT world does not fit in it — measured 2026-08-14, one class, terrain and
        // heap the only variables: flat@1g 12 of 13 failed with OutOfMemoryError, generated@1g 1 of
        // 12, flat@2g 1 of 12. Why is unexplained (a flat chunk holds FEWER non-empty sections, so
        // the storage argument is backwards); if you turn the flat world on, raise this with
        // -PserverXmx=2g and expect the tier to get slower, not faster.
        command.add("-Xmx" + System.getProperty("forge.test.server.xmx", "1g"));
        command.add("-Djava.awt.headless=true");
        command.add("-Dforge.test.server=true");
        command.add("-D" + com.github.stannismod.forge.testing.TestTimeouts.PROP_FACTOR + "="
                + com.github.stannismod.forge.testing.TestTimeouts.factor());
        command.add("-cp");
        command.add(Objects.requireNonNull(System.getProperty("java.class.path"), "java.class.path"));
        command.add(launcherClass);

        if (legacyArgs) {
            String assetsDirProp = System.getProperty(PROP_ASSETS_DIR);
            Path assetsDir = assetsDirProp != null
                    ? Paths.get(assetsDirProp)
                    : gradleUserHome().resolve("caches").resolve("retro_futura_gradle").resolve("assets");
            command.add("--nogui");
            command.add("--gameDir");
            command.add(root.toAbsolutePath().toString());
            command.add("--assetsDir");
            command.add(assetsDir.toAbsolutePath().toString());
            command.add("--version");
            command.add("FML_DEV");
            command.add("--assetIndex");
            command.add("1.12.2");
            command.add("--username");
            command.add("Developer");
            command.add("--accessToken");
            command.add("FML");
            command.add("--userProperties");
            command.add("{}");
            command.add("--uuid");
            command.add(UUID.randomUUID().toString().replace("-", ""));
            command.add("--port");
            command.add(String.valueOf(port));
            command.add("--universe");
            command.add(root.toAbsolutePath().toString());
            command.add("--world");
            command.add("world");
        } else {
            // FG6's net.minecraftforge.legacydev.MainServer takes no args — it reads
            // working directory + server.properties. Port comes from server.properties
            // (already written above in startInternal) and gameDir is the cwd.
            command.add("--nogui");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static Path gradleUserHome() {
        String env = System.getenv("GRADLE_USER_HOME");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim());
        }
        return Paths.get(System.getProperty("user.home"), ".gradle");
    }

    private static int reservePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static Thread startReader(Process process, List<String> transcript) {
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    synchronized (transcript) {
                        transcript.add(line);
                        transcript.notifyAll();
                    }
                }
            } catch (IOException ignored) {
                // The process is terminating or the stream has already been closed.
            }
        }, "forge-dedicated-server-log-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static void writeEula(Path root) throws IOException {
        Files.write(root.resolve("eula.txt"),
                java.util.Collections.singletonList("eula=true"), StandardCharsets.UTF_8);
    }

    /**
     * The world seed every harness world is generated from.
     *
     * <p>Each harness boot creates a FRESH world directory (see the temp root above), so an
     * empty {@code level-seed} — the previous behaviour — meant vanilla rolled a NEW RANDOM
     * seed for every single test run. Terrain under a fixture's coordinates then varied
     * run to run: a spot that was open air in one run sat inside a hillside in the next, which
     * surfaces as a test that "flakes" while the code under test is perfectly deterministic.
     * That cost real diagnosis time (a player suffocating inside terrain read as a space suit
     * failing to grant vacuum immunity).</p>
     *
     * <p>Pinning it makes terrain reproducible: a fixture that collides with the landscape now
     * fails EVERY run — loud and fixable — instead of one run in five. Override with
     * {@code -Dforge.test.level.seed=<value>} for a different (still fixed) landscape, or with
     * {@code random} to restore vanilla's per-run roll — which is how you check that a fixture is
     * not quietly seed-dependent, and how the determinism guard test proves it can still fail.</p>
     */
    private static String levelSeed() {
        String seed = System.getProperty("forge.test.level.seed");
        if (seed == null || seed.isEmpty()) {
            return DEFAULT_LEVEL_SEED;
        }
        // An empty level-seed is vanilla's "roll a new one"; this is the explicit way to ask.
        return "random".equalsIgnoreCase(seed.trim()) ? "" : seed;
    }

    /**
     * A superflat preset whose surface is exactly {@code y=64}: bedrock + 62 stone + grass is 64
     * blocks, so the topmost solid block is y=63 and the first air is y=64 — the height fixtures
     * across this suite build at, because on a generated world that is roughly sea level.
     *
     * <p><b>Tried as the harness default on 2026-08-14 and rejected on the numbers.</b> The appeal
     * is real — it removes the landscape variable that had four client fixtures assembling ships
     * inside a mountain — but the bill is: a dedicated server that OOMs at the 1g heap this harness
     * had used for years (12 of 13 methods of one class against 1 of 12 on generated terrain);
     * ~15 % MORE wall clock once the heap is raised enough to survive (server tier 29m14s against
     * 25m24s, client 25m46s against 22m19s — the apparent speed-up at 1g was measured on runs that
     * were dying); and three client reds that generated terrain does not produce and nobody has
     * explained. WHY a flat world needs more heap is itself unexplained: a flat chunk holds FEWER
     * non-empty sections than a generated one, so the obvious storage argument is backwards. The
     * leading unmeasured hypothesis is CONTACT — every hull rests on a continuous solid plane and
     * the per-tick collision set is allocation churn — and a heap histogram would settle it.</p>
     *
     * <p>Format is vanilla's 1.12 superflat preset: {@code version;layers-bottom-up;biomeId}.
     * Biome 1 is plains.</p>
     */
    private static final String FLAT_PRESET = "3;minecraft:bedrock,62*minecraft:stone,minecraft:grass;1";

    /**
     * Global switch: {@code -PlevelType=FLAT} gives every harness world the flat preset above. It
     * is the one-run way to ask "is the landscape the reason this test fails?", and it is how the
     * OOM above was attributed — the answer took one run because the knob existed.
     */
    private static boolean flatTerrainForced() {
        return "FLAT".equalsIgnoreCase(System.getProperty("forge.test.level.type", ""));
    }

    private static String buildServerProperties(int port, boolean flatTerrain) {
        String newline = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("enable-command-block=true").append(newline);
        builder.append("allow-nether=true").append(newline);
        builder.append("difficulty=1").append(newline);
        builder.append("gamemode=1").append(newline);
        builder.append("generate-structures=false").append(newline);
        builder.append("hardcore=false").append(newline);
        builder.append("level-name=world").append(newline);
        builder.append("level-seed=").append(levelSeed()).append(newline);
        boolean flat = flatTerrain || flatTerrainForced();
        builder.append("level-type=").append(flat ? "FLAT" : "DEFAULT").append(newline);
        builder.append("generator-settings=").append(flat ? FLAT_PRESET : "").append(newline);
        builder.append("max-tick-time=-1").append(newline);
        builder.append("motd=Forge Test").append(newline);
        builder.append("network-compression-threshold=256").append(newline);
        builder.append("online-mode=false").append(newline);
        builder.append("op-permission-level=4").append(newline);
        builder.append("pvp=false").append(newline);
        builder.append("spawn-animals=false").append(newline);
        builder.append("spawn-monsters=false").append(newline);
        builder.append("spawn-npcs=false").append(newline);
        builder.append("spawn-protection=0").append(newline);
        builder.append("server-ip=").append(newline);
        builder.append("server-port=").append(port).append(newline);
        builder.append("snooper-enabled=false").append(newline);
        builder.append("use-native-transport=false").append(newline);
        builder.append("view-distance=4").append(newline);
        return builder.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

