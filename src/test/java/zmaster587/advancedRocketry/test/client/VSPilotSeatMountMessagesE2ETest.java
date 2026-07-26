package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A pilot seat answers the player instead of failing silently. Three refusal/notice contracts of
 * the seat's right-click, all observed from the REAL client (the click goes through the real
 * interaction path; the message is read off the client's own action-bar overlay):
 *
 * <ol>
 *   <li><b>Unassembled notice</b>: sitting on the seat of a craft that is NOT assembled seats the
 *       player AND tells him, on the action bar, that the ship must be assembled to fly — the
 *       controls are otherwise silently dead and the player has no way to know why.</li>
 *   <li><b>Self-click no-op</b>: clicking one's own occupied seat is silent — no message, no
 *       dismount.</li>
 *   <li><b>Occupied refusal</b>: clicking a seat whose mount already carries a DIFFERENT passenger
 *       does not mount and answers with a message NAMING the occupant. The occupied refusal wins
 *       over the unassembled notice — exactly one message per click.</li>
 * </ol>
 *
 * <p>Runs on a bare world seat (no ship assembly): these contracts live at the seat itself, and
 * the world frame is the one place the harness can land a real right-click. No VS required.</p>
 */
public class VSPilotSeatMountMessagesE2ETest extends AbstractClientE2ETest {

    private static final int SEAT_X = 4200, SEAT_Y = 71, SEAT_Z = 4200;
    /** Leg 4's own seat, clear of leg 3's NPC occupant so neither leg has to be torn down. */
    private static final int LINKED_X = SEAT_X + 2, LINKED_Z = SEAT_Z + 2;
    private static final Pattern OCCUPANT_NAME = Pattern.compile("\"occupantName\":\"([^\"]+)\"");

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void theSeatAnswersTheClickerInsteadOfFailingSilently() throws Exception {
        // ---- Arrange: a platform with a single loose (unassembled, unlinked) pilot seat. -------
        exec("artest chunk warmup 0 " + ((SEAT_X - 8) >> 4) + " " + ((SEAT_Z - 8) >> 4)
                + " " + ((SEAT_X + 8) >> 4) + " " + ((SEAT_Z + 8) >> 4));
        exec("artest fill 0 " + (SEAT_X - 3) + " " + (SEAT_Y - 1) + " " + (SEAT_Z - 3)
                + " " + (SEAT_X + 3) + " " + (SEAT_Y - 1) + " " + (SEAT_Z + 3)
                + " minecraft:obsidian");
        exec("artest fill 0 " + (SEAT_X - 3) + " " + SEAT_Y + " " + (SEAT_Z - 3)
                + " " + (SEAT_X + 3) + " " + (SEAT_Y + 4) + " " + (SEAT_Z + 3) + " minecraft:air");
        String place = exec("artest fill 0 " + SEAT_X + " " + SEAT_Y + " " + SEAT_Z
                + " " + SEAT_X + " " + SEAT_Y + " " + SEAT_Z + " advancedrocketry:pilotSeat");
        assertTrue("ARRANGEMENT: placing the pilot seat failed: " + place,
                place.contains("\"ok\":true"));

        standBesideTheSeat();
        emptyTheHand();

        // ---- 1) Unassembled notice: the click seats him AND explains the dead controls. --------
        bot().interactBlock(SEAT_X, SEAT_Y, SEAT_Z);
        JsonObject riding = awaitRiding(30, true);
        assertTrue("a right-click on a loose pilot seat must seat the player: " + riding,
                isRiding(riding));
        String overlay = awaitOverlayContaining("not assembled", 30);
        assertTrue("sitting on an UNASSEMBLED craft's pilot seat must answer with the action-bar "
                        + "notice that the ship must be assembled to fly. overlay=\"" + overlay + "\"",
                overlay.toLowerCase(Locale.ROOT).contains("not assembled"));

        // ---- 2) Self-click: silent no-op (no new message, still seated). -----------------------
        awaitOverlayExpired(200);
        bot().interactBlock(SEAT_X, SEAT_Y, SEAT_Z);
        bot().waitTicks(15);
        int overlayTicks = bot().reportChat(1).get("overlayTicks").getAsInt();
        assertTrue("clicking one's OWN occupied seat must be a silent no-op — no new action-bar "
                        + "message (overlayTicks=" + overlayTicks + ")",
                overlayTicks == 0);
        assertTrue("a self-click must not unseat the pilot", isRiding(bot().reportRidingEntity()));

        // ---- 3) Occupied refusal: no mount, one message naming the occupant. -------------------
        exec("artest player dismount");
        awaitRiding(30, false);
        String occupy = exec("artest vs seat-occupy 0 " + SEAT_X + " " + SEAT_Y + " " + SEAT_Z);
        assertTrue("ARRANGEMENT: the seat-occupy probe must seat an NPC occupant: " + occupy,
                occupy.contains("\"ok\":true") && occupy.contains("\"mounted\":true"));
        Matcher nm = OCCUPANT_NAME.matcher(occupy);
        assertTrue("ARRANGEMENT: seat-occupy must report the occupant's name: " + occupy, nm.find());
        String occupantName = nm.group(1);

        standBesideTheSeat();
        // The occupancy must still HOLD at the moment of the click — measured server-side, not
        // assumed from the occupy call an instant earlier.
        String occupancy = exec("artest vs seat-status 0 " + SEAT_X + " " + SEAT_Y + " " + SEAT_Z);
        assertTrue("ARRANGEMENT: the NPC occupant must still be seated when the bot clicks (it was "
                        + "mounted a moment ago): " + occupancy,
                occupancy.contains("\"passengers\":[{"));
        bot().interactBlock(SEAT_X, SEAT_Y, SEAT_Z);
        String refusal = awaitOverlayContaining(occupantName, 30);
        assertTrue("clicking an OCCUPIED pilot seat must answer with a message NAMING the occupant "
                        + "(\"" + occupantName + "\"). overlay=\"" + refusal + "\""
                        + " riding=" + bot().reportRidingEntity()
                        + " seatStatus=" + exec("artest vs seat-status 0 " + SEAT_X + " " + SEAT_Y
                                + " " + SEAT_Z),
                refusal.contains(occupantName));
        assertTrue("the occupied refusal must also WIN over the unassembled notice — exactly one "
                        + "message per click. overlay=\"" + refusal + "\"",
                !refusal.toLowerCase(Locale.ROOT).contains("not assembled"));
        JsonObject afterRefusal = bot().reportRidingEntity();
        assertTrue("a click on an occupied seat must NOT mount the clicker: " + afterRefusal,
                !isRiding(afterRefusal));

        // ---- 4) LINKED but not a ship: the notice must STILL fire. -----------------------------
        // This is the state a FAILED assembly leaves behind, and it is the one the notice exists
        // for. The seat carries a flight-computer link — recorded by the assembler before the
        // physics mod confirms the spawn, and kept when the spawn is rejected — while no ship
        // manages it. Gating "assembled" on the link ALONE suppressed the notice here and lit the
        // full tier-2 flight HUD on an inert pile of blocks; the gate is a live ship resolve.
        exec("artest player dismount");
        awaitRiding(30, false);
        String place2 = exec("artest fill 0 " + LINKED_X + " " + SEAT_Y + " " + LINKED_Z
                + " " + LINKED_X + " " + SEAT_Y + " " + LINKED_Z + " advancedrocketry:pilotSeat");
        assertTrue("ARRANGEMENT: placing the second pilot seat failed: " + place2,
                place2.contains("\"ok\":true"));
        String linked = exec("artest vs seat-link 0 " + LINKED_X + " " + SEAT_Y + " " + LINKED_Z
                + " " + LINKED_X + " " + (SEAT_Y + 1) + " " + LINKED_Z);
        assertTrue("ARRANGEMENT: the seat must end up LINKED — without that this leg tests the same "
                        + "unlinked case as leg 1 and proves nothing: " + linked,
                linked.contains("\"linked\":true"));
        assertTrue("ARRANGEMENT CONTROL: and it must NOT be managed by a ship, or the notice is "
                        + "correctly absent for a reason that has nothing to do with the bug: " + linked,
                linked.contains("\"managedByShip\":false"));

        standBeside(LINKED_X, LINKED_Z);
        awaitOverlayExpired(200);
        bot().interactBlock(LINKED_X, SEAT_Y, LINKED_Z);
        String linkedOverlay = awaitOverlayContaining("not assembled", 30);
        assertTrue("sitting on a LINKED seat whose craft never became a ship must still answer with "
                        + "the \"not assembled\" notice — the link is a build-time intention, not "
                        + "evidence that a ship exists. overlay=\"" + linkedOverlay + "\"",
                linkedOverlay.toLowerCase(Locale.ROOT).contains("not assembled"));
    }

    // ---- Arrangement helpers -------------------------------------------------------------------

    /** Teleport until the client OBSERVABLY stands within interaction reach of the seat. */
    private void standBesideTheSeat() throws Exception {
        standBeside(SEAT_X, SEAT_Z);
    }

    /** Same, for any seat column on this platform. */
    private void standBeside(int seatX, int seatZ) throws Exception {
        double distSq = Double.POSITIVE_INFINITY;
        JsonObject state = null;
        for (int attempt = 0; attempt < 6 && distSq >= 25.0; attempt++) {
            exec("tp @a " + (seatX + 0.5) + " " + SEAT_Y + " " + (seatZ + 1.5) + " 0 0");
            bot().waitTicks(20);
            state = bot().reportState();
            if (state.has("worldReady") && state.get("worldReady").getAsBoolean()) {
                double dx = state.get("playerX").getAsDouble() - (seatX + 0.5);
                double dy = state.get("playerY").getAsDouble() - SEAT_Y;
                double dz = state.get("playerZ").getAsDouble() - (seatZ + 0.5);
                distSq = dx * dx + dy * dy + dz * dz;
            }
        }
        assertTrue("ARRANGEMENT: the client must observably stand within reach of the seat, or the "
                + "right-click is dropped before the block sees it. state=" + state, distSq < 25.0);
    }

    /** Server-side clear + client-observed empty hand (a held stack can eat the right-click). */
    private void emptyTheHand() throws Exception {
        exec("clear @a");
        bot().selectHotbar(0);
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonObject items = bot().reportPlayerItems();
            if (items.has("worldReady") && items.get("worldReady").getAsBoolean()
                    && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
            bot().waitTicks(5);
        }
        assertTrue("ARRANGEMENT: the bot's hand must be observably empty; held=" + heldId,
                heldId != null && heldId.isEmpty());
    }

    // ---- Observation helpers -------------------------------------------------------------------

    /** Poll until the client reports riding == {@code want} (bounded); returns the last sample. */
    private JsonObject awaitRiding(int samples, boolean want) throws Exception {
        JsonObject riding = null;
        for (int i = 0; i < samples; i++) {
            riding = bot().reportRidingEntity();
            if (isRiding(riding) == want) {
                break;
            }
            bot().waitTicks(5);
        }
        return riding;
    }

    /** Poll the action-bar overlay until it contains {@code needle} (bounded); returns the last. */
    private String awaitOverlayContaining(String needle, int samples) throws Exception {
        String overlay = "";
        for (int i = 0; i < samples; i++) {
            overlay = bot().reportChat(1).get("overlay").getAsString();
            if (overlay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                break;
            }
            bot().waitTicks(5);
        }
        return overlay;
    }

    /** Wait (bounded) for the current action-bar overlay to run out, so "no NEW message" is
     *  distinguishable from "the old message is still fading". */
    private void awaitOverlayExpired(int maxTicks) throws Exception {
        for (int waited = 0; waited < maxTicks; waited += 10) {
            if (bot().reportChat(1).get("overlayTicks").getAsInt() <= 0) {
                return;
            }
            bot().waitTicks(10);
        }
        assertTrue("ARRANGEMENT: the previous action-bar message never expired within " + maxTicks
                + " ticks", false);
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }
}
