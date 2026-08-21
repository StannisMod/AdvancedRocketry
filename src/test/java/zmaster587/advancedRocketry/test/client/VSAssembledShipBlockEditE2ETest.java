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
 * The two clicks that EDIT an assembled ship: breaking one of its blocks and placing a block on it,
 * performed with the real attack and use keys against whatever the crosshair actually resolved.
 *
 * <p><b>Why this test exists.</b> Its sibling
 * {@link VSAssembledShipRealRightClickBoardingE2ETest} proves that a real use-key press ACTIVATES a
 * block of an assembled ship (the pilot seat). Activation is only one of the three things a player
 * does with a block, and it is the one that needs the least from the click: the position alone
 * decides everything. Breaking additionally needs the server's digging path to accept a position
 * that exists in no world chunk the player stands in, and placing additionally needs the hit vector
 * - which vanilla computes as {@code hitVec - blockPos}, a subtraction of a WORLD-frame point from a
 * SUBSPACE-frame position once a ship is involved. Neither is covered by an activation test, and a
 * player who reports "blocks on my ship do not react" is reporting about all three.</p>
 *
 * <p><b>The aim is proven, never assumed.</b> Both legs point the crosshair down at the deck the bot
 * is standing on and read {@link ClientBot#reportMouseOver()} to learn WHICH block was resolved -
 * the ship's own subspace address - before any key is pressed. So a red names its own hop: a
 * MISS is the raytrace failing to reach the ship, and a resolved block that then refuses to break or
 * to accept a placement is the interaction being refused.</p>
 *
 * <p><b>The observation is the SERVER's.</b> A creative break clears the block client-side
 * immediately and independently of whether the server agreed, so a client-side reading of the deck
 * would go green on a click the server discarded. Both legs read the block back through
 * {@code artest block at}, in the ship's subspace, on the server.</p>
 *
 * <p>Manual server + client lifecycle, matching the sibling boarding tests.</p>
 */
public class VSAssembledShipBlockEditE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern SEAT_SUB = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern SHIP_WORLD = Pattern.compile(
            "\"shipWorldX\":(-?[0-9.E\\-]+),\"shipWorldY\":(-?[0-9.E\\-]+),\"shipWorldZ\":(-?[0-9.E\\-]+)");

    /** The decked variant: the bot has to STAND on the ship for these clicks to be the player's. */
    private static final String VARIANT = "with-pilot-deck";
    private static final int BX = 2800, BY = 64, BZ = 2800;

    /** Mouse buttons enter {@code KeyBinding} as {@code -100 + button}: LMB attack, RMB use. */
    private static final int KEY_ATTACK = -100;
    private static final int KEY_USE_ITEM = -99;

    /** Where the bot stands to work, as an offset from the seat's LIVE world position. */
    private static final double STAND_OFF_X = 1.5;

    /** Vanilla eye height for a standing player - the raytrace starts here, not at the feet. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * Pitches tried, in order, when looking for a deck block to work on. Straight down (90) resolves
     * the block the bot is standing ON, which cannot take a placement - its up face is where the bot
     * is. The shallower entries reach a block in FRONT of the bot, whose up face is free.
     */
    private static final float[] AIM_PITCHES = {55.0F, 65.0F, 75.0F, 45.0F, 85.0F};

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

        root = Files.createTempDirectory("forge-shipblockedit-");
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

    /**
     * A real attack-key press on a block of an assembled ship removes that block from the ship.
     *
     * <p>Creative mode, so one press is one break and the test measures the interaction rather than
     * a mining-speed budget. The block read back is the one the crosshair itself named.</p>
     */
    @Test
    public void aRealAttackKeyPressBreaksABlockOfAnAssembledShip() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath",
                exec("artest vs available").contains("\"available\":true"));

        Deck deck = standOnTheDeckAndAimAtIt();

        exec("gamemode 1 @a");
        bot().waitTicks(10);

        String before = blockAt(deck.x, deck.y, deck.z);
        assertTrue("ARRANGEMENT: the crosshair's block must be a REAL block on the server before the "
                + "break, or the leg measures nothing. before=" + before + deck.diag,
                before.contains("\"isAir\":false"));

        bot().setKey(KEY_ATTACK, true);
        bot().waitTicks(10);
        bot().setKey(KEY_ATTACK, false);

        String after = blockAt(deck.x, deck.y, deck.z);
        for (int attempt = 0; attempt < budget() && !after.contains("\"isAir\":true"); attempt++) {
            bot().waitTicks(5);
            after = blockAt(deck.x, deck.y, deck.z);
        }

        assertTrue("a real attack-key press aimed at an ASSEMBLED ship's block must BREAK it. The "
                + "crosshair was proven to be on that very block and the server confirmed it was "
                + "solid, so a failure here is the digging path refusing a subspace position - not a "
                + "missed aim. before=" + before + " after=" + after + deck.diag,
                after.contains("\"isAir\":true"));
    }

    /**
     * A real use-key press with a block in hand, aimed at an assembled ship's deck, places that block
     * onto the ship - at the subspace position the crosshair's own side-hit names.
     */
    @Test
    public void aRealUseKeyPressPlacesABlockOnAnAssembledShip() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath",
                exec("artest vs available").contains("\"available\":true"));

        Deck deck = standOnTheDeckAndAimAtIt();

        assertTrue("ARRANGEMENT: the crosshair must report the face it struck, or there is no "
                + "position for a placement to land on." + deck.diag,
                "up".equalsIgnoreCase(deck.sideHit));

        exec("clear @a");
        exec("give @a minecraft:stone 8");
        bot().selectHotbar(0);
        JsonObject items = bot().reportPlayerItems();
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            if (isWorldReady(items)) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.contains("stone")) {
                    break;
                }
            }
            bot().waitTicks(5);
            items = bot().reportPlayerItems();
        }
        assertTrue("ARRANGEMENT: the bot must be HOLDING the stone it is about to place. held="
                + heldId + " items=" + items + deck.diag,
                heldId != null && heldId.contains("stone"));

        // Re-aim: clearing and giving items does not move the crosshair, but a settling ship does.
        bot().setLook(deck.yaw, deck.pitch);
        bot().waitTicks(5);
        JsonObject aim = bot().reportMouseOver();
        assertTrue("ARRANGEMENT: the crosshair must still be on the same ship block after the hand "
                + "was filled. aim=" + aim + deck.diag,
                isBlockAt(aim, deck.x, deck.y, deck.z));

        String target = blockAt(deck.x, deck.y + 1, deck.z);
        assertTrue("ARRANGEMENT: the space the placement would fill must be EMPTY beforehand, or a "
                + "green would mean nothing. target=" + target + deck.diag,
                target.contains("\"isAir\":true"));

        bot().setKey(KEY_USE_ITEM, true);
        bot().waitTicks(5);
        bot().setKey(KEY_USE_ITEM, false);

        String placed = blockAt(deck.x, deck.y + 1, deck.z);
        for (int attempt = 0; attempt < budget() && placed.contains("\"isAir\":true"); attempt++) {
            bot().waitTicks(5);
            placed = blockAt(deck.x, deck.y + 1, deck.z);
        }

        assertTrue("a real use-key press with a block in hand, aimed at an ASSEMBLED ship's deck, "
                + "must place that block ON the ship - one block above the face the crosshair struck, "
                + "in the ship's own subspace. A failure here is the placement path losing the "
                + "position, not a missed aim. placed=" + placed + deck.diag,
                placed.contains("\"block\":\"minecraft:stone\""));
    }

    // ---- arrangement ---------------------------------------------------------------------------

    /** What the crosshair resolved on the ship, plus the look that put it there. */
    private static final class Deck {
        final int x, y, z;
        final String sideHit;
        final float yaw, pitch;
        final String diag;

        Deck(int x, int y, int z, String sideHit, float yaw, float pitch, String diag) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.sideHit = sideHit;
            this.yaw = yaw;
            this.pitch = pitch;
            this.diag = diag;
        }
    }

    /**
     * Builds and assembles the fixture, puts the bot on the deck beside the seat, and aims it at the
     * deck until {@code reportMouseOver} names a ship block. Returns that block's SUBSPACE address -
     * the raytrace's own answer, never a computed one.
     */
    private Deck standOnTheDeckAndAimAtIt() throws Exception {
        int budget = budget();

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

        String found = findSeat();
        Matcher sm = SEAT_SUB.matcher(found);
        assertTrue("ARRANGEMENT: find-seat must resolve the assembled ship's subspace seat: " + found,
                sm.find());
        int seatSubY = Integer.parseInt(sm.group(2));

        JsonObject aim = null;
        double[] seatWorld = null;
        float usedYaw = 0.0F, usedPitch = 0.0F;
        for (int attempt = 0; attempt < budget && aim == null; attempt++) {
            found = findSeat();
            Matcher wm = SHIP_WORLD.matcher(found);
            if (!wm.find()) {
                bot().waitTicks(5);
                continue;
            }
            seatWorld = new double[]{Double.parseDouble(wm.group(1)),
                    Double.parseDouble(wm.group(2)), Double.parseDouble(wm.group(3))};

            exec("tp @a " + (seatWorld[0] + STAND_OFF_X) + " " + (seatWorld[1] + 1.0)
                    + " " + seatWorld[2] + " 0 0");
            bot().waitTicks(20);
            JsonObject state = bot().reportState();
            if (!isWorldReady(state)) {
                bot().waitTicks(5);
                continue;
            }

            for (float pitch : AIM_PITCHES) {
                bot().setLook(0.0F, pitch);
                // The raytrace refreshes once per client tick, so the new rotation needs a tick.
                bot().waitTicks(5);
                JsonObject candidate = bot().reportMouseOver();
                if (isShipDeckHit(candidate, seatSubY)) {
                    aim = candidate;
                    usedYaw = 0.0F;
                    usedPitch = pitch;
                    break;
                }
            }
        }

        String diag = " seatWorld=" + java.util.Arrays.toString(seatWorld)
                + " seatSubY=" + seatSubY + " eye=" + EYE_HEIGHT
                + " lastMouseOver=" + bot().reportMouseOver() + " findSeat=" + found;

        assertTrue("ARRANGEMENT: the crosshair must resolve a BLOCK of the assembled ship when aimed "
                + "at the deck the bot is standing on. A MISS here means the raytrace never reaches "
                + "the ship, which is a finding in its own right - and it makes every click below "
                + "unmeasurable." + diag, aim != null);

        return new Deck(aim.get("blockX").getAsInt(), aim.get("blockY").getAsInt(),
                aim.get("blockZ").getAsInt(),
                aim.has("sideHit") ? aim.get("sideHit").getAsString() : "",
                usedYaw, usedPitch, diag + " aim=" + aim);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static int budget() {
        return (int) (40 * TestTimeouts.factor());
    }

    private ClientBot bot() {
        return clientHarness.bot();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    /** The SERVER's reading of a block at a SUBSPACE position - the only honest one after a click. */
    private String blockAt(int x, int y, int z) throws Exception {
        return exec("artest block at 0 " + x + " " + y + " " + z);
    }

    private String shipInfoAtBase() throws Exception {
        return exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
    }

    private String findSeat() throws Exception {
        return exec("artest vs find-seat 0 " + BX + " " + (BY + 5) + " " + BZ);
    }

    private static boolean isWorldReady(JsonObject report) {
        return report != null && report.has("worldReady") && report.get("worldReady").getAsBoolean();
    }

    /**
     * A crosshair reading that is a block of the SHIP rather than of the world: the physics mod
     * reports a ship hit at the ship's own subspace address, whose Y sits at the seat's own height
     * band - the world under this fixture is hundreds of blocks away in Y and every subspace X/Z is
     * far outside any terrain the bot could be looking at.
     */
    private static boolean isShipDeckHit(JsonObject aim, int seatSubY) {
        if (aim == null || !aim.has("typeOfHit") || !"BLOCK".equals(aim.get("typeOfHit").getAsString())) {
            return false;
        }
        if (!aim.has("blockY") || !aim.has("sideHit")) {
            return false;
        }
        return Math.abs(aim.get("blockY").getAsInt() - seatSubY) <= 4
                && "up".equalsIgnoreCase(aim.get("sideHit").getAsString());
    }

    private static boolean isBlockAt(JsonObject aim, int x, int y, int z) {
        return aim != null && aim.has("blockX")
                && aim.get("blockX").getAsInt() == x
                && aim.get("blockY").getAsInt() == y
                && aim.get("blockZ").getAsInt() == z;
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
