package com.github.stannismod.forge.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class TestContext implements AutoCloseable {

    private final String testId;
    private final Path workDir;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    public TestContext(String testId, Path workDir) {
        this.testId = testId;
        this.workDir = workDir;
    }

    public String testId() {
        return testId;
    }

    public Path workDir() {
        return workDir;
    }

    public void ensureWorkDir() throws IOException {
        Files.createDirectories(workDir);
    }

    public void note(String message) {
        notes.add(message);
    }

    public List<String> notes() {
        return Collections.unmodifiableList(notes);
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public void close() {
        // The harness keeps cleanup explicit to stay predictable in tests.
    }
}

