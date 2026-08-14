package com.github.stannismod.forge.testing.junit;

import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.TestClient;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;

/**
 * JUnit 4 base class for scenarios that need a real Forge dedicated server
 * running for the duration of one test method.
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>{@code @Before} — checks {@link #PROP_HARNESS_ENABLED}; if disabled, the
 *       test is marked SKIPPED via {@link Assume#assumeTrue}. Otherwise spawns a
 *       fresh dedicated server JVM via
 *       {@link RealDedicatedServerHarness#start()} and waits for the boot
 *       marker.</li>
 *   <li>{@code @Test} — your scenario body. Use {@link #harness()} or
 *       {@link #client()} to drive the server.</li>
 *   <li>{@code @After} — closes the harness if it was started.</li>
 * </ul>
 *
 * <p>Each test method gets its own harness (one server JVM per test). This is
 * intentional: scenarios are designed to be independent, and Gradle's
 * {@code maxParallelForks} can multiply this across worker JVMs for parallel
 * execution.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 *   public class WeatherBaselineTest extends AbstractHeadlessServerTest {
 *       @Test
 *       public void rainIsolatedPerDimension() throws Exception {
 *           client().execute("artest weather set 0 rain 12000");
 *           List<String> after = client().execute("artest weather get 0");
 *           assertTrue(String.join("\n", after).contains("\"isRaining\":true"));
 *       }
 *   }
 * }</pre>
 *
 * <p>For scenarios that need to boot two harnesses against the same workDir
 * (e.g. persistence-restart tests), do NOT extend this class — call
 * {@link RealDedicatedServerHarness#startWith} directly from a plain {@code @Test}
 * method, since this base class manages exactly one harness.</p>
 */
public abstract class AbstractHeadlessServerTest {

    /**
     * System property opting IN to real server harness invocation. When unset or
     * {@code false}, every test extending this class is reported as SKIPPED via
     * {@link Assume}. Set to {@code true} when running with a properly
     * configured dev classpath (ForgeGradle runServer-style: launchwrapper,
     * tweakClass, mcLocation system properties on the parent JVM, etc.).
     */
    public static final String PROP_HARNESS_ENABLED = "forge.test.harness.enabled";

    private RealDedicatedServerHarness harness;

    /**
     * Whether this test wants a FLAT world (surface at y=64 everywhere) instead of the generated
     * terrain the harness hands out by default.
     *
     * <p>Override sparingly. A flat world removes the landscape from a fixture's coordinates, which
     * is tempting — but it was tried as the harness default on 2026-08-14 and cost a bigger server
     * heap, more wall clock and three unexplained reds; see
     * {@code RealDedicatedServerHarness}'s flat preset for the numbers. A fixture that needs
     * standable ground is usually better served by standing on a spot whose terrain was SURVEYED
     * (see {@code FixtureGroundOnPinnedSeedTest}).</p>
     */
    protected boolean requiresFlatTerrain() {
        return false;
    }

    @Before
    public final void startHarness() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + PROP_HARNESS_ENABLED + "=true to enable",
                Boolean.parseBoolean(System.getProperty(PROP_HARNESS_ENABLED, "false")));
        harness = requiresFlatTerrain()
                ? RealDedicatedServerHarness.startWithFlatTerrain()
                : RealDedicatedServerHarness.start();
    }

    @After
    public final void stopHarness() throws Exception {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    /** The active harness. Available inside {@code @Test} methods. */
    protected final RealDedicatedServerHarness harness() {
        return harness;
    }

    /** Shortcut for {@code harness().client()}. */
    protected final TestClient client() {
        return harness.client();
    }
}
