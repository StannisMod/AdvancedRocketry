package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runners.MethodSorters;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Class-scoped client-harness base: ONE server JVM and ONE client JVM for every {@code @Test} in
 * the subclass, instead of one pair per method.
 *
 * <h2>Why</h2>
 *
 * <p>Measured on the maintainer's box, 2026-08-06, from the result XML:
 * {@code ModCountParityE2ETest} — whose entire body is one {@code report_mods} call — takes
 * <b>119.2 s</b>, and {@code OreScannerRightClickClientE2ETest}'s two methods take 120.1 s and
 * 101.8 s and print <b>two distinct client pids</b>. A four-scenario run on ONE shared harness
 * costs <b>73.8 s of boot plus 2.1-3.7 s per scenario</b>. Boot is 25-35x the scenario, and better
 * than 95 % of this tier's wall clock. {@code build.gradle}'s {@code forkEvery 1L} then makes the
 * whole tier's floor equal to its LONGEST class, so the 27-method {@code FreeFlightModeE2ETest}
 * pins it at ~35 min in one fork while the other seven idle.</p>
 *
 * <h2>What a subclass owes</h2>
 *
 * <ol>
 *   <li><b>{@code @FixMethodOrder(MethodSorters.NAME_ASCENDING)} on the concrete class.</b> JUnit's
 *       annotation is NOT {@code @Inherited} (checked: it carries only {@code @Retention} and
 *       {@code @Target}), so this base cannot supply it — and without it "the methods are
 *       independent" is a belief rather than a property. {@link #enforceDeterministicOrder} fails
 *       loudly rather than letting a subclass run in an undefined order.</li>
 *   <li><b>Stay inside {@link #plot()}.</b> Each scenario is handed its own 64-block patch, never
 *       recycled. A scenario that asks a GLOBAL question ({@code artest rocket list},
 *       {@code artest station list}) must narrow the answer with {@link Plot#contains}.</li>
 *   <li><b>Declare the phase</b> as it goes, through {@link #scenario()} — that is what lets a
 *       failure name the broken system without anyone opening this file.</li>
 *   <li><b>No un-restored global mutation.</b> Atmosphere density, weather, permaload and a server
 *       restart are not shareable; a scenario needing one belongs on the per-method
 *       {@link AbstractClientE2ETest} instead.</li>
 * </ol>
 *
 * <h2>The reset, and why it is asserted rather than trusted</h2>
 *
 * <p>A shared client carries state across scenarios. Measured on the second scenario of a shared
 * run, ALL FOUR of these were still holding the first scenario's leavings: an open
 * {@code GuiModular}; an action-bar overlay at {@code overlayTicks=50}, still counting down; the
 * previous scenario's item in the hotbar; and the player standing on the previous plot.</p>
 *
 * <p>The chat backlog is the dangerous one, and it is why the pilot for this base class was chosen
 * to be a chat-asserting test: a scenario that proves "the player was told X" by searching the last
 * N chat lines passes on the PREVIOUS scenario's identical line, with no stimulus behind it at all.
 * {@code ItemSealDetectorPlayerMessagesE2ETest} has three methods expecting the same message.</p>
 *
 * <p>So {@link #resetBetweenScenarios} does the reset and then <b>asserts the world is clean</b>. A
 * reset nobody checks is indistinguishable from no reset.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public abstract class AbstractSharedClientE2ETest {

    private static RealDedicatedServerHarness sharedServer;
    private static RealClientHarness sharedClient;
    /** Set once the shared harness stops answering; every later scenario then fails FAST. */
    private static final AtomicBoolean HARNESS_DEAD = new AtomicBoolean(false);
    private static String firstFailure;
    /** Scenario name -> its plot. Stable within a run because the method order is pinned. */
    private static final Map<String, Plot> PLOTS = new HashMap<>();
    private static int nextPlotIndex;

    /**
     * Never cleared in an {@code @After}. JUnit runs {@code @After} BEFORE
     * {@link TestWatcher#failed}, so nulling it there destroys the journal the watcher exists to
     * print — measured on this class's first run: every red reported
     * "never started — failed before or inside the shared setup" and no journal at all, for six
     * failures that had in fact run their whole arrangement. A fresh instance is assigned per
     * scenario in {@link #resetBetweenScenarios} instead.
     */
    private Scenario scenario;

    @Rule
    public final TestName testName = new TestName();

    /**
     * Prints the taxonomy line, the journal and the scenario's own state bundle on a failure, and
     * decides whether the harness is still alive.
     */
    @Rule
    public final TestRule verdict = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            Scenario.Phase effective;
            if (HARNESS_DEAD.get()) {
                effective = Scenario.Phase.CASCADE;
            } else if (e instanceof Scenario.ArrangementFailure) {
                effective = Scenario.Phase.ARRANGEMENT;
            } else if (scenario == null) {
                effective = Scenario.Phase.HARNESS;
            } else {
                effective = scenario.phase();
            }

            // "Is the harness still there?" is the difference between one broken contract and a
            // whole group reporting the same corpse. Ask it before printing the verdict, and ask
            // it of the CLIENT, which is the half that dies.
            boolean alive = pingClient();
            if (!alive && effective != Scenario.Phase.CASCADE) {
                effective = Scenario.Phase.HARNESS;
                HARNESS_DEAD.set(true);
            }
            if (firstFailure == null) {
                firstFailure = description.getMethodName();
            }

            StringBuilder out = new StringBuilder();
            out.append('\n');
            out.append(scenario == null
                    ? "E2E verdict=" + effective + " scenario=" + description.getMethodName()
                      + " (never started — failed before or inside the shared setup)"
                    : scenario.verdictLine(effective));
            out.append("\n  harnessAlive=").append(alive);
            if (firstFailure != null && !firstFailure.equals(description.getMethodName())) {
                out.append(" firstFailureInThisGroup=").append(firstFailure);
            }
            out.append('\n');
            if (scenario != null) {
                out.append(scenario.renderJournal());
                if (alive) {
                    out.append(renderStateBundle(scenario));
                }
            }
            System.out.println(out);
        }
    };

    // ── lifecycle ────────────────────────────────────────────────────────────

    @BeforeClass
    public static void bootSharedHarness() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D"
                        + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        HARNESS_DEAD.set(false);
        firstFailure = null;
        PLOTS.clear();
        nextPlotIndex = 0;

        long startedNanos = System.nanoTime();
        sharedServer = RealDedicatedServerHarness.start();
        try {
            sharedClient = RealClientHarness.start(sharedServer);
        } catch (Exception startupFailure) {
            try {
                sharedServer.close();
            } catch (Exception cleanup) {
                startupFailure.addSuppressed(cleanup);
            }
            sharedServer = null;
            throw startupFailure;
        }
        // The number this whole base class exists to amortise — print it so a run can be audited
        // against the claim rather than against a memory of it.
        System.out.println("[shared-harness] boot ms="
                + (System.nanoTime() - startedNanos) / 1_000_000L
                + " — one server JVM + one client JVM for the whole class");
    }

    @AfterClass
    public static void closeSharedHarness() throws Exception {
        Exception deferred = null;
        if (sharedClient != null) {
            try {
                sharedClient.close();
            } catch (Exception e) {
                deferred = e;
            }
            sharedClient = null;
        }
        if (sharedServer != null) {
            try {
                sharedServer.close();
            } catch (Exception e) {
                if (deferred == null) deferred = e;
                else deferred.addSuppressed(e);
            }
            sharedServer = null;
        }
        if (deferred != null) throw deferred;
    }

    @Before
    public final void enforceDeterministicOrder() {
        FixMethodOrder order = getClass().getAnnotation(FixMethodOrder.class);
        assertTrue(getClass().getName() + " extends " + AbstractSharedClientE2ETest.class.getSimpleName()
                        + " but does not carry @FixMethodOrder(MethodSorters.NAME_ASCENDING)."
                        + " The annotation is NOT @Inherited, so the base class cannot supply it, and"
                        + " without it JUnit does not guarantee method order — which makes every"
                        + " independence claim in a shared-harness class unverifiable.",
                order != null && order.value() == MethodSorters.NAME_ASCENDING);
    }

    @Before
    public final void failFastWhenTheGroupIsAlreadyDown() {
        if (HARNESS_DEAD.get()) {
            throw new AssertionError("E2E verdict=CASCADE scenario=" + testName.getMethodName()
                    + " — the shared harness died earlier in this class (first failure: "
                    + firstFailure + "). This scenario never ran; read that one instead.");
        }
    }

    @Before
    public final void resetBetweenScenarios() throws Exception {
        final Plot.Lane lane = lane();
        Plot plot = PLOTS.computeIfAbsent(testName.getMethodName(),
                name -> new Plot(nextPlotIndex++, name, 0, lane));
        scenario = new Scenario(testName.getMethodName(), subsystem(), plot);

        // SERVER side first: its commands echo harness markers into the chat the client reset is
        // about to clear. Doing it the other way round leaves the markers behind and the clean
        // assertion below fails for a reason that has nothing to do with the previous scenario.
        serverClient().execute("clear @a");
        serverClient().execute("tp @a " + (plot.centerX() + 0.5) + " " + (Plot.DEFAULT_Y + 1)
                + " " + (plot.centerZ() + 0.5) + " 0 0");
        bot().waitTicks(10);

        JsonObject cleared = bot().resetClientState();
        bot().waitTicks(2);

        // Assert the reset, do not trust it. This is the shared harness's own contract, and it is
        // the assertion the spike that produced this class failed on before any of it existed.
        JsonObject state = bot().reportState();
        JsonObject chat = bot().reportChat(20);
        String screen = state.has("screen") ? state.get("screen").getAsString() : "";
        int overlayTicks = chat.has("overlayTicks") ? chat.get("overlayTicks").getAsInt() : -1;
        int chatLines = chat.has("count") ? chat.get("count").getAsInt() : -1;
        double px = state.get("playerX").getAsDouble();
        double pz = state.get("playerZ").getAsDouble();

        assertEquals("a scenario must start with no screen open; the previous one left "
                + cleared + " behind", "", screen);
        assertEquals("a scenario must start with no action-bar overlay counting down"
                + " (the overlay STRING lingers after expiry, so the TICKS are the real gate);"
                + " reset reported " + cleared, 0, overlayTicks);
        assertEquals("a scenario must start with an empty chat backlog, or an assertion that"
                + " searches the last N lines can pass on a previous scenario's identical message;"
                + " reset reported " + cleared, 0, chatLines);
        assertTrue("a scenario must start inside its own plot " + plot + "; the client reports the"
                + " player at " + px + "," + pz, plot.contains(px, pz));

        // Held item is RECORDED, not asserted: `clear @a` is the reset, but a third-party mod in
        // the dev runtime may hand the player something on its own (TheOneProbe does), and pinning
        // an empty hand would make this base class fail for a reason that is not about sharing.
        scenario.record("plot", plot)
                .record("resetCleared", cleared)
                .record("heldAtStart", state.has("heldItem") ? state.get("heldItem").getAsString() : "?");
    }

    /**
     * Clear the CLIENT's chat/overlay immediately before a stimulus, and prove it is clear.
     *
     * <p>The per-scenario reset in {@link #resetBetweenScenarios} is not enough for a scenario that
     * OBSERVES chat, and the reason is the harness itself: every server command the arrangement
     * issues echoes a {@code [Server] FORGE_TEST_DONE &lt;uuid&gt;} line into the player's chat.
     * Measured on this class's first shared run — a six-command arrangement left <b>13 lines</b> in
     * the backlog by the time the right-click happened. A "the player was told X" assertion that
     * searches the last N lines is then searching a window it does not control.</p>
     *
     * <p><b>Issue no SERVER command between this call and the stimulus.</b> Client-side bridge
     * calls ({@code interactBlock}, {@code setKey}, {@code waitTicks}, every {@code report*}) are
     * safe — they produce no marker.</p>
     */
    protected final void armChatObservation() throws Exception {
        JsonObject cleared = bot().resetClientState();
        JsonObject chat = bot().reportChat(20);
        int remaining = chat.has("count") ? chat.get("count").getAsInt() : -1;
        scenario.record("armedChatObservation", cleared);
        scenario.requireArranged("the chat channel must be empty at the moment of the stimulus,"
                + " so a matching line can only have come from THIS stimulus; after the reset it"
                + " still holds " + remaining + " line(s): " + chat.get("lines")
                + " — did a server command run between armChatObservation() and the stimulus?",
                remaining == 0);
    }

    // ── what a subclass implements / uses ────────────────────────────────────

    /**
     * The subsystem this class's scenarios are about, as it should appear in a failure line
     * (e.g. {@code "seal-detector"}, {@code "free-flight"}). It is DECLARED because no amount of
     * stack-walking can infer which system a red belongs to.
     */
    protected abstract String subsystem();

    /**
     * Where this class's plots live. Override when the scenarios work at GROUND level, or when a
     * class is MIGRATING an existing test — keep the coordinates that test already proved green
     * rather than moving it onto fresh terrain, which is a change of subject disguised as a
     * refactor. See {@link Plot.Lane}.
     */
    protected Plot.Lane lane() {
        return Plot.Lane.DEFAULT;
    }

    protected final Scenario scenario() {
        return scenario;
    }

    protected final Plot plot() {
        return scenario.plot();
    }

    protected final RealDedicatedServerHarness server() {
        return sharedServer;
    }

    protected final com.github.stannismod.forge.testing.server.TestClient serverClient() {
        return sharedServer.client();
    }

    protected final ClientBot bot() {
        return sharedClient.bot();
    }

    /** Runs a server probe and joins its reply — the shape every AR client test already uses. */
    protected final String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }

    // ── internals ────────────────────────────────────────────────────────────

    private boolean pingClient() {
        if (sharedClient == null) {
            return false;
        }
        try {
            sharedClient.bot().reportState();
            return true;
        } catch (Throwable dead) {
            return false;
        }
    }

    private String renderStateBundle(Scenario s) {
        if (s.stateBundle().isEmpty()) {
            return "--- no state bundle declared (Scenario.describeOnFailureWith) ---\n";
        }
        StringBuilder sb = new StringBuilder("--- state bundle ---\n");
        for (String command : s.stateBundle()) {
            String reply;
            try {
                reply = exec(command);
            } catch (Throwable t) {
                reply = "<probe failed: " + t + ">";
            }
            sb.append("  ").append(command).append("\n    ").append(reply).append('\n');
        }
        return sb.toString();
    }
}
