package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * The relog-persistence contract of the ship-frame crew (any-attitude crew contract C14): a
 * player who logs out standing ABOARD a ship's deck logs back in ABOARD, at the same deck point,
 * at any ship attitude - never handed to world gravity while the capture re-seeds.
 *
 * <p>The subject is the HARD side of every axis this bug lives on: a real client player, captured
 * on the deck of an INVERTED ship (world gravity points away from the deck overhead, so any
 * un-captured tick starts a fall), across a REAL relog ({@code ClientBot.reconnect} - a full
 * server logout with player-data save and a fresh login, not a teleport).</p>
 *
 * <p>Gated on real VS - run with {@code -PwithVS}.</p>
 */
public class VSCrewRelogPersistenceE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";

    @Test
    public void aPlayerWhoRelogsOnAnInvertedDeckStaysAboardIt() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6520, by = 64, bz = 6520;

        // Capture the client player on the OPEN top deck while the ship is upright, then roll the
        // ship to inverted UNDER him - the capture carries his deck spot through the roll, leaving
        // him standing on the deck of an inverted ship (hanging under the hull in world terms).
        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("the player must be captured on the deck before the roll: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        double h = Math.toRadians(170.0) / 2.0;
        assertTrue("attitude hold must accept the inversion",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(200);
        double upY = readDouble(shipInfo(bx, by, bz), Pattern.compile("\"qx\":(-?[0-9.E\\-]+)"));
        // upY from the quat: for a roll about X, upY = 1 - 2*qx^2 (qy=qz=0). Read qx directly.
        upY = 1.0 - 2.0 * upY * upY;
        assertTrue("the ship must be (near-)inverted for the relog to be able to drop the player "
                + "(upY=" + upY + ")", upY < -0.9);
        String capBefore = exec("artest vs deck-capture");
        assertTrue("the player must still be captured on the inverted deck before the relog: "
                + capBefore, capBefore.contains("\"alreadyTracked\":true"));
        double preY = bot().reportState().get("playerY").getAsDouble();

        // The REAL relog: full server logout (player data saved) + fresh login.
        bot().reconnect();
        bot().waitForWorld();
        // Give the rejoined client time to stream chunks, load the ship and re-engage the
        // capture; poll rather than sleep a fixed window so a working build passes fast.
        boolean aboard = false;
        String capNow = "";
        for (int i = 0; i < 40 && !aboard; i++) {
            bot().waitTicks(5);
            capNow = exec("artest vs deck-capture");
            // ABOARD specifically: a hull-stand catch (falling under the inverted hull until the
            // hull geometry stops the body somewhere) is exactly the captured-but-world-camera
            // desync of the original report - it must NOT satisfy this contract.
            aboard = capNow.contains("\"alreadyTracked\":true")
                    && !capNow.contains("\"hullStand\":true");
        }
        double postY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[relog] preY=" + preY + " postY=" + postY + " aboard=" + aboard
                + " dY=" + (postY - preY));
        System.out.println("[relog] cap=" + capNow);

        // Contract C14: still ABOARD (deck semantics, not a hull-stand catch), still AT the deck
        // spot he logged out on - never handed to world gravity for a visible fall.
        assertTrue("after a relog on an inverted deck the player must be captured ABOARD again "
                + "(deck semantics, not hull-stand), not handed to world gravity: " + capNow,
                aboard);
        assertTrue("after a relog the player must still be AT his deck spot, not fallen off "
                + "(preY=" + preY + " postY=" + postY + ")", Math.abs(postY - preY) < 1.5);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(int bx, int by, int bz) throws Exception {
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);

        int shipsBefore = count("ship-count-all");
        String assemble = assembleFixture(bx, by, bz);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));

        int all = shipsBefore;
        for (int i = 0; i < 40 && all <= shipsBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a NEW VS ship (was " + shipsBefore + ", now " + all + ")",
                all > shipsBefore);
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        String info = "";
        double[] where = null;
        for (int i = 0; i < 40 && where == null; i++) {
            bot().waitTicks(5);
            info = shipInfo(bx, by, bz);
            if (!info.contains("\"managed\":true")) {
                continue;
            }
            double[] candidate = {readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
            if (distance(candidate, new double[]{bx, by, bz}) < 24.0) {
                where = candidate;
            }
        }
        assertTrue("the ship built at this base must LOAD with the client present; nearest was: " + info,
                where != null);
        return where;
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
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    private String shipInfo(int bx, int by, int bz) throws Exception {
        return exec("artest vs ship-info 0 " + bx + " " + by + " " + bz);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
