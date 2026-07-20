package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Flight Assist is an AUTOPILOT, not a dead-man switch: the FA state (mode + cruise setpoint) is a
 * SETTING the ship keeps executing when the pilot leaves his seat. A pilot who ramps a cruise with
 * a real held key and then dismounts leaves a ship that KEEPS CRUISING at that setpoint — it must
 * not brake to a hover the moment nobody is seated (that behaviour made the "autopilot" a
 * per-tick input echo). Re-mounting must not interrupt or reset the executing cruise either — the
 * saved setting is the pilot's to come back to.
 *
 * <p>Full honest path: the cruise is ramped by a REAL held key on the real client; the dismount
 * and re-mount are player actions (probe-driven where the harness cannot right-click a subspace
 * seat); the ship's continued motion is the server oracle. The zero-setpoint degenerate case
 * (station-hold, "the hovering ship fell" fix) stays pinned by the existing flight suite. Gated
 * on real VS — run with {@code -PwithVS}.</p>
 */
public class VSShipUnmannedCruiseE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 4800, BY = 64, BZ = 4800;

    @Test
    public void aDismountedPilotsShipKeepsCruisingAndSurvivesRemount() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());

        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        String assemble = assembleFixture(BX, BY, BZ);
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

        // Seat the bot, ramp a vertical cruise with the REAL key (Flight Assist is on by default:
        // holding the throttle ramps the setpoint; ~3 s of full deflection reaches cruise speed).
        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must find the pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("bot must mount the seat dummy: " + mount, mount.contains("\"mounted\":true"));
        bot().waitTicks(10);

        // 60 ticks of full deflection = the whole setpoint ramp (rest -> cruise speed). Kept
        // short deliberately: the ship keeps climbing for the rest of the test, and it must stay
        // within the (grounded) client's load range the whole time.
        double yRamped = y0;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int i = 0; i < 30; i++) {
                bot().waitTicks(2);
            }
            yRamped = shipY();
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        assertTrue("ARRANGEMENT: the held key must have ramped a real climb before the dismount "
                        + "can test anything (y0=" + y0 + " yRamped=" + yRamped + ")",
                yRamped - y0 > 2.0);
        bot().waitTicks(10);

        // Dismount mid-cruise. (The probe dismount stands in for any exit that is not the brake
        // key — standing up must not zero the cruise; braking to a stop before standing is the
        // pilot's separate, deliberate choice.)
        double yDismount = shipY();
        exec("artest player dismount");
        bot().waitTicks(40);
        double yUnmanned = shipY();
        assertTrue("an unmanned ship with Flight Assist on and a non-zero cruise setpoint must "
                        + "KEEP CRUISING after the pilot dismounts — that is what makes it an "
                        + "autopilot (yDismount=" + yDismount + " after 2s=" + yUnmanned + ")",
                yUnmanned - yDismount > 4.0);

        // Re-mounting must not interrupt (or reset) the executing cruise: the seat's dummy is
        // REUSED and the ship flies on while the returned pilot holds no key.
        String remount = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must still find the seat: " + remount,
                remount.contains("\"seatFound\":true"));
        assertTrue("the re-mount must REUSE the seat's single dummy: " + remount,
                remount.contains("\"reused\":true"));
        Matcher rm = DUMMY_ID.matcher(remount);
        assertTrue(remount, rm.find());
        String mounted = exec("artest player mount-entity " + rm.group(1));
        assertTrue("bot must re-mount the seat dummy: " + mounted,
                mounted.contains("\"mounted\":true"));
        double yRemount = shipY();
        bot().waitTicks(40);
        double yAfter = shipY();
        assertTrue("a re-mounted pilot receives the executing cruise BACK — the ship must not "
                        + "stop or reset because he sat down (yRemount=" + yRemount
                        + " after 2s=" + yAfter + ")",
                yAfter - yRemount > 4.0);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private double shipY() throws Exception {
        return readDouble(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ), POS_Y);
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
