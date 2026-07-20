package com.github.stannismod.forge.testing;

import java.time.Duration;

/**
 * Load-scaled wall-clock ceilings for the harness. Every hard timeout in the framework (server
 * boot, bot handshake, {@code waitTicks}, command round-trips) was sized for ONE server+client
 * pair on an idle machine; under N concurrent forks the effective tick rate stretches and those
 * fixed ceilings turn CPU contention into spurious reds. Rather than hunting every call site with
 * its own knob, the build passes one multiplier — {@code forge.test.timeout.factor}, typically
 * {@code 1 + forks/4} — and every ceiling scales through here.
 *
 * <p>Read lazily on each use (never cached in a static): the property is set per-JVM at launch,
 * both for the Gradle test worker and for the spawned client JVM (the harness forwards it), so
 * one value governs a whole fork. A missing/garbled property or anything below 1 means 1 —
 * ceilings never shrink below their single-fork sizing.</p>
 */
public final class TestTimeouts {

    /** System property carrying the multiplier; set by the build from the fork count. */
    public static final String PROP_FACTOR = "forge.test.timeout.factor";

    private TestTimeouts() { }

    /** The current multiplier: {@code >= 1}, from {@link #PROP_FACTOR}, defaulting to 1. */
    public static double factor() {
        String raw = System.getProperty(PROP_FACTOR);
        if (raw == null) {
            return 1.0d;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed < 1.0d ? 1.0d : parsed;
        } catch (NumberFormatException garbled) {
            return 1.0d;
        }
    }

    /** {@code base} stretched by the current factor. */
    public static Duration scaled(Duration base) {
        return Duration.ofMillis((long) (base.toMillis() * factor()));
    }

    /** {@code baseNanos} stretched by the current factor. */
    public static long scaledNanos(long baseNanos) {
        return (long) (baseNanos * factor());
    }

    /** {@code baseMillis} stretched by the current factor, clamped to {@code int} for socket APIs. */
    public static int scaledMillis(long baseMillis) {
        double stretched = baseMillis * factor();
        return stretched > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) stretched;
    }
}
