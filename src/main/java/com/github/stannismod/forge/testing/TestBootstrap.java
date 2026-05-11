package com.github.stannismod.forge.testing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class TestBootstrap {

    private final TestRegistry registry;
    private final TestReportWriter reportWriter;

    public TestBootstrap(TestRegistry registry, TestReportWriter reportWriter) {
        this.registry = registry;
        this.reportWriter = reportWriter;
    }

    public List<TestOutcome> run(Path reportRoot) throws IOException {
        List<TestOutcome> outcomes = new TestOrchestrator(registry).runAll(reportRoot);
        reportWriter.write(reportRoot, outcomes);
        return outcomes;
    }
}

