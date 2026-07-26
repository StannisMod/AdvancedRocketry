package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A pilot who logs out SEATED logs back in SEATED — with a WORKING control chain: after the relog,
 * holding the real vertical-up key must lift the ship again, with no re-board and no re-click.
 *
 * <p>Subject: a planet-side assembled tier-2 ship. Here the relog path is vanilla's own mount
 * persistence — the seat mount rides the player's save data (removed from the world at logout,
 * re-spawned and re-mounted at login) and carries its seat binding in its entity NBT — so the pin
 * covers the full chain: the restored mount must still resolve its seat in the seat's CURRENT
 * frame, the client's input gate must re-open against the restored mount, and the input must reach
 * the ship's flight computer. A green "still riding" with a dead key would be exactly the
 * play-reported shape of a broken chain, which is why the post-relog CLIMB is the load-bearing
 * assertion, not the seating.</p>
 *
 * <p>The relog is real ({@code ClientBot.reconnect} — a full server logout with player-data save
 * and a fresh login). The boarding is the {@code vs seat-mount} probe + mount-entity (the harness
 * cannot right-click a post-assembly ship-subspace block); the flight stimulus and every
 * observation are the real client's (held key in; client-rendered rider altitude out).</p>
 *
 * <p>Gated on real VS — run with {@code -PwithVS}.</p>
 */
public class VSPilotSeatRelogControlE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    /** A surveyed natural site: flat, solid, unroofed plains under the pinned world seed.
     *  Pinned by {@code HarnessFixtureSitesTest}; do not move it without re-surveying. */
    private static final int BX = 1056, BY = 67, BZ = 6116;

    /** A demonstrable climb: well above settle jitter, cheap to reach. */
    private static final double MIN_CLIMB = 1.0;

    @Test
    public void aPilotWhoRelogsSeatedKeepsControlOfHisShip() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());

        // ---- ARRANGE: build + assemble a piloted ship, seat the client player on it. ------------
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        int shipsBefore = count("ship-count-all");
        String assemble = assembleFixture(BX, BY, BZ);
        assertTrue("ARRANGEMENT: a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int all = shipsBefore;
        int budget = (int) (40 * TestTimeouts.factor());
        for (int i = 0; i < budget && all <= shipsBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("ARRANGEMENT: assembly must create a NEW VS ship (was " + shipsBefore
                + ", now " + all + ")", all > shipsBefore);
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(40);

        String mountInfo = exec("artest vs seat-mount 0");
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("ARRANGEMENT: seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("ARRANGEMENT: bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10);

        // ---- CONTROL LEG (pre-relog): the chain works before the relog, or the post-relog leg
        // cannot indict the relog. -----------------------------------------------------------
        double y0 = clientPlayerY();
        double y1 = climbWith(Keyboard.KEY_R, y0, budget);
        assertTrue("ARRANGEMENT (control leg): the pilot must be able to fly BEFORE the relog. "
                + "clientY " + y0 + " -> " + y1, (y1 - y0) >= MIN_CLIMB);
        bot().waitTicks(30); // let the station-hold settle the hovering ship

        // ---- ACT: the real relog — full server logout (player data saved) + fresh login. -------
        bot().reconnect();
        bot().waitForWorld();

        // ---- ASSERT 1: seated again, with NO re-board — two consecutive positive samples. ------
        JsonObject riding = bot().reportRidingEntity();
        boolean prev = isRiding(riding);
        boolean seatedTwice = false;
        int rejoinBudget = (int) (60 * TestTimeouts.factor());
        for (int i = 0; i < rejoinBudget && !seatedTwice; i++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
            seatedTwice = prev && isRiding(riding);
            prev = isRiding(riding);
        }
        assertTrue("a pilot who logged out SEATED must log back in SEATED - no re-board. riding="
                + riding, seatedTwice);

        // ---- ASSERT 2 (load-bearing): the restored chain still FLIES the ship. -----------------
        double y2 = clientPlayerY();
        double y3 = climbWith(Keyboard.KEY_R, y2, budget);
        assertTrue("after the relog, held input must MOVE THE SHIP - a restored seat with a dead "
                        + "key is a broken control chain. clientY " + y2 + " -> " + y3
                        + " (need +" + MIN_CLIMB + ") delivery=" + exec("artest vs seat-delivery"),
                (y3 - y2) >= MIN_CLIMB);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Hold {@code key} until the client-rendered rider altitude climbs {@link #MIN_CLIMB} over
     *  {@code from} (bounded, early-exit); returns the last observed altitude. */
    private double climbWith(int key, double from, int budget) throws Exception {
        double last = from;
        bot().holdKey(key);
        try {
            for (int i = 0; i < budget && (last - from) < MIN_CLIMB; i++) {
                bot().waitTicks(5);
                last = clientPlayerY();
            }
        } finally {
            bot().releaseKey(key);
        }
        return last;
    }

    private double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("ARRANGEMENT: chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("ARRANGEMENT: pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("ARRANGEMENT: fixture (" + VARIANT + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("ARRANGEMENT: fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
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
}
