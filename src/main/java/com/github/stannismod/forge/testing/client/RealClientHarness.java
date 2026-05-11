package com.github.stannismod.forge.testing.client;

import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class RealClientHarness implements AutoCloseable {

    private static final String CLIENT_USERNAME = "ForgeTestClient";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    private static final int NORMAL_PRIORITY_CLASS = 0x00000020;
    private static final int CREATE_NEW_PROCESS_GROUP = 0x00000200;
    private static final int WAIT_TIMEOUT = 0x00000102;
    private static final int WAIT_FAILED = 0xFFFFFFFF;
    private static final int STILL_ACTIVE = 259;
    private static final int STARTF_USESHOWWINDOW = 0x00000001;
    private static final int SW_SHOWNOACTIVATE = 4;

    private final Path root;
    private final Process process;
    private final ClientBot bot;
    private final Path clientLogFile;

    private RealClientHarness(Path root, Process process, ClientBot bot, Path clientLogFile) {
        this.root = root;
        this.process = process;
        this.bot = bot;
        this.clientLogFile = clientLogFile;
    }

    public static RealClientHarness start(RealDedicatedServerHarness serverHarness) throws Exception {
        Path root = Files.createTempDirectory("forge-client-");
        Files.createDirectories(root.resolve("resourcepacks"));
        bootstrapClientFiles(root);

        int controlPort = reservePort();
        Path clientLogFile = root.resolve("client.log");
        Process process = null;
        try (java.net.ServerSocket controlSocket = openControlSocket(controlPort)) {
            process = launchClient(root, serverHarness.port(), controlPort, clientLogFile);

            ClientBot bot = awaitClientBot(controlSocket);
            bot.waitForWorld();
            return new RealClientHarness(root, process, bot, clientLogFile);
        } catch (Exception exception) {
            shutdownProcess(process);
            deleteRecursively(root);
            throw new IOException("Failed to start real client harness. Recent client log:\n" + tailFile(clientLogFile), exception);
        }
    }

    public Path root() {
        return root;
    }

    public ClientBot bot() {
        return bot;
    }

    @Override
    public void close() throws IOException {
        try {
            if (bot != null) {
                bot.close();
            }
        } finally {
            try {
                shutdownProcess(process);
            } finally {
                deleteRecursively(root);
            }
        }
    }

    private static Process launchClient(Path root, int serverPort, int controlPort, Path clientLogFile) throws IOException {
        Path javaBinary = resolveJavaBinary();
        Path assetsDir = gradleUserHome().resolve("caches").resolve("retro_futura_gradle").resolve("assets");
        Path nativesDir = resolveNativesDir();

        String currentClassPath = Objects.requireNonNull(System.getProperty("java.class.path"), "java.class.path");
        Path libDir = Files.createTempDirectory(root, "client-libs-");
        String launcherClassPath = buildLauncherClassPath(currentClassPath, libDir);

        List<String> javaArgs = new ArrayList<>();
        javaArgs.add("-Djava.awt.headless=true");
        javaArgs.add("-Dforge.test.client=true");
        javaArgs.add("-Dforge.test.client.port=" + controlPort);
        javaArgs.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        javaArgs.add("-Dorg.lwjgl.librarypath=" + nativesDir.toAbsolutePath());
        javaArgs.add("-Dforge.test.client.logFile=" + clientLogFile.toAbsolutePath());
        javaArgs.add("-cp");
        javaArgs.add(launcherClassPath);
        javaArgs.add("GradleStart");
        javaArgs.add("--server");
        javaArgs.add("127.0.0.1");
        javaArgs.add("--port");
        javaArgs.add(String.valueOf(serverPort));
        javaArgs.add("--gameDir");
        javaArgs.add(root.toAbsolutePath().toString());
        javaArgs.add("--assetsDir");
        javaArgs.add(assetsDir.toAbsolutePath().toString());
        javaArgs.add("--resourcePackDir");
        javaArgs.add(root.resolve("resourcepacks").toAbsolutePath().toString());
        javaArgs.add("--version");
        javaArgs.add("FML_DEV");
        javaArgs.add("--assetIndex");
        javaArgs.add("1.12.2");
        javaArgs.add("--username");
        javaArgs.add(CLIENT_USERNAME);
        javaArgs.add("--accessToken");
        javaArgs.add("FML");
        javaArgs.add("--userProperties");
        javaArgs.add("{}");
        javaArgs.add("--profileProperties");
        javaArgs.add("{}");
        javaArgs.add("--uuid");
        javaArgs.add(UUID.nameUUIDFromBytes(("OfflinePlayer:" + CLIENT_USERNAME).getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""));
        javaArgs.add("--width");
        javaArgs.add("640");
        javaArgs.add("--height");
        javaArgs.add("480");

        List<String> command = new ArrayList<>();
        command.add(javaBinary.toString());
        command.addAll(javaArgs);

        if (WINDOWS) {
            try {
                return launchWindowsClient(root, javaBinary, javaArgs);
            } catch (IOException nativeLaunchFailure) {
                ProcessBuilder fallback = new ProcessBuilder(command);
                fallback.directory(root.toFile());
                fallback.redirectErrorStream(true);
                Process process = fallback.start();
                return new LoggedProcess(process, clientLogFile);
            }
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static ClientBot awaitClientBot(java.net.ServerSocket serverSocket) throws IOException {
        serverSocket.setSoTimeout((int) TimeUnit.MINUTES.toMillis(2));
        java.net.Socket socket = serverSocket.accept();
        return new ClientBot(socket);
    }

    private static Path resolveJavaBinary() throws IOException {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return Paths.get(WINDOWS ? "javaw.exe" : "java");
        }

        List<Path> candidates = new ArrayList<>();
        Path javaHomePath = Paths.get(javaHome);
        if (WINDOWS) {
            candidates.add(javaHomePath.resolve("bin").resolve("javaw.exe"));
            candidates.add(javaHomePath.resolve("bin").resolve("java.exe"));
            Path parent = javaHomePath.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("bin").resolve("javaw.exe"));
                candidates.add(parent.resolve("bin").resolve("java.exe"));
            }
        } else {
            candidates.add(javaHomePath.resolve("bin").resolve("java"));
            Path parent = javaHomePath.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("bin").resolve("java"));
            }
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        throw new IOException("Unable to locate Java launcher for java.home=" + javaHome + ", candidates=" + candidates);
    }

    private static Process launchWindowsClient(Path root, Path javaBinary, List<String> javaArgs) throws IOException {
        List<String> commandLineArgs = new ArrayList<>();
        commandLineArgs.add(javaBinary.toAbsolutePath().toString());
        commandLineArgs.addAll(javaArgs);

        STARTUPINFO startupInfo = new STARTUPINFO();
        startupInfo.cb = startupInfo.size();
        startupInfo.dwFlags = STARTF_USESHOWWINDOW;
        startupInfo.wShowWindow = (short) SW_SHOWNOACTIVATE;

        PROCESS_INFORMATION processInformation = new PROCESS_INFORMATION();
        startupInfo.write();
        processInformation.write();
        boolean created = Kernel32Native.INSTANCE.CreateProcessW(
                new WString(javaBinary.toAbsolutePath().toString()),
                new WString(buildCommandLine(commandLineArgs)),
                null,
                null,
                false,
                NORMAL_PRIORITY_CLASS | CREATE_NEW_PROCESS_GROUP,
                Pointer.NULL,
                new WString(root.toAbsolutePath().toString()),
                startupInfo,
                processInformation);

        if (!created) {
            throw new IOException("Failed to create client process at "
                    + javaBinary.toAbsolutePath()
                    + ", lastError="
                    + Native.getLastError());
        }

        processInformation.read();
        Kernel32Native.INSTANCE.CloseHandle(processInformation.hThread);
        return new NativeClientProcess(processInformation.hProcess, processInformation.dwProcessId);
    }

    private static String buildCommandLine(List<String> javaArgs) {
        StringBuilder commandLine = new StringBuilder();
        for (int i = 0; i < javaArgs.size(); i++) {
            String arg = javaArgs.get(i);
            if (i > 0) {
                commandLine.append(' ');
            }
            commandLine.append(quoteForCommandLine(arg));
        }
        return commandLine.toString();
    }

    private static String quoteForCommandLine(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        int backslashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                for (int j = 0; j < backslashes * 2 + 1; j++) {
                    builder.append('\\');
                }
                builder.append('"');
                backslashes = 0;
                continue;
            }
            for (int j = 0; j < backslashes; j++) {
                builder.append('\\');
            }
            backslashes = 0;
            builder.append(c);
        }
        for (int j = 0; j < backslashes; j++) {
            builder.append('\\');
        }
        builder.append('"');
        return builder.toString();
    }

    private static void bootstrapClientFiles(Path root) throws IOException {
        List<String> options = new ArrayList<>();
        options.add("pauseOnLostFocus:false");
        options.add("fboEnable:true");
        options.add("renderDistance:8");
        Files.write(root.resolve("options.txt"), options, StandardCharsets.UTF_8);
    }

    private static int reservePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static java.net.ServerSocket openControlSocket(int port) throws IOException {
        java.net.ServerSocket socket = new java.net.ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new java.net.InetSocketAddress("127.0.0.1", port));
        return socket;
    }

    private static Path gradleUserHome() {
        String env = System.getenv("GRADLE_USER_HOME");
        if (env != null && !env.trim().isEmpty()) {
            return Paths.get(env.trim());
        }
        return Paths.get(System.getProperty("user.home"), ".gradle");
    }

    private static Path resolveNativesDir() throws IOException {
        Path[] candidates = new Path[] {
                gradleUserHome().resolve("caches").resolve("minecraft").resolve("net").resolve("minecraft").resolve("natives").resolve("1.12.2"),
                Paths.get(System.getProperty("user.home"), ".gradle").resolve("caches").resolve("minecraft").resolve("net").resolve("minecraft").resolve("natives").resolve("1.12.2")
        };

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("lwjgl64.dll")) || Files.isRegularFile(candidate.resolve("lwjgl.dll"))) {
                return candidate;
            }
        }

        throw new IOException("Unable to locate LWJGL natives directory in any known Gradle cache location");
    }

    private static Path findCachedJar(String fileName) throws IOException {
        Path cacheRoot = gradleUserHome().resolve("caches").resolve("modules-2").resolve("files-2.1");
        try (java.util.stream.Stream<Path> stream = Files.walk(cacheRoot)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && fileName.equals(path.getFileName().toString()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Missing cached jar: " + fileName + " under " + cacheRoot));
        }
    }

    private static String buildLauncherClassPath(String currentClassPath, Path libDir) throws IOException {
        List<String> entries = new ArrayList<>();
        String[] split = currentClassPath.split(java.io.File.pathSeparator);
        for (String rawEntry : split) {
            if (rawEntry == null || rawEntry.trim().isEmpty()) {
                continue;
            }

            Path entryPath = Paths.get(rawEntry);
            if (Files.isDirectory(entryPath)) {
                entries.add(entryPath.toAbsolutePath().toString());
                continue;
            }

            if (rawEntry.endsWith(".jar")) {
                Path target = libDir.resolve(entryPath.getFileName());
                Files.copy(entryPath, target, StandardCopyOption.REPLACE_EXISTING);
                continue;
            }

            entries.add(entryPath.toAbsolutePath().toString());
        }

        copyCachedJar(libDir, "lwjgl-2.9.4-nightly-20150209.jar");
        copyCachedJar(libDir, "lwjgl_util-2.9.4-nightly-20150209.jar");
        copyCachedJar(libDir, "jinput-2.0.5.jar");
        copyCachedJar(libDir, "librarylwjglopenal-20100824.jar");
        copyCachedJar(libDir, "lwjgl-platform-2.9.4-nightly-20150209-natives-windows.jar");
        copyCachedJar(libDir, "jinput-platform-2.0.5-natives-windows.jar");

        entries.add(libDir.toAbsolutePath() + java.io.File.separator + "*");
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static void copyCachedJar(Path libDir, String fileName) throws IOException {
        Path source = findCachedJar(fileName);
        Path target = libDir.resolve(source.getFileName());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static String tailFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return "";
            }
            int from = Math.max(0, lines.size() - 40);
            StringBuilder builder = new StringBuilder();
            for (int i = from; i < lines.size(); i++) {
                if (i > from) {
                    builder.append(System.lineSeparator());
                }
                builder.append(lines.get(i));
            }
            return builder.toString();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void shutdownProcess(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } finally {
            if (process instanceof NativeClientProcess) {
                ((NativeClientProcess) process).closeHandle();
            }
        }
    }

    private static final class NativeClientProcess extends Process {
        private final Pointer processHandle;
        private final int processId;

        private NativeClientProcess(Pointer processHandle, int processId) {
            this.processHandle = processHandle;
            this.processId = processId;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    // No stdin pipe.
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            int waitResult = Kernel32Native.INSTANCE.WaitForSingleObject(processHandle, -1);
            if (waitResult == WAIT_FAILED) {
                throw new IllegalStateException("WaitForSingleObject failed for client process " + processId);
            }
            return exitValue();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long timeoutMillis = unit.toMillis(timeout);
            int waitResult = Kernel32Native.INSTANCE.WaitForSingleObject(processHandle, (int) Math.min(Integer.MAX_VALUE, timeoutMillis));
            if (waitResult == WAIT_FAILED) {
                throw new IllegalStateException("WaitForSingleObject failed for client process " + processId);
            }
            return waitResult != WAIT_TIMEOUT;
        }

        @Override
        public int exitValue() {
            IntByReference code = new IntByReference();
            if (!Kernel32Native.INSTANCE.GetExitCodeProcess(processHandle, code)) {
                throw new IllegalThreadStateException("Unable to query exit code for client process " + processId);
            }
            int value = code.getValue();
            if (value == STILL_ACTIVE) {
                throw new IllegalThreadStateException("Client process " + processId + " is still running");
            }
            return value;
        }

        @Override
        public void destroy() {
            Kernel32Native.INSTANCE.TerminateProcess(processHandle, 1);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return Kernel32Native.INSTANCE.WaitForSingleObject(processHandle, 0) == WAIT_TIMEOUT;
        }

        private void closeHandle() {
            Kernel32Native.INSTANCE.CloseHandle(processHandle);
        }
    }

    private interface Kernel32Native extends Library {
        Kernel32Native INSTANCE = Native.loadLibrary("kernel32", Kernel32Native.class);

        boolean CreateProcessW(WString lpApplicationName,
                               WString lpCommandLine,
                               Pointer lpProcessAttributes,
                               Pointer lpThreadAttributes,
                               boolean bInheritHandles,
                               int dwCreationFlags,
                               Pointer lpEnvironment,
                               WString lpCurrentDirectory,
                               STARTUPINFO lpStartupInfo,
                               PROCESS_INFORMATION lpProcessInformation);

        int WaitForSingleObject(Pointer hHandle, int dwMilliseconds);

        boolean GetExitCodeProcess(Pointer hProcess, IntByReference lpExitCode);

        boolean TerminateProcess(Pointer hProcess, int uExitCode);

        boolean CloseHandle(Pointer hObject);
    }

    public static final class STARTUPINFO extends Structure {
        public int cb;
        public String lpReserved;
        public String lpDesktop;
        public String lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public Pointer lpReserved2;
        public Pointer hStdInput;
        public Pointer hStdOutput;
        public Pointer hStdError;

        @Override
        protected List<String> getFieldOrder() {
            return java.util.Arrays.asList(
                    "cb",
                    "lpReserved",
                    "lpDesktop",
                    "lpTitle",
                    "dwX",
                    "dwY",
                    "dwXSize",
                    "dwYSize",
                    "dwXCountChars",
                    "dwYCountChars",
                    "dwFillAttribute",
                    "dwFlags",
                    "wShowWindow",
                    "cbReserved2",
                    "lpReserved2",
                    "hStdInput",
                    "hStdOutput",
                    "hStdError");
        }
    }

    public static final class PROCESS_INFORMATION extends Structure {
        public Pointer hProcess;
        public Pointer hThread;
        public int dwProcessId;
        public int dwThreadId;

        @Override
        protected List<String> getFieldOrder() {
            return java.util.Arrays.asList(
                    "hProcess",
                    "hThread",
                    "dwProcessId",
                    "dwThreadId");
        }
    }

    private static final class LoggedProcess extends Process {
        private final Process delegate;
        private final Thread stdoutPump;
        private final Thread stderrPump;

        private LoggedProcess(Process delegate, Path logFile) throws IOException {
            this.delegate = delegate;
            this.stdoutPump = pump(delegate.getInputStream(), logFile);
            this.stderrPump = pump(delegate.getErrorStream(), logFile);
        }

        @Override
        public OutputStream getOutputStream() {
            return delegate.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return delegate.getInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return delegate.getErrorStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            int code = delegate.waitFor();
            joinPump(stdoutPump);
            joinPump(stderrPump);
            return code;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            boolean finished = delegate.waitFor(timeout, unit);
            if (finished) {
                joinPump(stdoutPump);
                joinPump(stderrPump);
            }
            return finished;
        }

        @Override
        public int exitValue() {
            return delegate.exitValue();
        }

        @Override
        public void destroy() {
            delegate.destroy();
        }

        @Override
        public Process destroyForcibly() {
            delegate.destroyForcibly();
            return this;
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        private static Thread pump(InputStream input, Path logFile) throws IOException {
            Thread thread = new Thread(() -> {
                try (InputStream in = input;
                     OutputStream out = Files.newOutputStream(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // Best effort logging only.
                }
            }, "forge-client-log-pump");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private static void joinPump(Thread thread) throws InterruptedException {
            if (thread != null) {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            }
        }
    }
}

