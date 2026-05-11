package com.github.stannismod.forge.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TestRegistry {

    private final List<HeadlessGameTest> tests = new ArrayList<>();

    public TestRegistry register(HeadlessGameTest test) {
        tests.add(test);
        return this;
    }

    public List<HeadlessGameTest> tests() {
        return Collections.unmodifiableList(tests);
    }
}

