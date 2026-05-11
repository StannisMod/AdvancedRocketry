package com.github.stannismod.forge.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestOrchestrator {

    private final TestRegistry registry;

    public TestOrchestrator(TestRegistry registry) {
        this.registry = registry;
    }

    public List<TestOutcome> runAll(Path reportRoot) throws IOException {
        Files.createDirectories(reportRoot);
        List<TestOutcome> outcomes = new ArrayList<>();
        for (HeadlessGameTest test : registry.tests()) {
            outcomes.add(runOne(test, reportRoot.resolve(safeId(test.id()))));
        }
        return outcomes;
    }

    private TestOutcome runOne(HeadlessGameTest test, Path workDir) throws IOException {
        Files.createDirectories(workDir);
        TestContext context = new TestContext(test.id(), workDir);
        long startedAt = System.nanoTime();
        TestStatus status = TestStatus.RUNNING;
        Throwable failure = null;
        int ticks = 0;

        try {
            test.setUp(context);
            while (status == TestStatus.RUNNING) {
                if (ticks >= Math.max(1, test.timeoutTicks())) {
                    status = TestStatus.FAILED;
                    failure = new AssertionError("Timed out after " + ticks + " ticks");
                    break;
                }

                TestStatus nextStatus = test.tick(context);
                ticks++;
                status = nextStatus == null ? TestStatus.RUNNING : nextStatus;
            }
        } catch (Throwable t) {
            status = TestStatus.FAILED;
            failure = t;
        } finally {
            try {
                test.tearDown(context);
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                }
                status = TestStatus.FAILED;
            }
            context.close();
        }

        long duration = System.nanoTime() - startedAt;
        return new TestOutcome(
                test.id(),
                test.category(),
                test.required(),
                status,
                ticks,
                duration,
                failure,
                new ArrayList<>(context.notes())
        );
    }

    private static String safeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

