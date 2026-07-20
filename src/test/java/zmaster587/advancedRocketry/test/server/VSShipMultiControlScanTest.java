package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * One craft — one command authority, enforced at the assembly SCAN. A tier-2 build carrying more
 * than one Advanced Flight Computer (or more than one pilot seat) must be rejected with its own
 * error code before anything can assemble:
 *
 * <ul>
 *   <li><b>Two flight computers</b>: every AFC on a physics ship ticks AND is a force controller,
 *       so a second one — e.g. a scavenged block whose NBT still says "hold station" — fights the
 *       linked computer for the ship every tick. Unbuildable is the only safe state.</li>
 *   <li><b>Two pilot seats</b>: only the last-scanned seat is linked at assembly; a pilot in any
 *       other seat has silently dead controls. Passenger seats (the plain seat block) stay
 *       unrestricted.</li>
 * </ul>
 *
 * <p>Both tests drive the REAL scan (fixture + one extra control block + {@code rocket assemble})
 * and read the scan status the builder GUI shows the player. Gated on real VS — the restriction
 * exists for the ship path; without VS the blocks are inert cargo.</p>
 */
public class VSShipMultiControlScanTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    @Test
    public void aSecondFlightComputerIsRejectedAtScan() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)",
                serverHasVs());

        int baseX = 2400, baseY = 64, baseZ = 2400;
        String coords = placeFixture(baseX, baseY, baseZ, "with-pilot-seat");
        // A second AFC in the free drill cell (rocketX+1, rocketY+3) — inside the scanned build.
        String fill = String.join("\n", client().execute("artest fill 0 "
                + (baseX + 4) + " " + (baseY + 4) + " " + (baseZ + 3) + " "
                + (baseX + 4) + " " + (baseY + 4) + " " + (baseZ + 3)
                + " advancedrocketry:advancedFlightComputer"));
        assertTrue("placing the second flight computer failed: " + fill, fill.contains("\"ok\":true"));

        String assemble = String.join("\n", client().execute("artest rocket assemble 0 " + coords));
        assertTrue("a build with TWO flight computers must be rejected at scan with its own error "
                        + "code: " + assemble,
                assemble.contains("\"status\":\"MULTIPLEFLIGHTCOMPUTERS\""));
    }

    @Test
    public void aSecondPilotSeatIsRejectedAtScan() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)",
                serverHasVs());

        int baseX = 2600, baseY = 64, baseZ = 2400;
        String coords = placeFixture(baseX, baseY, baseZ, "with-pilot-seat");
        // A second pilot seat in the free cell beside the linked one (rocketX+1, rocketY+4).
        String fill = String.join("\n", client().execute("artest fill 0 "
                + (baseX + 4) + " " + (baseY + 5) + " " + (baseZ + 3) + " "
                + (baseX + 4) + " " + (baseY + 5) + " " + (baseZ + 3)
                + " advancedrocketry:pilotSeat"));
        assertTrue("placing the second pilot seat failed: " + fill, fill.contains("\"ok\":true"));

        String assemble = String.join("\n", client().execute("artest rocket assemble 0 " + coords));
        assertTrue("a build with TWO pilot seats must be rejected at scan with its own error code: "
                        + assemble,
                assemble.contains("\"status\":\"MULTIPLEPILOTSEATS\""));
    }

    private boolean serverHasVs() throws Exception {
        return String.join("\n", client().execute("artest vs available"))
                .contains("\"available\":true");
    }

    /** Place the fixture on a pad WITHOUT assembling; returns the builder pos as "bx by bz". */
    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        String warmup = String.join("\n", client().execute(
                "artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2));
        assertTrue("chunk warmup failed: " + warmup, warmup.contains("\"ok\":true"));
        String fillAir = String.join("\n", client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
                        + " minecraft:air"));
        assertTrue("pre-clear failed: " + fillAir, fillAir.contains("\"ok\":true"));
        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant));
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }
}
