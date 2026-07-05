package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Foundation coverage for the parts-wear rework:
 *
 * <ul>
 *   <li>motors, fuel tanks and seats host the wear capability in the world;</li>
 *   <li>{@code wear get/set} round-trips a stage;</li>
 *   <li>worn motors produce less thrust after assembly (graduated consequence).</li>
 * </ul>
 *
 * <p>The launch-time consequences (tank leak / explosion / seat-block) need a
 * pilot or stochastic launch and are covered later; this pins the data model
 * and the thrust contract that feeds TWR.</p>
 */
public class WearSystemTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS = Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");

    private void preClear(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        client().execute("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        client().execute("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air");
    }

    private int[] buildFixture(int baseX, int baseY, int baseZ) throws Exception {
        preClear(baseX, baseY, baseZ);
        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture build failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("no builderPos: " + fixture, bp.find());
        return new int[]{Integer.parseInt(bp.group(1)), Integer.parseInt(bp.group(2)), Integer.parseInt(bp.group(3))};
    }

    private int assembleAndGetId(int[] builderPos) throws Exception {
        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + builderPos[0] + " " + builderPos[1] + " " + builderPos[2]));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));
        String list = String.join("\n", client().execute("artest rocket list 0"));
        Matcher m = ROCKET_LIST_ID.matcher(list);
        int id = -1;
        while (m.find()) id = Integer.parseInt(m.group(1));
        assertTrue("no rocket id after assemble: " + list, id >= 0);
        return id;
    }

    private int thrustOf(int entityId) throws Exception {
        String info = String.join("\n", client().execute("artest rocket info " + entityId));
        Matcher m = Pattern.compile("\"thrust\":(-?\\d+)").matcher(info);
        assertTrue("no thrust in info: " + info, m.find());
        return Integer.parseInt(m.group(1));
    }

    @Test
    public void motorTankSeatHostWearCapability() throws Exception {
        int bx = 2900, by = 64, bz = 2900;
        buildFixture(bx, by, bz);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;

        // Engine, fuel tank, seat positions (see fixture builder).
        String engine = String.join("\n", client().execute(
                "artest wear get 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ));
        assertTrue("motor must host wear cap: " + engine, engine.contains("\"registered\":true"));

        String tank = String.join("\n", client().execute(
                "artest wear get 0 " + rocketX + " " + (rocketY + 1) + " " + rocketZ));
        assertTrue("fuel tank must host wear cap: " + tank, tank.contains("\"registered\":true"));

        String seat = String.join("\n", client().execute(
                "artest wear get 0 " + rocketX + " " + (rocketY + 4) + " " + rocketZ));
        assertTrue("seat must host wear cap: " + seat, seat.contains("\"registered\":true"));
    }

    @Test
    public void wearStageRoundTripsThroughCapability() throws Exception {
        int bx = 2960, by = 64, bz = 2900;
        buildFixture(bx, by, bz);
        int ex = bx + 3 - 1, ey = by + 1, ez = bz + 3;

        String set = String.join("\n", client().execute("artest wear set 0 " + ex + " " + ey + " " + ez + " 7"));
        assertTrue("wear set failed: " + set, set.contains("\"ok\":true"));

        String get = String.join("\n", client().execute("artest wear get 0 " + ex + " " + ey + " " + ez));
        Matcher m = Pattern.compile("\"stage\":(\\d+)").matcher(get);
        assertTrue("no stage in get: " + get, m.find());
        assertEquals("wear stage must persist", 7, Integer.parseInt(m.group(1)));
    }

    private double breakingProbOf(int entityId) throws Exception {
        String info = String.join("\n", client().execute("artest rocket info " + entityId));
        Matcher m = Pattern.compile("\"breakingProb\":(-?\\d+(?:\\.\\d+)?)").matcher(info);
        assertTrue("no breakingProb in info: " + info, m.find());
        return Double.parseDouble(m.group(1));
    }

    @Test
    public void wornMotorRaisesBreakingProbability() throws Exception {
        // Pristine rocket: zero failure probability.
        int ax = 2900, ay = 64, az = 3020;
        int pristine = assembleAndGetId(buildFixture(ax, ay, az));
        assertEquals("pristine rocket must have zero breaking probability",
                0.0, breakingProbOf(pristine), 1e-6);

        // Max out one engine's wear before assembly → breaking probability rises.
        int bx = 2960, by = 64, bz = 3020;
        int[] builder = buildFixture(bx, by, bz);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;
        client().execute("artest wear set 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ + " 10");
        int worn = assembleAndGetId(builder);
        assertTrue("a fully-worn motor must raise the breaking probability",
                breakingProbOf(worn) > 0);
    }

    @Test
    public void standaloneRepairResetsMotorWear() throws Exception {
        int bx = 2900, by = 64, bz = 3080;
        int[] builder = buildFixture(bx, by, bz);
        int rocketId = assembleAndGetId(builder);
        // Wear one motor to stage 5 (no PrecisionAssembler nearby → standalone path).
        String inject = String.join("\n", client().execute("artest infra inject-broken-part " + rocketId + " 5"));
        assertTrue("inject-broken-part failed: " + inject, inject.contains("\"ok\":true"));
        assertTrue("worn motor must give a non-zero breaking probability",
                breakingProbOf(rocketId) > 0);

        // Service station off to the side, with its own clear pocket + redstone power.
        int sx = bx - 4, sy = by + 1, sz = bz;
        client().execute("artest fill 0 " + (sx - 1) + " " + sy + " " + (sz - 1)
                + " " + (sx + 1) + " " + (sy + 2) + " " + (sz + 1) + " minecraft:air");
        String place = String.join("\n", client().execute(
                "artest place 0 " + sx + " " + sy + " " + sz + " advancedrocketry:serviceStation"));
        assertTrue("service station place failed: " + place, place.contains("\"placed\":true"));
        // Redstone power — performFunction requires getEquivalentPower=true.
        client().execute("artest place 0 " + sx + " " + (sy + 1) + " " + sz + " minecraft:redstone_block");

        String link = String.join("\n", client().execute(
                "artest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("link failed: " + link, link.contains("\"ok\":true"));

        // Load the stage-5 repair recipe's non-part materials (ingot + plate),
        // each well above the x3 standalone multiplier.
        String load0 = String.join("\n", client().execute(
                "artest wear station-load 0 " + sx + " " + sy + " " + sz + " 0 ore:ingotTitaniumIridium 16"));
        assertTrue("station-load ingot failed: " + load0, load0.contains("\"ok\":true"));
        String load1 = String.join("\n", client().execute(
                "artest wear station-load 0 " + sx + " " + sy + " " + sz + " 1 ore:plateTitaniumAluminide 16"));
        assertTrue("station-load plate failed: " + load1, load1.contains("\"ok\":true"));

        // Drive performFunction directly (no assembler → standalone repair branch).
        client().execute("artest infra service-perform-function 0 " + sx + " " + sy + " " + sz);
        client().execute("artest infra service-perform-function 0 " + sx + " " + sy + " " + sz);

        assertEquals("standalone repair must reset the worn motor (breaking prob back to 0)",
                0.0, breakingProbOf(rocketId), 1e-6);
    }

    @Test
    public void wornTankAndSeatSurfaceForLaunchGate() throws Exception {
        int bx = 2960, by = 64, bz = 3080;
        int[] builder = buildFixture(bx, by, bz);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;
        client().execute("artest wear set 0 " + rocketX + " " + (rocketY + 1) + " " + rocketZ + " 8");  // a fuel tank
        client().execute("artest wear set 0 " + rocketX + " " + (rocketY + 4) + " " + rocketZ + " 10"); // the seat
        int rocketId = assembleAndGetId(builder);

        String status = String.join("\n", client().execute("artest wear rocket-status " + rocketId + " 0.7"));
        assertTrue("rocket-status must find the rocket: " + status, status.contains("\"found\":true"));

        Matcher tanks = Pattern.compile("\"wornTankCount\":(\\d+)").matcher(status);
        assertTrue("no wornTankCount: " + status, tanks.find());
        assertTrue("a worn fuel tank must be surfaced for the launch gate: " + status,
                Integer.parseInt(tanks.group(1)) >= 1);
        assertTrue("a critically-worn seat must be detected: " + status,
                status.contains("\"hasCriticallyWornSeat\":true"));
    }

    @Test
    public void wornMotorsProduceLessThrust() throws Exception {
        // Pristine reference rocket.
        int[] pristineBuilder = {0, 0, 0};
        int ax = 2900, ay = 64, az = 2960;
        pristineBuilder = buildFixture(ax, ay, az);
        int pristineThrust = thrustOf(assembleAndGetId(pristineBuilder));
        assertTrue("pristine thrust must be positive", pristineThrust > 0);

        // Worn rocket: max out both engine wear stages before assembly.
        int bx = 2960, by = 64, bz = 2960;
        int[] wornBuilder = buildFixture(bx, by, bz);
        int rocketX = bx + 3, rocketY = by + 1, rocketZ = bz + 3;
        client().execute("artest wear set 0 " + (rocketX - 1) + " " + rocketY + " " + rocketZ + " 10");
        client().execute("artest wear set 0 " + (rocketX + 1) + " " + rocketY + " " + rocketZ + " 10");
        int wornThrust = thrustOf(assembleAndGetId(wornBuilder));

        assertTrue("worn rocket must still have some thrust: " + wornThrust, wornThrust > 0);
        assertTrue("fully-worn motors must produce less thrust than pristine ("
                        + wornThrust + " vs " + pristineThrust + ")",
                wornThrust < pristineThrust);
    }
}
