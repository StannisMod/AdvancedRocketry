package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a REAL client is told about the space subsystem, and whether it believes the right thing.
 * Three scenarios, one client.
 *
 * <p>All three read a value that exists ONLY on the client — a static populated by a packet handler,
 * the dimension the client renders, a clock the client keeps for itself — and all three are
 * falsifiable in the same way: delete the client jar and there is nothing left to read, so none of
 * them can pass server-side.</p>
 *
 * <h2>Why these three share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: 86.1 + 119.0 + 138.0 s across three client
 * boots — <b>5.7 minutes</b> for three reads.</p>
 *
 * <p>The subsystem state each scenario builds (a pool slot, a cell, a settled ledger entry, a POI)
 * is minted fresh per call and addressed by the id the probe returned, so nothing here answers with
 * a neighbour's object. The one genuinely global thing any of them touches — the space clock — is
 * put back in a {@code finally}, and the scenario that moves the player into a slot dimension
 * relies on the shared reset to bring him home, which now asserts the world the client renders
 * rather than trusting the teleport.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code SystemBodiesClientSyncE2ETest}, {@code SlotDimClientEntryE2ETest},
 * {@code TheClientKnowsTheSpaceClockE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SpaceSubsystemClientSyncGroupE2ETest extends AbstractSharedClientE2ETest {

    private static final Pattern FIRST_DIM = Pattern.compile("\"dims\":\\[(-?\\d+)");
    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    /** The slot the settle actually bound the cell to — the one place that decides it. */
    private static final Pattern BOUND_DIM = Pattern.compile("\"slotDim\":(-?\\d+)");
    private static final String CLIENT_BODIES_CLASS =
            "zmaster587.advancedRocketry.network.PacketSystemBodiesSync";
    private static final String CLOCK = "zmaster587.advancedRocketry.space.SpaceClockSync";

    /** How far the server's clock is jumped. Far past anything a sync period could account for. */
    private static final long JUMP_TICKS = 1_000_000L;
    /** Two full sync periods plus slack, so a missed phase is not a failure. */
    private static final int SYNC_WAIT_TICKS = 520;
    /**
     * How far the two sides may stand apart. One sync period is 200 ticks and the client keeps
     * counting on its own between baselines, so the honest bound is a period plus the round trip —
     * not zero. Six orders of magnitude below the jump above.
     */
    private static final long ALLOWED_SPLIT_TICKS = 600L;

    @Override
    protected String subsystem() {
        return "space-client-sync";
    }

    private String botName() throws Exception {
        String health = exec("artest player health");
        Matcher m = PLAYER_NAME.matcher(health);
        scenario().requireArranged("player health must echo the player name: " + health, m.find());
        return m.group(1);
    }

    // ── slot-dim entry ────────────────────────────────────────────────────────

    /**
     * From {@code SlotDimClientEntryE2ETest}. A REAL client enters a space-subsystem slot dimension
     * without dim-registration errors — the client half of the slot-dim registration sync
     * ({@code PacketSlotDimSync}).
     *
     * <p>Slot dims are registered server-side only, at pool registration; before the sync existed no
     * client had ever been inside one, and on a dedicated server a transfer into one would respawn
     * the client into a Forge dimension it never registered. This drives the full production chain
     * with the pool registered WHILE the client is online (the runtime-growth broadcast path):
     * register pool &rarr; broadcast sync &rarr; bind a cell world &rarr; transfer the real player
     * through {@code PlayerList.transferPlayerToDimension} &rarr; the client's OWN world must be the
     * slot dim and keep rendering.</p>
     */
    @Test
    public void aRealClientEntersASlotDimAndKeepsRendering() throws Exception {
        scenario().arranging("register a pool slot while the client is online, and bind a cell");
        String botName = botName();
        String reg = exec("artest space pool-register 1");
        Matcher dimM = FIRST_DIM.matcher(reg);
        scenario().requireArranged("pool-register must return the new dim id: " + reg, dimM.find());
        int slotDim = Integer.parseInt(dimM.group(1));
        scenario().record("slotDim", slotDim);
        exec("artest space load " + slotDim + " e2ecell");

        // Move the REAL player through the production transfer FIRST — an empty loaded slot is
        // auto-unloaded by Forge at tick end and its unsaved edits are DISCARDED (the documented
        // slot lifecycle), so the floor can only be placed once a player holds the world loaded.
        scenario().arranging("transfer the player in high, place a floor, then step onto it");
        String enter = exec("artest space enter " + botName + " " + slotDim + " 0.5 200 0.5");
        scenario().requireArranged("space enter must succeed: " + enter, enter.contains("\"ok\":true"));
        bot().waitTicks(10);
        exec("artest space set-block " + slotDim + " 0 64 0");
        String reposition = exec("artest space enter " + botName + " " + slotDim + " 0.5 66 0.5");
        scenario().requireArranged("repositioning onto the platform must succeed: " + reposition,
                reposition.contains("\"ok\":true"));
        bot().waitTicks(40);

        scenario().asserting("the world the CLIENT renders is the slot dim, and it keeps running");
        JsonObject clientWorld = bot().reportWeather();
        assertTrue("client must have a world after the transfer",
                clientWorld.get("worldReady").getAsBoolean());
        assertEquals("the client's own world must be the slot dim (registration sync landed)",
                slotDim, clientWorld.get("dim").getAsInt());

        // …the client SETTLES standing on the platform (not void-falling / not frozen). Poll: the
        // chunk send + the server's position correction can take a while on a loaded suite run.
        double clientY = Double.NaN;
        boolean settled = false;
        for (int i = 0; i < 60 && !settled; i++) {
            bot().waitTicks(5);
            clientY = bot().reportState().get("playerY").getAsDouble();
            settled = clientY > 63.5 && clientY < 68.0;
        }
        JsonObject clientBlock = bot().blockState(0, 64, 0);
        String serverView = exec("artest player health");
        assertTrue("client-rendered Y must settle at the platform (~65), got " + clientY
                + "; client block(0,64,0)=" + clientBlock + "; server player: " + serverView, settled);

        bot().waitTicks(40);
        assertEquals("the client must still be in the slot dim two seconds later",
                slotDim, bot().reportWeather().get("dim").getAsInt());

        String post = exec("artest player health");
        assertTrue("server must still see the player: " + post, post.contains("\"player\":\""));
    }

    // ── system bodies broadcast ───────────────────────────────────────────────

    /**
     * From {@code SystemBodiesClientSyncE2ETest}. A REAL separate-JVM client receives the server's
     * per-slot system-body broadcast and stores it in {@code PacketSystemBodiesSync.CLIENT_BODIES} —
     * the client half of the {@code SystemBodiesProducer} render feed (the data {@code BoundarySky}
     * draws). The billboard APPEARANCE is {@code BoundarySkyRendersInSlotCellE2ETest}'s.
     *
     * <p><b>The client is put INSIDE the cell's slot world before it is asked what it received.</b>
     * A sky is per-dimension and a player renders exactly one world, so the server sends each player
     * only the dimension he is in. Standing the subject where the bodies are is therefore not a
     * workaround — it is the arrangement a real pilot is in, and the control leg below is what says
     * so.</p>
     */
    @Test
    public void aRealClientReceivesTheSettledShipsCellBodies() throws Exception {
        try {
            scenario().arranging("install the space stack and register a descend-target POI");
            String setup = exec("artest space entry-setup 1");
            Matcher dimM = FIRST_DIM.matcher(setup);
            scenario().requireArranged("entry-setup must return a slot dim: " + setup, dimM.find());

            // A descend-target PLANET at cell (0,5000,0), local (1000,500,-300). sy=5000 dodges the
            // fallback stars (all at sy=sz=0), so bodiesAt returns ONLY this POI.
            String poi = exec("artest space add-poi 0 5000 0 1000 500 -300 PLANET 0 7");
            scenario().requireArranged("add-poi must register a descend target: " + poi,
                    poi.contains("\"ok\":true") && poi.contains("\"descendTarget\":true"));

            // The dimension under test is the one the subsystem ACTUALLY bound the cell to, read
            // back from the settle. It is not the test's to choose: slot ids are minted per boot,
            // and a number picked here would only be a guess at the binding.
            String settle = exec("artest space ledger-settle 0 5000 0 " + dimM.group(1));
            scenario().requireArranged("ledger-settle must succeed: " + settle,
                    settle.contains("\"ok\":true"));
            Matcher boundM = BOUND_DIM.matcher(settle);
            scenario().requireArranged("the settle must report which slot the cell was bound to: "
                    + settle, boundM.find());
            int slotDim = Integer.parseInt(boundM.group(1));
            scenario().record("slotDim", slotDim);

            // CONTROL, and it runs FIRST, while the player is still OUTSIDE the cell: a sky he is
            // not in is a sky he is not sent. Without this leg the assertion below is satisfied
            // just as well by a build that broadcasts every live cell to everybody.
            scenario().measuring("what a player OUTSIDE the cell is sent (the control)");
            String outside = null;
            for (int i = 0; i < 8; i++) {
                bot().waitTicks(5);
                JsonObject sf = bot().readStaticField(CLIENT_BODIES_CLASS, "CLIENT_BODIES");
                outside = sf.get("isNull").getAsBoolean() ? "" : sf.get("value").getAsString();
            }
            scenario().record("clientBodiesOutside", outside);
            assertFalse("a player who is not in the cell's world must not be sent its sky, got: "
                    + outside, outside != null && outside.contains(slotDim + "=[RenderBody{"));

            scenario().arranging("put the player where a pilot in that cell would be");
            String enter = exec("artest space enter " + botName() + " " + slotDim + " 0.5 200 0.5");
            scenario().requireArranged("space enter must succeed: " + enter,
                    enter.contains("\"ok\":true"));

            scenario().asserting("the client's own CLIENT_BODIES carries the cell's bodies, intact");
            String value = null;
            boolean got = false;
            for (int i = 0; i < 16 && !got; i++) {
                bot().waitTicks(5);
                JsonObject sf = bot().readStaticField(CLIENT_BODIES_CLASS, "CLIENT_BODIES");
                if (!sf.get("isNull").getAsBoolean()) {
                    value = sf.get("value").getAsString();
                    got = value.contains(slotDim + "=[") && value.contains("RenderBody{");
                }
            }
            assertTrue("client CLIENT_BODIES must carry the slot dim's bodies, got: " + value, got);
            assertTrue("descend-target flag survived to the client: " + value,
                    value.contains("descend=true"));
            assertTrue("planet dim survived: " + value, value.contains("dim=0"));
            assertTrue("ship->body direction survived: " + value, value.contains("dir=1000,500,-300"));
        } finally {
            try {
                exec("artest space entry-clear");
            } catch (Exception ignored) {
                // Teardown only: the next scenario's own arrangement mints fresh ids regardless.
            }
        }
    }

    // ── the space clock ───────────────────────────────────────────────────────

    /**
     * From {@code TheClientKnowsTheSpaceClockE2ETest}. The space clock is readable on both logical
     * sides and answers the same value on each, to within the sync period — no caller needs to know
     * which side it is on.
     *
     * <p>Before this shipped, a client asking the space subsystem what time it was got a constant
     * {@code 0}: {@code SpaceSubsystem.spaceClock()} resolved a {@code MinecraftServer} that does
     * not exist in a client JVM.</p>
     *
     * <h2>What makes this able to fail</h2>
     * <p>A clock that merely LOOKS right at rest proves nothing: in a fresh world every counter is
     * small, so "the client's number is close to the server's" is satisfiable by two unrelated small
     * numbers. So the server's clock is JUMPED a million ticks and the client is required to follow
     * it. On a build with no sync the client's answer does not move at all, and the two claims below
     * separate by six orders of magnitude rather than by rounding.</p>
     */
    @Test
    public void theClientsSpaceClockFollowsTheServers() throws Exception {
        scenario().asserting("a joined client has been told the clock at all");
        bot().waitForWorld();

        // The load-bearing claim first: a build that never sends the baseline can only answer
        // "false" here.
        assertEquals("a joined client must have been told the space clock, or it cannot answer the"
                + " same value the server does. \"false\" means the baseline never arrived, which is"
                + " the whole mechanism missing.",
                "true", clientHasSync());

        scenario().measuring("both clocks before the jump");
        long serverBefore = serverClock();
        long clientBefore = clientClock();
        scenario().record("serverBefore", serverBefore).record("clientBefore", clientBefore);

        try {
            String moved = exec("artest space set-clock " + (serverBefore + JUMP_TICKS));
            scenario().requireArranged("the server clock must move: " + moved,
                    moved.contains("\"ok\":true"));

            bot().waitTicks(SYNC_WAIT_TICKS);

            scenario().asserting("the client's clock FOLLOWED the server's, and the two agree");
            long serverAfter = serverClock();
            long clientAfter = clientClock();
            scenario().record("serverAfter", serverAfter).record("clientAfter", clientAfter);

            // THE DISCRIMINATOR. A client that is not being synced keeps counting its own ticks and
            // moves by a few hundred; one that is synced moves by the jump.
            long clientMoved = clientAfter - clientBefore;
            assertTrue("the client's space clock must FOLLOW the server's, not merely tick along"
                            + " beside it: the server jumped " + JUMP_TICKS + " ticks and the client"
                            + " moved " + clientMoved + " (before=" + clientBefore + " after="
                            + clientAfter + "; server before=" + serverBefore + " after="
                            + serverAfter + ")",
                    clientMoved > JUMP_TICKS / 2L);

            long split = Math.abs(serverAfter - clientAfter);
            assertTrue("the two sides must answer the same clock to within a sync period: server="
                            + serverAfter + " client=" + clientAfter + " split=" + split
                            + " (allowed " + ALLOWED_SPLIT_TICKS + ")",
                    split <= ALLOWED_SPLIT_TICKS);
        } finally {
            // Put the world's clock back: this harness server is not this scenario's private
            // property, and on a shared one the next scenario inherits whatever is left.
            exec("artest space set-clock " + serverBefore);
        }
    }

    private long clientClock() throws Exception {
        JsonObject answer = bot().invokeStaticInt(CLOCK, "now");
        return Long.parseLong(answer.get("returned").getAsString().trim());
    }

    private String clientHasSync() throws Exception {
        return bot().invokeStaticInt(CLOCK, "hasSync").get("returned").getAsString().trim();
    }

    private long serverClock() throws Exception {
        String frame = exec("artest space frame 0 0 0");
        Matcher m = Pattern.compile("\"clock\":(-?\\d+)").matcher(frame);
        assertTrue("the probe reports no server clock: " + frame, m.find());
        return Long.parseLong(m.group(1));
    }
}
