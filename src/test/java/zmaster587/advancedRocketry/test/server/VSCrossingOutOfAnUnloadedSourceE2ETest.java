package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The other way a crossing can leave a ship behind: the REGISTRY entry of a source that was not loaded
 * when its blocks were cut.
 *
 * <p>{@link VSCrossingLeavesNoShipBehindE2ETest} covers the case where the source ship is loaded — there,
 * a physics object is what can be stranded. This one covers the opposite starting state, where no physics
 * object exists at all, so whatever collects a crossing's leftovers has to work without one. An entry left
 * behind here has no blocks and nothing loaded behind it, yet it still answers position lookups in that
 * world — including the opening lookup of the next crossing out of the same place — and it is persisted
 * with the world, so it outlives a restart.</p>
 *
 * <p><b>Its own class, and its own server.</b> The arrangement it needs — a registered ship that is NOT
 * loaded — is exactly the state that makes the shared-harness load pump crash the server, so this leg is
 * kept away from any method that holds {@code permaload}. {@code permaload} is never set true here.</p>
 *
 * <p><b>The counts are taken before anything asks the world to load a ship</b>, because loading a
 * leftover entry is itself what would collect it: measuring afterwards would measure the measurement.</p>
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSCrossingOutOfAnUnloadedSourceE2ETest extends AbstractHeadlessServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int BASE_X = 7000, BASE_Z = 7000;
    private static final int BUILD_Y = 80, SKY_Y = 150;
    private static final int HOP = 160;
    private static final double POSE_TOLERANCE = 64.0;

    @Test
    public void aCrossingOutOfAnUnloadedSourceLeavesNoRegistryEntry() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        buildShip();
        // With no player near it and no permaload, VS unloads the ship again and keeps its registry entry.
        // Registered, with nothing loaded behind it, is this test's whole subject; if the ship stayed
        // loaded this would silently become a copy of the other class's arrangement.
        assertTrue("the source ship never unloaded, so this test would measure the loaded-source "
                        + "arrangement instead of its own: " + counters(), waitUntilNoShipIsAt(BASE_X, BUILD_Y));

        int registryBefore = queryableShips();
        int loadedBefore = loadedShips();

        String cross = repack(BASE_X, BUILD_Y, BASE_X + HOP, SKY_Y);
        assertTrue("the crossing itself failed, so this test measures nothing: " + cross,
                cross.contains("\"ok\":true"));
        settle();

        int registryAfter = queryableShips();
        int loadedAfter = loadedShips();

        // Only now prove the crossing really produced a ship at the destination. Doing it after the reads
        // keeps the load pump out of the measurement, and still fails loudly - rather than as a clean
        // conservation - if nothing ever arrived.
        assertTrue("the crossed ship never arrived at " + (BASE_X + HOP) + "," + SKY_Y
                + "; crossing=" + cross + " " + counters(), waitUntilShipIsAt(BASE_X + HOP, SKY_Y));

        assertEquals("a crossing out of an UNLOADED source left its registry entry behind: registered "
                        + "ships went " + registryBefore + " -> " + registryAfter + " across a crossing "
                        + "that moved a single ship (loaded " + loadedBefore + " -> " + loadedAfter + "). "
                        + "An entry with no blocks and nothing loaded behind it still answers every "
                        + "position lookup in this world, the next crossing out of this cell included, "
                        + "and it is written to disk with the world.",
                registryBefore, registryAfter);
    }

    // --- arrangement --------------------------------------------------------------------------------

    private void buildShip() throws Exception {
        clearArea(BASE_X, BUILD_Y);
        clearArea(BASE_X + HOP, SKY_Y);
        int registryBefore = queryableShips();
        String coords = placeFixture(BASE_X, BUILD_Y, BASE_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the ship never entered VS's registry: " + counters(),
                waitUntilRegistryExceeds(registryBefore));
    }

    private String repack(int sx, int sy, int dx, int dy) throws Exception {
        return exec("artest vs ship-repack 0 " + sx + " " + sy + " " + BASE_Z
                + " " + dx + " " + dy + " " + BASE_Z);
    }

    // --- observation --------------------------------------------------------------------------------

    private int loadedShips() throws Exception {
        return extractInt(exec("artest vs ship-count 0"), "count");
    }

    private int queryableShips() throws Exception {
        return extractInt(exec("artest vs ship-count-all 0"), "count");
    }

    private String counters() throws Exception {
        return "[loaded=" + loadedShips() + " registry=" + queryableShips() + "]";
    }

    /** The probe's ship lookup is unbounded, so the pose comparison is what makes this about THIS spot. */
    private boolean shipIsAt(int x, int y) throws Exception {
        String info = exec("artest vs ship-info 0 " + x + " " + y + " " + BASE_Z);
        if (!info.contains("\"managed\":true")) {
            return false;
        }
        double dx = extractDouble(info, "posX") - x;
        double dy = extractDouble(info, "posY") - y;
        double dz = extractDouble(info, "posZ") - BASE_Z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= POSE_TOLERANCE;
    }

    private boolean waitUntilRegistryExceeds(int floor) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (queryableShips() > floor) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /**
     * Poll until a loaded ship sits at {@code (x,y,BASE_Z)}. Nothing holds ships loaded here, so a load
     * has to be pumped each round and the ship is only resident for about a tick — hence read immediately
     * after the pump. Safe only because {@code permaload} is never set in this class.
     */
    private boolean waitUntilShipIsAt(int x, int y) throws Exception {
        for (int i = 0; i < 40; i++) {
            exec("artest vs load-ships 0");
            if (shipIsAt(x, y)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** Poll until no loaded ship sits at {@code (x,y,BASE_Z)} — deliberately without pumping any load. */
    private boolean waitUntilNoShipIsAt(int x, int y) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (!shipIsAt(x, y)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** A bounded pause for the world ticks that spawn a queued ship and collect a cut one. */
    private void settle() throws Exception {
        Thread.sleep(3000);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private void clearArea(int baseX, int baseY) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (BASE_Z + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (baseY - 2) + " " + (BASE_Z - 4)
                + " " + (baseX + 20) + " " + (baseY + 12) + " " + (BASE_Z + 20)
                + " minecraft:air").contains("\"ok\":true"));
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
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }
}
