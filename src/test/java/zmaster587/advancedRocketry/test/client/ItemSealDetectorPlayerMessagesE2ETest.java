package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * player-visible side of
 * {@link zmaster587.advancedRocketry.item.ItemSealDetector#onItemUse}.
 *
 * <p>The server-tier
 * {@link zmaster587.advancedRocketry.test.server.SealDetectorDispatchTest}
 * already pins the dispatch matrix (which branch fires per fixture) by
 * driving {@code SealableBlockHandler} predicates directly through a
 * mirroring probe. What it does NOT cover is the player-visible side:
 * does {@code onItemUse} actually deliver the matching
 * {@code msg.sealdetector.&lt;branch&gt;} chat message to the player's
 * client.</p>
 *
 * <p>This e2e pins exactly that — a real {@code EntityPlayerMP} holds an
 * {@code ItemSealDetector}, right-clicks a placed fixture through the real
 * client, and the i18n-RESOLVED reply is read off the client's own chat.</p>
 *
 * <p>Fixtures mirror {@code SealDetectorDispatchTest} (stone /
 * cobblestone &rarr; "sealed", air / leaves / sand &rarr; "notsealmat",
 * stone_slab &rarr; "other"). The {@code notsealblock}, {@code notfullblock}
 * and {@code fluid} branches are out of scope here for the same reason
 * they are out of scope on the server tier — they need deterministic
 * fixtures (config-driven banned block, fluid registry) that aren't
 * available without extra plumbing.</p>
 *
 * <p>Cross-pin: every player-message branch is asserted to equal the
 * {@code seal-detector check} probe's branch field at the same
 * coordinate. Any future drift between production
 * ({@code ItemSealDetector.onItemUse}) and the mirroring probe
 * ({@code TestProbeCommand.handleSealDetector}) makes the cross-pin
 * fail loud — that's the whole point of running them side by side.</p>
 *
 * <h2>Why this class is the shared-harness pilot</h2>
 *
 * <p>Six scenarios that used to cost six full server+client boots (~11 minutes of which ~99 % was
 * startup) now cost one. It was chosen as the first migration because it is the one that can go
 * WRONG in the interesting way: <b>three of its six methods expect the identical chat line</b>
 * ("Material will not hold a seal"). In a shared world with no chat reset, the leaves scenario finds
 * the air scenario's leftover line the instant it looks, and passes without the production path
 * running at all — a silent false green in three places. The base class's per-scenario reset asserts
 * an EMPTY chat backlog for exactly this reason;
 * {@link #aaChatBacklogIsEmptyWhenAScenarioStarts()} pins it from this side too, and every branch
 * scenario re-reads the backlog immediately BEFORE its right-click, so a regression in the reset
 * reddens here rather than going quiet.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on
 * headless CI.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ItemSealDetectorPlayerMessagesE2ETest extends AbstractSharedClientE2ETest {

    private static final int Y = Plot.DEFAULT_Y;

    /** Offsets INSIDE this scenario's own plot; the plot itself is what separates scenarios. */
    private static final int FIXTURE_DX = 32;
    private static final int FIXTURE_DZ = 32;
    private static final int PERCH_DZ = FIXTURE_DZ - 2;

    private static final Pattern BRANCH = Pattern.compile("\"branch\":\"([^\"]+)\"");

    @Override
    protected String subsystem() {
        return "seal-detector";
    }

    private void forceLoadAround(int x, int z) throws Exception {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                exec("artest chunk forceload " + plot().dim + " " + (cx + dx) + " " + (cz + dz));
            }
        }
    }

    private String fieldOf(Pattern p, String src, String label) {
        Matcher m = p.matcher(src);
        assertTrue("expected " + label + " field in: " + src, m.find());
        return m.group(1);
    }

    /** Polls until the CLIENT renders {@code itemId} in the main hand (~10 s cap). */
    private void waitForHeld(String itemId) throws Exception {
        String held = "";
        for (int waited = 0; waited < 200; waited += 5) {
            bot().waitTicks(5);
            held = bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
            if (itemId.equals(held)) return;
        }
        scenario().arrangementFailed("the client never rendered " + itemId
                + " in hand within 200 ticks; held=" + held
                + " — the detector was never in the player's hand, so no branch could dispatch");
    }

    /** Stages the fixture in this scenario's plot, stands the player on a stone perch one
     *  block away holding the seal detector, RIGHT-CLICKS the fixture through
     *  the real client ({@code interactBlock} &rarr; CPacketPlayerTryUseItemOnBlock),
     *  and asserts the i18n-RESOLVED reply lands on the player's chat.
     *  Cross-pins the branch against the server-tier seal-detector mirror. */
    private void assertSealDetectorBranch(String fixtureBlock, String expected,
                                          String expectedChatText) throws Exception {
        int dim = plot().dim;
        int x = plot().x(FIXTURE_DX);
        int z = plot().z(FIXTURE_DZ);
        int perchZ = plot().z(PERCH_DZ);

        scenario()
                // What the SERVER thinks is at the fixture coordinate, and which branch its own
                // mirror would dispatch there. Between them they separate "the fixture was never
                // placed" from "it was placed and production chose a different branch" — the two
                // readings a bare "the chat line never arrived" cannot tell apart.
                .describeOnFailureWith(
                        "artest block at " + dim + " " + x + " " + Y + " " + z,
                        "artest seal-detector check " + dim + " " + x + " " + Y + " " + z)
                .arranging("place the " + fixtureBlock + " fixture at " + x + "," + Y + "," + z);

        forceLoadAround(x, z);
        String placed = exec("artest place " + dim + " " + x + " " + Y + " " + z + " " + fixtureBlock);
        // Air placement is a no-op for /artest place but force-loads the chunk — accept either
        // "placed":true or a "placed":false echoing that the block was already there.
        scenario().requireArranged("place must not error at " + x + "," + Y + "," + z
                + " with " + fixtureBlock + "; resp=" + placed, !placed.contains("\"error\""));

        scenario().arranging("perch the player two blocks south of the fixture");
        String perch = exec("artest place " + dim + " " + x + " " + Y + " " + perchZ + " minecraft:stone");
        scenario().requireArranged("perch place must not error: " + perch, !perch.contains("\"error\""));

        scenario().arranging("give the seal detector and wait for the CLIENT to render it in hand");
        String give = exec("artest player give-held advancedrocketry:sealdetector");
        scenario().requireArranged("give-held sealdetector must succeed: " + give,
                give.contains("\"ok\":true"));
        exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (z - 1.5));
        waitForHeld("advancedrocketry:sealdetector");

        // Arm the observation channel at the LAST moment before the stimulus. The arrangement above
        // issues ~13 server commands and every one of them echoes a "[Server] FORGE_TEST_DONE
        // <uuid>" line into the player's chat — measured, 13 lines in the backlog on this class's
        // first shared run. Without this, a line matching the expected text proves nothing about
        // THIS right-click. Nothing between here and interactBlock may be a server command.
        scenario().measuring("arm the chat channel immediately before the right-click");
        armChatObservation();

        scenario().asserting("the player reads the " + expected + " reply on their own chat");
        bot().interactBlock(x, Y, z);

        boolean found = false;
        String seen = "";
        for (int waited = 0; waited < 200 && !found; waited += 10) {
            bot().waitTicks(10);
            com.google.gson.JsonArray lines = bot().reportChat(20).getAsJsonArray("lines");
            seen = lines.toString();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).getAsString().contains(expectedChatText)) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue("client chat must show '" + expectedChatText + "' for " + fixtureBlock
                + " at " + x + "," + Y + "," + z + "; backlog was empty before the click and now"
                + " holds: " + seen, found);

        scenario().asserting("production dispatch and the server-tier mirror agree on the branch");
        String checkResp = exec("artest seal-detector check " + dim + " " + x + " " + Y + " " + z);
        assertEquals("production dispatch and server-tier mirror must agree on branch for "
                        + fixtureBlock, expected, fieldOf(BRANCH, checkResp, "branch"));
    }

    // ── the reset's own witness, from this side ───────────────────────────────

    /**
     * Named to sort FIRST so it runs before any scenario has written to chat, and again meaningful
     * on every later run of the class: it pins that a scenario is handed an empty backlog. If the
     * shared-harness reset ever stops clearing chat, this reddens — instead of the three
     * same-message scenarios below quietly passing on each other's leftovers.
     */
    @Test
    public void aaChatBacklogIsEmptyWhenAScenarioStarts() throws Exception {
        scenario().asserting("a scenario starts with an empty chat backlog");
        com.google.gson.JsonObject chat = bot().reportChat(20);
        scenario().record("lines", chat.get("lines")).record("overlayTicks", chat.get("overlayTicks"));
        assertEquals("a shared-harness scenario must start with no chat lines: " + chat.get("lines"),
                0, chat.get("count").getAsInt());
    }

    // ───────────────────── sealed branch ──────────────────────────────────

    /** Solid ROCK material full-block &rarr; "sealed". */
    @Test
    public void stoneFixtureDispatchesSealedMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:stone", "sealed", "Should hold a nice seal");
    }

    /** Pins that "sealed" isn't pinned to the singular stone block —
     *  any solid full-block ROCK material should reach the player as
     *  "sealed", per SealableBlockHandler.isBlockSealed's material gate. */
    @Test
    public void cobblestoneFixtureDispatchesSealedMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:cobblestone", "sealed", "Should hold a nice seal");
    }

    // ───────────────────── notsealmat branch ──────────────────────────────

    /** Material.AIR is on the default materialBanList &rarr; "notsealmat". */
    @Test
    public void airFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:air", "notsealmat", "Material will not hold a seal");
    }

    /** Material.LEAVES is on the default materialBanList — multi-material
     *  ban-list pin (not just AIR). */
    @Test
    public void leavesFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:leaves", "notsealmat", "Material will not hold a seal");
    }

    /** Material.SAND is on the default materialBanList — silent removal
     *  from the ban-list would let sand seal rooms (player-visible
     *  regression). */
    @Test
    public void sandFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:sand", "notsealmat", "Material will not hold a seal");
    }

    // ───────────────────── other branch ───────────────────────────────────

    /** Stone slab: ROCK material (not banned), but half-block bounds &rarr;
     *  isFullBlock=false &rarr; dispatch falls through to "other" (after
     *  short-circuiting on the non-IFluidBlock check). */
    @Test
    public void stoneSlabFixtureDispatchesOtherMessageToPlayer() throws Exception {
        assertSealDetectorBranch("minecraft:stone_slab", "other", "Air will leak through this block");
    }
}
