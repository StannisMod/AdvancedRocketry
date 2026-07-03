package com.github.stannismod.forge.testing.junit;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.TestClient;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;

/**
 * JUnit 4 base class for end-to-end scenarios that need both a real Forge
 * dedicated server AND a real Minecraft client connected to it.
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>{@code @Before} — checks {@link #PROP_HARNESS_ENABLED} AND
 *       {@link #PROP_CLIENT_ENABLED}. If either is missing, the test is marked
 *       SKIPPED via {@link Assume#assumeTrue}. Otherwise spawns the server
 *       harness, then a client JVM connected to it via
 *       {@link RealClientHarness#start(RealDedicatedServerHarness)}, then waits
 *       for the in-world handshake.</li>
 *   <li>{@code @Test} — your scenario body. Use {@link #server()} for
 *       server-side commands and {@link #bot()} for client interactions
 *       (right-click, GUI button clicks, hotbar selection, etc.).</li>
 *   <li>{@code @After} — closes client and server in order.</li>
 * </ul>
 *
 * <p>Client E2E tests are <b>significantly</b> heavier than headless server
 * tests: each takes ~60-90s (server JVM + client JVM + GL + bridge handshake)
 * and consumes ~3-4 GB RAM. Plan {@code maxParallelForks} accordingly — typical
 * dev workstations can run 2-3 concurrent client tests; CI may need a single
 * worker.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 *   public class PlanetSelectorGuiE2ETest extends AbstractClientE2ETest {
 *       @Test
 *       public void clickingPlanetUpdatesServerSelection() throws Exception {
 *           bot().openInventory();
 *           // … GUI interactions …
 *           List<String> state = server().client().execute("artest selector info Player");
 *           assertTrue(String.join("\n", state).contains("\"selected\":\"earth\""));
 *       }
 *   }
 * }</pre>
 */
public abstract class AbstractClientE2ETest {

    /** Same as {@link AbstractHeadlessServerTest#PROP_HARNESS_ENABLED}. */
    public static final String PROP_HARNESS_ENABLED = AbstractHeadlessServerTest.PROP_HARNESS_ENABLED;

    /**
     * System property opting IN to client harness invocation. Defaults to
     * {@code false} because the client needs an OpenGL-capable display, which
     * isn't present on headless CI runners. Set to {@code true} on desktop
     * environments OR on CI with Xvfb / equivalent virtual display.
     */
    public static final String PROP_CLIENT_ENABLED = "forge.test.client.enabled";

    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public final void startBoth() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + PROP_HARNESS_ENABLED + "=true to enable",
                Boolean.parseBoolean(System.getProperty(PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D" + PROP_CLIENT_ENABLED + "=true to enable",
                Boolean.parseBoolean(System.getProperty(PROP_CLIENT_ENABLED, "false")));

        serverHarness = RealDedicatedServerHarness.start();
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startupException) {
            // Don't leak a running server JVM if client startup fails.
            try {
                serverHarness.close();
            } catch (Exception cleanupException) {
                startupException.addSuppressed(cleanupException);
            }
            serverHarness = null;
            throw startupException;
        }
    }

    @After
    public final void stopBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                deferred = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (deferred == null) deferred = e;
                else deferred.addSuppressed(e);
            }
            serverHarness = null;
        }
        if (deferred != null) throw deferred;
    }

    /** The active server harness. */
    protected final RealDedicatedServerHarness server() {
        return serverHarness;
    }

    /** Shortcut for {@code server().client()} — issues server commands. */
    protected final TestClient serverClient() {
        return serverHarness.client();
    }

    /** The active client harness. */
    protected final RealClientHarness clientHarness() {
        return clientHarness;
    }

    /** Shortcut for {@code clientHarness().bot()} — drives client UI. */
    protected final ClientBot bot() {
        return clientHarness.bot();
    }
}
