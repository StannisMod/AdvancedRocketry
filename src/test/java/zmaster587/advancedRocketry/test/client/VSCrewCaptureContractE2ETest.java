package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * The capture/release contract of the ship-frame crew (any-attitude crew contract C1/C3/C9), pinned
 * against a REAL CLIENT PLAYER — the subject that broke in the inverted-boarding playtest. Two
 * boundary behaviours that the world-AABB containment gate got wrong:
 *
 * <ul>
 *   <li><b>Jumping on the TOP deck keeps the capture (C3).</b> The hull's top surface sits at the
 *       ship's world-AABB ceiling; a jump apex from there crossed the old grown-box gate
 *       (`leftShipBox`) and the capture died MID-AIR — vanilla, blind to the subspace deck, then
 *       tunnelled the body through the whole ship. The stay region is measured in SUBSPACE with a
 *       real margin, so a jump must ride out and land back on the deck, still captured.</li>
 *   <li><b>A player walking on world TERRAIN near a ship is never captured (C1/C9).</b> A ground
 *       position mapped through a parked ship's transform can alias onto a subspace block, and the
 *       old first-contact gate then captured a walker who stood on plain ground beside the hull
 *       (the playtest's "entered the ship transform at a random place"). Terra firma always keeps
 *       world-frame movement.</li>
 * </ul>
 *
 * <p>Gated on real VS — run with {@code -PwithVS}. Each test builds its own ship at its own base so
 * a ship one leaves behind cannot poison the next.</p>
 */
public class VSCrewCaptureContractE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";

    // ---- C3: a jump from the top deck must not release the capture ------------------------------

    @Test
    public void jumpingOnTheTopDeckKeepsTheCaptureAndLandsBackOnIt() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5220, by = 64, bz = 5220;

        // The subject is on the HARD side of the geometry: the fixture's walkable deck is the hull's
        // TOP surface, so the player's feet stand at the ship's world-AABB ceiling and a vanilla jump
        // apex (~1.25) pokes above the old grown-box gate. On the old gate this exact jump released
        // the capture mid-air; the contract is that it must not.
        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("the client player must be captured on the deck before the jump: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"verdict\":true"));
        double deckY = bot().reportState().get("playerY").getAsDouble();

        // A REAL jump: the space key on the real client. Sample the capture through the whole arc -
        // the failure mode is a release at the apex, which a single after-the-fact read can miss if
        // a fresh first-contact re-captured on landing.
        int tracked = 0, samples = 0;
        double apex = deckY;
        StringBuilder trace = new StringBuilder();
        bot().holdKey(Keyboard.KEY_SPACE);
        try {
            for (int i = 0; i < 10; i++) {
                bot().waitTicks(2);
                samples++;
                String cap = exec("artest vs deck-capture");
                boolean t = cap.contains("\"alreadyTracked\":true");
                if (t) tracked++;
                double y = bot().reportState().get("playerY").getAsDouble();
                apex = Math.max(apex, y);
                trace.append(String.format("[%d y=%.2f tracked=%b] ", i, y, t));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_SPACE);
        }
        bot().waitTicks(40); // land and settle
        String capture = exec("artest vs deck-capture");
        double settledY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[crewcap] jump deckY=" + deckY + " apex=" + apex + " settledY=" + settledY
                + " tracked=" + tracked + "/" + samples + " :: " + trace);
        System.out.println("[crewcap] jump capture=" + capture);

        assertTrue("the jump must actually leave the deck (apex=" + apex + " deckY=" + deckY + ")",
                apex - deckY > 0.5);
        assertTrue("the capture must survive the whole jump arc, not release mid-air (" + tracked + "/"
                + samples + " samples tracked): " + trace, tracked == samples);
        assertTrue("after the jump the player must be resolved back on the deck: " + capture,
                capture.contains("\"verdict\":true"));
        assertTrue("the player must land back ON the deck, not through it: deckY=" + deckY
                + " settledY=" + settledY, Math.abs(settledY - deckY) < 1.5);
    }

    // ---- C1/C9: terra firma near a ship never captures ------------------------------------------

    @Test
    public void walkingOnTheGroundBesideAParkedShipNeverEntersItsFrame() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5320, by = 64, bz = 5320;

        double[] ship = buildShip(bx, by, bz);

        // Tilt the parked ship: an axis-aligned world box around a rotated hull over-includes a large
        // ground area, and a tilted transform is what aliased a GROUND position onto a subspace block
        // in the playtest (a walker was captured into a 44.7-degree ship's frame). This is the hard
        // side of the axis; an upright ship rarely aliases.
        double h = Math.toRadians(45.0) / 2.0;
        assertTrue("attitude hold must accept the tilt",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h)).contains("\"commanded\":true"));
        bot().waitTicks(120);
        String info = shipInfo(bx, by, bz);
        double sx = readDouble(info, POS_X), sz = readDouble(info, POS_Z);

        // Put the REAL client player on the GROUND beside the hull, inside the grown world box, and
        // WALK him along it with the real forward key. He stands on terra firma the whole way.
        exec("tp @a " + (sx + 4) + " " + (by + 1) + " " + (sz + 4) + " 0 0");
        bot().waitTicks(30);
        double groundY = bot().reportState().get("playerY").getAsDouble();

        int captured = 0, samples = 0;
        double yMin = groundY, yMax = groundY;
        StringBuilder trace = new StringBuilder();
        bot().holdKey(Keyboard.KEY_W);
        try {
            for (int i = 0; i < 12; i++) {
                bot().waitTicks(4);
                samples++;
                String cap = exec("artest vs deck-capture");
                boolean t = cap.contains("\"alreadyTracked\":true");
                boolean terrain = cap.contains("\"supportedByWorldTerrain\":true");
                if (t) captured++;
                double y = bot().reportState().get("playerY").getAsDouble();
                yMin = Math.min(yMin, y);
                yMax = Math.max(yMax, y);
                trace.append(String.format("[%d y=%.2f cap=%b terra=%b] ", i, y, t, terrain));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
        }
        System.out.println("[crewcap] ground-walk groundY=" + groundY + " yMin=" + yMin + " yMax="
                + yMax + " captured=" + captured + "/" + samples + " :: " + trace);

        assertTrue("a player walking on world terrain beside a parked ship must NEVER be captured "
                + "into its frame (" + captured + "/" + samples + " samples captured): " + trace,
                captured == 0);
        assertTrue("his world-frame walk must stay on the ground - no ship-frame yank (y "
                + yMin + ".." + yMax + " around " + groundY + ")", yMax - yMin < 2.0);
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
        System.out.println("[crewcap] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
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
