package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.findSlotWithItem;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.openGuiByRightClick;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.screenOf;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.waitForNoScreen;

/**
 * A machine stands in the world, the player right-clicks it open, and drives it with nothing but
 * clicks. Seven scenarios, one client.
 *
 * <p>What binds them is the instrument, not the subsystem: every one of these needs a REAL client
 * because the contract lives in the client&harr;server round trip — a libVulpes {@code GuiModular}
 * that must actually open, a slot click that must reach {@code Container.transferStackInSlot}, a
 * button id that must reach {@code useNetworkData}, an open container that must survive (or not
 * survive) a distance check on the server's own tick.</p>
 *
 * <h2>Why these seven share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: 119.3 + 88.3 + 116.6 + 120.3 + 126.8 +
 * 228.9 s across seven client boots — <b>13.3 minutes</b> for seven interactions.</p>
 *
 * <h2>What the sharing makes dangerous here</h2>
 *
 * <ul>
 *   <li><b>Two of the source classes stood on the SAME block.</b> {@code GuidanceComputerGuiE2ETest}
 *       and {@code PlanetSelectorGuiE2ETest} both placed their machine at (8, 64, 8) — harmless
 *       under one world per method, a straight overwrite when the world is shared. The plot
 *       allocator removes that without anyone having to notice it.</li>
 *   <li><b>An open screen outlives its scenario.</b> Every scenario here opens one; each closes it,
 *       and the shared reset closes anything left and then asserts the screen is gone.</li>
 *   <li><b>A GLOBAL query answering with a neighbour's object.</b>
 *       {@link #clickingScanThenBuildAssemblesRocket()} reads {@code artest rocket list}, which is
 *       world-wide, and narrows it to {@link Plot#contains}.</li>
 *   <li><b>Chat while a GUI is open.</b> {@link #thePilotCopiesPicksAndArmsAtTheConsoleWithNothingButClicks()}
 *       reads the console's replies off the chat overlay with the console still open, so it arms
 *       the channel with the chat-only clear — the full client reset would close the very screen
 *       it is about to click.</li>
 * </ul>
 *
 * <p>The lane is wide (128) because two members need more than a 64-block box: the railgun pair
 * stands 60 blocks apart, and the rocket fixture builds a pad plus a launch clearance.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code GuidanceComputerGuiE2ETest}, {@code PlanetSelectorGuiE2ETest},
 * {@code NavigationComputerGuiE2ETest}, {@code InventoryBypassRedirectE2ETest},
 * {@code RocketBuilderGuiE2ETest}, {@code RailgunCargoTransitE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MachineGuiClientGroupE2ETest extends AbstractSharedClientE2ETest {

    private static final int Y = Plot.DEFAULT_Y;

    /** Where a scenario's machine stands inside its plot, and where the player stands to reach it. */
    private static final int MACHINE_DX = 16;
    private static final int MACHINE_DZ = 16;

    private static final String GUI_MODULAR = "zmaster587.libVulpes.inventory.GuiModular";
    private static final String GUI_CHEST = "net.minecraft.client.gui.inventory.GuiChest";
    private static final String CHIP = "advancedrocketry:planetidchip";

    /** {@code zmaster587.advancedRocketry.api.Constants.STAR_ID_OFFSET}. */
    private static final int STAR_ID_OFFSET = 10000;

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    /** One {@code rocket list} entry: id plus the x/y/z it stands at. */
    private static final Pattern ROCKET_ENTRY = Pattern.compile(
            "\\{\"id\":(-?\\d+),\"uuid\":\"[^\"]*\",\"dim\":-?\\d+,"
                    + "\"pos\":\\[(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)]}");

    // Navigation console button ids — the console's own module ids, which libVulpes puts straight
    // on the GuiButton.
    private static final int BUTTON_COPY = 0;
    private static final int BUTTON_ARM = 5;
    private static final int BUTTON_PICK_FIRST = 10;
    /** Addresses the probe seeds into the brought crystal: sectors 100..102, named {@code probe-N}. */
    private static final int SEEDED = 3;
    private static final int FIRST_SECTOR = 100;

    private static final Pattern SHIP_COUNT = Pattern.compile("\"ship\":(\\d+)");
    private static final Pattern SOURCE_COUNT = Pattern.compile("\"source\":(\\d+)");
    private static final Pattern TARGET = Pattern.compile("\"target\":(null|\"[^\"]*\")");
    private static final Pattern ARMED = Pattern.compile("\"armed\":(true|false)");

    // Observatory region-scan probe fields.
    private static final Pattern TELESCOPE_ORIGIN = Pattern.compile("\"origin\":\"([^\"]*)\"");
    private static final Pattern TELESCOPE_AIM_DISTANCE = Pattern.compile("\"aimDistance\":(\\d+)");
    private static final Pattern TELESCOPE_ADDRESSES = Pattern.compile("\"addresses\":(-?\\d+)");

    // Railgun probe fields.
    private static final Pattern FIRED = Pattern.compile("\"fired\":(true|false)");
    private static final Pattern DEST_MATCHED = Pattern.compile("\"destMatched\":(\\d+)");
    private static final Pattern SRC_REMAINING = Pattern.compile("\"srcInputRemaining\":(\\d+)");
    private static final Pattern FIRE_STATUS = Pattern.compile("\"fireStatus\":\"([A-Z_]+)\"");
    private static final Pattern DEST_LOADED = Pattern.compile("\"destLoaded\":(true|false)");

    @Override
    protected String subsystem() {
        return "machine-gui";
    }

    /**
     * A wide lane: the railgun pair needs 60 blocks between its two multiblocks and the rocket
     * fixture wants a pad plus clearance, neither of which fits the default 64-block box.
     */
    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(4000, 4000, 128, 128);
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    private void warmupPlotChunks() throws Exception {
        int dim = plot().dim;
        int cx0 = plot().originX >> 4;
        int cz0 = plot().originZ >> 4;
        int cx1 = (plot().originX + plot().size - 1) >> 4;
        int cz1 = (plot().originZ + plot().size - 1) >> 4;
        for (int cx = cx0 - 1; cx <= cx1 + 1; cx++) {
            for (int cz = cz0 - 1; cz <= cz1 + 1; cz++) {
                exec("artest chunk forceload " + dim + " " + cx + " " + cz);
            }
        }
    }

    /**
     * Places {@code blockId} on a stone footing at the machine offset and stands the player one
     * block above it, looking straight down — the pose every one of these GUIs is opened from.
     * Returns the machine's world position as {@code {x, y, z}}.
     */
    private int[] placeMachineAndStandOnIt(String blockId) throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);

        scenario().arranging("place " + blockId + " at " + x + "," + Y + "," + z);
        warmupPlotChunks();
        String place = exec("artest place " + dim + " " + x + " " + Y + " " + z + " " + blockId);
        scenario().requireArranged("could not place " + blockId + ": " + place,
                place.contains("\"placed\":true") || place.contains("\"ok\":true"));

        // Stand ON the machine's own column, one block up, looking down at its top face. The
        // source classes all used exactly this pose; in open air it needs no terrain at all.
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5) + " 0 90");
        bot().waitTicks(40);
        return new int[]{x, Y, z};
    }

    /** Opens the machine's GUI by right-clicking it, and says what the click did if it does not. */
    private String openMachineGui(int[] at) throws Exception {
        String screen = openGuiByRightClick(bot(), at[0], at[1], at[2]);
        if (screen.startsWith(GUI_MODULAR)) {
            return screen;
        }
        JsonObject direct = bot().interactBlock(at[0], at[1], at[2]);
        bot().waitTicks(20);
        scenario().arrangementFailed("right-clicking the machine must open its GUI. screen=\""
                + screen + "\" afterDirectClick=\"" + screenOf(bot().reportState())
                + "\" clickResult=" + direct
                + " blockAtMachine=" + bot().blockState(at[0], at[1], at[2])
                + " playerState=" + bot().reportState());
        return screen;
    }

    private static int readInt(String json, Pattern p) {
        return Integer.parseInt(readGroup(json, p));
    }

    private static boolean readBoolean(String json, Pattern p) {
        return Boolean.parseBoolean(readGroup(json, p));
    }

    private static String readGroup(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected " + p.pattern() + " in: " + json, m.find());
        return m.group(1);
    }

    // ── rocket assembler ──────────────────────────────────────────────────────

    /**
     * From {@code RocketBuilderGuiE2ETest}. Builds the rocket structure with
     * {@code /artest fixture rocket}, opens the assembler's GUI by right-click, and drives the real
     * two-button assembly flow entirely through the client GUI: {@code clickButtonById(0)} (Scan),
     * then {@code clickButtonById(1)} (Build) pressed on a poll —
     * {@code TileRocketAssemblingMachine.useNetworkData} ignores a Build press while
     * {@code isScanning()}, so it simply "takes" once the scan pass finishes.
     *
     * <p>Unlike the headless {@code server/RocketAssemblySmokeTest} — which calls
     * {@code scanRocket}/{@code assembleRocket} directly — this exercises the machine's real
     * energy-gated {@code performFunction} tick loop, so the builder is kept powered via
     * {@code /artest energy inject}.</p>
     */
    @Test
    public void clickingScanThenBuildAssemblesRocket() throws Exception {
        int dim = plot().dim;
        int baseX = plot().x(8);
        int baseZ = plot().z(8);

        scenario().arranging("build the rocket fixture on a platform inside the plot");
        warmupPlotChunks();
        // The pad is built in open air on ground of the scenario's own making, so no world seed can
        // put a hill under it — the failure mode FreeFlightModeE2ETest carries a pre-clear for.
        String footing = exec("artest fill " + dim + " " + baseX + " " + (Y - 1) + " " + baseZ
                + " " + (baseX + 12) + " " + (Y - 1) + " " + (baseZ + 12) + " minecraft:stone");
        scenario().requireArranged("pad footing fill must succeed: " + footing,
                footing.contains("\"ok\":true"));

        String fixture = exec("artest fixture rocket " + dim + " " + baseX + " " + Y + " " + baseZ);
        scenario().requireArranged("fixture rocket failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        scenario().requireArranged("fixture response missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));
        String builder = dim + " " + bx + " " + by + " " + bz;
        scenario().record("builderPos", bx + "," + by + "," + bz)
                .describeOnFailureWith("artest rocket list " + dim);

        scenario().arranging("stand on the launchpad within reach of the builder");
        exec("tp @a " + (baseX + 2.5) + " " + (Y + 1) + " " + (baseZ + 2.5) + " 0 0");
        bot().waitTicks(40);

        String screen = openGuiByRightClick(bot(), bx, by, bz);
        scenario().requireArranged("expected the assembler GUI to open, got: " + screen,
                screen.startsWith(GUI_MODULAR));

        scenario().asserting("Scan then Build, clicked on the real GUI, assemble a rocket");
        exec("artest energy inject " + builder + " 100000000");
        bot().clickButtonById(0);

        int rocketId = -1;
        String list = "";
        for (int waited = 0; waited < 3600 && rocketId < 0; waited += 40) {
            exec("artest energy inject " + builder + " 100000000");
            bot().clickButtonById(1);
            bot().waitTicks(40);
            list = exec("artest rocket list " + dim);
            rocketId = rocketIdInThisPlot(list);
        }
        assertTrue("clicking Scan then Build must assemble a rocket standing in " + plot()
                + "; rocket list was " + list, rocketId >= 0);
        scenario().record("rocketId", rocketId);

        // Player truth: the CLIENT world renders the assembled rocket entity — the spawn was
        // synced to the player's screen, not just to the registry.
        int seen = -1;
        for (int waited = 0; waited < 100; waited += 10) {
            bot().waitTicks(10);
            seen = bot().reportEntities("EntityRocket", 64).get("count").getAsInt();
            if (seen >= 1) break;
        }
        assertTrue("the client must see the assembled EntityRocket near the pad; count=" + seen,
                seen >= 1);

        bot().closeScreen();
    }

    /**
     * The id of a rocket standing in THIS scenario's plot, or -1.
     *
     * <p>{@code artest rocket list} is a GLOBAL query. Taking "the one that is there" is correct
     * only while the world holds exactly one rocket, which is precisely what a shared world stops
     * guaranteeing.</p>
     */
    private int rocketIdInThisPlot(String list) {
        Matcher entry = ROCKET_ENTRY.matcher(list);
        while (entry.find()) {
            double px = Double.parseDouble(entry.group(2));
            double pz = Double.parseDouble(entry.group(4));
            if (plot().contains(px, pz)) {
                return Integer.parseInt(entry.group(1));
            }
        }
        return -1;
    }

    // ── railgun ───────────────────────────────────────────────────────────────

    /**
     * From {@code RailgunCargoTransitE2ETest}. Issue #61 ("[BUG] Railgun does not work"): a
     * same-dimension shot fires with a real client connected — cargo leaves the source input and
     * arrives at the destination output (status FIRED).
     *
     * <p>{@code RailgunFiringContractTest} pins these contracts on a dedicated server; this re-pins
     * the player-visible ones with a live client, so a client/server desync in the teleport path
     * would surface here where the server-only test is blind.</p>
     */
    @Test
    public void cargoTransitsBetweenLinkedRailgunsClientSide() throws Exception {
        int dim = plot().dim;
        scenario().arranging("build and validate two linked railguns 60 blocks apart");
        warmupPlotChunks();
        int sx = plot().x(4), sz = plot().z(32);
        int dx = plot().x(64), dz = plot().z(32);
        buildAndCompleteRailgun(sx, sz);
        buildAndCompleteRailgun(dx, dz);
        scenario().record("source", sx + "," + Y + "," + sz).record("dest", dx + "," + Y + "," + dz);

        scenario().asserting("cargo transits from the source's input to the destination's output");
        String fire = exec("artest infra railgun-fire " + dim + " " + sx + " " + Y + " " + sz
                + " " + dim + " " + dx + " " + Y + " " + dz + " minecraft:cobblestone 16");
        scenario().requireArranged("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("railgun MUST fire to a linked railgun in the same dimension with a client "
                + "connected (issue #61 baseline); fire=" + fire,
                "true".equals(readGroup(fire, FIRED)));
        assertTrue("status must read FIRED after a successful shot; fire=" + fire,
                "FIRED".equals(readGroup(fire, FIRE_STATUS)));
        assertTrue("destination output port must contain >= 16 cobblestone after firing; fire="
                + fire, readInt(fire, DEST_MATCHED) >= 16);
        assertEquals("source input port must be drained after firing; fire=" + fire,
                0, readInt(fire, SRC_REMAINING));
    }

    /**
     * From {@code RailgunCargoTransitE2ETest}. Under a live client, a genuinely unavailable
     * destination (an unregistered dimension that cannot be loaded) does NOT fire and REPORTS the
     * reason (TARGET_UNAVAILABLE) — the #61 fix's "no more silent no-op" — with the cargo preserved.
     */
    @Test
    public void railgunReportsUnavailableForUnloadableDestinationClientSide() throws Exception {
        int dim = plot().dim;
        // Not registered on the harness server, so production cannot load it however hard it tries.
        final int unregisteredDim = 31337;

        scenario().arranging("build and validate one railgun");
        warmupPlotChunks();
        int sx = plot().x(4), sz = plot().z(32);
        buildAndCompleteRailgun(sx, sz);

        scenario().asserting("an unloadable destination is refused, out loud, with the cargo kept");
        String fire = exec("artest infra railgun-fire " + dim + " " + sx + " " + Y + " " + sz
                + " " + unregisteredDim + " 0 64 0 minecraft:cobblestone 16");
        scenario().requireArranged("railgun-fire probe must succeed: " + fire,
                fire.contains("\"ok\":true"));

        assertTrue("railgun must NOT fire at an unloadable (unregistered) destination; fire=" + fire,
                "false".equals(readGroup(fire, FIRED)));
        assertTrue("unregistered dim cannot be loaded -> destLoaded:false; fire=" + fire,
                "false".equals(readGroup(fire, DEST_LOADED)));
        assertTrue("status must report TARGET_UNAVAILABLE (not a silent no-op); fire=" + fire,
                "TARGET_UNAVAILABLE".equals(readGroup(fire, FIRE_STATUS)));
        assertEquals("cargo must be preserved on a failed shot; fire=" + fire,
                16, readInt(fire, SRC_REMAINING));
    }

    private void buildAndCompleteRailgun(int x, int z) throws Exception {
        int dim = plot().dim;
        String fixture = exec("artest fixture multiblock railgun " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("fixture multiblock railgun failed at " + x + "," + Y + "," + z
                + ": " + fixture, fixture.contains("\"ok\":true"));
        String tryComplete = exec("artest machine try-complete " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("railgun must validate at " + x + "," + Y + "," + z + ": "
                + tryComplete, tryComplete.contains("\"isComplete\":true"));
    }

    // ── guidance computer ─────────────────────────────────────────────────────

    /**
     * From {@code GuidanceComputerGuiE2ETest}. A planet-id chip handed to the player is
     * shift-clicked ({@code ClickType.QUICK_MOVE}) out of the player inventory and into the guidance
     * computer's own slot; {@code report_slots} confirms the chip crossed from a {@code playerSlot}
     * into a machine slot — i.e. the click drove {@code Container.transferStackInSlot} on the server
     * and the result synced back. Slots are addressed by the container slot number the report gives,
     * never by guessed coordinates.
     */
    @Test
    public void shiftClickingChipMovesItIntoTheGuidanceComputer() throws Exception {
        int[] at = placeMachineAndStandOnIt("advancedrocketry:guidanceComputer");
        exec("give @a " + CHIP + " 1");
        bot().waitTicks(20);

        scenario().arranging("open the guidance computer's GUI");
        String screen = openMachineGui(at);
        scenario().record("screen", screen);

        scenario().measuring("find the chip in the player half of the open container");
        JsonObject before = bot().reportSlots();
        int chipSlot = findSlotWithItem(before, CHIP, true);
        scenario().requireArranged("chip not found in the player inventory portion of the GUI: "
                + before, chipSlot != -1);

        scenario().asserting("a shift-click quick-moves the chip into the machine's own slot");
        bot().clickSlot(chipSlot, 0, "QUICK_MOVE");
        bot().waitTicks(10);

        JsonObject after = bot().reportSlots();
        assertTrue("shift-click did not move the chip into a guidance computer slot: " + after,
                findSlotWithItem(after, CHIP, false) != -1);
        assertTrue("chip still left behind in the player inventory: " + after,
                findSlotWithItem(after, CHIP, true) == -1);

        bot().closeScreen();
    }

    // ── observatory: the region scan ──────────────────────────────────────────

    /**
     * Builds the observatory as a REAL multiblock and stands the player beside its controller.
     *
     * <p>A bare controller block is not enough here, and the difference is not cosmetic: a
     * multiblock machine refuses to open its GUI until its structure validates, so a lone block
     * right-clicks to nothing at all (measured — the click returned PASS with no screen). The
     * fixture's footprint runs from the controller towards +Z and +Y, so the player is placed on the
     * -Z side, which is the one face left in the open.</p>
     */
    private int[] buildObservatoryAndStandBesideIt() throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);

        scenario().arranging("build the observatory multiblock at " + x + "," + Y + "," + z);
        warmupPlotChunks();
        String fixture = exec("artest fixture multiblock observatory " + dim + " " + x + " " + Y
                + " " + z);
        scenario().requireArranged("fixture multiblock observatory failed: " + fixture,
                fixture.contains("\"ok\":true"));

        String completed = "";
        for (int attempt = 0; attempt < 8; attempt++) {
            completed = exec("artest machine try-complete " + dim + " " + x + " " + Y + " " + z);
            if (completed.contains("\"isComplete\":true")) {
                break;
            }
            bot().waitTicks(10);
        }
        scenario().requireArranged("the observatory structure never validated: " + completed,
                completed.contains("\"isComplete\":true"));

        // Stand on the structure's open face, one block from the controller, looking at it. The
        // footing is not decoration: the fixture clears its own footprint to air, and a player
        // teleported into that air FALLS — measured, and it reads exactly like a dead click,
        // because the server silently drops an interaction from out of reach.
        StringBuilder footing = new StringBuilder();
        for (int dx = -1; dx <= 1; dx++) {
            footing.append(exec("artest place " + dim + " " + (x + dx) + " " + Y + " " + (z - 1)
                    + " minecraft:stone")).append(' ');
        }
        // Stand in the MIDDLE of the footing block, not at its edge: the block at z-1 spans
        // [z-1, z), so z-1.5 is half a block beyond it and over open air.
        //
        // Re-issued rather than waited out: a single teleport followed by a fixed wait puts the
        // player on a footing his own client has not received yet, and he falls through it — the
        // same fall, to the same fraction of a block, every time. Standing still is a convergence,
        // so it is polled.
        double standingY = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            exec("tp @a " + (x + 0.5) + " " + (Y + 1) + " " + (z - 0.5) + " 0 0");
            bot().waitTicks(20);
            standingY = bot().reportState().get("playerY").getAsDouble();
            if (Math.abs(standingY - (Y + 1)) <= 1.0) {
                break;
            }
        }
        scenario().requireArranged("the player fell off the footing (y=" + standingY + ", wanted "
                + (Y + 1) + ") — every click from here would be out of reach."
                + " footingPlacements=" + footing
                + " blockUnderFoot=" + bot().blockState(x, Y, z - 1)
                + " controller=" + bot().blockState(x, Y, z),
                Math.abs(standingY - (Y + 1)) <= 1.0);
        return new int[]{x, Y, z};
    }

    /**
     * The telescope's third tab, driven with nothing but clicks: switch to it, aim the instrument
     * farther out, press Observe, and let the observation finish — then check the crystal sitting in
     * the machine holds an address it did not have before.
     *
     * <p>The button ids are the tile's own module ids, which libVulpes puts straight on the
     * GuiButton: 2 is the third tab (the tab strip numbers itself from 0), 5 is the distance
     * increment and 6 is Observe. The tab strip and the machine share one id space, which is why the
     * scan controls were given ids above the strip's.</p>
     *
     * <p>The aim is read back from the SERVER after the clicks and the fixture system is placed at
     * whatever distance the clicks actually produced — so the arrangement follows the GUI rather
     * than assuming it worked.</p>
     */
    @Test
    public void theOperatorAimsTheTelescopeAndObservesWithNothingButClicks() throws Exception {
        int dim = plot().dim;
        int[] at = buildObservatoryAndStandBesideIt();
        String where = dim + " " + at[0] + " " + at[1] + " " + at[2];

        scenario().arranging("a blank crystal in the machine, and the default (no-research) regime");
        // The default game: without the research master switch a survey is not a matter of time —
        // what the instrument reaches, it resolves. That is what the player at this GUI sees, so it
        // is what this drives.
        exec("artest config set planetsMustBeDiscovered false");
        exec("artest config set telescopeScanBaseTicks 0");
        exec("artest config set telescopeScanTicksPerSector 1");
        exec("artest config set telescopeScanHalfWidthSectors 1");
        exec("artest config set telescopeScanRangeSectors 24");
        String crystal = exec("artest telescope crystal " + where);
        scenario().requireArranged("could not put a crystal in the observatory: " + crystal,
                crystal.contains("\"ok\":true"));

        String before = exec("artest telescope info " + where);
        scenario().requireArranged("the observatory's world must have a galactic address: " + before,
                before.contains("\"origin\":\""));
        String[] home = readGroup(before, TELESCOPE_ORIGIN).split("_");
        scenario().record("origin", readGroup(before, TELESCOPE_ORIGIN));

        scenario().arranging("open the observatory and switch to its region-scan tab");
        String screen = openMachineGui(at);
        scenario().record("screen", screen)
                .describeOnFailureWith("artest telescope info " + where);
        bot().clickButtonById(2);
        bot().waitTicks(20);

        scenario().asserting("the aim buttons reach the machine, and Observe starts the look");
        bot().clickButtonById(5);
        bot().waitTicks(15);
        bot().clickButtonById(5);
        bot().waitTicks(15);

        String aimed = exec("artest telescope info " + where);
        long aimDistance = readInt(aimed, TELESCOPE_AIM_DISTANCE);
        assertTrue("clicking the distance button twice must move the aim out from 1: " + aimed,
                aimDistance > 1);

        // Put a system exactly where the operator has it pointed — the default aim is +X, and the
        // distance is whatever his clicks produced.
        String system = exec("artest telescope system " + (Long.parseLong(home[0]) + aimDistance)
                + " " + home[1] + " " + home[2]);
        scenario().requireArranged("could not place a system to be found: " + system,
                system.contains("\"ok\":true"));

        bot().clickButtonById(6);
        bot().waitTicks(20);

        scenario().measuring("the crystal in the machine, after a survey driven only by clicks");
        String done = exec("artest telescope info " + where);
        for (int attempt = 0; attempt < 20 && readInt(done, TELESCOPE_ADDRESSES) < 1; attempt++) {
            bot().waitTicks(20);
            done = exec("artest telescope info " + where);
        }
        assertTrue("a survey driven entirely from the GUI left the crystal empty: " + done,
                readInt(done, TELESCOPE_ADDRESSES) >= 1);

        bot().closeScreen();
    }

    // ── planet selector ───────────────────────────────────────────────────────

    /**
     * From {@code PlanetSelectorGuiE2ETest}. Introspects the open GUI's buttons via
     * {@code report_buttons}, then clicks a planet button <em>by its stable mod-assigned id</em>
     * ({@code GuiButton.id} == the planet's dimension id; see {@code ModulePlanetSelector}).
     * Clicking a planet fires {@code TilePlanetSelector.onSelected} &rarr; {@code PacketMachine}
     * &rarr; server {@code useNetworkData} &rarr; {@code dimCache}, which the
     * {@code /artest selector info} probe then confirms — the whole client&rarr;server selection
     * round-trip rather than just "the GUI opened".
     */
    @Test
    public void selectingPlanetUpdatesServerSelection() throws Exception {
        int[] at = placeMachineAndStandOnIt("advancedrocketry:planetSelector");

        scenario().arranging("open the planet selector's GUI");
        String screen = openMachineGui(at);
        scenario().record("screen", screen)
                .describeOnFailureWith("artest selector info " + plot().dim + " " + at[0] + " "
                        + at[1] + " " + at[2]);

        scenario().measuring("pick a planet button by id range (control buttons sit outside it)");
        JsonObject buttons = bot().reportButtons();
        int planetId = ClientGuiTestSupport.findButtonId(buttons, 0, STAR_ID_OFFSET);
        scenario().requireArranged("no clickable planet button in selector GUI: " + buttons,
                planetId != Integer.MIN_VALUE);
        scenario().record("planetButtonId", planetId);

        scenario().asserting("clicking it registers the selection server-side");
        bot().clickButtonById(planetId);
        bot().waitTicks(20);

        String selectorInfo = exec("artest selector info " + plot().dim + " " + at[0] + " " + at[1]
                + " " + at[2]);
        assertTrue("clicking planet button " + planetId
                + " did not register a selection server-side: " + selectorInfo,
                selectorInfo.contains("\"hasSelection\":true"));
        assertTrue("selection did not resolve to a planet: " + selectorInfo,
                selectorInfo.contains("\"selectedDim\":"));

        bot().closeScreen();
    }

    // ── navigation console ────────────────────────────────────────────────────

    /**
     * From {@code NavigationComputerGuiE2ETest}. The navigation console driven the way a pilot
     * drives it: right-click it open, then nothing but button clicks.
     *
     * <p>What is pinned, in the order the pilot does it:</p>
     * <ol>
     *   <li><b>Arming with nowhere to go is refused, and said out loud.</b> The negative comes first
     *       because it doubles as the proof that a click on this GUI reaches the server at all — the
     *       refusal in the pilot's own chat is the click's receipt.</li>
     *   <li><b>Copying a brought crystal does not empty it.</b></li>
     *   <li><b>The console lists what the ship now knows</b>, read off the real GUI's buttons.</li>
     *   <li><b>Picking a listed address aims the ship at THAT address.</b></li>
     *   <li><b>Arm, then disarm</b> — each answered in chat, each reflected in the console state.</li>
     * </ol>
     *
     * <p>Runs on a console standing in the world, NOT on an assembled ship: the harness's
     * right-click takes literal coordinates and has no raycast, so a block that lives in a ship's
     * subspace cannot be clicked. That is a limit of the instrument, not of the contract — and the
     * pilot can legitimately arm before assembly, which is what this does.</p>
     */
    @Test
    public void thePilotCopiesPicksAndArmsAtTheConsoleWithNothingButClicks() throws Exception {
        int dim = plot().dim;
        int[] at = placeMachineAndStandOnIt("advancedrocketry:navigationComputer");
        int navX = at[0], navY = at[1], navZ = at[2];
        String where = dim + " " + navX + " " + navY + " " + navZ;
        scenario().describeOnFailureWith("artest nav status " + where,
                "artest nav modules " + where);

        scenario().arranging("seed the brought crystal and give the ship a blank one to copy into");
        String seed = exec("artest nav crystal " + where + " 0 " + SEEDED + " " + FIRST_SECTOR);
        scenario().requireArranged("the source slot must hold a crystal carrying " + SEEDED
                + " addresses: " + seed, seed.contains("\"addresses\":" + SEEDED));
        // The ship's own crystal is the DESTINATION, and the copy is add-only into it: with that
        // slot empty there is nowhere to copy to and the button is a silent no-op.
        String shipCrystal = exec("artest nav crystal " + where + " 1 0");
        scenario().requireArranged("the ship slot must hold a (blank) crystal to copy INTO: "
                + shipCrystal, shipCrystal.contains("\"addresses\":0"));
        String before = exec("artest nav status " + where);
        scenario().requireArranged("ARRANGEMENT CONTROL: the ship's own crystal must start EMPTY, "
                + "or the copy leg below cannot tell a successful copy from a pre-loaded console: "
                + before, readInt(before, SHIP_COUNT) == 0);

        emptyTheHand();
        String screen = openMachineGui(at);
        scenario().record("screen", screen);

        // ---- 1) Try to arm with nowhere to go. ------------------------------------------------
        scenario().measuring("arm the chat channel with the console still open");
        armChatObservation();

        scenario().asserting("arming with no destination is refused, and the pilot is told why");
        bot().clickButtonById(BUTTON_ARM);
        String refusal = awaitChatContaining("no jump target", 30);
        assertTrue("arming with no destination chosen must be REFUSED and the pilot told why "
                + "(this is also the receipt proving a click on this GUI reaches the server). "
                + "chat=\"" + refusal + "\"",
                refusal.toLowerCase(Locale.ROOT).contains("no jump target"));
        String afterRefusal = exec("artest nav status " + where);
        assertFalse("and the console must not be armed: " + afterRefusal,
                readBoolean(afterRefusal, ARMED));

        // ---- 2) Copy the brought crystal into the ship's own. ---------------------------------
        scenario().asserting("COPY writes the addresses across and leaves the source holding them");
        bot().clickButtonById(BUTTON_COPY);
        String copied = awaitStatusWhere(where, SHIP_COUNT, SEEDED, 30);
        assertEquals("clicking COPY must write the brought crystal's addresses into the ship's own "
                + "crystal: " + copied, SEEDED, readInt(copied, SHIP_COUNT));
        assertEquals("and the brought crystal must KEEP them — the console exchanges knowledge, it "
                + "does not move it: " + copied, SEEDED, readInt(copied, SOURCE_COUNT));

        // ---- 3) The console lists what the ship now knows. -------------------------------------
        // Reopened, because the address list is built when the screen is.
        bot().closeScreen();
        scenario().requireArranged("the console GUI must close", waitForNoScreen(bot(), 60).isEmpty());
        openMachineGui(at);

        scenario().asserting("the console LISTS the addresses the ship now knows");
        JsonObject buttons = bot().reportButtons();
        int listed = countAddressButtons(buttons);
        assertEquals("the console must LIST the addresses the ship now knows — the pilot picks a "
                + "destination off this list, so a copy he cannot see is a copy he cannot use: "
                + buttons, SEEDED, listed);
        // NOT asserted here: the labels themselves. Every libVulpes module button is a
        // GuiImageButton built with an empty displayString and draws its caption itself, so the
        // harness's button report is structurally blind to it. What the list CONTAINS is pinned
        // below instead, by picking off it.

        // ---- 4) Pick one: the ship is aimed at THAT address. ------------------------------------
        scenario().asserting("picking the first listed address aims the ship at that address");
        bot().clickButtonById(BUTTON_PICK_FIRST);
        String aimed = awaitStatusWhereNotNull(where, TARGET, 30);
        String expected = "\"" + FIRST_SECTOR + "_0_0\"";
        assertEquals("clicking the first listed address must aim the ship at THAT address — the "
                + "list's order is what the pilot picks by, so aiming at some other entry is the "
                + "same defect as not aiming at all: " + aimed, expected, readGroup(aimed, TARGET));

        // ---- 5) Arm, and stand down again. Both answered. ---------------------------------------
        scenario().measuring("re-arm the chat channel before the arming click");
        armChatObservation();

        scenario().asserting("arming a chosen destination is accepted, confirmed, and real");
        bot().clickButtonById(BUTTON_ARM);
        String armedChat = awaitChatContaining("jump armed", 30);
        assertTrue("arming a chosen destination must be accepted and confirmed to the pilot. "
                + "chat=\"" + armedChat + "\"",
                armedChat.toLowerCase(Locale.ROOT).contains("jump armed"));
        String armedStatus = exec("artest nav status " + where);
        assertTrue("and the console must actually BE armed — the message is not the state: "
                + armedStatus, readBoolean(armedStatus, ARMED));

        scenario().asserting("pressing the same button again stands the jump down, and says so");
        bot().clickButtonById(BUTTON_ARM);
        String disarmedChat = awaitChatContaining("jump disarmed", 30);
        assertTrue("pressing the same button again must stand the jump down, and say so. chat=\""
                + disarmedChat + "\"",
                disarmedChat.toLowerCase(Locale.ROOT).contains("jump disarmed"));
        String disarmedStatus = exec("artest nav status " + where);
        assertFalse("a disarmed console must not stay armed: " + disarmedStatus,
                readBoolean(disarmedStatus, ARMED));

        bot().closeScreen();
    }

    /** Poll the client's chat until a line contains {@code needle} (bounded); returns the last hit. */
    private String awaitChatContaining(String needle, int samples) throws Exception {
        String seen = "";
        for (int i = 0; i < samples; i++) {
            JsonObject chat = bot().reportChat(8);
            seen = chat.toString();
            if (seen.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                return seen;
            }
            bot().waitTicks(5);
        }
        return seen;
    }

    /** Poll {@code nav status} until the numeric group of {@code p} equals {@code want} (bounded). */
    private String awaitStatusWhere(String where, Pattern p, int want, int samples) throws Exception {
        String status = "";
        for (int i = 0; i < samples; i++) {
            status = exec("artest nav status " + where);
            Matcher m = p.matcher(status);
            if (m.find() && Integer.parseInt(m.group(1)) == want) {
                return status;
            }
            bot().waitTicks(5);
        }
        return status;
    }

    /** Poll {@code nav status} until {@code p}'s group is no longer {@code null} (bounded). */
    private String awaitStatusWhereNotNull(String where, Pattern p, int samples) throws Exception {
        String status = "";
        for (int i = 0; i < samples; i++) {
            status = exec("artest nav status " + where);
            Matcher m = p.matcher(status);
            if (m.find() && !"null".equals(m.group(1))) {
                return status;
            }
            bot().waitTicks(5);
        }
        return status;
    }

    /** How many address-pick buttons the open console shows. */
    private static int countAddressButtons(JsonObject reportButtons) {
        JsonArray list = reportButtons.getAsJsonArray("buttons");
        int found = 0;
        for (JsonElement element : list) {
            JsonObject button = element.getAsJsonObject();
            if (button.get("id").getAsInt() >= BUTTON_PICK_FIRST
                    && button.get("visible").getAsBoolean()) {
                found++;
            }
        }
        return found;
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
        scenario().arrangementFailed("the bot's hand must be observably empty; held=" + heldId);
    }

    // ── inventory-bypass mixin ────────────────────────────────────────────────

    /**
     * From {@code InventoryBypassRedirectE2ETest}. Live end-to-end pin for the
     * {@code MixinEntityPlayer(MP)InventoryAccess} {@code @Redirect}.
     *
     * <p>The unit-level pin ({@code testUnit.RocketInventoryHelperRedirectTest}) covers the
     * boolean-logic surface of {@code RocketInventoryHelper.shouldAllowContainerInteract}, but it
     * cannot prove the mixin's {@code @Redirect} actually intercepts vanilla's
     * {@code Container.canInteractWith} call inside {@code EntityPlayerMP.onUpdate}. That needs a
     * live {@code EntityPlayer} with an open container GUI, ticked by the dedicated server's normal
     * loop.</p>
     *
     * <p>Two phases: bypass ON — teleport far past vanilla's 8-block reach, GUI must stay open;
     * bypass OFF — the GUI must close on the next tick. A vanilla chest is the container so the
     * redirect target is the exact vanilla signature the mixin pins.</p>
     */
    @Test
    public void mixinRedirectKeepsContainerOpenAcrossDistance() throws Exception {
        int dim = plot().dim;
        int x = plot().x(MACHINE_DX);
        int z = plot().z(MACHINE_DZ);

        scenario().arranging("place a vanilla chest and stand on it");
        warmupPlotChunks();
        exec("artest player inv-bypass remove");
        String place = exec("artest place " + dim + " " + x + " " + Y + " " + z + " minecraft:chest");
        scenario().requireArranged("chest place must succeed: " + place,
                place.contains("\"placed\":true"));
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5) + " 0 90");
        bot().waitTicks(40);

        // Opened SERVER-side (mirrors BlockChest.onBlockActivated -> player.displayGUIChest) rather
        // than by right-click: the right-click packet was dropped before the chunk/player settled
        // in the original class, a settle-timing race orthogonal to the mixin contract under test.
        // The S2C open-window packet makes the real client render GuiChest.
        String open = exec("artest player open-chest " + dim + " " + x + " " + Y + " " + z);
        scenario().requireArranged("server-side open-chest must succeed: " + open,
                open.contains("\"ok\":true"));
        String screen = waitForScreen(GUI_CHEST, 100);
        scenario().requireArranged("chest GUI must open after server-side displayGUIChest; "
                + "openResp=" + open, GUI_CHEST.equals(screen));

        scenario().asserting("with the bypass on, the GUI survives a 200-block teleport");
        String addResp = exec("artest player inv-bypass add");
        scenario().requireArranged("inv-bypass add must report inBypass:true: " + addResp,
                addResp.contains("\"inBypass\":true"));

        exec("tp @a " + (x + 200) + " " + (Y + 1) + " " + (z + 200) + " 0 0");
        bot().waitTicks(40);

        JsonObject afterTpWithBypass = bot().reportState();
        // Diagnostic: re-check bypass status post-teleport so a failure can distinguish "bypass
        // dropped from the set" from "the mixin redirect didn't fire". The bypass map uses
        // WeakReferences.
        String statusAfterTp = exec("artest player inv-bypass status");
        assertEquals("with inv-bypass active, the chest GUI must remain open across a 200-block "
                + "teleport (the mixin redirect should force canInteractWith -> true on every "
                + "EntityPlayerMP.onUpdate tick); reportState=" + afterTpWithBypass
                + " bypassStatus=" + statusAfterTp,
                GUI_CHEST, screenOf(afterTpWithBypass));

        scenario().asserting("with the bypass off, vanilla's distance check closes it");
        String removeResp = exec("artest player inv-bypass remove");
        scenario().requireArranged("inv-bypass remove must report inBypass:false: " + removeResp,
                removeResp.contains("\"inBypass\":false"));

        String finalScreen = waitForNoScreen(bot(), 200);
        assertEquals("after removing inv-bypass, vanilla's distance check must close the chest "
                + "GUI; final screen=" + finalScreen, "", finalScreen);
    }

    /** Polls the client for up to {@code maxTicks} until {@code wantScreen} is showing. */
    private String waitForScreen(String wantScreen, int maxTicks) throws Exception {
        String screen = screenOf(bot().reportState());
        for (int i = 0; i < maxTicks && !wantScreen.equals(screen); i++) {
            bot().waitTicks(2);
            screen = screenOf(bot().reportState());
        }
        return screen;
    }
}
