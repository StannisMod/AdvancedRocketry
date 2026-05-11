package com.github.stannismod.forge.testing.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class TestClient implements Closeable {

    private final Process process;
    private final Writer stdin;
    private final List<String> transcript;

    TestClient(Process process, Writer stdin, List<String> transcript) {
        this.process = process;
        this.stdin = stdin;
        this.transcript = transcript;
    }

    public List<String> execute(String command) throws IOException, InterruptedException {
        String marker = "FORGE_TEST_DONE " + UUID.randomUUID();
        int startIndex = snapshotSize();
        sendRaw(command);
        sendRaw("say " + marker);
        return awaitMarker(startIndex, marker, Duration.ofSeconds(30));
    }

    public List<String> awaitOutputContaining(String token, Duration timeout) throws InterruptedException {
        int startIndex = snapshotSize();
        return awaitMarker(startIndex, token, timeout);
    }

    public void sendRaw(String command) throws IOException {
        Objects.requireNonNull(command, "command");
        synchronized (stdin) {
            stdin.write(command);
            stdin.write('\n');
            stdin.flush();
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() throws IOException {
        if (!process.isAlive()) {
            return;
        }
        try {
            sendRaw("stop");
        } catch (IOException ignored) {
            // If stdin is already closed, fall through and destroy the process.
        }
        try {
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (stdin) {
                stdin.close();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    List<String> transcriptSnapshot() {
        synchronized (transcript) {
            return new ArrayList<>(transcript);
        }
    }

    private int snapshotSize() {
        synchronized (transcript) {
            return transcript.size();
        }
    }

    private List<String> awaitMarker(int startIndex, String token, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        int index = startIndex;
        List<String> captured = new ArrayList<>();

        while (System.nanoTime() < deadlineNanos) {
            String line = null;
            synchronized (transcript) {
                if (index < transcript.size()) {
                    line = transcript.get(index++);
                    captured.add(line);
                    if (line.contains(token)) {
                        captured.remove(captured.size() - 1);
                        return captured;
                    }
                } else {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    transcript.wait(Math.min(waitMillis, 250L));
                    continue;
                }
            }
        }

        throw new AssertionError("Timed out waiting for marker '" + token + "'. Recent output: " + tail());
    }

    private String tail() {
        List<String> snapshot = transcriptSnapshot();
        int from = Math.max(0, snapshot.size() - 25);
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < snapshot.size(); i++) {
            if (i > from) {
                builder.append(System.lineSeparator());
            }
            builder.append(snapshot.get(i));
        }
        return builder.toString();
    }

    static BufferedWriter newWriter(Process process) {
        return new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }
}

