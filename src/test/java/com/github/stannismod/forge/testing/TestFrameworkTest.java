package com.github.stannismod.forge.testing;


import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TestFrameworkTest {

    @Test
    public void orchestratorRunsTestsAndWritesReports() throws Exception {
        Path reportRoot = Files.createTempDirectory("forge-framework-report-");
        TestRegistry registry = new TestRegistry()
                .register(new PassingTest())
                .register(new MultiTickTest())
                .register(new FailingTest());

        List<TestOutcome> outcomes = new TestBootstrap(registry, new TestReportWriter()).run(reportRoot);

        Assert.assertEquals(3, outcomes.size());
        Assert.assertTrue(outcomes.get(0).passed());
        Assert.assertEquals(TestStatus.PASSED, outcomes.get(0).status());
        Assert.assertEquals(2, outcomes.get(1).ticks());
        Assert.assertEquals(TestStatus.FAILED, outcomes.get(2).status());
        Assert.assertNotNull(outcomes.get(2).failure());

        Path summaryTxt = reportRoot.resolve("summary.txt");
        Path summaryJson = reportRoot.resolve("summary.json");
        Assert.assertTrue(Files.exists(summaryTxt));
        Assert.assertTrue(Files.exists(summaryJson));

        String text = new String(Files.readAllBytes(summaryTxt), StandardCharsets.UTF_8);
        String json = new String(Files.readAllBytes(summaryJson), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("total=3"));
        Assert.assertTrue(text.contains("PASSED passing_case"));
        Assert.assertTrue(json.contains("\"total\":3"));
        Assert.assertTrue(json.contains("\"id\":\"failing_case\""));
    }

    private static final class PassingTest implements HeadlessGameTest {
        @Override
        public String id() {
            return "passing_case";
        }

        @Override
        public String category() {
            return "smoke";
        }

        @Override
        public boolean required() {
            return true;
        }

        @Override
        public int timeoutTicks() {
            return 4;
        }

        @Override
        public void setUp(TestContext context) throws Exception {
            context.ensureWorkDir();
            context.note("setup");
        }

        @Override
        public TestStatus tick(TestContext context) {
            context.note("tick");
            return TestStatus.PASSED;
        }

        @Override
        public void tearDown(TestContext context) {
            context.note("teardown");
        }
    }

    private static final class MultiTickTest implements HeadlessGameTest {
        private int ticks;

        @Override
        public String id() {
            return "multi_tick_case";
        }

        @Override
        public String category() {
            return "smoke";
        }

        @Override
        public boolean required() {
            return true;
        }

        @Override
        public int timeoutTicks() {
            return 4;
        }

        @Override
        public void setUp(TestContext context) {
            ticks = 0;
        }

        @Override
        public TestStatus tick(TestContext context) {
            ticks++;
            return ticks >= 2 ? TestStatus.PASSED : TestStatus.RUNNING;
        }

        @Override
        public void tearDown(TestContext context) {
        }
    }

    private static final class FailingTest implements HeadlessGameTest {
        @Override
        public String id() {
            return "failing_case";
        }

        @Override
        public String category() {
            return "smoke";
        }

        @Override
        public boolean required() {
            return false;
        }

        @Override
        public int timeoutTicks() {
            return 1;
        }

        @Override
        public void setUp(TestContext context) {
        }

        @Override
        public TestStatus tick(TestContext context) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void tearDown(TestContext context) {
        }
    }
}

