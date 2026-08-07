package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The player holds an AR item and right-clicks it. Six scenarios, one client.
 *
 * <p>Every member drives the SAME production entry point — {@code Item#onItemRightClick} reached
 * through {@code ClientBot.useItem()} &rarr; {@code CPacketPlayerTryUseItem} — and differs only in
 * which item is in the hand and where the answer shows up: the player's chat, an opened screen, a
 * spawned entity, or a satellite's queue. That shared shape is why they group: the arrangement is
 * "give the item, wait for the CLIENT to render it in hand, click", six times over.</p>
 *
 * <h2>What the sharing costs, and what it bought</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: these six scenarios lived in four classes
 * costing 110.3 + 115.3 + 164.1 + 229.0 s = <b>10.3 minutes across six client boots</b>, of which
 * the six clicks themselves are seconds.</p>
 *
 * <p>Two channels the sharing makes dangerous, both handled here rather than hoped away:</p>
 * <ul>
 *   <li><b>Chat.</b> {@link #rightClickInVanillaDimDispatchesAirReadoutToPlayerChat} proves "the
 *       player was told X" by searching the last N lines. The base class clears the backlog per
 *       scenario, and this one re-arms it immediately before the click — the harness itself writes
 *       into that channel, one {@code FORGE_TEST_DONE} marker per server command.</li>
 *   <li><b>Entities.</b> {@code reportEntities} counts what the CLIENT can see within a radius, and
 *       a craft spawned by one scenario is still in the world when the next one asks. Both
 *       hovercraft scenarios therefore work at the SAME offset inside their own plots, so the
 *       nearest foreign craft is a full plot stride away and outside the 32-block query.</li>
 * </ul>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code ItemAtmosphereAnalzerReadoutE2ETest}, {@code ItemBiomeChangerSatelliteActionE2ETest},
 * {@code OreScannerRightClickClientE2ETest}, {@code ItemHovercraftSpawnE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ItemRightClickClientGroupE2ETest extends AbstractSharedClientE2ETest {

    private static final int Y = Plot.DEFAULT_Y;

    /**
     * Where a scenario's own fixture block stands inside its plot. Deliberately NOT the centre: the
     * shared reset parks the player on the centre column, and a block placed under his feet is a
     * different arrangement from a block he walks up to.
     *
     * <p>Both hovercraft scenarios use this same offset on purpose — see the class javadoc: it is
     * what keeps a neighbouring scenario's craft outside the 32-block entity query.</p>
     */
    private static final int FIXTURE_DX = 20;
    private static final int FIXTURE_DZ = 20;

    private static final Pattern SAT_ID = Pattern.compile("\"satId\":(-?\\d+)");
    private static final Pattern POSLIST_SIZE = Pattern.compile("\"posListSize\":(-?\\d+)");

    @Override
    protected String subsystem() {
        return "item-use";
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    private void forceLoadAround(int x, int z) throws Exception {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                exec("artest chunk forceload " + plot().dim + " " + (cx + dx) + " " + (cz + dz));
            }
        }
    }

    /**
     * Polls until the CLIENT renders {@code itemId} in the main hand (~10 s cap). A server-side
     * equip needs a sync round-trip, and clicking before it lands drives the click with an empty
     * hand — which is a different production path and reads as a contract failure.
     */
    private void waitForHeld(String itemId) throws Exception {
        String held = "";
        for (int waited = 0; waited < 200; waited += 5) {
            bot().waitTicks(5);
            held = bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
            if (itemId.equals(held)) {
                scenario().record("clientHeld", held);
                return;
            }
        }
        scenario().arrangementFailed("the client never rendered " + itemId + " in hand within 200"
                + " ticks; held=" + held + " — the item was never in the player's hand, so the"
                + " right-click below could not have dispatched it");
    }

    private static int extractInt(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }

    // ── atmosphere analyser: the answer is two lines of chat ──────────────────

    /**
     * From {@code ItemAtmosphereAnalzerReadoutE2ETest}. Player-visible side of
     * {@code ItemAtmosphereAnalzer#onItemRightClick}, observed on the REAL client chat overlay —
     * i18n already resolved, exactly the two lines the player reads.
     *
     * <p>Dim 0 has no AtmosphereHandler &rarr; production falls back to {@code AtmosphereType.AIR}.
     * Both lines must reach the player's screen: "Atmosphere Type: …air…" and "Breathable: yes".</p>
     */
    @Test
    public void rightClickInVanillaDimDispatchesAirReadoutToPlayerChat() throws Exception {
        scenario().arranging("give the atmosphere analyser and wait for the client to render it");
        bot().waitForWorld();
        String give = exec("artest player give-held advancedrocketry:atmanalyser");
        scenario().requireArranged("give-held atmanalyser must succeed: " + give,
                give.contains("\"ok\":true"));
        waitForHeld("advancedrocketry:atmanalyser");

        // Nothing between here and useItem() may be a server command: every one of them echoes a
        // marker into the very channel this scenario reads its verdict from.
        scenario().measuring("arm the chat channel immediately before the right-click");
        armChatObservation();

        scenario().asserting("the player reads both readout lines on his own chat");
        bot().useItem();

        boolean sawType = false;
        boolean sawBreathableYes = false;
        for (int waited = 0; waited < 100 && !(sawType && sawBreathableYes); waited += 10) {
            bot().waitTicks(10);
            JsonArray lines = bot().reportChat(10).getAsJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).getAsString().toLowerCase(Locale.ROOT);
                if (line.contains("atmosphere type") && line.contains("air")) sawType = true;
                if (line.contains("breathable")) {
                    assertTrue("breathable line must read 'yes' for AIR, got: " + line,
                            line.contains("yes"));
                    sawBreathableYes = true;
                }
            }
        }
        assertTrue("client chat must show the resolved 'Atmosphere Type: …air' line", sawType);
        assertTrue("client chat must show the resolved 'Breathable: yes' line", sawBreathableYes);
    }

    // ── biome changer: the answer is a queue on a satellite ───────────────────

    /**
     * From {@code ItemBiomeChangerSatelliteActionE2ETest}. Player-visible side of
     * {@code ItemBiomeChanger#onItemRightClick}: arrange with the arrange-only
     * {@code equip-biomechanger} probe (register the satellite + equip the NBT-bound chip, no
     * click), CLIENT performs the click, and the satellite's queued-position list is the oracle —
     * server state is the contract here (save-format posList).
     */
    @Test
    public void rightClickQueuesPositionsIntoSatellitePosList() throws Exception {
        scenario().arranging("register a biome-changer satellite and equip its chip");
        bot().waitForWorld();

        String equip = exec("artest player equip-biomechanger " + plot().dim);
        scenario().requireArranged("equip-biomechanger must succeed: " + equip,
                equip.contains("\"ok\":true"));
        Matcher satM = SAT_ID.matcher(equip);
        scenario().requireArranged("equip response must carry satId: " + equip, satM.find());
        long satId = Long.parseLong(satM.group(1));
        scenario().record("satId", satId)
                .describeOnFailureWith("artest satellite poslist-size " + plot().dim + " " + satId);

        waitForHeld("advancedrocketry:biomechanger");

        scenario().measuring("the satellite's queue before the click");
        int posBefore = extractInt(
                exec("artest satellite poslist-size " + plot().dim + " " + satId), POSLIST_SIZE);
        scenario().record("posListBefore", posBefore);

        scenario().asserting("the right-click queues positions into the satellite's posList");
        bot().useItem();

        int posAfter = -1;
        for (int waited = 0; waited < 100; waited += 10) {
            bot().waitTicks(10);
            posAfter = extractInt(
                    exec("artest satellite poslist-size " + plot().dim + " " + satId), POSLIST_SIZE);
            if (posAfter > posBefore) break;
        }
        assertTrue("right-click must queue positions into the satellite's posList "
                        + "(before=" + posBefore + ", after=" + posAfter + ")",
                posAfter > posBefore);
        assertEquals("posList stores (x,y,z) triples — length must be divisible by 3, got "
                + posAfter, 0, posAfter % 3);
    }

    // ── ore scanner: the answer is a screen, or the absence of one ────────────

    /**
     * From {@code OreScannerRightClickClientE2ETest}. Empty satellite-ID branch: the held
     * OreScanner has no NBT &rarr; early-out, no GUI opens on the client, no crash.
     */
    @Test
    public void rightClickWithEmptySatelliteIdOpensNoGuiAndDoesNotCrash() throws Exception {
        scenario().arranging("equip an ore scanner with no satellite bound");
        bot().waitForWorld();
        String equip = exec("artest player equip-orescanner none");
        scenario().requireArranged("equip-orescanner must succeed: " + equip,
                equip.contains("\"ok\":true"));
        scenario().requireArranged("empty branch must report hadSatelliteId:false: " + equip,
                equip.contains("\"hadSatelliteId\":false"));
        waitForHeld("advancedrocketry:orescanner");

        scenario().asserting("no screen opens on the client");
        bot().useItem();
        bot().waitTicks(20);

        assertEquals("empty-satellite right-click must not open any screen",
                "", bot().reportState().get("screen").getAsString());
    }

    /**
     * From {@code OreScannerRightClickClientE2ETest}. Resolved satellite-ID branch: a registered
     * SatelliteOreMapping &rarr; the OreMapping GUI must actually OPEN on the client. (The old
     * probe-driven test only pinned "no crash" — it could not see whether the GUI opened.)
     */
    @Test
    public void rightClickWithRegisteredSatelliteIdOpensOreMappingGui() throws Exception {
        scenario().arranging("register an ore-mapping satellite and equip a scanner bound to it");
        bot().waitForWorld();
        String equip = exec("artest player equip-orescanner " + plot().dim);
        scenario().requireArranged("equip-orescanner must succeed: " + equip,
                equip.contains("\"ok\":true"));
        scenario().requireArranged("resolved branch must report hadSatelliteId:true: " + equip,
                equip.contains("\"hadSatelliteId\":true"));
        waitForHeld("advancedrocketry:orescanner");

        scenario().asserting("the OreMapping GUI opens on the client");
        bot().useItem();

        String screen = "";
        for (int waited = 0; waited < 100; waited += 10) {
            bot().waitTicks(10);
            screen = bot().reportState().get("screen").getAsString();
            if (!screen.isEmpty()) break;
        }
        assertTrue("right-click with a resolved SatelliteOreMapping must open the "
                + "OreMapping GUI on the client; screen='" + screen + "'",
                screen.contains("OreMapping"));

        // Left open, the next scenario would start on a screen — which the shared reset closes and
        // then asserts about. Closing it here keeps that assertion about the RESET rather than
        // about this scenario's manners.
        bot().closeScreen();
    }

    // ── hovercraft item: the answer is an entity the client can see ───────────

    /**
     * From {@code ItemHovercraftSpawnE2ETest}. Right-click looking down at a stone block must spawn
     * exactly one EntityHoverCraft the CLIENT can see, and consume the held stack (survival).
     *
     * <p>The player is dropped to survival for the consumption half of the contract; the shared
     * reset puts the mode back for whoever runs next.</p>
     */
    @Test
    public void rightClickAtTargetBlockSpawnsHovercraftAndConsumesStack() throws Exception {
        int dim = plot().dim;
        int x = plot().x(FIXTURE_DX);
        int z = plot().z(FIXTURE_DZ);

        scenario().arranging("place the stone target at " + x + "," + Y + "," + z);
        bot().waitForWorld();
        forceLoadAround(x, z);
        String placeResp = exec("artest place " + dim + " " + x + " " + Y + " " + z + " minecraft:stone");
        scenario().requireArranged("place must not error; resp=" + placeResp,
                !placeResp.contains("\"error\""));

        scenario().arranging("stand the survival player two blocks above it, holding the item");
        exec("gamemode survival @a");
        String give = exec("artest player give-held advancedrocketry:hovercraft");
        scenario().requireArranged("give-held must succeed: " + give, give.contains("\"ok\":true"));
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5));
        bot().waitTicks(10);
        waitForHeld("advancedrocketry:hovercraft");

        // The stimulus is ray-traced SERVER-side from the player's look, so the aim has to have
        // arrived before the click. Aiming and clicking in the same breath leaves the ray pointing
        // wherever the player was already facing — horizontally, into 5 blocks of empty air, where
        // production correctly returns PASS and spawns nothing. That is a failure of the
        // arrangement wearing the contract's clothes, so the aim is MEASURED on the client itself
        // before the click rather than assumed from having asked for it.
        scenario().measuring("aim straight down and confirm the CLIENT is holding that look");
        bot().setLook(0f, 90f);
        double pitch = Double.NaN;
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(2);
            pitch = bot().reportState().get("playerPitch").getAsDouble();
            if (pitch > 89.0) break;
        }
        scenario().record("clientPitch", pitch);
        scenario().requireArranged("the client must be looking straight down before the click, or"
                + " the item's 5-block ray traces into empty air; client pitch=" + pitch,
                pitch > 89.0);

        scenario().asserting("the client sees exactly one spawned hovercraft, and loses the stack");
        bot().useItem();

        int seen = waitForClientEntityCount("EntityHoverCraft", 1);
        // Read the stack whatever the entity count said: together the two separate "production
        // returned PASS/FAIL and spawned nothing" (stack intact) from "it spawned and the client
        // never saw it" (stack consumed). One of those is a client-sync bug and the other is not.
        JsonObject held = bot().reportPlayerItems().getAsJsonObject("held");
        scenario().record("heldAfterClick", held).record("clientHovercraftSeen", seen);

        assertEquals("client must see exactly one spawned EntityHoverCraft; the held stack after"
                + " the click was " + held + " (a stack still holding 1 means production returned"
                + " PASS or FAIL and never spawned anything)", 1, seen);
        assertEquals("survival right-click must consume the held hovercraft item; held="
                + held, 0, held.get("count").getAsInt());
    }

    /**
     * From {@code ItemHovercraftSpawnE2ETest}. Right-click into open air (no block within 5 blocks
     * of the eye) must pass: no entity spawned, stack preserved. Pins the empty-ray-trace branch.
     */
    @Test
    public void rightClickIntoEmptyAirReturnsPassWithoutSpawn() throws Exception {
        int x = plot().x(FIXTURE_DX);
        int z = plot().z(FIXTURE_DZ);

        scenario().arranging("stand the survival player in open sky with the item in hand");
        bot().waitForWorld();
        forceLoadAround(x, z);
        exec("gamemode survival @a");
        String give = exec("artest player give-held advancedrocketry:hovercraft");
        scenario().requireArranged("give-held must succeed: " + give, give.contains("\"ok\":true"));
        // 200 is 50 blocks above the plot's own fixture level, and nothing is ever placed there —
        // so the upward ray has nothing to hit that belongs to this scenario or any other.
        exec("tp @a " + (x + 0.5) + " 200 " + (z + 0.5));
        bot().waitTicks(10);
        waitForHeld("advancedrocketry:hovercraft");

        // The control this scenario turns on: it must be able to tell "nothing spawned" from
        // "something else's craft is in range". Read the count BEFORE the click, in the same
        // radius the verdict uses.
        scenario().measuring("hovercraft the client can already see, before any click");
        int before = bot().reportEntities("EntityHoverCraft", 32).get("count").getAsInt();
        scenario().record("hovercraftInRangeBefore", before);
        scenario().requireArranged("no hovercraft may be within the query radius before the click,"
                + " or a PASS is indistinguishable from a spawn; saw " + before, before == 0);

        scenario().asserting("an empty ray-trace spawns nothing and keeps the stack");
        bot().setLook(0f, -90f);
        bot().useItem();
        bot().waitTicks(20);

        assertEquals("no hovercraft must spawn on an empty ray-trace",
                0, bot().reportEntities("EntityHoverCraft", 32).get("count").getAsInt());
        JsonObject held = bot().reportPlayerItems().getAsJsonObject("held");
        assertEquals("stack must NOT be consumed on PASS; held=" + held,
                1, held.get("count").getAsInt());
    }

    /** Polls until the CLIENT sees {@code expected} entities of the class (~10 s cap). */
    private int waitForClientEntityCount(String classContains, int expected) throws Exception {
        int seen = -1;
        for (int waited = 0; waited < 200; waited += 10) {
            bot().waitTicks(10);
            seen = bot().reportEntities(classContains, 32).get("count").getAsInt();
            if (seen == expected) return seen;
        }
        return seen;
    }
}
