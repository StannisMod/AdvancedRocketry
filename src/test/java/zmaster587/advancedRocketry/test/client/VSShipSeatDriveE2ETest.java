package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Bisects the tier-2 pilot-SEAT flight path from the client keybind/packet path. A real seated
 * pilot moves the ship not at all in a playtest, yet the {@code vs ff-input} probe (static channel)
 * flies it fine ({@link VSShipClientLoadE2ETest}). The only new links the seat adds are: client
 * sampling + packet &rarr; seat {@code useNetworkData} &rarr; {@code getFlightComputer()} (offset resolve) &rarr;
 * {@code setPilotInput} (per-tile) &rarr; {@code update()} &rarr; force.
 *
 * <p>This test drives the ship through {@code vs seat-input}, which server-side finds the loaded
 * pilot seat, resolves its linked AFC via the stored offset, and sets that AFC's per-tile pilot
 * input — exercising EVERYTHING except the client sampling + packet. If the ship climbs here, the
 * seat&rarr;AFC&rarr;force pipeline is sound and the playtest break is client-side (packet / distance check /
 * keybinding); if the AFC does not resolve or the ship does not climb, the break is in the
 * seat&rarr;AFC link or the per-tile drive. Gated on real VS — run with {@code -PwithVS}.</p>
 */
public class VSShipSeatDriveE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 2600, BY = 64, BZ = 2600;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void seatPathResolvesAfcAndFliesTheShip() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());

        // Assemble far from any observer (double-load window), then approach to load.
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship (no rocket): " + assemble,
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

        double yBefore = Double.NaN;
        for (int i = 0; i < 40 && Double.isNaN(yBefore); i++) {
            bot().waitTicks(5);
            if (count("ship-count") >= 1) {
                String info = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
                if (info.contains("\"managed\":true")) {
                    yBefore = readDouble(info, POS_Y);
                }
            }
        }
        assertTrue("the ship must LOAD with the client present", !Double.isNaN(yBefore));

        // Server-side seat drive: the seat must resolve its AFC, and a full-up throttle through
        // the seat->AFC per-tile path must lift the ship (isolates ground friction: up only).
        double yAfter = yBefore;
        String lastSeat = "";
        for (int i = 0; i < 80 && yAfter - yBefore <= 1.5; i++) {
            lastSeat = exec("artest vs seat-input 0 0 1 0 0 0 0"); // throttleVertical = full up
            assertTrue("seat-input must find the pilot seat: " + lastSeat,
                    lastSeat.contains("\"seatFound\":true"));
            assertTrue("the pilot seat must resolve its linked flight computer (offset intact "
                            + "after VS relocation): " + lastSeat,
                    lastSeat.contains("\"afcResolved\":true"));
            bot().waitTicks(1);
            yAfter = readDouble(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ), POS_Y);
        }
        assertTrue("a throttle driven through the pilot seat -> AFC -> force path must lift the ship "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + ", lastSeat=" + lastSeat + ")",
                yAfter - yBefore > 1.0);
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
