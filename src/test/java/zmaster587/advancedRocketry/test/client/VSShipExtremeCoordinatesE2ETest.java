package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE e2e: is a tier-2 ship CONTROLLABLE — and does the real client keep tracking it — at extreme
 * world Y (~4,000,000, just under the TOP of the cells' realized pose band, so the whole
 * advertised vertical range is evidenced, not only the middle)? The honest-Y realization
 * question: entities are NOT capped by the 256
 * build height (blocks are; vanilla's only hard line for entities is the void-kill below −64), so a
 * ship's world-frame pose can realize a galactic local-Y directly. A green run = GO for amending
 * the planar realization rule to an honest Y mapping.
 *
 * <p>The leg re-runs the SAME full-path pilot contract as the in-run control (real seated bot, real
 * vertical-up key, ship climbs; client rider tracks the server ship) — so a FAIL localises to the
 * coordinate regime, not to the pilot path. The arrange step is the rigid ship teleport
 * ({@code vs teleport-ship}: pose moves, subspace blocks stay, VS Y-limits widen, riders carried).
 * Gated on real VS — run with {@code -PwithVS}.</p>
 *
 * <p>Findings recorded while building this spike:
 * (1) VS's load controller UNLOADS the teleported ship's physics object even with the pilot aboard
 * — {@code permanentlyLoaded} is the workaround here, production honest-Y must own loadedness;
 * (2) a VS collision mixin ({@code preGetCollisionBoxes}) prints a console line EVERY TICK for an
 * entity at extreme Y — log flood, and it races probe replies;
 * (3) after a SECOND relocation the ship's physics goes inert (neither pilot key nor push-ship
 * moves it) and the pilot-key path dies after a dismount&rarr;re-seat across the map — both are
 * relocation-SEQUENCE findings needing their own ordinary-coordinates control; the extreme-|X|
 * precision leg stays an open follow-up until they are resolved.</p>
 */
public class VSShipExtremeCoordinatesE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 3400, BY = 64, BZ = 3400;
    private static final double EXTREME_Y = 3_999_000d;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void aSeatedPilotKeepsControlAtExtremeY() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());

        // ── Arrange: assemble a piloted ship at the base site (same recipe as the pilot-keys e2e). ──
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int all = 0;
        for (int i = 0; i < 40 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a VS ship (all=" + all + ")", all >= 1);
        bot().waitTicks(40);
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);
        double y0 = Double.NaN;
        for (int i = 0; i < 40 && Double.isNaN(y0); i++) {
            bot().waitTicks(5);
            if (count("ship-count") >= 1) {
                String info = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
                if (info.contains("\"managed\":true")) {
                    y0 = readDouble(info, POS_Y);
                }
            }
        }
        assertTrue("the ship must LOAD with the client present", !Double.isNaN(y0));

        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must find the pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        assertTrue("bot must mount the seat dummy",
                exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
        bot().waitTicks(10);

        // SPIKE FINDING (recorded): after a rigid teleport to extreme Y, VS's load controller UNLOADS
        // the physics object even with the pilot aboard ("managed":false) — the ship exists but stops
        // ticking. permanentlyLoaded is the documented headless-observation lever; production honest-Y
        // realization must own ship loadedness explicitly at extreme poses.
        assertTrue(exec("artest vs permaload true").contains("\"ok\":true"));

        // ── CONTROL leg: the pilot path works at ordinary coordinates (proves the instrument fires). ──
        climbLeg("control @ base", BX, y0, BZ);

        // ── Leg 1: extreme Y (~2M). Source = wherever the rider (glued to the ship) currently is. ──
        double srcY = bot().reportRidingEntity().get("posY").getAsDouble();
        String tpY = exec("artest vs teleport-ship 0 " + BX + " " + srcY + " " + BZ
                + " " + BX + " " + EXTREME_Y + " " + BZ);
        assertTrue("teleport-ship to extreme Y must succeed: " + tpY, tpY.contains("\"ok\":true"));
        bot().waitTicks(30); // transform adoption + rider sync settle
        exec("artest vs unpark 0 " + BX + " " + EXTREME_Y + " " + BZ);
        bot().waitTicks(10);
        String serverInfoAfterTp = exec("artest vs ship-info 0 " + BX + " " + EXTREME_Y + " " + BZ);
        double riderY = bot().reportRidingEntity().get("posY").getAsDouble();
        assertTrue("the CLIENT-rendered rider must arrive at extreme Y (got " + riderY
                        + "); server ship after teleport: " + serverInfoAfterTp,
                riderY > EXTREME_Y - 200 && riderY < EXTREME_Y + 200);
        climbLeg("extreme Y", BX, EXTREME_Y, BZ);

        // The extreme-|X| leg is NOT automated yet — see the class javadoc: after a SECOND
        // relocation the ship's physics goes inert (neither the pilot key nor the push-ship
        // velocity setpoint moves it) and the pilot-key path dies after a dismount->re-seat across
        // the map. Both are relocation-sequence findings, not coordinate-regime ones; the XZ
        // precision leg stays an open follow-up until they are resolved.

        exec("artest player dismount");
        exec("artest vs permaload false");
    }

    /**
     * One controllability measurement at the ship's current location: hold the REAL vertical-up key,
     * the server ship must climb, and the CLIENT-rendered rider must climb WITH it (tracking within
     * the same tolerance the ordinary-coordinates pilot e2e uses — a precision breakdown at extreme
     * coordinates shows up here as divergence).
     */
    private void climbLeg(String label, double nearX, double nearY, double nearZ) throws Exception {
        double yBefore = shipY(nearX, nearY, nearZ);
        double riderYBefore = bot().reportRidingEntity().get("posY").getAsDouble();
        double yAfter = yBefore;
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        try {
            for (int i = 0; i < 100 && yAfter - yBefore <= 1.5; i++) {
                bot().waitTicks(2);
                yAfter = shipY(nearX, nearY, nearZ);
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        assertTrue("[" + label + "] the vertical-up key must lift the ship (yBefore=" + yBefore
                + " yAfter=" + yAfter + ")", yAfter - yBefore > 1.0);
        bot().waitTicks(6);
        double serverDelta = shipY(nearX, nearY, nearZ) - yBefore;
        double riderDelta = bot().reportRidingEntity().get("posY").getAsDouble() - riderYBefore;
        // Third witness on divergence: the SERVER-side player position separates "the seat glue died
        // server-side" (server player static too) from "the client stopped tracking" (server player
        // climbed, client did not).
        String serverPlayer = exec("artest player health");
        assertTrue("[" + label + "] the CLIENT rider must track the server ship's climb (client="
                + riderDelta + " server=" + serverDelta + "); server player: " + serverPlayer,
                Math.abs(riderDelta - serverDelta) < 3.0);
    }

    /**
     * The server ship's posY near a point, tolerant of unrelated console lines interleaving with the
     * probe's JSON reply (at extreme coordinates a VS collision mixin spams STDERR lines, which can
     * arrive inside the captured console window) — retry until a parseable reply comes back.
     */
    private double shipY(double nearX, double nearY, double nearZ) throws Exception {
        String last = "";
        for (int i = 0; i < 10; i++) {
            last = exec("artest vs ship-info 0 " + nearX + " " + nearY + " " + nearZ);
            Matcher m = POS_Y.matcher(last);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
            bot().waitTicks(2);
        }
        throw new AssertionError("ship-info never returned a parseable posY; last reply: " + last);
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
