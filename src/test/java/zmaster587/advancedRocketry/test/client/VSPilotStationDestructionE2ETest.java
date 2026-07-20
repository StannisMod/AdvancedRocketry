package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Destroying a piloted ship's control station RELEASES the ship instead of latching it. Two
 * destruction targets, each its own contract:
 *
 * <ul>
 *   <li><b>The occupied pilot seat</b>: the rider is dismounted, the seat's mount dummy is
 *       removed, and the flight computer drops the pilot's last input AND his cruise setpoint —
 *       the ship reverts to an unmanned station-hold. Without that, the computer executes the last
 *       command every tick and (with Flight Assist ramping the cruise) the ship becomes an
 *       uncontrollable runaway accelerating away with nobody at the controls.</li>
 *   <li><b>The linked flight computer</b>: the pilot is dismounted, told the computer is gone
 *       (action bar), the dummy removed — and the dead computer's command channels die with it, so
 *       nothing keeps thrusting a brainless ship.</li>
 * </ul>
 *
 * <p>Full honest path: a real client pilot (real held key → packet → seat → computer → force)
 * flies the ship; the break is a server-side block removal exactly like a mined block (the same
 * {@code breakBlock} path); every outcome is read from the CLIENT (riding state, entity presence,
 * the action-bar overlay) with the server ship position as the motion oracle. Gated on real VS —
 * run with {@code -PwithVS}.</p>
 */
public class VSPilotStationDestructionE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern SEAT_SUB = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern AFC_SUB = Pattern.compile(
            "\"afcX\":(-?\\d+),\"afcY\":(-?\\d+),\"afcZ\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";

    @Test
    public void breakingTheOccupiedSeatDismountsThePilotAndHoldsTheShip() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());
        int bx = 4400, by = 64, bz = 4400;
        FlyingShip ship = assembleLoadAndFly(bx, by, bz);

        // Break the seat WHILE the climb key is still held — the exact latch scenario: the client
        // can no longer send a release (the seat tile is gone), so only the destruction handler
        // stands between the ship and flying the last command forever.
        String broke = exec("artest fill 0 " + ship.seatX + " " + ship.seatY + " " + ship.seatZ
                + " " + ship.seatX + " " + ship.seatY + " " + ship.seatZ + " minecraft:air");
        assertTrue("breaking the seat block failed: " + broke, broke.contains("\"ok\":true"));
        try {
            // The rider must be DISMOUNTED, observed from the client's own riding state.
            JsonObject riding = awaitRiding(40, false);
            assertTrue("destroying the OCCUPIED pilot seat must dismount the rider (client-observed): "
                            + riding, !isRiding(riding));

            // The ship must revert to an unmanned HOLD — not keep flying the latched climb, and
            // not keep cruising a retained setpoint (destruction zeroes it). Let the brake settle,
            // then require the altitude to be stable over a 3-second window — with the key STILL
            // physically held, so a surviving latch would be climbing at cruise speed here.
            bot().waitTicks(40);
            double y1 = shipY(bx, by, bz);
            bot().waitTicks(60);
            double y2 = shipY(bx, by, bz);
            assertTrue("after the seat is destroyed the ship must HOLD, never fly the dead pilot's "
                            + "last command (y1=" + y1 + " y2=" + y2 + ")",
                    Math.abs(y2 - y1) < 2.0);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }

        // The seat's mount dummy must be gone, as the CLIENT sees the world.
        int dummies = clientDummyCount();
        assertTrue("the destroyed seat's mount dummy must be removed (client sees " + dummies + ")",
                dummies == 0);
    }

    @Test
    public void breakingTheLinkedComputerDismountsMessagesAndNeverThrusts() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());
        int bx = 4600, by = 64, bz = 4600;
        FlyingShip ship = assembleLoadAndFly(bx, by, bz);
        bot().releaseKey(Keyboard.KEY_R);
        bot().waitTicks(10);

        String broke = exec("artest fill 0 " + ship.afcX + " " + ship.afcY + " " + ship.afcZ
                + " " + ship.afcX + " " + ship.afcY + " " + ship.afcZ + " minecraft:air");
        assertTrue("breaking the flight computer block failed: " + broke,
                broke.contains("\"ok\":true"));

        // The pilot is dismounted and TOLD the computer is gone (client action bar).
        JsonObject riding = awaitRiding(40, false);
        assertTrue("destroying the linked flight computer must dismount the pilot (client-observed): "
                        + riding, !isRiding(riding));
        String overlay = awaitOverlayContaining("destroyed", 30);
        assertTrue("the dismounted pilot must be told his flight computer was destroyed "
                        + "(action bar). overlay=\"" + overlay + "\"",
                overlay.toLowerCase(Locale.ROOT).contains("destroyed"));

        // A brainless ship must never keep thrusting upward: the dead computer's channels die
        // with the tile. (It is free to FALL — only continued powered climb is the defect.)
        double y1 = shipY(bx, by, bz);
        bot().waitTicks(80);
        double y2 = shipY(bx, by, bz);
        assertTrue("a ship whose flight computer was destroyed must not keep climbing under the "
                        + "dead computer's last command (y1=" + y1 + " y2=" + y2 + ")",
                y2 <= y1 + 2.0);

        int dummies = clientDummyCount();
        assertTrue("the seat's mount dummy must be removed when the computer is destroyed "
                        + "(client sees " + dummies + ")", dummies == 0);
    }

    // ---- Shared arrangement --------------------------------------------------------------------

    private static final class FlyingShip {
        int seatX, seatY, seatZ;
        int afcX, afcY, afcZ;
    }

    /**
     * Assemble the with-pilot-seat ship at the base, load it with the client nearby, resolve the
     * seat's and computer's SUBSPACE blocks (stationary — resolved before the climb), seat the bot
     * (probe mount — the harness cannot right-click a subspace block), and fly it up a couple of
     * blocks on a REAL held key. Returns with the climb key still held.
     */
    private FlyingShip assembleLoadAndFly(int bx, int by, int bz) throws Exception {
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleFixture(bx, by, bz);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int all = 0;
        for (int i = 0; i < 40 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a VS ship (all=" + all + ")", all >= 1);
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);
        double y0 = Double.NaN;
        for (int i = 0; i < 40 && Double.isNaN(y0); i++) {
            bot().waitTicks(5);
            if (count("ship-count") >= 1) {
                String info = exec("artest vs ship-info 0 " + bx + " " + by + " " + bz);
                if (info.contains("\"managed\":true")) {
                    y0 = readDouble(info, POS_Y);
                }
            }
        }
        assertTrue("the ship must LOAD with the client present", !Double.isNaN(y0));

        // Resolve the seat + computer SUBSPACE blocks now, while the ship still sits at its build
        // site (subspace addresses are stationary; the ship's world pose is about to change).
        FlyingShip ship = new FlyingShip();
        String found = exec("artest vs find-seat 0 " + bx + " " + (by + 5) + " " + bz);
        Matcher sm = SEAT_SUB.matcher(found);
        assertTrue("find-seat must resolve the ship's subspace seat: " + found, sm.find());
        ship.seatX = Integer.parseInt(sm.group(1));
        ship.seatY = Integer.parseInt(sm.group(2));
        ship.seatZ = Integer.parseInt(sm.group(3));
        Matcher am = AFC_SUB.matcher(found);
        assertTrue("find-seat must resolve the seat's linked computer: " + found, am.find());
        ship.afcX = Integer.parseInt(am.group(1));
        ship.afcY = Integer.parseInt(am.group(2));
        ship.afcZ = Integer.parseInt(am.group(3));

        // Seat the bot and fly up on the REAL key path until the climb is unambiguous.
        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must find the pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("bot must mount the seat dummy: " + mount, mount.contains("\"mounted\":true"));
        bot().waitTicks(10);

        double yAfter = y0;
        bot().holdKey(Keyboard.KEY_R);
        for (int i = 0; i < 100 && yAfter - y0 <= 2.0; i++) {
            bot().waitTicks(2);
            yAfter = shipY(bx, by, bz);
        }
        assertTrue("ARRANGEMENT: the seated bot must be flying the ship before its station can be "
                        + "destroyed (y0=" + y0 + " yAfter=" + yAfter + ")",
                yAfter - y0 > 2.0);
        return ship;
    }

    // ---- Observation helpers -------------------------------------------------------------------

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

    private int clientDummyCount() throws Exception {
        return bot().reportEntities("EntityDummy", 64.0).getAsJsonArray("entities").size();
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private double shipY(int bx, int by, int bz) throws Exception {
        return readDouble(exec("artest vs ship-info 0 " + bx + " " + by + " " + bz), POS_Y);
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

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
