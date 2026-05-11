package com.github.stannismod.forge.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TestReportWriter {

    public void write(Path root, List<TestOutcome> outcomes) throws IOException {
        Files.createDirectories(root);
        Files.write(root.resolve("summary.txt"), buildText(outcomes).getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("summary.json"), buildJson(outcomes).getBytes(StandardCharsets.UTF_8));
    }

    private static String buildText(List<TestOutcome> outcomes) {
        StringBuilder builder = new StringBuilder();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (TestOutcome outcome : outcomes) {
            if (outcome.status() == TestStatus.PASSED) {
                passed++;
            } else if (outcome.status() == TestStatus.SKIPPED) {
                skipped++;
            } else if (outcome.status() == TestStatus.FAILED) {
                failed++;
            }
        }

        builder.append("total=").append(outcomes.size())
                .append(", passed=").append(passed)
                .append(", failed=").append(failed)
                .append(", skipped=").append(skipped)
                .append(System.lineSeparator());
        for (TestOutcome outcome : outcomes) {
            builder.append(outcome.status())
                    .append(' ')
                    .append(outcome.id())
                    .append(" [")
                    .append(outcome.category())
                    .append("] ticks=")
                    .append(outcome.ticks())
                    .append(" durationNanos=")
                    .append(outcome.durationNanos());
            if (outcome.failure() != null) {
                builder.append(" failure=").append(outcome.failure().getClass().getSimpleName());
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString();
    }

    private static String buildJson(List<TestOutcome> outcomes) {
        StringBuilder builder = new StringBuilder();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        for (TestOutcome outcome : outcomes) {
            if (outcome.status() == TestStatus.PASSED) {
                passed++;
            } else if (outcome.status() == TestStatus.SKIPPED) {
                skipped++;
            } else if (outcome.status() == TestStatus.FAILED) {
                failed++;
            }
        }

        builder.append("{");
        builder.append("\"total\":").append(outcomes.size()).append(",");
        builder.append("\"passed\":").append(passed).append(",");
        builder.append("\"failed\":").append(failed).append(",");
        builder.append("\"skipped\":").append(skipped).append(",");
        builder.append("\"tests\":[");
        for (int i = 0; i < outcomes.size(); i++) {
            if (i > 0) {
                builder.append(",");
            }
            TestOutcome outcome = outcomes.get(i);
            builder.append("{")
                    .append("\"id\":\"").append(escape(outcome.id())).append("\",")
                    .append("\"category\":\"").append(escape(outcome.category())).append("\",")
                    .append("\"status\":\"").append(outcome.status()).append("\",")
                    .append("\"required\":").append(outcome.required()).append(",")
                    .append("\"ticks\":").append(outcome.ticks()).append(",")
                    .append("\"durationNanos\":").append(outcome.durationNanos()).append(",")
                    .append("\"notes\":[");
            List<String> notes = outcome.notes();
            for (int j = 0; j < notes.size(); j++) {
                if (j > 0) {
                    builder.append(",");
                }
                builder.append("\"").append(escape(notes.get(j))).append("\"");
            }
            builder.append("]");
            if (outcome.failure() != null) {
                builder.append(",\"failure\":\"")
                        .append(escape(outcome.failure().toString()))
                        .append("\"");
            }
            builder.append("}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

