package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-10b Phase 7 — player-visible side of
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
 * {@code ItemSealDetector}, {@code onItemUse} runs against a placed
 * fixture (driven through {@code /artest player try-seal-detect} so we
 * don't have to wrangle the player into a precise right-click pose),
 * and the outbound {@code SPacketChat} packet is captured by a Netty
 * chat-tap so we can read the translation key the production code
 * dispatched.</p>
 *
 * <p>Fixtures mirror {@code SealDetectorDispatchTest} (stone /
 * cobblestone → "sealed", air / leaves / sand → "notsealmat",
 * stone_slab → "other"). The {@code notsealblock}, {@code notfullblock}
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
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on
 * headless CI.</p>
 */
public class ItemSealDetectorPlayerMessagesE2ETest extends AbstractClientE2ETest {

    private static final int DIM = 0;
    private static final int Y = 150;
    private static final int Z = 300;

    // Distinct from SealDetectorDispatchTest (200..260 / y=80 / z=200)
    // and InventoryBypassRedirectE2ETest (-200..-200) to avoid fixture
    // clashes if testClient runs all suites in one JVM.
    private static final int X_STONE       = 300;
    private static final int X_COBBLESTONE = 310;
    private static final int X_AIR         = 320;
    private static final int X_LEAVES      = 330;
    private static final int X_SAND        = 340;
    private static final int X_SLAB        = 350;

    private static final Pattern BRANCH = Pattern.compile("\"branch\":\"([^\"]+)\"");

    private void forceLoadAround(int x, int z) throws Exception {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                serverClient().execute("artest chunk forceload " + DIM
                        + " " + (cx + dx) + " " + (cz + dz));
            }
        }
    }

    private void place(int x, String blockId) throws Exception {
        forceLoadAround(x, Z);
        String resp = String.join("\n", serverClient().execute(
                "artest place " + DIM + " " + x + " " + Y + " " + Z + " " + blockId));
        // Air placement is a no-op for /artest place but force-loads the
        // chunk — accept either "placed":true or a "placed":false echoing
        // that the block was already there.
        assertFalse("place must not error at " + x + "," + Y + "," + Z
                        + " with " + blockId + "; resp=" + resp,
                resp.contains("\"error\""));
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
        throw new AssertionError("client never rendered " + itemId + " in hand; held=" + held);
    }

    /** Stages the fixture at (x, Y, Z), stands the player on a stone perch one
     *  block away holding the seal detector, RIGHT-CLICKS the fixture through
     *  the real client ({@code interactBlock} → CPacketPlayerTryUseItemOnBlock),
     *  and asserts the i18n-RESOLVED reply lands on the player's chat overlay.
     *  Cross-pins the branch against the server-tier seal-detector mirror. */
    private void assertSealDetectorBranch(int x, String fixtureBlock, String expected,
                                          String expectedChatText) throws Exception {
        place(x, fixtureBlock);
        // Perch for the player one block south of the fixture.
        String perch = String.join("\n", serverClient().execute(
                "artest place " + DIM + " " + x + " " + Y + " " + (Z - 2) + " minecraft:stone"));
        assertFalse("perch place must not error: " + perch, perch.contains("\"error\""));

        String give = String.join("\n", serverClient().execute(
                "artest player give-held advancedrocketry:sealdetector"));
        assertTrue("give-held sealdetector must succeed: " + give, give.contains("\"ok\":true"));
        serverClient().execute("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (Z - 1.5));
        waitForHeld("advancedrocketry:sealdetector");

        // The REAL right-click on the fixture block from the client.
        bot().interactBlock(x, Y, Z);

        // The player must READ the branch's resolved message on their chat.
        boolean found = false;
        String newest = "";
        for (int waited = 0; waited < 100 && !found; waited += 10) {
            bot().waitTicks(10);
            com.google.gson.JsonArray lines = bot().reportChat(5).getAsJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).getAsString();
                if (newest.isEmpty()) newest = line;
                if (line.contains(expectedChatText)) { found = true; break; }
            }
        }
        assertTrue("client chat must show '" + expectedChatText + "' for " + fixtureBlock
                + " at " + x + "," + Y + "," + Z + " (newest line: '" + newest + "')", found);

        // Cross-pin against the server-tier dispatch mirror.
        String checkResp = String.join("\n", serverClient().execute(
                "artest seal-detector check " + DIM + " " + x + " " + Y + " " + Z));
        assertEquals("production dispatch and server-tier mirror must agree on branch for "
                        + fixtureBlock, expected, fieldOf(BRANCH, checkResp, "branch"));
    }

    // ───────────────────── sealed branch ──────────────────────────────────

    /** Solid ROCK material full-block → "sealed". */
    @Test
    public void stoneFixtureDispatchesSealedMessageToPlayer() throws Exception {
        assertSealDetectorBranch(X_STONE, "minecraft:stone", "sealed", "Should hold a nice seal");
    }

    /** Pins that "sealed" isn't pinned to the singular stone block —
     *  any solid full-block ROCK material should reach the player as
     *  "sealed", per SealableBlockHandler.isBlockSealed's material gate. */
    @Test
    public void cobblestoneFixtureDispatchesSealedMessageToPlayer() throws Exception {
        assertSealDetectorBranch(X_COBBLESTONE, "minecraft:cobblestone", "sealed", "Should hold a nice seal");
    }

    // ───────────────────── notsealmat branch ──────────────────────────────

    /** Material.AIR is on the default materialBanList → "notsealmat". */
    @Test
    public void airFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch(X_AIR, "minecraft:air", "notsealmat", "Material will not hold a seal");
    }

    /** Material.LEAVES is on the default materialBanList — multi-material
     *  ban-list pin (not just AIR). */
    @Test
    public void leavesFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch(X_LEAVES, "minecraft:leaves", "notsealmat", "Material will not hold a seal");
    }

    /** Material.SAND is on the default materialBanList — silent removal
     *  from the ban-list would let sand seal rooms (player-visible
     *  regression). */
    @Test
    public void sandFixtureDispatchesNotSealMatMessageToPlayer() throws Exception {
        assertSealDetectorBranch(X_SAND, "minecraft:sand", "notsealmat", "Material will not hold a seal");
    }

    // ───────────────────── other branch ───────────────────────────────────

    /** Stone slab: ROCK material (not banned), but half-block bounds →
     *  isFullBlock=false → dispatch falls through to "other" (after
     *  short-circuiting on the non-IFluidBlock check). */
    @Test
    public void stoneSlabFixtureDispatchesOtherMessageToPlayer() throws Exception {
        assertSealDetectorBranch(X_SLAB, "minecraft:stone_slab", "other", "Air will leak through this block");
    }

}
