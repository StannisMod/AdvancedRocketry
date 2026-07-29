package zmaster587.advancedRocketry.test.milestone;

import java.nio.charset.StandardCharsets;
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

import static org.junit.Assert.assertTrue;

/**
 * The first milestone loop, walked the way a player walks it: build a jump-capable craft, turn it
 * into a ship at the assembler's own screen, sit down in the pilot seat and fly it off the planet.
 *
 * <p><b>What makes this a MILESTONE test rather than another e2e.</b> Every earlier test in this
 * repo is allowed to take a shortcut somewhere — a probe assembles the ship, a probe mounts the
 * seat, a probe starts the crossing — because each one is aimed at a single mechanic and the rest
 * is arrangement. Here nothing a player does may be done for him. Probes may place blocks, seed
 * config and take readings; every ACT is the client's: the crosshair is put on a block and the real
 * use key is pressed, the assembler's Scan and Build are real button clicks on the real screen, the
 * seat is boarded by aiming at it, and the climb to orbit is a key held down. What the probes are
 * used for here is the opposite of driving — they are the instruments the test reads the world
 * with.</p>
 *
 * <p><b>The legs, in order.</b></p>
 * <ol>
 *   <li>The craft is placed on a pad (blocks only — placing blocks is not an act a player performs
 *       through any interface a test can drive, and the fixture is the settled way to stand a
 *       structure up).</li>
 *   <li>The client walks up to the rocket assembler, opens its screen with a real use-key press,
 *       and clicks Scan and then Build. The completion signal is a VS ship existing where there was
 *       none — a tier-2 craft spawns no {@code EntityRocket}, so a rocket list would report success
 *       for a build that produced nothing.</li>
 *   <li>The client boards the pilot seat of the ship he just built, by aiming at the seat block and
 *       pressing use — and the seating is read back from the CLIENT, not from the server.</li>
 *   <li>He holds the vertical-up key until the ship crosses the atmosphere ceiling, and the arrival
 *       is measured from the client too: his own dimension changed, the ship is on the space
 *       ledger, and he is still in his seat.</li>
 * </ol>
 *
 * <p><b>The one arrangement knob</b> is the orbit line, seeded to the config minimum so the powered
 * climb takes seconds instead of minutes. It changes how LONG the climb is, not what the crossing
 * decides: the trigger predicate is "the ship is above the dimension's orbit line", whatever that
 * line happens to be. Fuel is turned off for the same class of reason — a fuel-adequacy check on
 * the pad is a different mechanic with its own tests, and leaving it on would make this loop fail
 * for a reason that has nothing to do with the loop.</p>
 *
 * <p>Manual server + client lifecycle: the config has to be written into the game directory before
 * the server boots. Gated on real Valkyrien Skies — run with {@code -PwithVS}.</p>
 */
public class M1PlanetToPlanetMilestoneE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern LEDGER = Pattern.compile("\"ledger\":(-?\\d+)");
    private static final Pattern SLOT_DIMS = Pattern.compile("\"slotDims\":\\[([0-9,\\-]*)]");
    private static final Pattern SEAT_SUB = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern AFC_SUB = Pattern.compile(
            "\"afcX\":(-?\\d+),\"afcY\":(-?\\d+),\"afcZ\":(-?\\d+)");
    private static final Pattern SHIP_WORLD = Pattern.compile(
            "\"shipWorldX\":(-?[0-9.E\\-]+),\"shipWorldY\":(-?[0-9.E\\-]+),\"shipWorldZ\":(-?[0-9.E\\-]+)");
    private static final Pattern TO_WORLD = Pattern.compile(
            "\"worldX\":(-?[0-9.E\\-]+),\"worldY\":(-?[0-9.E\\-]+),\"worldZ\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_ID = Pattern.compile("\"shipId\":\"([^\"]+)\"");
    private static final Pattern CELL = Pattern.compile("\"cell\":\"([^\"]*)\"");
    private static final Pattern NAV_TARGET = Pattern.compile("\"target\":(null|\"[^\"]*\")");
    private static final Pattern NAV_ARMED = Pattern.compile("\"armed\":(true|false)");
    private static final Pattern NAV_SHIP_ADDRESSES = Pattern.compile("\"ship\":(-?\\d+)");
    /** One body of a {@code space bodies} ship entry, in the order the probe writes its keys. */
    private static final Pattern BODY = Pattern.compile(
            "\\{\"dim\":(-?\\d+),\"kind\":\"([A-Z_]+)\",\"descendTarget\":(true|false),"
                    + "\"distance\":(\\d+)}");
    /**
     * The console is aimed at somewhere a ship can put down: the BODY it names may be descended to,
     * and its dimension is a real world rather than one of the space subsystem's own slot worlds.
     * The slot term matters — a slot world is void, and a ship "landing" in one has not reached a
     * planet at all.
     *
     * <p>Asked of the BODY rather than of the aim CELL on purpose. The aim is a prediction of where
     * that body will be when the ship arrives, so the cell is empty right now and will be empty
     * again later; the body is what the pilot picked and what he expects to find.</p>
     */
    private static final Pattern LANDABLE = Pattern.compile(
            "\"targetDescendTarget\":true,\"targetSlotWorld\":false");

    /** The body the console is aimed at, from {@code nav status}. */
    private static final Pattern NAV_TARGET_DIM = Pattern.compile("\"targetDim\":(-?\\d+)");
    /** The bodies of ONE cell, from {@code space cell-info} (not the whole system's list). */
    private static final Pattern CELL_BODIES = Pattern.compile("\"cellBodies\":\\[(.*?)]");
    /** Slot dimension ids that Advanced Rocketry also holds a body for — always empty. */
    private static final Pattern SLOT_DIMS_ALSO_BODIES =
            Pattern.compile("\"slotDimsAlsoBodies\":\\[([0-9,\\-]*)]");

    /** A jump-capable craft with a walkable deck: the ship this milestone is about. */
    private static final String VARIANT = "with-jump-drive";

    /** Far from every other fixture site, so a stray ship from another run can never be read here. */
    private static final int BX = 8400, BY = 64, BZ = 8400;

    /**
     * The seeded atmosphere ceiling: the config key's own minimum. The ONE arrangement knob in this
     * test — it shortens the climb from minutes to seconds and does not touch the entry predicate,
     * which asks whether the ship is above the line, not where the line is.
     */
    private static final int ORBIT_LINE = 255;

    /** Seat and standing square, as offsets from the ship's FLIGHT COMPUTER (the deck layout). */
    private static final int[] OFF_SEAT = {1, 0, 0};
    private static final int[] OFF_STAND = {1, 0, 1};
    /**
     * The navigation console, as an offset from the flight computer: two cells beyond the seat over
     * the one square the drive bay leaves to stand on, so the seated pilot has a clear sightline to
     * it without leaving his seat.
     */
    private static final int[] OFF_NAV = {1, 0, 2};

    /** Vanilla eye height for a standing player — a raytrace starts here, not at the feet. */
    private static final double EYE_HEIGHT = 1.62;

    /** The server drops a block interaction beyond (reach + 3), so the bot must observably be closer. */
    private static final double MAX_INTERACT_DIST_SQ = 64.0;

    /**
     * The use key's code. Mouse buttons enter {@code KeyBinding} as {@code -100 + button}, so RMB is
     * {@code -99} — the code the real mouse handler writes and the default binding of
     * {@code keyBindUseItem}.
     */
    private static final int KEY_USE_ITEM = -99;

    /** Button ids the assembler assigns its own controls: 0 = Scan, 1 = Build. */
    private static final int BUTTON_SCAN = 0;
    private static final int BUTTON_BUILD = 1;

    /**
     * Button ids the navigation console assigns its own controls: 5 = ARM, and one "pick" button per
     * listed address starting at 10.
     */
    private static final int BUTTON_ARM = 5;
    private static final int BUTTON_PICK_FIRST = 10;

    /** How many addresses the console puts a pick button next to. */
    private static final int LISTED_ADDRESSES = 4;

    /** The console's own slots, in container numbering: 0 = the source slot, 1 = the SHIP's slot. */
    private static final int CONSOLE_SLOT_SHIP = 1;

    /** The item the address list lives on. */
    private static final String CRYSTAL_ITEM = "advancedrocketry:memoryCrystal";

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

        root = Files.createTempDirectory("forge-m1-milestone-");
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        // Both knobs are seeded BEFORE the server boots, because the config is read once at load.
        // The file key is `rocketsRequireFuel`; the field (and the probe that reads it back) is
        // `rocketRequireFuel` — the assertion below is what proves this file was actually parsed
        // rather than silently ignored for a syntax the config reader did not recognise.
        String cfg = "# seeded by the milestone e2e\n"
                + "rockets {\n"
                + "    B:rocketsRequireFuel=false\n"
                + "    I:orbitHeight=" + ORBIT_LINE + "\n"
                + "}\n";
        Files.write(arConfigDir.resolve("advancedRocketry.cfg"), cfg.getBytes(StandardCharsets.UTF_8));

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
    public void aPlayerBuildsHisShipAtTheAssemblerBoardsItAndFliesItOffThePlanet() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        int budget = (int) (40 * TestTimeouts.factor());
        long tLeg = System.currentTimeMillis();

        // ---- LEG 0: the world this loop is walked in is the one the test asked for. -------------
        String fuelCfg = exec("artest config get rocketRequireFuel");
        assertTrue("ARRANGEMENT: the seeded config file must have been PARSED. This reads the live "
                        + "field back through the same config object production uses, so a stale "
                        + "`true` here means the file was written in a syntax the config reader "
                        + "skipped and every later leg would be running against defaults: " + fuelCfg,
                fuelCfg.contains("\"value\":false"));

        String status = exec("artest space subsystem-status");
        assertTrue("ARRANGEMENT: the production space subsystem must be REGISTERED — it owns the "
                        + "cells a ship arrives in, and without it the climb leg has nowhere to go: "
                        + status,
                status.contains("\"registered\":true"));
        // No dimension id may be owned twice. A space slot is a void world the subsystem rebinds at
        // will; a body is a place the universe registry describes, a crystal can name and a ship can
        // be flown to. One id doing both makes the registry's description a lie — it advertises a
        // planet whose world is empty space — and the descent that follows lands the ship nowhere.
        Matcher collided = SLOT_DIMS_ALSO_BODIES.matcher(status);
        assertTrue("ARRANGEMENT: subsystem-status must report the slot/body id overlap: " + status,
                collided.find());
        assertTrue("no space-slot dimension may also be an Advanced Rocketry body. Forge's free-id "
                        + "scan cannot see AR's own body ids (a surface-less body is never registered "
                        + "with Forge), so the pool can take one unless it asks AR too. "
                        + "slotDimsAlsoBodies=[" + collided.group(1) + "] status=" + status,
                collided.group(1).isEmpty());
        System.out.println("[M1] leg 0 (config + subsystem) " + elapsed(tLeg) + " status=" + status);

        // ---- LEG 1: stand the craft up on a pad. Blocks only — no interaction happens here. -----
        tLeg = System.currentTimeMillis();
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        int[] builderPos = placeFixture();
        System.out.println("[M1] leg 1 (fixture placed) " + elapsed(tLeg)
                + " builder=" + describe(builderPos));

        // ---- LEG 2: the CLIENT turns that pile of blocks into a ship, at the machine's screen. --
        tLeg = System.currentTimeMillis();
        int ships = assembleThroughTheAssemblersScreen(builderPos, budget);
        assertTrue("the craft a player built on the pad must become a SHIP when he presses Scan and "
                        + "then Build on the assembler's own screen. This is the whole tier-2 build "
                        + "route as a human drives it, and its completion signal has to be the ship "
                        + "itself: a tier-2 craft spawns no rocket entity, so the rocket list would "
                        + "report a healthy nothing. ships=" + ships
                        + " spawnDiag=" + exec("artest vs spawn-diag")
                        + " builderEnergy=" + energyAt(builderPos),
                ships >= 1);
        System.out.println("[M1] leg 2 (client-driven assembly) " + elapsed(tLeg) + " ships=" + ships);

        // ---- LEG 3: the CLIENT sits down in the seat of the ship he just built. -----------------
        tLeg = System.currentTimeMillis();
        String found = findSeat();
        int[] seatSub = readTriple(found, SEAT_SUB);
        int[] afcSub = readTriple(found, AFC_SUB);
        assertTrue("ARRANGEMENT: the assembled ship must expose a pilot seat AND the flight computer "
                        + "it was linked to — the deck square the pilot works from is addressed from "
                        + "that computer: " + found,
                seatSub != null && afcSub != null);

        // The subspace copy is a RIGID relocation of the pad build, so the seat must sit at exactly
        // the offset it was built at. Without this control the deck square the pilot is placed on
        // below is a guess, and a failed press could just as easily be a bot standing nowhere.
        assertTrue("ARRANGEMENT CONTROL: the ship's subspace copy must preserve the build's own "
                        + "geometry — seat minus flight computer should be " + describe(OFF_SEAT)
                        + " but is " + describe(new int[]{seatSub[0] - afcSub[0],
                                seatSub[1] - afcSub[1], seatSub[2] - afcSub[2]}) + ": " + found,
                seatSub[0] - afcSub[0] == OFF_SEAT[0]
                        && seatSub[1] - afcSub[1] == OFF_SEAT[1]
                        && seatSub[2] - afcSub[2] == OFF_SEAT[2]);

        // A held stack consumes the use press before the block ever sees it.
        emptyTheHand();

        Aim seatAim = aimAt(afcSub, seatSub, OFF_STAND, 0.5, 0.2, 0.5, budget);
        assertAimed(seatAim, seatSub, "pilot seat", "pilotseat");

        pressUse();
        JsonObject riding = bot().reportRidingEntity();
        for (int attempt = 0; attempt < budget && !isRiding(riding); attempt++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
        }
        String serverRiding = exec("artest player riding-entity");
        assertTrue("a real use-key press aimed at the ship's PILOT SEAT must seat the pilot, as the "
                        + "CLIENT itself renders him. The crosshair was proven to be on that very "
                        + "block, so a red here is the interaction being refused rather than a missed "
                        + "aim. clientRiding=" + riding + " serverRiding=" + serverRiding
                        + seatAim.diagnosis,
                isRiding(riding));
        assertTrue("…and what he is riding must be the seat's own mount entity, not some other body "
                        + "he happened to bump into. clientRiding=" + riding,
                riding.has("entityClass")
                        && riding.get("entityClass").getAsString().endsWith("EntityDummy"));
        assertTrue("…and the SERVER must agree he is aboard that same mount — a seat that exists only "
                        + "on the client carries no input at all. serverRiding=" + serverRiding
                        + " clientRiding=" + riding,
                serverRiding.contains("EntityDummy"));
        System.out.println("[M1] leg 3 (boarded by key press) " + elapsed(tLeg) + " riding=" + riding);

        // ---- LEG 4: he flies it off the planet, by HOLDING the key. -----------------------------
        // No permaload here on purpose: a client e2e already has an observer aboard the ship, and a
        // harness affordance that kept the ship loaded would hide a production failure to keep its
        // own ship loaded during the crossing.
        tLeg = System.currentTimeMillis();
        int ledger = 0;
        int climbBudget = (int) (800 * TestTimeouts.factor());
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < climbBudget && ledger < 1; attempt++) {
                bot().waitTicks(5);
                // While the crossing runs the origin-world ship vanishes (the cut), so the ledger —
                // not a position read — is the single source of arrival truth.
                if ((attempt & 1) == 1) {
                    Matcher lm = LEDGER.matcher(exec("artest space subsystem-status"));
                    if (lm.find()) {
                        ledger = Integer.parseInt(lm.group(1));
                    }
                }
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        String statusAfter = exec("artest space subsystem-status");
        assertTrue("a ship climbing under its own power past the orbit line (" + ORBIT_LINE + ") must "
                        + "be taken by the entry crossing and SETTLE in a space cell — that is the "
                        + "on-ramp a real player flies, and holding one key is the whole of his input. "
                        + "ledger=" + ledger + " status=" + statusAfter
                        + " clientRiding=" + bot().reportRidingEntity()
                        + " delivery=" + exec("artest vs seat-delivery"),
                ledger >= 1);
        System.out.println("[M1] leg 4 (powered climb to the cell) " + elapsed(tLeg)
                + " status=" + statusAfter);

        // ---- LEG 5: the arrival, measured from the CLIENT. --------------------------------------
        tLeg = System.currentTimeMillis();
        Matcher sd = SLOT_DIMS.matcher(statusAfter);
        assertTrue("ARRANGEMENT: subsystem-status must list its slot dims: " + statusAfter, sd.find());
        String slotDims = "," + sd.group(1) + ",";

        // (1) The client's OWN world is a space cell. report_state carries no dimension, so this is
        // read from the client's world info — the pilot followed his ship through the seam or he
        // did not, and nothing server-side can answer that for him.
        int clientDim = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < budget; attempt++) {
            bot().waitTicks(5);
            JsonObject weather = bot().reportWeather();
            if (weather.has("dim")) {
                clientDim = weather.get("dim").getAsInt();
                if (slotDims.contains("," + clientDim + ",")) {
                    break;
                }
            }
        }
        assertTrue("after the crossing the CLIENT itself must be in a space-cell dimension — a pilot "
                        + "whose ship left without him is the exact failure this leg exists to catch. "
                        + "clientDim=" + clientDim + " slotDims=[" + sd.group(1) + "] status="
                        + statusAfter,
                slotDims.contains("," + clientDim + ","));

        // (2) Still seated, sampled TWICE with a wait between: a seat lost in the crossing can read
        // riding=true for one packet-lag frame, but never twice in a row.
        JsonObject arrivalRiding = bot().reportRidingEntity();
        boolean prev = isRiding(arrivalRiding);
        boolean seatedTwice = false;
        for (int attempt = 0; attempt < budget && !seatedTwice; attempt++) {
            bot().waitTicks(5);
            arrivalRiding = bot().reportRidingEntity();
            seatedTwice = prev && isRiding(arrivalRiding);
            prev = isRiding(arrivalRiding);
        }
        assertTrue("the pilot who FLEW his own ship into space must still be in his seat on arrival — "
                        + "a crossing must never stand him up. riding=" + arrivalRiding
                        + " clientDim=" + clientDim
                        + " delivery=" + exec("artest vs seat-delivery"),
                seatedTwice);
        System.out.println("[M1] leg 5 (arrival, client-observed) " + elapsed(tLeg)
                + " clientDim=" + clientDim + " riding=" + arrivalRiding);

        // ---- LEG 6: the pilot picks a destination and arms the jump, at the console, by hand. ---
        tLeg = System.currentTimeMillis();
        int slotDim = clientDim;

        // The ship as the ledger knows it, and the cell it is about to leave. The default galaxy is
        // generated from a wall-clock seed, so NOTHING about the destination may be assumed: every
        // cell and dimension this leg and the next work with is READ from the world the pilot is in.
        String afcProbe = exec("artest space find-afc " + slotDim);
        assertTrue("ARRANGEMENT: the ship the pilot flew up must be findable in the space cell he "
                        + "arrived in — the ledger says he is here, so a ship that cannot be located "
                        + "means the arrival left no body behind: " + afcProbe,
                afcProbe.contains("\"found\":true"));
        String shipId = readString(afcProbe, SHIP_ID);
        String launchCell = readString(exec("artest space ledger-get " + shipId), CELL);
        assertTrue("ARRANGEMENT: the ledger must name the cell the jump departs FROM — without it "
                        + "the arrival assertion below cannot tell a jump from a no-op. shipId="
                        + shipId + " ledger=" + exec("artest space ledger-get " + shipId),
                launchCell != null && !launchCell.isEmpty());

        // Read while he is still SEATED, so the ship's own world point is on record before the one
        // moment in this loop when the pilot is not a reliable pointer to his craft.
        String seatProbe = findSeatAboard(slotDim, budget);
        int[] navAfcSub = readTriple(seatProbe, AFC_SUB);
        assertTrue("ARRANGEMENT: the arrived ship must still expose the seat and the flight computer "
                        + "it is linked to — the console the pilot reaches for is addressed from that "
                        + "computer: " + seatProbe,
                navAfcSub != null);
        int[] navSub = add(navAfcSub, OFF_NAV);

        // The drive's capacitor holds no charge on a freshly built craft and this ship carries no
        // generator, so the window it needs is paid for here. Seeding stored energy is the same class
        // of arrangement as the assembler's power above — a player flies with a charged drive, and
        // the acts this leg is about are the crystal, the two clicks and the key press.
        String charged = exec("artest drive charge " + slotDim + " " + describeArgs(navAfcSub) + " full");
        System.out.println("[M1] drive charged: " + charged);

        // He stands up to navigate. Not a convenience: while a pilot is at the controls the client
        // holds his view ON THE SHIP'S ATTITUDE every tick, so his crosshair is locked dead ahead and
        // level and cannot be put on anything — the console included. Navigation is therefore deck
        // work, which is what the one clear square between the seat and the console is for. Standing
        // up is an ACT, so it is a real key: sneak, the way anyone leaves a vehicle.
        standUp(budget);

        // The crystal is handed over the way any item is handed to a player; putting it IN the console
        // is the act, and it is the act that seeds the addresses (nothing else in the game does).
        exec("give @a " + CRYSTAL_ITEM + " 1");
        // Hold nothing: the console is opened with a bare hand, so nothing can eat the use press, and
        // the crystal is then moved by real slot clicks rather than by being used from the hand.
        holdNothing(budget);

        String consoleScreen = openConsoleFromTheDeck(slotDim, navAfcSub, navSub, budget);
        assertTrue("a real use-key press aimed at the NAVIGATION CONSOLE must open its screen on the "
                        + "client — every choice the pilot makes about where to fly is made on that "
                        + "screen, so a console that swallows the press strands a jump-capable ship. "
                        + "screen=\"" + consoleScreen + "\"",
                consoleScreen.startsWith("zmaster587.libVulpes.inventory.GuiModular"));

        // Move the crystal into the SHIP slot with an explicit pick-up / put-down pair. A shift-click
        // would merge it into the source slot instead, where the address list is never read from —
        // silently, with no error — so the two clicks are what a player actually performs here.
        JsonObject slots = bot().reportSlots();
        int crystalSlot = slotHolding(slots, CRYSTAL_ITEM);
        assertTrue("ARRANGEMENT: the crystal handed to the pilot must be reachable from the console's "
                        + "own window — the console shows the hotbar and nothing else, so a crystal "
                        + "anywhere but the hotbar could never be inserted. slots=" + slots,
                crystalSlot >= 0);
        bot().clickSlot(crystalSlot, 0, "PICKUP");
        bot().waitTicks(5);
        bot().clickSlot(CONSOLE_SLOT_SHIP, 0, "PICKUP");

        int listed = 0;
        String navStatus = "";
        for (int attempt = 0; attempt < budget && listed < 2; attempt++) {
            bot().waitTicks(5);
            navStatus = exec("artest nav status " + slotDim + " " + describeArgs(navSub));
            listed = readInt(navStatus, NAV_SHIP_ADDRESSES, 0);
        }
        assertTrue("putting a memory crystal into the console's SHIP slot must give the pilot a list "
                        + "of places he can fly to — that insertion is the only thing in the game that "
                        + "writes a starter crystal's addresses, so an empty list here leaves a "
                        + "jump-capable ship with nowhere to go, and a list of ONE leaves him only the "
                        + "cell he is already in. listed=" + listed
                        + " nav=" + navStatus + " slots=" + bot().reportSlots(),
                listed >= 2);

        // Reopen the window: its buttons are built when the screen is, so the list the pilot clicks
        // on is the one he sees after the crystal is in. Closing and looking again is what he does.
        bot().closeScreen();
        bot().waitTicks(10);
        consoleScreen = openConsoleFromTheDeck(slotDim, navAfcSub, navSub, budget);
        assertTrue("ARRANGEMENT: the console must reopen once the crystal is in it: " + consoleScreen,
                consoleScreen.startsWith("zmaster587.libVulpes.inventory.GuiModular"));

        // He picks where to go. Not blind: he clicks an address, sees what the console says is at it,
        // and moves on if that is not somewhere he can land — which is what the pick button's own body
        // readout is for. Every pick is a real button click; only the "what is there" is read by probe.
        // Entry 0 is skipped outright: it is the ship's own home cell, and the gate refuses a jump to
        // the cell you are already in.
        int pickIndex = -1;
        int targetDim = Integer.MIN_VALUE;
        String targetCell = "";
        String targetInfo = "";
        StringBuilder considered = new StringBuilder();
        for (int candidate = 1; candidate < LISTED_ADDRESSES && pickIndex < 0; candidate++) {
            bot().clickButtonById(BUTTON_PICK_FIRST + candidate);
            bot().waitTicks(10);
            String picked = exec("artest nav status " + slotDim + " " + describeArgs(navSub));
            String cell = unquote(readString(picked, NAV_TARGET));
            considered.append(' ').append(candidate).append("->").append(cell)
                    .append(picked.substring(Math.max(0, picked.indexOf('{'))));
            if (cell.isEmpty() || "null".equals(cell) || cell.equals(launchCell)) {
                continue;
            }
            if (LANDABLE.matcher(picked).find()) {
                pickIndex = candidate;
                targetCell = cell;
                targetDim = readInt(picked, NAV_TARGET_DIM, Integer.MIN_VALUE);
                targetInfo = picked;
            }
        }
        assertTrue("the addresses a starter crystal gives a pilot must include somewhere he can "
                        + "actually FLY TO AND LAND ON — a list of destinations none of which holds a "
                        + "planet is a map with no places on it, and the milestone loop has nowhere to "
                        + "go. considered=" + considered,
                pickIndex >= 0);
        assertTrue("a listed address must name a BODY, not merely a point in space — that identity is "
                        + "what keeps the ship aimed at the planet the pilot chose while it flies, and "
                        + "without it the console is handing him a coordinate the destination has "
                        + "already left. nav=" + targetInfo,
                targetDim != Integer.MIN_VALUE && targetDim >= 0);
        bot().waitTicks(10);
        bot().clickButtonById(BUTTON_ARM);
        bot().waitTicks(10);

        String armedStatus = "";
        boolean armed = false;
        for (int attempt = 0; attempt < budget && !armed; attempt++) {
            bot().waitTicks(5);
            armedStatus = exec("artest nav status " + slotDim + " " + describeArgs(navSub));
            armed = "true".equals(readString(armedStatus, NAV_ARMED));
        }
        String armChat = chatText(30);
        assertTrue("picking a listed address and pressing ARM must leave the ship ARMED at that "
                        + "address — those two clicks are the whole of how a player commits to a "
                        + "destination, and an unarmed ship refuses the jump key outright. armed="
                        + armed + " nav=" + armedStatus + " chat=" + armChat,
                armed);
        assertTrue("…and the pilot must be TOLD, in his own chat, that the ship is armed. A silent "
                        + "arming leaves him with no way to know the ship will move when he presses "
                        + "the key. chat=" + armChat + " nav=" + armedStatus,
                armChat.contains("jump armed") || armChat.contains("msg.jump.armed"));

        // Judged on the BODY, not on the aim cell. The aim is the computer's prediction of where that
        // body will be when the ship arrives, so it legitimately moves between the pick and the arm —
        // the planet is orbiting. What must NOT change is which planet.
        assertTrue("…and the destination it is armed at must still be the BODY he picked — an ARM that "
                        + "quietly re-aimed the ship would send him somewhere he never chose. "
                        + "pickedDim=" + targetDim + " nav=" + armedStatus,
                targetDim == readInt(armedStatus, NAV_TARGET_DIM, Integer.MIN_VALUE));
        assertTrue("…and the ship must still be able to say WHERE that body is: an armed jump whose "
                        + "target cannot be located is a burst about to be spent on nothing. nav="
                        + armedStatus,
                armedStatus.contains("\"targetResolved\":true"));
        System.out.println("[M1] leg 6 (target picked + armed at the console) " + elapsed(tLeg)
                + " launchCell=" + launchCell + " pick=" + pickIndex + " targetDim=" + targetDim
                + " target=" + targetCell
                + " targetInfo=" + targetInfo + " considered=" + considered
                + " drive=" + exec("artest drive info " + slotDim + " " + describeArgs(navAfcSub)));

        // ---- LEG 7: he fires the jump with the real jump key. -----------------------------------
        tLeg = System.currentTimeMillis();
        // The key handler bails outright while any screen is up, so the console is shut first — the
        // same thing a player does before reaching for the controls.
        bot().closeScreen();
        bot().waitTicks(10);

        // And he sits back down: the jump key is the PILOT's, routed through the seat he occupies, so
        // a player standing on his own deck cannot fire the drive he just armed.
        JsonObject reboarded = sitBackDown(slotDim, navAfcSub, budget);
        assertTrue("ARRANGEMENT: the pilot must be able to take his seat again after navigating — the "
                        + "jump he armed is fired from the controls, not from the deck. riding="
                        + reboarded + " serverRiding=" + exec("artest player riding-entity"),
                isRiding(reboarded));

        pressJumpKey();
        String pressChat = chatText(30);
        // Two legitimate branches, both real player paths: a clean ship spools straight up, and a
        // ship the gate has only an ADVISORY about asks for a second press to confirm. Which one this
        // run took is printed below and reported with the result.
        String jumpBranch = "spooling";
        if (!pressChat.contains("spooling") && !pressChat.contains("msg.jump.spooling")) {
            assertTrue("the jump key, pressed by a seated pilot of an ARMED ship, must be ANSWERED — "
                            + "either the drive spools or the gate asks him to confirm an advisory. "
                            + "Silence means the press never reached the ship at all. chat="
                            + pressChat + " delivery=" + exec("artest vs seat-delivery")
                            + " riding=" + bot().reportRidingEntity()
                            + " drive=" + exec("artest drive info " + slotDim + " "
                            + describeArgs(navAfcSub)),
                    pressChat.contains("confirm") || pressChat.contains("msg.jump.confirm"));
            jumpBranch = "confirm-then-commit";
            pressJumpKey();
            pressChat = chatText(30);
            assertTrue("…and the CONFIRMING press must spool the drive: the gate's advisory is an "
                            + "'are you sure', not a refusal, so a second press has to carry the ship. "
                            + "chat=" + pressChat + " drive=" + exec("artest drive info " + slotDim
                            + " " + describeArgs(navAfcSub)),
                    pressChat.contains("spooling") || pressChat.contains("msg.jump.spooling"));
        }
        System.out.println("[M1] jump branch: " + jumpBranch + " chat=" + pressChat);

        // The flight is short but the wind-up is not instant; poll the ledger, which is the only
        // place that answers "where is the ship" while it has no body anywhere.
        String arrivedCell = launchCell;
        int jumpBudget = (int) (400 * TestTimeouts.factor());
        for (int attempt = 0; attempt < jumpBudget && arrivedCell.equals(launchCell); attempt++) {
            bot().waitTicks(5);
            String entry = exec("artest space ledger-get " + shipId);
            String cell = readString(entry, CELL);
            if (cell != null && !cell.isEmpty() && entry.contains("\"state\":\"SETTLED\"")) {
                arrivedCell = cell;
            }
        }
        String ledgerAfterJump = exec("artest space ledger-get " + shipId);
        assertTrue("a jump the pilot armed and fired must MOVE the ship to another cell — the whole "
                        + "point of the hyperdrive is that the craft is somewhere else afterwards. "
                        + "launchCell=" + launchCell + " arrivedCell=" + arrivedCell
                        + " target=" + targetCell + " ledger=" + ledgerAfterJump
                        + " status=" + exec("artest space subsystem-status"),
                !arrivedCell.equals(launchCell));

        // THE promise of the loop: the pilot picked a planet, and when the drive stops he is at that
        // planet. Asserted on the BODY rather than on the cell string armed earlier, because the
        // planet moves while the ship flies — that is precisely why the computer aims ahead of it.
        // A ship aimed at where the body WAS lands in a cell the body has left, which reads here as
        // an arrived cell whose own body list does not contain the destination.
        String arrivedCellInfo = exec("artest space cell-info " + cellArgs(arrivedCell));
        String arrivedBodies = readString(arrivedCellInfo, CELL_BODIES);
        assertTrue("…and the cell it arrives in must be the one the destination BODY is in when it "
                        + "gets there — a destination the pilot chose at the console is a promise the "
                        + "drive has to keep, and it is only kept if the planet is there on arrival. "
                        + "The navigation computer aims at where the body WILL be, so a red here is "
                        + "that prediction being wrong (or absent): compare the cell armed at leg 6 "
                        + "with where the body actually is now. targetDim=" + targetDim
                        + " armedCell=" + targetCell + " arrivedCell=" + arrivedCell
                        + " arrived=" + arrivedCellInfo + " ledger=" + ledgerAfterJump,
                arrivedBodies != null && arrivedBodies.contains("\"dim\":" + targetDim + ","));

        // The pilot, observed from the CLIENT: same two questions leg 5 asks, because a jump is the
        // second world transition of the loop and a seat lost in it is lost just as silently.
        String statusAfterJump = exec("artest space subsystem-status");
        Matcher sdj = SLOT_DIMS.matcher(statusAfterJump);
        assertTrue("ARRANGEMENT: subsystem-status must list its slot dims: " + statusAfterJump,
                sdj.find());
        String jumpSlotDims = "," + sdj.group(1) + ",";
        int jumpDim = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < budget; attempt++) {
            bot().waitTicks(5);
            JsonObject weather = bot().reportWeather();
            if (weather.has("dim")) {
                jumpDim = weather.get("dim").getAsInt();
                if (jumpSlotDims.contains("," + jumpDim + ",")) {
                    break;
                }
            }
        }
        assertTrue("the pilot who fired the jump must come out of it in a space cell too — a drive "
                        + "that carries the hull and leaves the crew behind has not moved the SHIP. "
                        + "clientDim=" + jumpDim + " slotDims=[" + sdj.group(1) + "] ledger="
                        + ledgerAfterJump + " status=" + statusAfterJump,
                jumpSlotDims.contains("," + jumpDim + ","));

        JsonObject jumpRiding = bot().reportRidingEntity();
        boolean prevJump = isRiding(jumpRiding);
        boolean seatedThroughJump = false;
        for (int attempt = 0; attempt < budget && !seatedThroughJump; attempt++) {
            bot().waitTicks(5);
            jumpRiding = bot().reportRidingEntity();
            seatedThroughJump = prevJump && isRiding(jumpRiding);
            prevJump = isRiding(jumpRiding);
        }
        assertTrue("the pilot who FIRED the jump must still be in his seat when it ends — he never "
                        + "stood up, so nothing about crossing a cell may stand him up. A red here is "
                        + "the crew capture, the re-seat, or the dimension hand-off, in that order: "
                        + "riding=" + jumpRiding + " serverRiding=" + exec("artest player riding-entity")
                        + " clientDim=" + jumpDim + " delivery=" + exec("artest vs seat-delivery")
                        + " ledger=" + ledgerAfterJump,
                seatedThroughJump);
        System.out.println("[M1] leg 7 (jump fired on the key) " + elapsed(tLeg)
                + " branch=" + jumpBranch + " " + launchCell + " -> " + arrivedCell
                + " clientDim=" + jumpDim + " riding=" + jumpRiding);

        // ---- LEG 8: he takes the ship down to the planet, on a held key. ------------------------
        tLeg = System.currentTimeMillis();
        // What the flight computer will see when it looks around this cell. It takes the NEAREST body
        // inside the descent radius, so that is the one the test must load and the one it must judge —
        // reading it here rather than deciding it keeps the test measuring the production choice.
        String bodies = exec("artest space bodies");
        int nearestDim = nearestDescendTargetDim(bodies);
        // The cell he left is the control: it is a body's cell too (he took off from it), so if BOTH
        // read empty the registry cannot attribute any cell, and if only the destination does, the
        // console offered an address the rest of the game does not agree exists.
        String arrivedInfo = exec("artest space cell-info " + cellArgs(arrivedCell));
        String launchInfo = exec("artest space cell-info " + cellArgs(launchCell));
        assertTrue("an address the navigation console OFFERED, and the drive actually flew the pilot "
                        + "to, must still have at it the body whose address it was WHEN HE GETS "
                        + "THERE. A crystal that lists a planet, a gate that clears the jump to it and "
                        + "a drive that spends the charge, all ending at a cell the game reports as "
                        + "empty, strand the ship: the descent trigger reads this same list and can "
                        + "never fire. If the cell held a body when the target was armed and holds "
                        + "none now, the body MOVED while the ship was in flight — compare the "
                        + "targetInfo leg 6 printed against `arrived` below, and see which cell the "
                        + "destination's dimension occupies now. arrivedCell=" + arrivedCell
                        + " arrived=" + arrivedInfo
                        + " launchCell(control)=" + launchCell + " launch=" + launchInfo
                        + " bodies=" + bodies,
                nearestDim != Integer.MIN_VALUE);

        // Pin the destination world. The descent resolver asks Forge for it and refuses in silence if
        // it is not loaded — a reading/arrangement probe, not an act: in a live game the world is
        // already up because somebody lives there.
        String loaded = exec("artest dim load " + nearestDim);
        assertTrue("ARRANGEMENT: the destination world must be loaded before the descent is attempted, "
                        + "or the resolver refuses quietly and the leg measures nothing: " + loaded,
                loaded.contains("\"loaded\":true"));

        // He flies. The descent trigger is PROXIMITY under power, not altitude — the ship has to be
        // under a pilot's hand for the computer to look for a body at all, so the key stays down.
        int descentDim = jumpDim;
        int descentBudget = (int) (600 * TestTimeouts.factor());
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < descentBudget && descentDim == jumpDim; attempt++) {
                bot().waitTicks(5);
                JsonObject weather = bot().reportWeather();
                if (weather.has("dim")) {
                    descentDim = weather.get("dim").getAsInt();
                }
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        assertTrue("a piloted ship that is close to a body must be taken DOWN off the space cell — that "
                        + "entry is the only way a tier-2 craft reaches a surface, and the pilot's whole "
                        + "input is holding one key near the body he flew to. The CLIENT's own dimension "
                        + "is what answers. clientDim=" + descentDim + " cellDim=" + jumpDim
                        + " nearestBodyDim=" + nearestDim + " dimLoad=" + loaded
                        + " descentStatus=" + exec("artest space descent-status")
                        + " ledger=" + exec("artest space ledger-get " + shipId)
                        + " bodies=" + bodies + " delivery=" + exec("artest vs seat-delivery"),
                descentDim != jumpDim);
        assertTrue("…and where it puts him down must be a real WORLD, with ground under it. The space "
                        + "subsystem's own slot worlds are empty voids that exist to hold a cell; a "
                        + "descent that ends in one has landed the ship nowhere, and the pilot who flew "
                        + "across a system to reach a planet steps out into nothing. clientDim="
                        + descentDim + " slotDims=[" + sdj.group(1) + "] nearestBodyDim=" + nearestDim
                        + " dimLoad=" + loaded + " bodies=" + bodies,
                !jumpSlotDims.contains("," + descentDim + ","));
        assertTrue("…and the world he steps out onto must be the PLANET HE PICKED at the console. "
                        + "That is the whole loop: choose a body, fly to it, land on it. Landing on "
                        + "something else in the neighbourhood means the aim, the arrival or the "
                        + "trigger's choice of body disagreed with the pilot. pickedDim=" + targetDim
                        + " landedDim=" + descentDim + " nearestBodyDim=" + nearestDim
                        + " bodies=" + bodies,
                descentDim == targetDim);

        JsonObject landedRiding = bot().reportRidingEntity();
        boolean prevLanded = isRiding(landedRiding);
        boolean seatedThroughDescent = false;
        for (int attempt = 0; attempt < budget && !seatedThroughDescent; attempt++) {
            bot().waitTicks(5);
            landedRiding = bot().reportRidingEntity();
            seatedThroughDescent = prevLanded && isRiding(landedRiding);
            prevLanded = isRiding(landedRiding);
        }
        assertTrue("and the pilot must still be flying his ship when it reaches the ground of the "
                        + "planet he set out for — the loop is only closed if the man who took off is "
                        + "the man who lands. riding=" + landedRiding + " clientDim=" + descentDim
                        + " serverRiding=" + exec("artest player riding-entity")
                        + " delivery=" + exec("artest vs seat-delivery"),
                seatedThroughDescent);
        System.out.println("[M1] leg 8 (descent onto the planet) " + elapsed(tLeg)
                + " landedDim=" + descentDim + " nearestBodyDim=" + nearestDim
                + " riding=" + landedRiding);
    }

    // ---- legs 6-8: the console, the jump key and the descent ------------------------------------

    /**
     * The pilot leaves his seat on the real dismount key, and the CLIENT is what confirms he is on
     * his feet: the deck hold that keeps a standing pilot aboard runs on the server, so "he stood up"
     * has to be read where he is rendered.
     */
    private void standUp(int budget) throws Exception {
        JsonObject riding = bot().reportRidingEntity();
        bot().holdKey(Keyboard.KEY_LSHIFT);
        try {
            for (int attempt = 0; attempt < budget && isRiding(riding); attempt++) {
                bot().waitTicks(5);
                riding = bot().reportRidingEntity();
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_LSHIFT);
        }
        bot().waitTicks(10);
        assertTrue("a pilot must be able to LEAVE his seat on the dismount key — the navigation "
                        + "console is deck work, and a seat he cannot get out of would end the loop "
                        + "here. riding=" + riding + " serverRiding="
                        + exec("artest player riding-entity"),
                !isRiding(bot().reportRidingEntity()));
    }

    /** He takes the seat again, exactly the way he took it the first time: aim at it and press use. */
    private JsonObject sitBackDown(int dim, int[] afcSub, int budget) throws Exception {
        int[] seatSub = add(afcSub, OFF_SEAT);
        holdNothing(budget);
        Aim aim = aimAt(dim, afcSub, seatSub, OFF_STAND, 0.5, 0.2, 0.5, budget);
        assertAimed(aim, seatSub, "pilot seat", "pilotseat");
        pressUse();
        JsonObject riding = bot().reportRidingEntity();
        for (int attempt = 0; attempt < budget && !isRiding(riding); attempt++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
        }
        return riding;
    }

    /**
     * Put the crosshair on the navigation console from the deck square beside it and press use until
     * its screen opens. The crosshair is confirmed on the console's own block before every press, so
     * a red names the hop that failed rather than merely the outcome.
     */
    private String openConsoleFromTheDeck(int dim, int[] afcSub, int[] navSub, int budget)
            throws Exception {
        Aim aim = new Aim();
        for (int attempt = 0; attempt < 6; attempt++) {
            String already = screenOf(bot().reportState());
            if (!already.isEmpty()) {
                return already;
            }
            aim = aimAt(dim, afcSub, navSub, OFF_STAND, 0.5, 0.5, 0.5, budget);
            assertAimed(aim, navSub, "navigation console", "navigationcomputer");
            pressUse();
            for (int waited = 0; waited < 6; waited++) {
                bot().waitTicks(10);
                String screen = screenOf(bot().reportState());
                if (!screen.isEmpty()) {
                    return screen;
                }
            }
        }
        return "ARRANGEMENT: console never opened;" + aim.diagnosis;
    }

    /** An empty main hand, without wiping the inventory the pilot is carrying his crystal in. */
    private void holdNothing(int budget) throws Exception {
        bot().selectHotbar(1);
        String heldId = null;
        for (int attempt = 0; attempt < budget; attempt++) {
            bot().waitTicks(5);
            JsonObject items = bot().reportPlayerItems();
            if (isWorldReady(items) && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
        }
        assertTrue("ARRANGEMENT: the pilot's main hand must be EMPTY so the use press reaches the "
                + "block rather than being consumed by a held item; held=" + heldId,
                heldId != null && heldId.isEmpty());
    }

    /**
     * The last world point the ship was known to occupy. After a crossing the craft is somewhere the
     * test never chose, so the FIRST handle on it is the pilot aboard it — but a pilot who has stood
     * up is no longer a reliable handle (vanilla's dismount can leave him beside the hull rather than
     * on it), so every successful find is remembered and re-used as the fallback probe point.
     */
    private double[] shipAnchorHint;

    /** The last find-seat answer, verbatim, so a failed aim can name the probe that went quiet. */
    private String lastSeatProbe = "";

    /** The ship the client is aboard, found from his own position, or from where it last was. */
    private String findSeatAboard(int dim, int budget) throws Exception {
        for (int attempt = 0; attempt < budget; attempt++) {
            JsonObject state = bot().reportState();
            if (isWorldReady(state)) {
                lastSeatProbe = findSeatFrom(dim, state.get("playerX").getAsDouble(),
                        state.get("playerY").getAsDouble(), state.get("playerZ").getAsDouble());
                if (rememberAnchor(lastSeatProbe)) {
                    return lastSeatProbe;
                }
            }
            if (shipAnchorHint != null) {
                lastSeatProbe = findSeatFrom(dim, shipAnchorHint[0], shipAnchorHint[1],
                        shipAnchorHint[2]);
                if (rememberAnchor(lastSeatProbe)) {
                    return lastSeatProbe;
                }
            }
            bot().waitTicks(5);
        }
        return lastSeatProbe;
    }

    private String findSeatFrom(int dim, double x, double y, double z) throws Exception {
        return exec("artest vs find-seat " + dim + " " + (int) Math.floor(x)
                + " " + (int) Math.floor(y) + " " + (int) Math.floor(z));
    }

    /** Keep the ship's live world position from a successful probe; false when it found nothing. */
    private boolean rememberAnchor(String probe) {
        double[] anchor = readTripleD(probe, SHIP_WORLD);
        if (anchor == null) {
            return false;
        }
        shipAnchorHint = anchor;
        return true;
    }

    /** One press of the jump key, edge-triggered the way the real keyboard delivers it. */
    private void pressJumpKey() throws Exception {
        bot().setKey(Keyboard.KEY_J, true);
        bot().waitTicks(5);
        bot().setKey(Keyboard.KEY_J, false);
        bot().waitTicks(20);
    }

    /** The chat the player can actually read, flattened and lower-cased for substring checks. */
    private String chatText(int limit) throws Exception {
        JsonObject chat = bot().reportChat(limit);
        StringBuilder sb = new StringBuilder();
        if (chat != null && chat.has("lines")) {
            for (int i = 0; i < chat.getAsJsonArray("lines").size(); i++) {
                sb.append(chat.getAsJsonArray("lines").get(i).getAsString()).append(" | ");
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The dimension of the NEAREST body in this cell the ship may descend onto, or MIN_VALUE — the
     * same choice the flight computer makes when it scans, so the test judges production's pick
     * rather than substituting one of its own.
     */
    private static int nearestDescendTargetDim(String bodies) {
        Matcher m = BODY.matcher(bodies);
        int best = Integer.MIN_VALUE;
        long bestDistance = Long.MAX_VALUE;
        while (m.find()) {
            if (!"true".equals(m.group(3))) {
                continue;
            }
            long distance = Long.parseLong(m.group(4));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = Integer.parseInt(m.group(1));
            }
        }
        return best;
    }

    /**
     * The container slot number of the first slot holding {@code item}, or -1. Matched without
     * regard to case: the client renders the registry name through {@code ResourceLocation}, which
     * lower-cases it, so the id a slot reports is not character-identical to the one a command takes.
     */
    private static int slotHolding(JsonObject slots, String item) {
        if (slots == null || !slots.has("slots")) {
            return -1;
        }
        for (int i = 0; i < slots.getAsJsonArray("slots").size(); i++) {
            JsonObject slot = slots.getAsJsonArray("slots").get(i).getAsJsonObject();
            if (slot.has("item")
                    && item.equalsIgnoreCase(slot.get("item").getAsString())) {
                return slot.get("slot").getAsInt();
            }
        }
        return -1;
    }

    // ---- leg 2: the assembler, driven entirely from the client's screen -------------------------

    /**
     * Open the assembler's screen with a real use-key press, click Scan, then click Build until a
     * ship exists. Returns the ship count reached.
     *
     * <p>Build is pressed on a poll because the machine silently drops a Build press while a scan
     * pass is running: the press "takes" the moment the scan finishes, which is exactly how a human
     * clicking the button experiences it.</p>
     */
    private int assembleThroughTheAssemblersScreen(int[] builderPos, int budget) throws Exception {
        // Walk up to the machine: two blocks south of it on the pad, face-on, well inside the
        // server's interaction reach.
        double standX = builderPos[0] + 0.5, standZ = builderPos[2] + 2.5;
        exec("tp @a " + standX + " " + (BY + 1) + " " + standZ + " 0 0");
        bot().waitTicks(20);
        emptyTheHand();

        // The builder's own stored energy, read before a single button is pressed. The fixture
        // stands libVulpes' creative power plug on top of the assembler and that plug pushes into
        // every adjacent accepting tile each tick, so the machine is already full here — a creative
        // power source is something a player has, and the acts in this leg are the key press and the
        // two button clicks below. Printed rather than asserted on: the CONTRACT this leg pins is
        // that the two clicks produce a ship, and a machine that ran on a different power arrangement
        // would still have to satisfy it.
        System.out.println("[M1] assembler energy at the machine: " + energyAt(builderPos));

        String screen = openBuilderScreenByRealKeyPress(builderPos, budget);
        assertTrue("a real use-key press aimed at the ROCKET ASSEMBLER must open its screen on the "
                        + "client. Every act of the build is performed on that screen, so a machine "
                        + "that swallows the press leaves the player with no way to build a ship at "
                        + "all. screen=\"" + screen + "\"",
                screen.startsWith("zmaster587.libVulpes.inventory.GuiModular"));

        bot().clickButtonById(BUTTON_SCAN);

        int ships = 0;
        int assembleBudget = (int) (90 * TestTimeouts.factor());
        for (int attempt = 0; attempt < assembleBudget && ships < 1; attempt++) {
            // The screen can be knocked shut (a chunk reload, a stray escape); re-open it rather
            // than clicking into nothing, so a red names the machine and not a lost window.
            if (screenOf(bot().reportState()).isEmpty()) {
                screen = openBuilderScreenByRealKeyPress(builderPos, budget);
                if (!screen.startsWith("zmaster587.libVulpes.inventory.GuiModular")) {
                    continue;
                }
            }
            bot().clickButtonById(BUTTON_BUILD);
            bot().waitTicks(40);
            Matcher m = COUNT.matcher(exec("artest vs ship-count-all 0"));
            if (m.find()) {
                ships = Integer.parseInt(m.group(1));
            }
        }
        bot().closeScreen();
        return ships;
    }

    /**
     * Put the crosshair on the assembler and press the use key, retrying the whole aim-and-press
     * until a screen opens. The crosshair is confirmed on the machine's own block before every
     * press, so a red names the hop that failed rather than merely the outcome.
     */
    private String openBuilderScreenByRealKeyPress(int[] builderPos, int budget) throws Exception {
        Aim aim = new Aim();
        for (int attempt = 0; attempt < 6; attempt++) {
            String already = screenOf(bot().reportState());
            if (!already.isEmpty()) {
                return already;
            }
            aim = aimAtWorldBlock(builderPos, 0.5, 0.5, 0.5, budget);
            assertAimed(aim, builderPos, "rocket assembler", "rocketbuilder");
            pressUse();
            for (int waited = 0; waited < 6; waited++) {
                bot().waitTicks(10);
                String screen = screenOf(bot().reportState());
                if (!screen.isEmpty()) {
                    return screen;
                }
            }
        }
        return "";
    }

    // ---- aiming ---------------------------------------------------------------------------------

    /** One aim attempt's outcome: where the bot ended up, what it was looking at, and why. */
    private static final class Aim {
        JsonObject mouseOver;
        double distSq = Double.POSITIVE_INFINITY;
        String diagnosis = "";
    }

    /** Aim at a block that stands in the world (the assembler), from wherever the bot is standing. */
    private Aim aimAtWorldBlock(int[] target, double tx, double ty, double tz, int budget)
            throws Exception {
        double[] targetWorld = {target[0] + tx, target[1] + ty, target[2] + tz};
        Aim aim = new Aim();
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;
        for (int attempt = 0; attempt < budget; attempt++) {
            JsonObject state = bot().reportState();
            if (!isWorldReady(state)) {
                bot().waitTicks(5);
                continue;
            }
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();
            aim.distSq = look(targetWorld, px, py, pz);
            bot().waitTicks(5);
            aim.mouseOver = bot().reportMouseOver();
            if (isUnderCrosshair(aim.mouseOver, target)) {
                break;
            }
        }
        aim.diagnosis = " observedPlayer=(" + px + "," + py + "," + pz + ")"
                + " target=" + describe(target)
                + " targetWorld=" + java.util.Arrays.toString(targetWorld)
                + " distSq=" + aim.distSq + " mouseOver=" + aim.mouseOver;
        return aim;
    }

    /**
     * Stand on the ship's deck square and put the crosshair on {@code targetSub}, retrying until the
     * client itself confirms the pick. Both the stand and the aim are re-derived from the ship's live
     * pose every attempt: a freshly assembled ship settles for a while, and a position computed once
     * against a stale pose leaves the bot in mid-air beside a ship that has since moved.
     */
    private Aim aimAt(int[] afcSub, int[] targetSub, int[] standOffset,
                      double tx, double ty, double tz, int budget) throws Exception {
        return aimAt(0, afcSub, targetSub, standOffset, tx, ty, tz, budget);
    }

    private Aim aimAt(int dim, int[] afcSub, int[] targetSub, int[] standOffset,
                      double tx, double ty, double tz, int budget) throws Exception {
        Aim aim = new Aim();
        int[] standSub = add(afcSub, standOffset);
        double[] standWorld = null;
        double[] targetWorld = null;
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;

        for (int attempt = 0; attempt < budget; attempt++) {
            double[] shipAnchor = readTripleD(
                    dim == 0 ? findSeat() : findSeatAboard(dim, budget), SHIP_WORLD);
            if (shipAnchor == null) {
                bot().waitTicks(5);
                continue;
            }
            // The floor of the stand cell is the deck's top surface, so the feet go at its y with a
            // sliver of clearance rather than at its centre.
            standWorld = toWorld(dim, shipAnchor, standSub, 0.5, 0.05, 0.5);
            targetWorld = toWorld(dim, shipAnchor, targetSub, tx, ty, tz);
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
            aim.distSq = look(targetWorld, px, py, pz);
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
                + " distSq=" + aim.distSq + " mouseOver=" + aim.mouseOver
                // A null standWorld/targetWorld means the SHIP was never located, not that the aim
                // maths went wrong — so both probes that answered have to be in the message.
                + " seatProbe=" + lastSeatProbe
                + " toWorldProbe=" + lastToWorldProbe
                + " anchorHint=" + java.util.Arrays.toString(shipAnchorHint);
        return aim;
    }

    /** Point the client's head at a world point from an observed stance; returns the squared reach. */
    private double look(double[] targetWorld, double px, double py, double pz) throws Exception {
        double dx = targetWorld[0] - px;
        double dy = targetWorld[1] - (py + EYE_HEIGHT);
        double dz = targetWorld[2] - pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        bot().setLook((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
                (float) (-Math.toDegrees(Math.atan2(dy, horizontal))));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Every hop of the aim, asserted separately, so a red says which one broke. */
    private void assertAimed(Aim aim, int[] target, String what, String blockNeedle) {
        assertTrue("ARRANGEMENT: the bot must OBSERVABLY stand within the server's interaction reach "
                + "of the " + what + ", or the press is discarded before the block ever sees it."
                + aim.diagnosis, aim.distSq < MAX_INTERACT_DIST_SQ);
        assertTrue("HOP 1 (aim at the " + what + "): the client's crosshair must resolve to a BLOCK. "
                + "A MISS here means the sightline is obstructed or the aim maths is wrong, not that "
                + "the block is unclickable." + aim.diagnosis,
                aim.mouseOver != null && aim.mouseOver.has("typeOfHit")
                        && "BLOCK".equals(aim.mouseOver.get("typeOfHit").getAsString()));
        assertTrue("HOP 2 (aim at the " + what + "): the block under the crosshair must be the "
                + what + " as the CLIENT's own world reports it." + aim.diagnosis,
                aim.mouseOver.has("block") && aim.mouseOver.get("block").getAsString()
                        .toLowerCase(Locale.ROOT).contains(blockNeedle));
        assertTrue("HOP 3 (aim at the " + what + "): the raytrace must report the address "
                + describe(target) + " — that is what the interaction is handed." + aim.diagnosis,
                isUnderCrosshair(aim.mouseOver, target));
    }

    private static boolean isUnderCrosshair(JsonObject aim, int[] pos) {
        return aim != null
                && aim.has("typeOfHit") && "BLOCK".equals(aim.get("typeOfHit").getAsString())
                && aim.has("blockX")
                && aim.get("blockX").getAsInt() == pos[0]
                && aim.get("blockY").getAsInt() == pos[1]
                && aim.get("blockZ").getAsInt() == pos[2];
    }

    /** One real use-key press, the way the mouse handler writes it. */
    private void pressUse() throws Exception {
        bot().setKey(KEY_USE_ITEM, true);
        bot().waitTicks(5);
        bot().setKey(KEY_USE_ITEM, false);
    }

    // ---- arrangement ----------------------------------------------------------------------------

    /** Warm the chunks, clear the site and stand the craft up; returns the assembler's position. */
    private int[] placeFixture() throws Exception {
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
        return new int[]{Integer.parseInt(bp.group(1)), Integer.parseInt(bp.group(2)),
                Integer.parseInt(bp.group(3))};
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

    // ---- helpers --------------------------------------------------------------------------------

    private ClientBot bot() {
        return clientHarness.bot();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String energyAt(int[] pos) throws Exception {
        return exec("artest energy stored 0 " + pos[0] + " " + pos[1] + " " + pos[2]);
    }

    /** The seat's subspace address, its flight computer's, and the ship's live world position. */
    private String findSeat() throws Exception {
        return exec("artest vs find-seat 0 " + BX + " " + (BY + 5) + " " + BZ);
    }

    /**
     * A subspace point mapped into the world through the ship's own transform. {@code shipAnchor} is
     * any world point aboard that ship — the seat's live position serves.
     */
    private double[] toWorld(int dim, double[] shipAnchor, int[] sub, double dx, double dy, double dz)
            throws Exception {
        if (shipAnchor == null) {
            return null;
        }
        lastToWorldProbe = exec("artest vs to-world " + dim + " " + shipAnchor[0] + " "
                + shipAnchor[1] + " " + shipAnchor[2] + " " + (sub[0] + dx) + " " + (sub[1] + dy)
                + " " + (sub[2] + dz));
        return readTripleD(lastToWorldProbe, TO_WORLD);
    }

    /** The last subspace-to-world mapping answer, so a null world point can name what refused it. */
    private String lastToWorldProbe = "";

    private static String screenOf(JsonObject state) {
        return state != null && state.has("screen") ? state.get("screen").getAsString() : "";
    }

    private static boolean isWorldReady(JsonObject report) {
        return report != null && report.has("worldReady") && report.get("worldReady").getAsBoolean();
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private static String elapsed(long since) {
        return "took " + ((System.currentTimeMillis() - since) / 1000L) + "s";
    }

    private static int[] add(int[] base, int[] offset) {
        return new int[]{base[0] + offset[0], base[1] + offset[1], base[2] + offset[2]};
    }

    private static String describe(int[] triple) {
        return "(" + triple[0] + "," + triple[1] + "," + triple[2] + ")";
    }

    /** The same triple as three space-separated command arguments. */
    private static String describeArgs(int[] triple) {
        return triple[0] + " " + triple[1] + " " + triple[2];
    }

    /** A {@code sx_sy_sz} cell key as the three sector arguments a probe takes. */
    private static String cellArgs(String cellKey) {
        return cellKey.replace('_', ' ');
    }

    private static String readString(String json, Pattern p) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** A probe field that may come back quoted or as a bare {@code null}, as a plain string. */
    private static String unquote(String raw) {
        return raw == null ? "" : raw.replace("\"", "");
    }

    private static int readInt(String json, Pattern p, int fallback) {
        Matcher m = p.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
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
}
