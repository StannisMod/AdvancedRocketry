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
            // "Is the harness still there?" is the difference between one broken contract and a
            // whole group reporting the same corpse — so it is an INPUT to the verdict, not an
            // afterthought. Ask it of the CLIENT, which is the half that dies.
            boolean groupAlreadyDown = HARNESS_DEAD.get();
            // Do not ping a corpse we already buried — but then do not REPORT a liveness we never
            // measured either. Printing "harnessAlive=true" for a scenario aborted BECAUSE the
            // harness is dead is a field that states the opposite of the truth, and this class
            // exists so a reader can trust the line without opening the source.
            boolean alive = groupAlreadyDown || pingClient();
            String aliveReport = groupAlreadyDown
                    ? "not-probed (group already down)" : String.valueOf(alive);
            Scenario.Phase effective =
                    Scenario.classify(e, scenario, alive, groupAlreadyDown);
            if (!alive) {
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
            out.append("\n  harnessAlive=").append(aliveReport);
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

    /**
     * The ONE {@code @Before}, calling the three steps in an order this class controls.
     *
     * <p>They were three separate {@code @Before} methods until it was noticed that JUnit 4 does not
     * define the order of {@code @Before} methods declared in the SAME class. That is not a style
     * point here: the fast-fail check must run BEFORE the reset, or a class whose client has died
     * pays the reset's full round-trip — on a HUNG client, the command channel's own multi-minute
     * timeout — once per remaining scenario, which is exactly the cost the fast-fail exists to
     * avoid. An undefined order made the guarantee a coin flip.
     */
    @Before
    public final void prepareScenario() throws Exception {
        enforceDeterministicOrder();
        failFastWhenTheGroupIsAlreadyDown();
        resetBetweenScenarios();
    }

    private void enforceDeterministicOrder() {
        FixMethodOrder order = getClass().getAnnotation(FixMethodOrder.class);
        assertTrue(getClass().getName() + " extends " + AbstractSharedClientE2ETest.class.getSimpleName()
                        + " but does not carry @FixMethodOrder(MethodSorters.NAME_ASCENDING)."
                        + " The annotation is NOT @Inherited, so the base class cannot supply it, and"
                        + " without it JUnit does not guarantee method order — which makes every"
                        + " independence claim in a shared-harness class unverifiable.",
                order != null && order.value() == MethodSorters.NAME_ASCENDING);
    }

    private void failFastWhenTheGroupIsAlreadyDown() {
        if (HARNESS_DEAD.get()) {
            throw new AssertionError("E2E verdict=CASCADE scenario=" + testName.getMethodName()
                    + " — the shared harness died earlier in this class (first failure: "
                    + firstFailure + "). This scenario never ran; read that one instead.");
        }
    }

    private void resetBetweenScenarios() throws Exception {
        final Plot.Lane lane = lane();
        Plot plot = PLOTS.computeIfAbsent(testName.getMethodName(),
                name -> new Plot(nextPlotIndex++, name, 0, lane));
        scenario = new Scenario(testName.getMethodName(), subsystem(), plot);

        // SERVER side first: its commands echo harness markers into the chat the client reset is
        // about to clear. Doing it the other way round leaves the markers behind and the clean
        // assertion below fails for a reason that has nothing to do with the previous scenario.
        serverClient().execute("clear @a");
        // The harness server runs gamemode=1 (RealDedicatedServerHarness writes it into
        // server.properties), and a scenario that needs survival — a vacuum-damage or a
        // stack-consumption one — drops the player into it. Left behind, the NEXT scenario runs
        // under a different mode than the one its green runs were taken on. Restoring the
        // documented default is a no-op for every scenario that never touches it.
        serverClient().execute("gamemode creative @a");
        // Same argument for health: a damage scenario leaves the player short, and the next one's
        // "the player started at full health" precondition is then false through no fault of its
        // own. Both are un-restored global mutations of the SHARED subject, which is the one thing
        // this base class exists to stop.
        serverClient().execute("artest player set-health 20");
        // A family of scenarios can carry a channel this base knows nothing about — a seat the
        // player is still riding, a subsystem flag it switched on. It runs HERE, before the
        // teleport, because a player still bound to a vehicle is not moved by /tp: the plot
        // assertion below would then fail naming coordinates, which is the symptom and not the
        // cause. Everything the hook does is asserted by the hook itself.
        resetFamilyStateBeforeTeleport();
        // DIMENSION, and it must come before the teleport: vanilla /tp moves the player WITHIN the
        // world he is in, so a scenario left behind in the space dim or on a planet would be placed
        // at the right X/Z in the WRONG world — and the plot assertion below, which reads X and Z,
        // would happily agree. The transfer is conditional because it is not free: a scenario that
        // never left dim 0 must not pay a dimension change and a chunk re-send every time.
        JsonObject where = bot().reportWeather();
        int clientDim = where != null && where.has("dim") ? where.get("dim").getAsInt() : plot.dim;
        if (clientDim != plot.dim) {
            serverClient().execute("artest tp " + plot.dim);
            bot().waitTicks(20);
        }
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
        // IS THERE A CLIENT AT ALL — asked before anything is asserted ABOUT one, and it is a
        // different question from all three below. Every other read in this method is guarded; these
        // two were not, so a reportState() carrying no player — exactly what a crashed or
        // disconnected client answers — died here on a bare NullPointerException with no message.
        // That is the worst place in the file to lose the diagnosis: the assertions immediately
        // below exist to name what the previous scenario left behind, and none of them was ever
        // reached, so ONE failing scenario presented as N indistinguishable NPEs and reading it
        // cost a full control matrix to discover that all but the first were cascade.
        assertTrue("the client reports NO PLAYER, so it is GONE rather than dirty — a previous"
                + " scenario took it down, and this scenario plus every one after it is downstream"
                + " of that rather than failing on its own subject. Look at the FIRST red in this"
                + " class, not at this one. reportState()=" + state
                + ", resetClientState()=" + cleared,
                state != null && state.has("playerX") && state.has("playerZ"));
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
        if (!plot.contains(px, pz)) {
            // READ ONCE, then WAIT — never wait first. The teleport is a server write and this is a
            // client read, so a miss has two causes and only one of them is a fault: the body is
            // still on its way (a round trip this read got in front of), or something else owns it.
            // Waiting is therefore the RECOVERY, not the routine: a scenario whose first read lands
            // inside its plot spends exactly the ticks it always did, and only a scenario that has
            // already missed pays anything. An earlier cut polled unconditionally and cost a
            // neighbouring class five reds — a settle every scenario pays is not an observation of
            // the arrangement, it IS the arrangement.
            //
            // Measured 2026-08-12, four scenarios of one class in one run: THREE reached their plot
            // while being watched (they were early reads and nothing more) and one never arrived at
            // all, with its body below Y=-800 and falling. One message had been reporting both.
            String settle = diagnoseMissedPlot(plot);
            state = bot().reportState();
            px = state.has("playerX") ? state.get("playerX").getAsDouble() : px;
            pz = state.has("playerZ") ? state.get("playerZ").getAsDouble() : pz;
            if (!plot.contains(px, pz)) {
                org.junit.Assert.fail("a scenario must start inside its own plot " + plot
                        + "; the client reports the player at " + px + "," + pz
                        + settle + " resetCleared=" + cleared);
            }
            scenario.record("plotSettle", settle.replace('\n', ' '));
        }

        // Health is asserted on the CLIENT's own view, and polled rather than read once: the
        // set-health above is a server write and the client learns it on the next update packet.
        double health = state.has("health") ? state.get("health").getAsDouble() : -1.0;
        for (int waited = 0; waited < 40 && health < 19.5; waited += 5) {
            bot().waitTicks(5);
            // Guarded like every other read here: a client that dies DURING the poll would
            // otherwise reproduce the same bare NPE this method was just taught not to throw, one
            // loop iteration later and with the guard above already passed.
            JsonObject polled = bot().reportState();
            health = polled != null && polled.has("health")
                    ? polled.get("health").getAsDouble() : -1.0;
        }
        assertTrue("a scenario must start at full health as the CLIENT renders it, or a"
                + " damage-observing scenario measures the previous one's leftovers; client"
                + " reports " + health, health >= 19.5);

        // The world the CLIENT actually renders, asserted rather than inferred from the teleport
        // having been issued: the plot check above reads X and Z only, so without this a scenario
        // running in the wrong dimension at the right coordinates passes it.
        JsonObject renderedIn = bot().reportWeather();
        int renderedDim = renderedIn != null && renderedIn.has("dim")
                ? renderedIn.get("dim").getAsInt() : Integer.MIN_VALUE;
        assertEquals("a scenario must start in the world its plot lives in; the client renders "
                + renderedIn, plot.dim, renderedDim);

        // Held item is RECORDED, not asserted: `clear @a` is the reset, but a third-party mod in
        // the dev runtime may hand the player something on its own (TheOneProbe does), and pinning
        // an empty hand would make this base class fail for a reason that is not about sharing.
        scenario.record("plot", plot)
                .record("resetCleared", cleared)
                .record("heldAtStart", state.has("heldItem") ? state.get("heldItem").getAsString() : "?");
    }

    /**
     * Why the between-scenario teleport did not land, sampled ONLY once the check has failed.
     *
     * <p>One {@code x,z} pair cannot tell the two causes apart, and they need opposite fixes: a
     * client that never received the teleport is a round-trip that was read too early, while one
     * that received it and ended up elsewhere has a SECOND WRITER owning the body. Measured
     * 2026-08-12 — four scenarios of {@code VSShipFlightTelemetryE2ETest} red in two of three
     * identical tier runs, each printing one coordinate pair outside every plot in the lane, with
     * {@code harnessAlive=true}; nothing in the message distinguished the two, and the mode stayed
     * unattributable for a session.</p>
     *
     * <p><b>It runs after the verdict is already decided, and that is deliberate.</b> The first cut
     * of this made the plot check itself poll — which changes how many ticks every GREEN scenario
     * spends before it starts, and the class this was written for went from 8/8 to 5 reds in the
     * tier and 7/8 alone. An instrument that moves the arrangement is not an instrument. Everything
     * here is a client-side read (no server command, so no chat marker) on a path that is already
     * failing, so a passing scenario pays exactly nothing.</p>
     */
    private String diagnoseMissedPlot(Plot plot) throws Exception {
        StringBuilder trail = new StringBuilder();
        boolean arrived = false;
        JsonObject last = null;
        for (int sample = 0; sample < 6 && !arrived; sample++) {
            last = bot().reportState();
            trail.append(' ').append(describePlayerPoint(last));
            arrived = isInsidePlot(last, plot);
            if (!arrived) {
                bot().waitTicks(5);
            }
        }
        // WHO OWNS THIS BODY, asked of the server on a scenario that has already lost its verdict —
        // so the chat markers these commands echo can no longer disturb anything.
        //
        // Three questions, and the first two ask about different PLACES on purpose. A ship at the
        // PLOT would mean the teleport dropped the body into geometry and the physics mod ejected it.
        // A ship where the BODY actually is means the opposite and is far worse: the body is being
        // carried, so a capture outlived the scenario that made it and the teleport is being undone
        // every tick by whatever re-projects him onto his deck point. The deck capture answers which.
        String shipOnPlot = askServer("artest vs ship-info " + plot.dim
                + " " + plot.centerX() + " " + Plot.DEFAULT_Y + " " + plot.centerZ() + " 64");
        String shipOnBody = last != null && last.has("playerX")
                ? askServer("artest vs ship-info " + plot.dim
                        + " " + (int) Math.round(last.get("playerX").getAsDouble())
                        + " " + (int) Math.round(last.get("playerY").getAsDouble())
                        + " " + (int) Math.round(last.get("playerZ").getAsDouble()) + " 256")
                : "(no player point to ask about)";
        String capture = askServer("artest vs deck-capture");
        // AND THE CLIENT'S OWN RESOLVER, because the server's answer is only half the question. A body
        // travelling at a CONSTANT delta per tick with its own motion at zero is not being moved by its
        // physics — it is being carried by a rigid transform. When the server then reports no capture
        // and no ship within 256 blocks, the only remaining carrier is the client's own ship-frame
        // resolution continuing in a frame the server has already let go of. These counters say whether
        // it is resolving at all, which is the difference between that and a fourth explanation.
        String clientResolver = readClientCounters(
                "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel",
                "resolvedTicks", "declinedTicks", "externalMoveDrops",
                "lastBodyLocalX", "lastBodyLocalY", "lastBodyLocalZ");
        return "\n  readings taken AFTER the verdict, oldest first:" + trail
                + "\n  reached its plot while being watched: " + arrived
                + (arrived
                        ? " — the teleport DID land and the check above read it too early;"
                          + " this is a round-trip budget, not a stray writer."
                        : " — the body never arrived at all; a second writer owns it, or the"
                          + " teleport never reached this client.")
                + "\n  a ship within 64 blocks of the PLOT centre: " + shipOnPlot
                + "\n  a ship within 256 blocks of where the BODY ended up: " + shipOnBody
                + "\n  its deck capture, as the SERVER sees it: " + capture
                + "\n  the CLIENT's own ship-frame resolver: " + clientResolver
                + "\n  client world=" + bot().reportWeather()
                + " riding=" + bot().reportRidingEntity();
    }

    /** Client statics read from a diagnostic: a field that is absent says so and costs nothing else. */
    private String readClientCounters(String className, String... fields) {
        StringBuilder out = new StringBuilder();
        for (String field : fields) {
            out.append(out.length() == 0 ? "" : " ").append(field).append('=');
            try {
                JsonObject read = bot().readStaticField(className, field);
                out.append(read != null && read.has("value") ? read.get("value").getAsString() : read);
            } catch (Exception unreadable) {
                out.append("(unreadable)");
            }
        }
        return out.toString();
    }

    /** A server probe asked from a diagnostic: its own failure must never replace the one being told. */
    private String askServer(String command) {
        try {
            return String.valueOf(serverClient().execute(command));
        } catch (Exception unavailable) {
            return "(unavailable: " + unavailable + ")";
        }
    }

    /**
     * Is the client's own player point inside {@code plot}? Absent coordinates answer {@code false}
     * rather than throwing: a client with no player is a different failure, named by its own guard.
     */
    private static boolean isInsidePlot(JsonObject state, Plot plot) {
        return state != null && state.has("playerX") && state.has("playerZ")
                && plot.contains(state.get("playerX").getAsDouble(),
                        state.get("playerZ").getAsDouble());
    }

    /** One observation, short enough that a whole trail stays readable on one line. */
    private static String describePlayerPoint(JsonObject state) {
        if (state == null || !state.has("playerX") || !state.has("playerZ")) {
            return "(no-player)";
        }
        String motion = state.has("motionY")
                ? "/v=" + round2(state.get("motionX")) + "," + round2(state.get("motionY"))
                        + "," + round2(state.get("motionZ"))
                : "";
        return "(" + Math.round(state.get("playerX").getAsDouble()) + ","
                + (state.has("playerY") ? Math.round(state.get("playerY").getAsDouble()) : '?')
                + "," + Math.round(state.get("playerZ").getAsDouble()) + motion + ")";
    }

    private static double round2(com.google.gson.JsonElement value) {
        return Math.round(value.getAsDouble() * 100.0) / 100.0;
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
     *
     * <p>This clears the chat channel ONLY. It deliberately does not use the full client reset,
     * which closes the open screen: a GUI scenario's stimulus is a click on that screen, so arming
     * the channel with the full reset would destroy the arrangement it was called to protect.</p>
     */
    protected final void armChatObservation() throws Exception {
        // DRAIN, then clear, then verify — in that order, and repeat until it takes.
        //
        // A server command's completion marker is delivered to the client ASYNCHRONOUSLY: the
        // command channel answers as soon as the server has run it, and the chat packet arrives at
        // the client some ticks later. Clearing the backlog the instant the last arrangement
        // command returns therefore clears everything EXCEPT the marker still in flight, which
        // lands immediately afterwards — measured 2026-08-07, one line, one marker, on a scenario
        // whose arrangement ended with a server command. Waiting first lets the tail land so the
        // clear can actually remove it.
        JsonObject cleared = null;
        JsonObject chat = null;
        int remaining = -1;
        for (int attempt = 0; attempt < 4; attempt++) {
            bot().waitTicks(5);
            cleared = bot().clearChat();
            bot().waitTicks(2);
            chat = bot().reportChat(20);
            remaining = chat.has("count") ? chat.get("count").getAsInt() : -1;
            if (remaining == 0) {
                break;
            }
        }
        scenario.record("armedChatObservation", cleared);
        scenario.requireArranged("the chat channel must be empty at the moment of the stimulus,"
                + " so a matching line can only have come from THIS stimulus; after four"
                + " drain-and-clear rounds it still holds " + remaining + " line(s): "
                + (chat == null ? "?" : chat.get("lines"))
                + " — is a server command running between armChatObservation() and the stimulus?",
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
     * Reset the state channels that belong to a FAMILY of scenarios rather than to every client —
     * run between scenarios, before the teleport, and expected to assert what it closed.
     *
     * <p>The default is a no-op, and deliberately so: this base class's own reset was measured
     * against the non-VS tier, and every command added to it is paid by all 89 scenarios there. A
     * family that leaves something else behind (a ship seat the player is still riding, a
     * subsystem flag it switched on for its own arrangement) closes it here instead, in a base
     * class of its own.</p>
     */
    protected void resetFamilyStateBeforeTeleport() throws Exception {
    }

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

    /**
     * Liveness ceiling for the failure-path ping. Deliberately NOT the command channel's own
     * timeout, which is two minutes scaled by the fork factor — six minutes at eight forks. That is
     * the right budget for a command and a terrible one for "should the rest of this class run",
     * because a HUNG client (socket open, nobody answering) would cost it once per scenario.
     *
     * <p>Five seconds against a round trip measured in milliseconds, still scaled so a genuinely
     * starved client is not mistaken for a corpse.</p>
     */
    private static final int PING_TIMEOUT_MS = 5_000;

    private boolean pingClient() {
        if (sharedClient == null) {
            return false;
        }
        return sharedClient.bot().isAlive(
                com.github.stannismod.forge.testing.TestTimeouts.scaledMillis(PING_TIMEOUT_MS));
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
