package zmaster587.advancedRocketry.test.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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
import org.lwjgl.input.Keyboard;

import zmaster587.advancedRocketry.hyperdrive.DriveTuning;

import static org.junit.Assert.assertTrue;

/**
 * A jump-capable craft assembles WHOLE, and a pilot standing on its deck can reach both of the
 * blocks he has to touch by hand: the pilot seat and the navigation console.
 *
 * <p><b>Why this exists.</b> The hyperjump craft is the first AR build whose player-facing surface is
 * two separate blocks aboard an assembled physics ship — you sit down in one and open a GUI on the
 * other. Every earlier ship test needed only the seat, so the fixture that carries the drive never
 * had anywhere to stand: a pilot on the launch pad squints up at the hull from four blocks below,
 * which is inside the range where the physics mod's raytrace silently discards a ship hit (it keeps
 * one only when it is STRICTLY nearer than the vanilla world result, and at the end of reach the two
 * tie). This test is what pins the reworked deck-based fixture: one square on the deck from which
 * both consoles are one block away, face-on.</p>
 *
 * <p><b>Two things are measured, in this order.</b></p>
 * <ol>
 *   <li><b>Every drive component actually joined the ship.</b> Assembly returning {@code ok} says
 *       nothing about which blocks came with it: the physics mod grows a ship by flood-filling out
 *       of the flight computer, so a machine that is only diagonally reachable is left standing on
 *       the pad, and the craft flies away without it. Two independent legs, because either alone
 *       can be fooled: the block is READ at its own address in the ship's subspace (and its pad cell
 *       is empty), and the ship's own drive/navigation layers are asked what they can SEE from the
 *       flight computer.</li>
 *   <li><b>Both consoles answer a real key press</b> — the same aim-and-press the human performs,
 *       through the same code path, with the crosshair confirmed on the intended block before every
 *       press so a red names the hop that failed rather than merely the outcome.</li>
 * </ol>
 *
 * <p>Assembly here is ARRANGEMENT, not subject: it is driven by the probe. Manual server + client
 * lifecycle, matching the other ship-boarding e2e tests. Gated on real Valkyrien Skies — run with
 * {@code -PwithVS}.</p>
 */
public class VSJumpDriveFixtureBoardingE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern SEAT_SUB = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern AFC_SUB = Pattern.compile(
            "\"afcX\":(-?\\d+),\"afcY\":(-?\\d+),\"afcZ\":(-?\\d+)");
    private static final Pattern SHIP_WORLD = Pattern.compile(
            "\"shipWorldX\":(-?[0-9.E\\-]+),\"shipWorldY\":(-?[0-9.E\\-]+),\"shipWorldZ\":(-?[0-9.E\\-]+)");
    private static final Pattern TO_WORLD = Pattern.compile(
            "\"worldX\":(-?[0-9.E\\-]+),\"worldY\":(-?[0-9.E\\-]+),\"worldZ\":(-?[0-9.E\\-]+)");
    private static final Pattern BLOCK_ID = Pattern.compile("\"block\":\"([^\"]*)\"");
    private static final Pattern IS_AIR = Pattern.compile("\"isAir\":(true|false)");

    private static final String VARIANT = "with-jump-drive";
    private static final int BX = 2900, BY = 64, BZ = 2900;

    /** The craft's own centre column on the pad, and the deck level the pilot walks on. */
    private static final int CRAFT_X = BX + 3, CRAFT_Y = BY + 1, CRAFT_Z = BZ + 3;

    /**
     * Every block of the craft, as an offset from the FLIGHT COMPUTER. The flight computer is the
     * origin because it is the one block whose subspace address the ship itself hands out (the pilot
     * seat stores the offset back to it), and because a rigid relocation into subspace preserves
     * these offsets exactly — which is what makes reading a component at a derived address a real
     * measurement rather than a guess.
     */
    private static final int[] OFF_SEAT = {1, 0, 0};
    private static final int[] OFF_NAV = {1, 0, 2};
    private static final int[] OFF_GENERATOR = {3, 0, 0};
    private static final int[] OFF_CAPACITOR = {3, 1, 0};
    private static final int[][] OFF_SINKS = {{2, 1, 0}, {3, 1, -1}, {3, 1, 1}, {3, 0, -1}};
    private static final int[] OFF_EMITTER = {0, 1, 0};

    /**
     * The one square the pilot works from: the deck cell immediately south of the seat. The seat is
     * one block north of it and the navigation console one block south, both face-on and both well
     * inside the couple of blocks within which a ship hit survives the raytrace's distance
     * comparison.
     */
    private static final int[] OFF_STAND = {1, 0, 1};

    /** Vanilla eye height for a standing player — the raytrace starts here, not at the feet. */
    private static final double EYE_HEIGHT = 1.62;

    /** The server drops a block interaction beyond (reach + 3), so the bot must observably be closer. */
    private static final double MAX_INTERACT_DIST_SQ = 64.0;

    /**
     * The use key's code. Mouse buttons enter {@code KeyBinding} as {@code -100 + button}, so RMB is
     * {@code -99} — the code the real mouse handler writes and the default binding of
     * {@code keyBindUseItem}.
     */
    private static final int KEY_USE_ITEM = -99;

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

        root = Files.createTempDirectory("forge-jumpdrive-boarding-");
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
    public void aJumpCraftAssemblesWholeAndBothItsConsolesAnswerARealKeyPress() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        int budget = (int) (40 * TestTimeouts.factor());

        // ---- ARRANGEMENT: build the craft and let the assembler turn it into a ship. -------------
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        String assemble = assembleFixture();
        assertTrue("ARRANGEMENT: a " + VARIANT + " build must route to a ship: " + assemble,
                assemble.contains("\"ok\":true"));

        exec("tp @a " + (BX + 0.5) + " " + (BY + 10) + " " + (BZ + 0.5) + " 0 0");
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
        int[] seatSub = readTriple(found, SEAT_SUB);
        int[] afcSub = readTriple(found, AFC_SUB);
        assertTrue("ARRANGEMENT: find-seat must resolve the ship's pilot seat AND the flight computer "
                        + "it was linked to — the whole layout below is addressed from the computer: "
                        + found,
                seatSub != null && afcSub != null);

        // The subspace copy is a RIGID relocation of the pad build, so the seat must sit at exactly
        // the offset it was built at. Without this control every derived address below is a guess,
        // and a component read as "missing" could just as easily be one read at the wrong cell.
        assertTrue("ARRANGEMENT CONTROL: the ship's subspace copy must preserve the build's own "
                        + "geometry — seat minus flight computer should be "
                        + describe(OFF_SEAT) + " but is "
                        + describe(new int[]{seatSub[0] - afcSub[0], seatSub[1] - afcSub[1],
                                seatSub[2] - afcSub[2]}) + ": " + found,
                seatSub[0] - afcSub[0] == OFF_SEAT[0]
                        && seatSub[1] - afcSub[1] == OFF_SEAT[1]
                        && seatSub[2] - afcSub[2] == OFF_SEAT[2]);

        // ---- 1a) Every drive component is THERE, at its own address inside the ship. -------------
        // Read block by block rather than trusting a summary count: a flood fill that dropped one
        // machine leaves every other number looking healthy.
        assertComponentAboard(afcSub, OFF_NAV, "advancedrocketry:navigationComputer", "navigation computer");
        assertComponentAboard(afcSub, OFF_GENERATOR, "advancedrocketry:hyperdriveGenerator", "field generator");
        assertComponentAboard(afcSub, OFF_CAPACITOR, "advancedrocketry:jumpCapacitor", "jump capacitor");
        for (int i = 0; i < OFF_SINKS.length; i++) {
            assertComponentAboard(afcSub, OFF_SINKS[i], "advancedrocketry:jumpHeatSink",
                    "heat sink #" + (i + 1));
        }
        assertComponentAboard(afcSub, OFF_EMITTER, "advancedrocketry:jumpFieldEmitter", "hull emitter");

        // ---- 1b) …and the SHIP can see them, which is a different question. ----------------------
        // A block that rode into subspace but never got welded to the flight computer is invisible to
        // its own ship: the drive resolves its machines from the computer outward, so this leg is
        // what says the assembler did its welding rather than merely its moving.
        String drive = exec("artest drive info 0 " + afcSub[0] + " " + afcSub[1] + " " + afcSub[2]);
        assertTrue("the assembled ship must have a FIELD GENERATOR aboard — drivePower is measured "
                        + "off the generator, so a zero here is a generator the ship cannot see: " + drive,
                readLong(drive, "drivePower") > 0L);
        assertTrue("…a CAPACITOR standing against that generator — a bank counts only while it "
                        + "touches the generator's footprint, so this also pins that the two arrived "
                        + "adjacent: " + drive,
                readLong(drive, "capacitors") >= 1L);
        assertTrue("…a HULL EMITTER: " + drive, readLong(drive, "emitters") >= 1L);
        assertTrue("…and a measured HULL — the assembler is the only thing that ever measures the "
                        + "craft's extent, so an unmeasured hull means the ship route did not run: "
                        + drive,
                drive.contains("\"hullMeasured\":true"));
        // The heat sinks have no tile entity of their own, so nothing links them and nothing counts
        // them by name: what says they are aboard is that the bank they cool recovers FASTER than a
        // bare controller could. Drain it and read the recovery rate back out of the cooldown, then
        // compare against the controller's own rate — a shape ("the sinks are contributing"), with
        // no balance number pinned on either side of the comparison.
        String emptied = exec("artest drive charge 0 " + afcSub[0] + " " + afcSub[1] + " "
                + afcSub[2] + " empty");
        String cooled = exec("artest drive info 0 " + afcSub[0] + " " + afcSub[1] + " " + afcSub[2]);
        long cooldown = readLong(cooled, "cooldownTicks");
        long burst = readLong(cooled, "burstCost");
        // The cooldown is now burst / the bank's ACCEPT rate — a best case at full inflow — and heat
        // sinks are what raise that ceiling. So the shape under test is unchanged: read the implied
        // throughput back out and compare it against what a bare controller alone would allow.
        long observedRate = cooldown > 0L ? burst / cooldown : Long.MAX_VALUE;
        assertTrue("the HEAT SINKS must be raising this ship's bank throughput. A drained bank quoted "
                        + "at " + observedRate + "/tick (burst " + burst + " over " + cooldown
                        + " ticks) is what an uncooled controller alone allows — the sinks rode into "
                        + "subspace but the bank is not walking to them. emptied=" + emptied
                        + " info=" + cooled,
                cooldown >= 0L && burst > 0L
                        && observedRate > DriveTuning.CAPACITOR_BASE_ACCEPT_RATE * 2L);

        String gate = exec("artest nav gate 0 " + afcSub[0] + " " + afcSub[1] + " " + afcSub[2]);
        assertTrue("the ship must find its own NAVIGATION COMPUTER from the flight computer. That "
                        + "search is by the console's stored link back to this computer and adopts "
                        + "nothing, so it is the assembler's welding that is under test here: " + gate,
                gate.contains("\"navComputer\":true"));

        int[] navSub = add(afcSub, OFF_NAV);
        String navStatus = exec("artest nav status 0 " + navSub[0] + " " + navSub[1] + " " + navSub[2]);
        assertTrue("and the console itself must report that link: " + navStatus,
                navStatus.contains("\"linked\":true"));

        // ---- ARRANGEMENT: an EMPTY hand, or a held stack consumes the press before the block. ----
        emptyTheHand();

        // ---- ARRANGEMENT CONTROL: the subspace->world mapping the aiming below leans on. ---------
        // The navigation console has no probe of its own that reports where it is in the world, so
        // its aim point is mapped through the ship's transform. Check that instrument against the
        // seat, whose world position a dedicated probe reports independently, before trusting it.
        double[] seatWorld = readTripleD(findSeat(), SHIP_WORLD);
        double[] seatMapped = toWorld(seatWorld, seatSub, 0.5, 0.2, 0.5);
        assertTrue("ARRANGEMENT CONTROL: mapping the seat's own subspace cell through the ship's "
                        + "transform must land where the seat probe says the seat is; otherwise the "
                        + "console's aim point below is computed by an instrument that does not work."
                        + " probe=" + java.util.Arrays.toString(seatWorld)
                        + " mapped=" + java.util.Arrays.toString(seatMapped),
                seatMapped != null && horizontalDistance(seatWorld, seatMapped) < 0.5);

        // ---- 2a) Board the PILOT SEAT with a real use-key press. ---------------------------------
        Aim seatAim = aimAt(afcSub, seatSub, OFF_STAND, 0.5, 0.2, 0.5, budget);
        assertAimed(seatAim, seatSub, "pilot seat", "pilotseat");

        bot().setKey(KEY_USE_ITEM, true);
        bot().waitTicks(5);
        bot().setKey(KEY_USE_ITEM, false);

        JsonObject riding = bot().reportRidingEntity();
        for (int attempt = 0; attempt < budget && !isRiding(riding); attempt++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
        }
        assertTrue("a real use-key press aimed at the deck ship's PILOT SEAT must seat the pilot — "
                        + "the crosshair was proven to be on that very block, so a failure here is "
                        + "the interaction being refused, not a missed aim. clientRiding=" + riding
                        + " serverRiding=" + exec("artest player riding-entity") + seatAim.diagnosis,
                isRiding(riding));

        // ---- ARRANGEMENT: leave the seat again, the way a pilot does. ----------------------------
        leaveTheSeat(budget);

        // ---- 2b) Open the NAVIGATION CONSOLE with the same real use-key press. -------------------
        Aim navAim = aimAt(afcSub, navSub, OFF_STAND, 0.5, 0.5, 0.5, budget);
        assertAimed(navAim, navSub, "navigation console", "navigationcomputer");

        bot().setKey(KEY_USE_ITEM, true);
        bot().waitTicks(5);
        bot().setKey(KEY_USE_ITEM, false);

        String screen = "";
        for (int attempt = 0; attempt < budget && screen.isEmpty(); attempt++) {
            bot().waitTicks(5);
            screen = ClientGuiTestSupport.screenOf(bot().reportState());
        }
        assertTrue("a real use-key press aimed at the assembled ship's NAVIGATION CONSOLE must open "
                        + "its GUI on the client. This is the second block a jump-capable craft asks "
                        + "the pilot to touch, and unlike the seat its whole answer IS the screen — a "
                        + "console that swallows the press leaves the pilot with no way to aim the "
                        + "ship at all. screen=\"" + screen + "\""
                        + " serverSideModuleBuild=" + exec("artest nav modules 0 " + navSub[0] + " "
                                + navSub[1] + " " + navSub[2])
                        + navAim.diagnosis,
                screen.startsWith("zmaster587.libVulpes.inventory.GuiModular"));

        bot().closeScreen();
    }

    // ---- aiming ---------------------------------------------------------------------------------

    /** One aim attempt's outcome: where the bot ended up, what it was looking at, and why. */
    private static final class Aim {
        JsonObject mouseOver;
        double distSq = Double.POSITIVE_INFINITY;
        String diagnosis = "";
    }

    /**
     * Stand on the deck square and put the crosshair on {@code targetSub}, retrying until the client
     * itself confirms the pick. Both the stand and the aim are re-derived from the ship's live pose
     * every attempt: a freshly assembled ship settles for a while, and a position computed once
     * against a stale pose leaves the bot in mid-air beside a ship that has since moved.
     */
    private Aim aimAt(int[] afcSub, int[] targetSub, int[] standOffset,
                      double tx, double ty, double tz, int budget) throws Exception {
        Aim aim = new Aim();
        int[] standSub = add(afcSub, standOffset);
        double[] shipAnchor = null;
        double[] standWorld = null;
        double[] targetWorld = null;
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;

        for (int attempt = 0; attempt < budget; attempt++) {
            shipAnchor = readTripleD(findSeat(), SHIP_WORLD);
            if (shipAnchor == null) {
                bot().waitTicks(5);
                continue;
            }
            // The floor of the stand cell is the deck's top surface, so the feet go at its y with a
            // sliver of clearance rather than at its centre.
            standWorld = toWorld(shipAnchor, standSub, 0.5, 0.05, 0.5);
            targetWorld = toWorld(shipAnchor, targetSub, tx, ty, tz);
            if (standWorld == null || targetWorld == null) {
                bot().waitTicks(5);
                continue;
            }
            exec("tp @a " + standWorld[0] + " " + standWorld[1] + " " + standWorld[2] + " 0 0");
            bot().waitTicks(20);

            JsonObject state = bot().reportState();
            if (!isWorldReady(state)) {
                bot().waitTicks(5);
                continue;
            }
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();

            double dx = targetWorld[0] - px;
            double dy = targetWorld[1] - (py + EYE_HEIGHT);
            double dz = targetWorld[2] - pz;
            aim.distSq = dx * dx + dy * dy + dz * dz;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            bot().setLook((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
                    (float) (-Math.toDegrees(Math.atan2(dy, horizontal))));
            // The raytrace is refreshed once per client tick (Minecraft.runTick), so the new
            // rotation needs at least one tick before objectMouseOver can reflect it.
            bot().waitTicks(5);

            aim.mouseOver = bot().reportMouseOver();
            if (isUnderCrosshair(aim.mouseOver, targetSub)) {
                break;
            }
        }
        aim.diagnosis = " observedPlayer=(" + px + "," + py + "," + pz + ")"
                + " standSubspace=" + describe(standSub)
                + " standWorld=" + java.util.Arrays.toString(standWorld)
                + " targetSubspace=" + describe(targetSub)
                + " targetWorld=" + java.util.Arrays.toString(targetWorld)
                + " distSq=" + aim.distSq + " mouseOver=" + aim.mouseOver;
        return aim;
    }

    /** Every hop of the aim, asserted separately, so a red says which one broke. */
    private void assertAimed(Aim aim, int[] targetSub, String what, String blockNeedle) {
        assertTrue("ARRANGEMENT: the bot must OBSERVABLY stand within the server's interaction reach "
                + "of the " + what + ", or the press is discarded before the block ever sees it."
                + aim.diagnosis, aim.distSq < MAX_INTERACT_DIST_SQ);
        assertTrue("HOP 1 (aim at the " + what + "): the client's crosshair must resolve to a BLOCK. "
                + "A MISS here means the sightline off the deck square is obstructed or the aim "
                + "maths is wrong, not that the ship is unclickable." + aim.diagnosis,
                aim.mouseOver != null && aim.mouseOver.has("typeOfHit")
                        && "BLOCK".equals(aim.mouseOver.get("typeOfHit").getAsString()));
        assertTrue("HOP 2 (aim at the " + what + "): the block under the crosshair must be the "
                + what + " as the CLIENT's own world reports it." + aim.diagnosis,
                aim.mouseOver.has("block") && aim.mouseOver.get("block").getAsString()
                        .toLowerCase(Locale.ROOT).contains(blockNeedle));
        assertTrue("HOP 3 (aim at the " + what + "): the raytrace must report the block's SUBSPACE "
                + "address " + describe(targetSub) + " — that is what the interaction is handed, "
                + "and it is why a world-position shortcut can never reach an assembled ship."
                + aim.diagnosis,
                isUnderCrosshair(aim.mouseOver, targetSub));
    }

    private static boolean isUnderCrosshair(JsonObject aim, int[] sub) {
        return aim != null
                && aim.has("typeOfHit") && "BLOCK".equals(aim.get("typeOfHit").getAsString())
                && aim.has("blockX")
                && aim.get("blockX").getAsInt() == sub[0]
                && aim.get("blockY").getAsInt() == sub[1]
                && aim.get("blockZ").getAsInt() == sub[2];
    }

    // ---- component membership -------------------------------------------------------------------

    /**
     * One component: present at its derived address inside the ship, and gone from the pad. The pad
     * half is the control — the assembler lifts the craft a block before the physics mod takes it,
     * so a machine that never made it into the ship is still standing right there.
     */
    private void assertComponentAboard(int[] afcSub, int[] offset, String blockId, String what)
            throws Exception {
        int[] sub = add(afcSub, offset);
        String aboard = exec("artest block at 0 " + sub[0] + " " + sub[1] + " " + sub[2]);
        // Compared case-insensitively: the registry lowercases what the mod registered, so the id a
        // probe reads back is never spelled the way the source spells it.
        assertTrue("the " + what + " must have joined the ship: the flood fill out of the flight "
                        + "computer decides what comes along, and a machine it could not reach is "
                        + "left behind while the craft flies off without it. Expected " + blockId
                        + " at subspace " + describe(sub) + " (offset " + describe(offset)
                        + " from the flight computer at " + describe(afcSub) + "): " + aboard,
                blockId.toLowerCase(Locale.ROOT)
                        .equals(readGroup(aboard, BLOCK_ID).toLowerCase(Locale.ROOT)));

        int[] lifted = liftedBuildPos(offset);
        String onPad = exec("artest block at 0 " + lifted[0] + " " + lifted[1] + " " + lifted[2]);
        assertTrue("CONTROL: …and it must be GONE from the pad. A " + what + " still standing at "
                        + describe(lifted) + " is a block the physics mod declined to take, and the "
                        + "reading above would then be of some other ship's machine: " + onPad,
                Boolean.parseBoolean(readGroup(onPad, IS_AIR)));
    }

    /** Where a component sits on the pad after the assembler's one-block lift, in world coordinates. */
    private static int[] liftedBuildPos(int[] offsetFromAfc) {
        // The flight computer is built at (CRAFT_X - 1, CRAFT_Y + 4, CRAFT_Z); the assembler cuts the
        // craft out and pastes it one block higher before handing it to the physics mod.
        return new int[]{CRAFT_X - 1 + offsetFromAfc[0], CRAFT_Y + 5 + offsetFromAfc[1],
                CRAFT_Z + offsetFromAfc[2]};
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ClientBot bot() {
        return clientHarness.bot();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String shipInfoAtBase() throws Exception {
        return exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
    }

    /** The seat's subspace address, its flight computer's, and the ship's live world position. */
    private String findSeat() throws Exception {
        return exec("artest vs find-seat 0 " + BX + " " + (BY + 5) + " " + BZ);
    }

    /**
     * A subspace point mapped into the world through the ship's own transform. {@code shipAnchor} is
     * any world point aboard that ship — the seat's live position serves.
     */
    private double[] toWorld(double[] shipAnchor, int[] sub, double dx, double dy, double dz)
            throws Exception {
        if (shipAnchor == null) {
            return null;
        }
        String mapped = exec("artest vs to-world 0 " + shipAnchor[0] + " " + shipAnchor[1] + " "
                + shipAnchor[2] + " " + (sub[0] + dx) + " " + (sub[1] + dy) + " " + (sub[2] + dz));
        return readTripleD(mapped, TO_WORLD);
    }

    /** Server-side clear plus a client-observed empty hand (a held stack eats the use press). */
    private void emptyTheHand() throws Exception {
        exec("clear @a");
        bot().selectHotbar(0);
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonObject items = bot().reportPlayerItems();
            if (isWorldReady(items) && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
            bot().waitTicks(5);
        }
        assertTrue("ARRANGEMENT: the bot's main hand must be EMPTY so the use press reaches the "
                + "block rather than being consumed by a held item; held=" + heldId,
                heldId != null && heldId.isEmpty());
    }

    /** Sneak off the seat the way a pilot does, falling back to the probe if the key path misses. */
    private void leaveTheSeat(int budget) throws Exception {
        boolean off = false;
        bot().holdKey(Keyboard.KEY_LSHIFT);
        for (int attempt = 0; attempt < budget && !off; attempt++) {
            bot().waitTicks(2);
            off = !isRiding(bot().reportRidingEntity());
        }
        bot().releaseKey(Keyboard.KEY_LSHIFT);
        if (!off) {
            exec("artest player dismount");
            bot().waitTicks(5);
            off = !isRiding(bot().reportRidingEntity());
        }
        assertTrue("ARRANGEMENT: the pilot must leave the seat before reaching for the console — a "
                + "seated player's use press goes to the ship, not to the block he is looking at.",
                off);
    }

    private String assembleFixture() throws Exception {
        int cx1 = (BX - 2) >> 4, cz1 = (BZ - 2) >> 4;
        int cx2 = (BX + 7) >> 4, cz2 = (BZ + 7) >> 4;
        assertTrue("ARRANGEMENT: chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("ARRANGEMENT: pre-clear failed",
                exec("artest fill 0 " + (BX - 2) + " " + (BY + 1) + " " + (BZ - 2)
                        + " " + (BX + 7) + " " + (BY + 12) + " " + (BZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + BX + " " + BY + " " + BZ + " " + VARIANT);
        assertTrue("ARRANGEMENT: fixture (" + VARIANT + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("ARRANGEMENT: fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    // ---- tiny parsing ---------------------------------------------------------------------------

    private static boolean isWorldReady(JsonObject report) {
        return report != null && report.has("worldReady") && report.get("worldReady").getAsBoolean();
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private static int[] add(int[] base, int[] offset) {
        return new int[]{base[0] + offset[0], base[1] + offset[1], base[2] + offset[2]};
    }

    private static String describe(int[] triple) {
        return "(" + triple[0] + "," + triple[1] + "," + triple[2] + ")";
    }

    private static double horizontalDistance(double[] a, double[] b) {
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int[] readTriple(String json, Pattern p) {
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))};
    }

    private static double[] readTripleD(String json, Pattern p) {
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3))};
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : Long.MIN_VALUE;
    }

    private static String readGroup(String json, Pattern p) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
