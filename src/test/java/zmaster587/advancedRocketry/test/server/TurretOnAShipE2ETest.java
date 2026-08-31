package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A gun bolted to a ship, which is the configuration every other turret test cannot reach.
 *
 * <h3>The whole difficulty in one sentence</h3>
 * <p>A turret on a ship stands in the SHIPYARD — a fixed address millions of blocks from where its
 * hull visibly is — while the target it is given, and the round it fires, belong to the world. So
 * the gun holds its bearing in the ship's frame and converts at the muzzle: the point through
 * {@code toWorldFrameFor}, the direction through {@code rotateToWorldFrameFor}, plus the hull's own
 * velocity. None of those three announces itself when it is wrong; the failure is simply a round
 * that appears somewhere nobody can see.</p>
 *
 * <h3>What makes this evidence</h3>
 * <p>The round is located after the shot. If any leg of the conversion were missing it would be in
 * the shipyard, five million blocks out — and every other assertion here (the gun assembled, it was
 * charged, it fired) would still pass. That distance is the discriminator, and it is asserted
 * explicitly rather than inferred from a hit.</p>
 */
public class TurretOnAShipE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** This class's own build site and destination, clear of the other ship scenarios. */
    private static final int SRC_X = 6800, SRC_Y = 80, SRC_Z = 6800;
    private static final int FAR_X = 6800, FAR_Y = 150, FAR_Z = 9200;

    /** Anything past this is a shipyard address rather than a place in the world. */
    private static final double SHIPYARD_THRESHOLD = 1_000_000.0D;

    private static final long TIMEOUT_MS = 25_000L;

    @Test
    public void aGunOnAShipFiresIntoTheWorldRatherThanIntoTheShipyard() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());
        exec("artest vs permaload true");
        exec("artest shot clear 0");

        String shipId = buildAndMoveShip();

        // A block of this ship whose SUBSPACE address we know: its pilot seat. The gun goes beside it.
        String seat = exec("artest vs find-seat 0 id " + shipId);
        assertTrue("could not locate the ship's seat, so there is nowhere known to mount a gun: "
                + seat, seat.contains("\"seatFound\":true"));
        int subX = extractInt(seat, "seatX"), subY = extractInt(seat, "seatY"),
                subZ = extractInt(seat, "seatZ");
        assertTrue("the seat is not at a shipyard address (" + subX + "), so this is not the case"
                + " the test is about", Math.abs(subX) > SHIPYARD_THRESHOLD);

        int gunX = subX + 3, gunY = subY, gunZ = subZ;
        buildGun(gunX, gunY, gunZ);

        String built = awaitOperable(gunX, gunY, gunZ);
        assertTrue("a gun aboard a named ship never assembled — it is being treated as if the ship"
                + " were unnamed: " + built, built.contains("\"operable\":true"));

        // Where the hull actually is, this tick.
        String info = exec("artest vs ship-info 0 " + FAR_X + " " + FAR_Y + " " + FAR_Z);
        assertTrue("the ship is not where it was moved to: " + info, info.contains("\"managed\":true"));
        double worldX = readDouble(info, "posX"), worldY = readDouble(info, "posY"),
                worldZ = readDouble(info, "posZ");

        exec("artest turret charge 0 " + gunX + " " + gunY + " " + gunZ);
        // A target in the WORLD, well clear of the hull.
        exec("artest turret target 0 " + gunX + " " + gunY + " " + gunZ + " " + (worldX + 60.0D)
                + " " + worldY + " " + worldZ);

        String fired = awaitShots(gunX, gunY, gunZ, 1);
        assertTrue("a gun aboard a ship never fired: " + fired, extractInt(fired, "shots") >= 1);

        // THE assertion: the round is in the world, near the hull — not at the shipyard address the
        // gun's own BlockPos would have given it.
        String flight = exec("artest shot list 0");
        double furthest = furthestShotX(flight);
        assertTrue("a round is in the air at x=" + furthest + ", which is a shipyard address: the"
                + " muzzle point was never mapped out of the ship's frame, so the gun is shelling a"
                + " place no player can reach: " + flight, furthest < SHIPYARD_THRESHOLD);
        double nearest = nearestShotDistance(flight, worldX, worldY, worldZ);
        assertTrue("the nearest round is " + nearest + " blocks from the hull that fired it — it is"
                + " in the world, but not where this ship is: " + flight, nearest < 400.0D);
    }

    // ---- fixture

    /** Build the fixture, assemble it into a ship, and move it far from where it was built. */
    private String buildAndMoveShip() throws Exception {
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z);
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));

        String info = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            exec("artest vs load-ships 0");
            info = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
            if (info.contains("\"managed\":true")) {
                break;
            }
            Thread.sleep(250L);
        }
        assertTrue("the build never became a ship managed at its build site: " + info,
                info != null && info.contains("\"managed\":true"));

        String tp = exec("artest vs teleport-ship 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z
                + " " + FAR_X + " " + FAR_Y + " " + FAR_Z);
        assertTrue("the ship could not be moved: " + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + FAR_X + " " + FAR_Y + " " + FAR_Z);
        return extractString(info, "id");
    }

    private String placeFixture(int baseX, int baseY, int baseZ) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " with-pilot-seat");
        Matcher m = BUILDER_POS.matcher(fixture);
        assertTrue("fixture did not report a builder position: " + fixture, m.find());
        return m.group(1) + " " + m.group(2) + " " + m.group(3);
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " "
                + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " "
                + (baseZ - 4) + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    /** The same reference gun the ground tests use, placed at SUBSPACE coordinates. */
    private void buildGun(int gx, int gy, int gz) throws Exception {
        place("advancedrocketry:turret", gx, gy, gz);
        for (int i = 1; i <= 4; i++) {
            place("advancedrocketry:gunBarrel", gx, gy + i, gz);
        }
        place("advancedrocketry:gunCooling", gx, gy, gz + 1);
        place("advancedrocketry:gunCooling", gx, gy, gz - 1);
    }

    // ---- reads

    private String awaitOperable(int gx, int gy, int gz) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = read(gx, gy, gz);
        while (System.currentTimeMillis() < deadline && !state.contains("\"operable\":true")) {
            Thread.sleep(250L);
            state = read(gx, gy, gz);
        }
        return state;
    }

    private String awaitShots(int gx, int gy, int gz, int wanted) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = read(gx, gy, gz);
        while (System.currentTimeMillis() < deadline && extractInt(state, "shots") < wanted) {
            Thread.sleep(250L);
            state = read(gx, gy, gz);
        }
        return state;
    }

    private String read(int gx, int gy, int gz) throws Exception {
        return exec("artest turret read 0 " + gx + " " + gy + " " + gz);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /** The largest |x| any shot in flight reports, or 0 when nothing is up. */
    private static double furthestShotX(String json) {
        Matcher m = Pattern.compile("\"x\":(-?[\\d.eE+]+)").matcher(json);
        double furthest = 0.0D;
        while (m.find()) {
            furthest = Math.max(furthest, Math.abs(Double.parseDouble(m.group(1))));
        }
        return furthest;
    }

    /** How close the nearest shot is to a world point. */
    private static double nearestShotDistance(String json, double x, double y, double z) {
        Matcher m = Pattern.compile("\"x\":(-?[\\d.eE+]+),\"y\":(-?[\\d.eE+]+),\"z\":(-?[\\d.eE+]+)")
                .matcher(json);
        double best = Double.POSITIVE_INFINITY;
        while (m.find()) {
            double dx = Double.parseDouble(m.group(1)) - x;
            double dy = Double.parseDouble(m.group(2)) - y;
            double dz = Double.parseDouble(m.group(3)) - z;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[\\d.eE+]+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
