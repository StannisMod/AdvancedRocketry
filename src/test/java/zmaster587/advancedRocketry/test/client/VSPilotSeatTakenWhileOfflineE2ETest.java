package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A pilot seat TAKEN while its pilot was OFFLINE stays with the occupant. The returning pilot is
 * restored STANDING aboard his ship — never seated, never fighting the occupant for the chair —
 * and is told, by name, who took his seat. And through it all the seat keeps exactly ONE bound
 * mount dummy: vanilla persists a seated player's mount inside his own player data and re-spawns
 * it at login, so without a reconciliation the returning pilot brings a DUPLICATE dummy back with
 * him — two invisible mounts on one seat, the empty one clearing the ship's pilot input every tick
 * (a control tug-of-war), and two riders both believing they hold the chair.
 *
 * <p><b>Why this must be a client test.</b> The subject is a live player's LOGIN — the one seam
 * every lower tier fakes. The offline window is real: the client genuinely quits the server (his
 * player data, mount included, is written to disk), the seat is taken while the world runs without
 * him, and his return is a real fresh login that re-reads that data. The refusal message is read
 * off the returning client's own action bar, the not-seated outcome off its own riding report.</p>
 *
 * <p><b>Subject on the hard side:</b> a real ASSEMBLED ship (the seat block lives in ship
 * subspace, its dummy at the seat's live world position — the frame split that every seat-binding
 * bug in this repo has lived in), not a loose world seat. The boarding is the {@code vs seat-mount}
 * probe + mount-entity (the harness cannot right-click a post-assembly ship-subspace block); the
 * occupant is the {@code vs seat-occupy} armor stand (no AI, holds the seat indefinitely — a
 * second human client is not needed to make the seat contested).</p>
 *
 * <p>Gated on real VS — run with {@code -PwithVS}.</p>
 */
public class VSPilotSeatTakenWhileOfflineE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern OCCUPANT_NAME = Pattern.compile("\"occupantName\":\"([^\"]+)\"");
    private static final Pattern OCCUPANT_ID = Pattern.compile("\"occupantId\":(-?\\d+)");
    private static final Pattern BOUND_COUNT = Pattern.compile("\"boundCount\":(-?\\d+)");
    private static final Pattern SEAT_AT = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern POS = Pattern.compile(
            "\"posX\":(-?[0-9.E\\-]+),\"posY\":(-?[0-9.E\\-]+),\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 7600, BY = 64, BZ = 7600;

    /** The account the client harness plays under — the server keys his data and probes by it. */
    private static final String BOT = "ForgeTestClient";

    /**
     * How far off his seat the restored-standing pilot may be and still count as "aboard at his
     * post". Covers the deck spot beside/under the seat plus settle drift; far too small to be
     * satisfied by a world-spawn fallback or a fall off the hull.
     */
    private static final double ABOARD_EPSILON = 6.0;

    @Test
    public void aSeatTakenWhileThePilotWasOfflineStaysWithTheOccupant() throws Exception {
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
        // Keep the ship observable while nobody is online: the offline window below leaves the
        // server empty, and an unloaded ship would fail every probe the arrangement depends on.
        exec("artest vs permaload true");
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(40);

        String mountInfo = exec("artest vs seat-mount 0");
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("ARRANGEMENT: seat-mount must report a dummy id: " + mountInfo, dm.find());
        Matcher sm = SEAT_AT.matcher(mountInfo);
        assertTrue("ARRANGEMENT: seat-mount must report the seat's block pos: " + mountInfo,
                sm.find());
        final int seatX = Integer.parseInt(sm.group(1));
        final int seatY = Integer.parseInt(sm.group(2));
        final int seatZ = Integer.parseInt(sm.group(3));
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("ARRANGEMENT: bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10);
        assertTrue("ARRANGEMENT: the CLIENT must confirm it is seated before it logs out: "
                + bot().reportRidingEntity(), isRiding(bot().reportRidingEntity()));

        // ---- ACT 1: a REAL logout that leaves the world running (disconnect half only). ---------
        bot().disconnect();
        String offline = "";
        boolean gone = false;
        for (int i = 0; i < budget && !gone; i++) {
            Thread.sleep(250); // the client has no world to wait ticks in; poll the server side
            offline = exec("artest player position-of " + BOT);
            // "no such player" = others online, he is not; "no players connected" = the server is
            // empty (this test's single-client case). Both mean he is gone.
            gone = offline.contains("\"error\":\"no such player\"")
                    || offline.contains("\"error\":\"no players connected\"");
        }
        assertTrue("ARRANGEMENT: the server must see the pilot GONE after the disconnect (his "
                + "player data, mount included, written to disk): " + offline, gone);

        // With no player near them the ship's chunks can drop out from under the probes below —
        // force them back in before acting on the seat.
        exec("artest chunk warmup 0 " + ((BX - 2) >> 4) + " " + ((BZ - 2) >> 4)
                + " " + ((BX + 7) >> 4) + " " + ((BZ + 7) >> 4));

        // Vanilla takes a seated player's mount WITH him into his player data — witnessed here
        // because the occupy below must therefore spawn the seat's fresh (single) dummy, and
        // because it is exactly what the returning login will try to re-spawn back into the world.
        String seatWhileGone = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        assertTrue("ARRANGEMENT: with its pilot offline the seat must have NO bound dummy left "
                + "(vanilla persists the mount inside the player's own data): " + seatWhileGone,
                seatWhileGone.contains("\"dummyFound\":false"));

        // ---- ACT 2: someone takes the seat while he is offline. ---------------------------------
        String occupy = exec("artest vs seat-occupy 0 " + seatX + " " + seatY + " " + seatZ);
        assertTrue("ARRANGEMENT: the seat-occupy probe must seat an NPC occupant: " + occupy,
                occupy.contains("\"ok\":true") && occupy.contains("\"mounted\":true"));
        Matcher nm = OCCUPANT_NAME.matcher(occupy);
        assertTrue("ARRANGEMENT: seat-occupy must report the occupant's name: " + occupy, nm.find());
        final String occupantName = nm.group(1);
        Matcher om = OCCUPANT_ID.matcher(occupy);
        assertTrue("ARRANGEMENT: seat-occupy must report the occupant's id: " + occupy, om.find());
        final String occupantId = om.group(1);
        String occupancy = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        assertTrue("ARRANGEMENT: the occupancy must HOLD before the pilot returns: " + occupancy,
                occupancy.contains("\"id\":" + occupantId));

        // ---- ACT 3: the pilot comes back — a real fresh login over his saved data. --------------
        bot().connect();
        bot().waitForWorld();

        // The refusal message rides the action bar (delayed past the join flood, gone ~4s later),
        // so it is watched FOR while the login settles rather than sampled after. The loop exits
        // early once seen; the end-state assertions below run either way.
        String lastOverlay = "";
        boolean sawMessage = false;
        int settleBudget = (int) (80 * TestTimeouts.factor());
        for (int i = 0; i < settleBudget && !sawMessage; i++) {
            bot().waitTicks(5);
            String ov = bot().reportChat(1).get("overlay").getAsString();
            if (!ov.isEmpty()) {
                lastOverlay = ov;
            }
            sawMessage = ov.contains(occupantName);
        }

        String seatAfter = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        JsonObject riding = bot().reportRidingEntity();
        String observed = "seatStatus=" + seatAfter + " riding=" + riding
                + " overlay=\"" + lastOverlay + "\"";

        // ---- ASSERT 1: the occupant KEEPS the seat. ---------------------------------------------
        assertTrue("the occupant who took the seat while its pilot was offline must still hold it "
                + "after the pilot returns: " + observed, seatAfter.contains("\"id\":" + occupantId));

        // ---- ASSERT 2: one seat — ONE dummy, even across a relog. -------------------------------
        // Vanilla re-spawns the returning pilot's persisted mount; unreconciled, that is a second
        // invisible dummy on the same seat, whose empty twin clears the ship's pilot input every
        // tick. The player-visible shape of that bug is a control tug-of-war nobody can attribute.
        Matcher bc = BOUND_COUNT.matcher(seatAfter);
        assertTrue("seat-status must report boundCount: " + seatAfter, bc.find());
        assertEquals("a seat must keep exactly ONE bound mount dummy across its pilot's relog - "
                + "a re-spawned duplicate fights the occupant for the ship's controls: " + observed,
                1, Integer.parseInt(bc.group(1)));

        // ---- ASSERT 3: the returner is NOT seated — twice, so a late re-mount cannot hide. ------
        assertFalse("a pilot whose seat was taken while he was offline must NOT come back seated: "
                + observed, isRiding(riding));
        bot().waitTicks(20);
        JsonObject ridingLater = bot().reportRidingEntity();
        assertFalse("...and must STAY unseated (no delayed re-mount stealing the seat back): "
                + ridingLater, isRiding(ridingLater));

        // ---- ASSERT 4: he is restored STANDING ABOARD, at his post — not dropped at spawn, -----
        // not fallen off the hull. Client-observed position against the seat's live world
        // position (server oracle, read fresh: the ship may have settled).
        double[] seatWorld = seatWorldPosition(seatX, seatY, seatZ);
        JsonObject state = bot().reportState();
        assertTrue("the client must report a player position: " + state,
                state.get("worldReady").getAsBoolean());
        double cx = state.get("playerX").getAsDouble();
        double cy = state.get("playerY").getAsDouble();
        double cz = state.get("playerZ").getAsDouble();
        String posObserved = "client=(" + cx + "," + cy + "," + cz + ") seatWorld=("
                + seatWorld[0] + "," + seatWorld[1] + "," + seatWorld[2] + ") " + observed;
        assertEquals("the displaced pilot must be restored ABOARD at his post on X: " + posObserved,
                seatWorld[0], cx, ABOARD_EPSILON);
        assertEquals("the displaced pilot must be restored ABOARD at his post on Y: " + posObserved,
                seatWorld[1], cy, ABOARD_EPSILON);
        assertEquals("the displaced pilot must be restored ABOARD at his post on Z: " + posObserved,
                seatWorld[2], cz, ABOARD_EPSILON);

        // ---- ASSERT 5: he is TOLD, by name, who took his seat. ----------------------------------
        assertTrue("the returning pilot must be told WHO took his seat (\"" + occupantName
                + "\") on his action bar - a silently-lost chair reads as a broken relog: "
                + observed, sawMessage);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The seat's live WORLD position, read off the seat's bound dummy: {@code EntityDummy}
     *  glues itself to the seat's world image every tick, so the occupant's dummy IS the seat's
     *  live world-frame oracle (the seat BLOCK's own coordinates are ship-subspace). */
    private double[] seatWorldPosition(int seatX, int seatY, int seatZ) throws Exception {
        String status = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        Matcher dm = DUMMY_ID.matcher(status);
        assertTrue("seat-status must expose the bound dummy for the position oracle: " + status,
                dm.find());
        String pos = exec("artest entity info 0 " + dm.group(1));
        Matcher pm = POS.matcher(pos);
        assertTrue("the entity-info probe must answer for the seat's dummy: " + pos, pm.find());
        return new double[]{Double.parseDouble(pm.group(1)),
                Double.parseDouble(pm.group(2)), Double.parseDouble(pm.group(3))};
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

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }
}
