package zmaster587.advancedRocketry.test.server;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;

import static org.junit.Assert.assertTrue;

/**
 * A pilot seat's mount faces where its SHIP faces.
 *
 * <p>The dummy a pilot rides is bound to a seat block that lives in the ship's subspace while the
 * ship itself flies around the world, and it is snapped onto the seat's live world position every
 * tick. Its POSITION therefore tracks the ship — but position is only half of a mount. Anything that
 * asks the thing the player is riding <em>which way am I pointing</em> reads its ROTATION, and a
 * rotation nobody writes is a constant: it answers "due south" on every ship, on every heading, for
 * ever. That is not a rendering nicety — it is a mount that lies about the ship it is glued to.
 *
 * <p><b>The arrangement has to be able to fail.</b> A freshly assembled ship sits on the axis, where
 * a stuck-at-zero rotation and the true heading are the same number. So the ship is TURNED first and
 * the turn is read back off the ship itself; the assertion only means something after that gate, and
 * the gate is a hard failure rather than a skip.
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSSeatDummyFacesTheShipE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int SRC_X = 8800, SRC_Y = 80, SRC_Z = 8800;

    /** Quaternion for a ~90-degree yaw about world +Y: far from the fixture's own axis-aligned heading. */
    private static final double TURN_QW = 0.70711, TURN_QY = 0.70711;

    /** Degrees. Generous: what is under test is that the mount TURNS WITH the ship, not the controller's
     *  settling error, and a hovering attitude hold parks within a couple of degrees. */
    private static final double YAW_TOLERANCE_DEG = 6.0;

    @Test
    public void theSeatMountTurnsWithItsShip() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        exec("artest vs permaload true");
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("the pilot-seat build must route to a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source ship never assembled/loaded", waitForLoadedShip(0) >= 1);

        String seat = exec("artest vs find-seat 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("the pilot seat must be found in the assembled ship (else nothing below is measured): "
                + seat, seat.contains("\"seatFound\":true"));
        int seatX = extractInt(seat, "seatX"), seatY = extractInt(seat, "seatY"), seatZ = extractInt(seat, "seatZ");

        String mountAt = exec("artest vs seat-mount-at 0 " + seatX + " " + seatY + " " + seatZ);
        assertTrue("the seat's mount dummy must spawn: " + mountAt, mountAt.contains("\"ok\":true"));

        // Where the ship points BEFORE the turn, and where its mount thinks it points.
        String infoBefore = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("the ship must be managed for its attitude to be readable: " + infoBefore,
                infoBefore.contains("\"managed\":true"));
        double shipYawBefore = shipYawOf(infoBefore);
        double mountYawBefore = mountYaw(seatX, seatY, seatZ);

        // ── TURN THE SHIP ───────────────────────────────────────────────────────────────────────
        // Commanded on an UNMANNED ship: a seated pilot's own input would overwrite the attitude
        // target every tick. The ship hovers while the controller slews it round.
        double px = extractDouble(infoBefore, "posX");
        double py = extractDouble(infoBefore, "posY");
        double pz = extractDouble(infoBefore, "posZ");
        assertTrue("the attitude hold must accept the yaw command",
                exec("artest vs point 0 " + (int) px + " " + (int) py + " " + (int) pz
                        + " " + TURN_QW + " 0.0 " + TURN_QY + " 0.0").contains("\"commanded\":true"));

        double shipYawAfter = shipYawBefore;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(250);
            shipYawAfter = shipYawOf(exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z));
            if (Math.abs(wrapDegrees(shipYawAfter - shipYawBefore)) > 45.0) {
                break;
            }
        }

        // The gate: unless the SHIP really turned, "the mount agrees with the ship" is a comparison
        // of two zeroes and would be green on a build where nothing writes the mount at all.
        double shipTurned = Math.abs(wrapDegrees(shipYawAfter - shipYawBefore));
        assertTrue("the ship itself must have turned well away from its assembled heading, or the "
                        + "assertion below cannot fail (ship yaw " + shipYawBefore + " -> " + shipYawAfter
                        + ", turned " + shipTurned + " deg)",
                shipTurned > 45.0);

        // ── THE SUBJECT ─────────────────────────────────────────────────────────────────────────
        double mountYawAfter = mountYaw(seatX, seatY, seatZ);
        assertTrue("the mount must have turned WITH its ship - a mount whose rotation never moves is "
                        + "a mount that reports the wrong heading to everything that asks it (mount yaw "
                        + mountYawBefore + " -> " + mountYawAfter + " while the ship turned " + shipTurned
                        + " deg)",
                Math.abs(wrapDegrees(mountYawAfter - mountYawBefore)) > 45.0);
        assertTrue("the mount must face where its ship faces (mount yaw " + mountYawAfter
                        + " vs ship yaw " + shipYawAfter + ")",
                Math.abs(wrapDegrees(mountYawAfter - shipYawAfter)) <= YAW_TOLERANCE_DEG);
    }

    @After
    public void cleanup() throws Exception {
        if (serverHasVs()) {
            exec("artest vs permaload false");
        }
    }

    // --- observation --------------------------------------------------------------------------------

    /** The mount's own yaw, off the seat block it is bound to. */
    private double mountYaw(int seatX, int seatY, int seatZ) throws Exception {
        String status = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        assertTrue("the seat's bound mount must be found for its rotation to be read: " + status,
                status.contains("\"dummyFound\":true"));
        return extractDouble(status, "dummyYaw");
    }

    /** The ship's own heading, out of the attitude quaternion VS reports for it. */
    private double shipYawOf(String shipInfo) {
        FreeFlightPhysics.Quat q = new FreeFlightPhysics.Quat(
                extractDouble(shipInfo, "qw"), extractDouble(shipInfo, "qx"),
                extractDouble(shipInfo, "qy"), extractDouble(shipInfo, "qz"));
        return FreeFlightPhysics.eulerFromQuat(q)[0];
    }

    private static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    // --- arrangement --------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") >= 1) {
                exec("artest vs load-ships " + dim);
                if (extractInt(exec("artest vs ship-count " + dim), "count") >= 1) {
                    return 1;
                }
            }
            Thread.sleep(250);
        }
        return 0;
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20) + " minecraft:air")
                .contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE]-?\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }
}
