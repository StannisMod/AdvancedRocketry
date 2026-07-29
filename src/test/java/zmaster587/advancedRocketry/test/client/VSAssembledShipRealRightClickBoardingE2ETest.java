package zmaster587.advancedRocketry.test.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.client.ClientBot;
import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * A player can board an ALREADY ASSEMBLED physics ship by aiming at its pilot seat and pressing the
 * real use key - the same two actions a human performs, through the same two code paths.
 *
 * <p><b>Why this test exists.</b> The harness was believed unable to reach an assembled ship's
 * blocks at all, so every crew test boards through a server-side probe that spawns the seat's mount
 * and mounts the bot onto it. That belief rests on the harness's {@code interactBlock}, which calls
 * {@code PlayerControllerMP.processRightClickBlock} DIRECTLY with a caller-supplied world position -
 * and an assembled ship's blocks do not live at any world position, they live in the ship's own
 * subspace. But that is a property of THAT shortcut, not of the client: vanilla never reaches
 * {@code processRightClickBlock} with a hand-picked position either. It polls the use KEY BINDING
 * every tick ({@code Minecraft.processKeyBinds}), and {@code rightClickMouse()} then feeds it
 * whatever the crosshair raytrace ({@code mc.objectMouseOver}) resolved - which on a physics ship is
 * the ship's own block, found by the physics mod's own hook on the raytrace. So the position problem
 * never arises: nobody supplies a position, the game finds one.</p>
 *
 * <p><b>The claim under test</b> is therefore narrow and mechanical: aiming with {@code setLook} and
 * pressing the use key ({@code -99}, the code the mouse handler itself writes for RMB and the default
 * binding of {@code keyBindUseItem}) boards the pilot seat of an assembled ship. Nothing about the
 * assembly, the flight computer or the flight itself is under test here - the arrangement uses
 * probes freely, and only the aim-and-press hop is measured.</p>
 *
 * <p><b>Every hop is observed, so a red names one.</b> An aim that missed and a click the server
 * refused are otherwise indistinguishable, which is exactly how the original limit came to be
 * mis-attributed. So the test asserts (1) the crosshair is on a block, (2) that block is the PILOT
 * SEAT in the client's own world, (3) at the seat's SUBSPACE position - the physics mod's raytrace
 * returning a subspace position is the whole reason a world-position shortcut could never work - and
 * only then presses, and finally asserts (4) the CLIENT reports itself riding the seat's mount, with
 * the server's own view cross-checked.</p>
 *
 * <p><b>The recipe, for any test that wants to board an assembled ship for real.</b> Empty the hand
 * and confirm it from the client; read the seat's live WORLD position and its SUBSPACE address from
 * the same probe reading; stand within a couple of blocks of the seat (see {@link #VARIANT} - aiming
 * from far away loses the hit to a distance comparison, not to any missing capability); aim with
 * {@code setLook} computed from the CLIENT's own eye position; confirm the crosshair with
 * {@code reportMouseOver} before pressing; then {@code setKey(-99, true)} / wait / release.</p>
 *
 * <p>Manual server + client lifecycle rather than the shared base class, matching the other
 * ship-boarding e2e tests. Gated on real Valkyrien Skies - run with {@code -PwithVS}.</p>
 */
public class VSAssembledShipRealRightClickBoardingE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern SEAT_SUB = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern SHIP_WORLD = Pattern.compile(
            "\"shipWorldX\":(-?[0-9.E\\-]+),\"shipWorldY\":(-?[0-9.E\\-]+),\"shipWorldZ\":(-?[0-9.E\\-]+)");

    /**
     * The decked variant, because the pilot must stand NEXT TO the seat rather than squint up at it
     * from the pad. That is not cosmetic: the physics mod only prefers a ship hit over the world
     * result when it is STRICTLY nearer, and a world raytrace that hits nothing still reports the
     * last block boundary it crossed - which, for a ray aimed at a target near the end of its reach,
     * sits exactly ON the ship surface. Aimed from the pad the two distances tie and the ship hit is
     * discarded; aimed from the deck two blocks away the ship surface is struck early and wins by
     * blocks. The seat block itself is identical in both variants.
     */
    private static final String VARIANT = "with-pilot-deck";
    private static final int BX = 2800, BY = 64, BZ = 2800;

    /**
     * The use key's code. Mouse buttons enter {@code KeyBinding} as {@code -100 + button}, so RMB is
     * {@code -99} - the code the real mouse handler writes and the default binding of
     * {@code keyBindUseItem}. Injecting it therefore drives the identical poll the human's click does.
     */
    private static final int KEY_USE_ITEM = -99;

    /** Where the fixture puts the seat before assembly; assembly carries it into the ship's subspace. */
    private static final int BUILD_SEAT_X = BX + 3, BUILD_SEAT_Y = BY + 5, BUILD_SEAT_Z = BZ + 3;

    /**
     * Where the bot stands to board, as an offset from the seat's LIVE world position: on the deck
     * (which is the block directly beneath the seat, so its top surface is the seat block's floor),
     * a block and a half along +X. Close enough that the seat is struck early in the ray, far enough
     * that the bot is not inside the seat block.
     */
    private static final double STAND_OFF_X = 1.5;

    /** Vanilla eye height for a standing player - the raytrace starts here, not at the feet. */
    private static final double EYE_HEIGHT = 1.62;

    /** The server drops a block interaction beyond (reach + 3), so the bot must observably be closer. */
    private static final double MAX_INTERACT_DIST_SQ = 64.0;

    private Path root;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue("Server harness disabled - set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled - set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        root = Files.createTempDirectory("forge-realclick-boarding-");
        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startFailed) {
            serverHarness.close();
            serverHarness = null;
            throw startFailed;
        }
    }

    @After
    public void stopBoth() throws Exception {
        Exception first = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                first = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
            serverHarness = null;
        }
        if (first != null) {
            throw first;
        }
    }

    @Test
    public void aRealUseKeyPressOnAnAssembledShipsSeatBoardsThePilot() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        int budget = (int) (40 * TestTimeouts.factor());

        // ---- ARRANGEMENT: build, assemble, and get the ship LOADED with the client present. ------
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("ARRANGEMENT: a " + VARIANT + " build must route to a ship: " + assemble,
                assemble.contains("\"ok\":true"));

        exec("tp @a " + (BX + 0.5) + " " + (BY + 8) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);
        double yRest = Double.NaN;
        for (int attempt = 0; attempt < budget && Double.isNaN(yRest); attempt++) {
            bot().waitTicks(5);
            Matcher m = POS_Y.matcher(shipInfoAtBase());
            if (m.find()) {
                yRest = Double.parseDouble(m.group(1));
            }
        }
        assertTrue("ARRANGEMENT: the ship must LOAD with the client present: " + shipInfoAtBase(),
                !Double.isNaN(yRest));

        // The seat's SUBSPACE address (stationary, what the raytrace should report) and its live
        // WORLD position (what the bot has to aim at). Both come from the same probe reading.
        String found = findSeat();
        Matcher sm = SEAT_SUB.matcher(found);
        assertTrue("ARRANGEMENT: find-seat must resolve the assembled ship's subspace seat: " + found,
                sm.find());
        int seatSubX = Integer.parseInt(sm.group(1));
        int seatSubY = Integer.parseInt(sm.group(2));
        int seatSubZ = Integer.parseInt(sm.group(3));

        // ---- ARRANGEMENT: an EMPTY hand, or a held stack consumes the press before the block. ----
        // A freshly joined player does not start empty-handed (mods hand out items on first join),
        // so the hand is cleared and then VERIFIED from the client, never assumed.
        exec("clear @a");
        bot().selectHotbar(0);
        JsonObject items = bot().reportPlayerItems();
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            if (isWorldReady(items)) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    break;
                }
            }
            bot().waitTicks(5);
            items = bot().reportPlayerItems();
        }
        assertTrue("ARRANGEMENT: the bot's main hand must be EMPTY so the use press reaches the seat "
                + "block rather than being consumed by a held item. held=" + heldId + " items=" + items,
                heldId != null && heldId.isEmpty());

        // ---- HOP 1-3: put the crosshair on the seat, and PROVE it landed there. ------------------
        // Re-derived every attempt from the seat's LIVE world position: a freshly assembled ship
        // settles for a while, so an aim computed once against a stale pose misses by design.
        JsonObject aim = null;
        double[] seatWorld = null;
        double distSq = Double.POSITIVE_INFINITY;
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;
        for (int attempt = 0; attempt < budget; attempt++) {
            found = findSeat();
            Matcher wm = SHIP_WORLD.matcher(found);
            if (!wm.find()) {
                bot().waitTicks(5);
                continue;
            }
            seatWorld = new double[]{Double.parseDouble(wm.group(1)),
                    Double.parseDouble(wm.group(2)), Double.parseDouble(wm.group(3))};

            // Put the bot on the deck beside the seat, re-derived from the seat's LIVE position: a
            // freshly assembled ship settles for a while, and a stand computed once against a stale
            // pose leaves the bot in mid-air beside a ship that has since moved.
            exec("tp @a " + (seatWorld[0] + STAND_OFF_X) + " " + (seatWorld[1] + 1.0)
                    + " " + seatWorld[2] + " 0 0");
            bot().waitTicks(20);
            JsonObject state = bot().reportState();
            if (!isWorldReady(state)) {
                bot().waitTicks(5);
                continue;
            }
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();

            double dx = seatWorld[0] - px;
            double dy = seatWorld[1] - (py + EYE_HEIGHT);
            double dz = seatWorld[2] - pz;
            distSq = dx * dx + dy * dy + dz * dz;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
            bot().setLook(yaw, pitch);
            // The raytrace is refreshed once per client tick (Minecraft.runTick), so the new
            // rotation needs at least one tick before objectMouseOver can reflect it.
            bot().waitTicks(5);

            aim = bot().reportMouseOver();
            if (isSeatUnderCrosshair(aim, seatSubX, seatSubY, seatSubZ)) {
                break;
            }
        }

        String aimDiag = " observedPlayer=(" + px + "," + py + "," + pz + ")"
                + " seatWorld=" + java.util.Arrays.toString(seatWorld)
                + " seatSubspace=(" + seatSubX + "," + seatSubY + "," + seatSubZ + ")"
                + " buildSeat=(" + BUILD_SEAT_X + "," + BUILD_SEAT_Y + "," + BUILD_SEAT_Z + ")"
                + " distSq=" + distSq + " mouseOver=" + aim + " findSeat=" + found;

        assertTrue("ARRANGEMENT: the bot must OBSERVABLY stand within the server's interaction reach "
                + "of the seat, or the press is discarded before the seat block ever sees it."
                + aimDiag, distSq < MAX_INTERACT_DIST_SQ);

        assertTrue("HOP 1 (aim): the client's crosshair must resolve to a BLOCK. A MISS here means "
                + "the aim maths or the sightline is wrong, not that the ship is unclickable."
                + aimDiag,
                aim != null && aim.has("typeOfHit") && "BLOCK".equals(aim.get("typeOfHit").getAsString()));

        assertTrue("HOP 2 (aim): the block under the crosshair must be the PILOT SEAT as the CLIENT's "
                + "own world reports it - the ship's blocks are reachable by the raytrace." + aimDiag,
                aim.has("block") && aim.get("block").getAsString().toLowerCase(java.util.Locale.ROOT)
                        .contains("pilotseat"));

        assertTrue("HOP 3 (aim): the raytrace must report the seat's SUBSPACE position. This is the "
                + "hop that makes a world-position shortcut impossible and a real key press possible: "
                + "the physics mod resolves the pick against the ship, so the position handed to the "
                + "interaction is the ship's own block address, not a rendered world coordinate."
                + aimDiag,
                aim.has("blockX")
                        && aim.get("blockX").getAsInt() == seatSubX
                        && aim.get("blockY").getAsInt() == seatSubY
                        && aim.get("blockZ").getAsInt() == seatSubZ);

        // ---- HOP 4: press the real use key, exactly as the human's right mouse button does. ------
        bot().setKey(KEY_USE_ITEM, true);
        bot().waitTicks(5);
        bot().setKey(KEY_USE_ITEM, false);

        JsonObject riding = bot().reportRidingEntity();
        for (int attempt = 0; attempt < budget && !isRiding(riding); attempt++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
        }

        String serverRiding = exec("artest player riding-entity");
        String boardDiag = " clientRiding=" + riding + " serverRiding=" + serverRiding
                + " mouseOverAfter=" + bot().reportMouseOver() + aimDiag;

        assertTrue("a real use-key press aimed at an ASSEMBLED ship's pilot seat must board the "
                + "player - the crosshair was proven to be on that very seat block, so a failure "
                + "here is the interaction itself being refused, not a missed aim." + boardDiag,
                isRiding(riding));

        assertTrue("the client must be riding the SEAT's mount, not some other entity it happened "
                + "to board." + boardDiag,
                riding.has("entityClass")
                        && riding.get("entityClass").getAsString().endsWith("EntityDummy"));

        // The server's own view, so a client-only ghost mount cannot pass for a boarding.
        assertTrue("the SERVER must agree the player is riding the seat's mount - a client-side-only "
                + "mount would render a pilot who is not aboard anything." + boardDiag,
                serverRiding.contains("EntityDummy"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private ClientBot bot() {
        return clientHarness.bot();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String shipInfoAtBase() throws Exception {
        return exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
    }

    /** The seat's subspace address + live world position, resolved from the craft's build site. */
    private String findSeat() throws Exception {
        return exec("artest vs find-seat 0 " + BX + " " + (BY + 5) + " " + BZ);
    }

    private static boolean isWorldReady(JsonObject report) {
        return report != null && report.has("worldReady") && report.get("worldReady").getAsBoolean();
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private static boolean isSeatUnderCrosshair(JsonObject aim, int seatX, int seatY, int seatZ) {
        return aim != null
                && aim.has("typeOfHit") && "BLOCK".equals(aim.get("typeOfHit").getAsString())
                && aim.has("blockX")
                && aim.get("blockX").getAsInt() == seatX
                && aim.get("blockY").getAsInt() == seatY
                && aim.get("blockZ").getAsInt() == seatZ;
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("ARRANGEMENT: chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("ARRANGEMENT: pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("ARRANGEMENT: fixture (" + variant + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("ARRANGEMENT: fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
