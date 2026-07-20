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
        return startInternal(root, /*bootstrap=*/true, /*cleanupOnClose=*/true);
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
        Files.createDirectories(root);
        boolean bootstrap = !Files.exists(root.resolve("eula.txt"));
        return startInternal(root, bootstrap, cleanupOnClose);
    }

    private static RealDedicatedServerHarness startInternal(Path root, boolean bootstrap,
                                                            boolean cleanupOnClose)
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
                    buildServerProperties(port).getBytes(StandardCharsets.UTF_8));
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
            if (cleanupOnClose) {
                deleteRecursively(root);
            }
        }
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
        // ~1/4 of physical RAM, which is what makes concurrent forks memory-infeasible. 1g fits a
        // modded 1.12 dedicated server; override per machine via the property.
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

    private static String buildServerProperties(int port) {
        String newline = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("enable-command-block=true").append(newline);
        builder.append("allow-nether=true").append(newline);
        builder.append("difficulty=1").append(newline);
        builder.append("gamemode=1").append(newline);
        builder.append("generate-structures=false").append(newline);
        builder.append("hardcore=false").append(newline);
        builder.append("level-name=world").append(newline);
        builder.append("level-seed=").append(newline);
        builder.append("level-type=DEFAULT").append(newline);
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

