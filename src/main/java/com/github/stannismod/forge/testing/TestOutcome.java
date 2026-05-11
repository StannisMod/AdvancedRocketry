package com.github.stannismod.forge.testing;

import java.util.Collections;
import java.util.List;

public final class TestOutcome {

    private final String id;
    private final String category;
    private final boolean required;
    private final TestStatus status;
    private final int ticks;
    private final long durationNanos;
    private final Throwable failure;
    private final List<String> notes;

    public TestOutcome(
            String id,
            String category,
            boolean required,
            TestStatus status,
            int ticks,
            long durationNanos,
            Throwable failure,
            List<String> notes) {
        this.id = id;
        this.category = category;
        this.required = required;
        this.status = status;
        this.ticks = ticks;
        this.durationNanos = durationNanos;
        this.failure = failure;
        this.notes = notes == null ? Collections.emptyList() : Collections.unmodifiableList(notes);
    }

    public String id() {
        return id;
    }

    public String category() {
        return category;
    }

    public boolean required() {
        return required;
    }

    public TestStatus status() {
        return status;
    }

    public int ticks() {
        return ticks;
    }

    public long durationNanos() {
        return durationNanos;
    }

    public Throwable failure() {
        return failure;
    }

    public List<String> notes() {
        return notes;
    }

    public boolean passed() {
        return status == TestStatus.PASSED;
    }
}

