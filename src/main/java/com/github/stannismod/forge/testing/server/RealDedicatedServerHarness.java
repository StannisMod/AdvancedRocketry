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

    private RealDedicatedServerHarness(Path root, int port, TestClient client, Thread readerThread) {
        this.root = root;
        this.port = port;
        this.client = client;
        this.readerThread = readerThread;
    }

    public static RealDedicatedServerHarness start() throws IOException, InterruptedException {
        Path root = Files.createTempDirectory("forge-dedicated-server-");
        int port = reservePort();
        bootstrapServerFiles(root, port);
        Process process = launchServer(root, port);

        List<String> transcript = new ArrayList<>();
        Thread readerThread = startReader(process, transcript);
        TestClient client = new TestClient(process, TestClient.newWriter(process), transcript);
        RealDedicatedServerHarness harness = new RealDedicatedServerHarness(root, port, client, readerThread);
        client.awaitOutputContaining("For help, type \"help\" or \"?\"", Duration.ofMinutes(3));
        return harness;
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
            deleteRecursively(root);
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
        Path javaBinary = javaExe == null
                ? Paths.get("java.exe")
                : Paths.get(javaExe, "bin", "java.exe");
        String launcherClass = System.getProperty(PROP_LAUNCHER_CLASS, "GradleStartServer");
        boolean legacyArgs = Boolean.parseBoolean(System.getProperty(PROP_LEGACY_ARGS, "true"));

        List<String> command = new ArrayList<>();
        command.add(javaBinary.toString());
        command.add("-Djava.awt.headless=true");
        command.add("-Dforge.test.server=true");
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
            // (already written by bootstrapServerFiles) and gameDir is the cwd.
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

    private static void bootstrapServerFiles(Path root, int port) throws IOException {
        Files.write(root.resolve("eula.txt"), java.util.Collections.singletonList("eula=true"), StandardCharsets.UTF_8);
        Files.write(root.resolve("server.properties"), buildServerProperties(port).getBytes(StandardCharsets.UTF_8));
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

