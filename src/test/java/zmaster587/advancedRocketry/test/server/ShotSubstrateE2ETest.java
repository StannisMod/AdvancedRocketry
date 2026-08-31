package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a shot IS between muzzle and impact: a record that crosses distance the world is not loaded
 * for, and that cannot be outrun by a wall.
 *
 * <p>Both properties are about the substrate, not about weapons or damage. The first says a flight
 * costs the host nothing but three vectors — a shot that quietly force-loaded a corridor of chunks
 * would pass every "did it arrive" test ever written and still be the thing this design exists to
 * avoid. The second says the integration is swept: at a step longer than a wall is thick, a
 * position-by-position simulation reports a clean miss through solid stone, and the faster the round
 * the more reliably it lies.</p>
 */
public class ShotSubstrateE2ETest extends AbstractSharedServerTest {

    /**
     * The band ships fly in — millions of blocks up, where a cell's contents live and the world has no
     * blocks of its own. Firing here is what makes "nothing was loaded" a statement about the
     * substrate rather than about an empty patch of overworld somebody might later build in.
     */
    private static final double POSE_BAND_Y = 2_000_064.5D;

    /** A site of this class's own, clear of the other server scenarios. */
    private static final int X = 9200, Y = 80, Z = 9200;

    /** The emitter's mid-shell radius, as the shield tests use it — the face a round bounces off. */
    private static final double SHELL_RADIUS = 4.0D;

    @Test
    public void aShotCrossesEmptySpaceWithoutLoadingAnyWorld() throws Exception {
        exec("artest shot clear 0");
        double originX = 40_000.5D;
        double originZ = 40_000.5D;
        double perTick = 50.0D;

        long id = readLong(exec("artest shot fire 0 " + originX + " " + POSE_BAND_Y + " " + originZ
                + " " + perTick + " 0 0 4000 400"), "id");
        assertTrue("the launch was refused, so nothing else here means anything", id > 0);

        for (int tick = 0; tick < 10; tick++) {
            exec("artest shield tick 0");
        }

        String flying = exec("artest shot read 0 " + id);
        assertTrue("the shot ended in mid-flight across empty space: " + flying,
                flying.contains("\"present\":true"));
        // Against its OWN age, not against a tick count: this server is really running, so the
        // number of steps a shot has taken is not something a test gets to decide. What is pinned is
        // the rate — one velocity per tick of age, whoever did the ticking.
        int age = extractInt(flying, "age");
        assertTrue("the shot never advanced at all: " + flying, age >= 10);
        assertEquals("a shot must move by its velocity once per tick of its own age: " + flying,
                originX + perTick * age, readDouble(flying, "x"), 1.0E-6D);
        assertEquals("nothing acts on it out there, so it must not have been deflected: " + flying,
                POSE_BAND_Y, readDouble(flying, "y"), 1.0E-6D);
        assertEquals(originZ, readDouble(flying, "z"), 1.0E-6D);

        // The precise claim: it crossed those chunks and none of them came into memory. A count of
        // all loaded chunks would move for reasons that have nothing to do with this shot.
        int chunkZ = (int) Math.floor(originZ) >> 4;
        for (int blocksAlong = 0; blocksAlong <= 480; blocksAlong += 160) {
            int chunkX = (int) Math.floor(originX + blocksAlong) >> 4;
            String loaded = exec("artest chunk loaded 0 " + chunkX + " " + chunkZ);
            assertTrue("the flight loaded the world under it at chunk " + chunkX + "," + chunkZ
                    + ": " + loaded + ". A shot that pulls a corridor of chunks along with it is an"
                    + " attack on the host, which is the whole reason it is not an entity",
                    loaded.contains("\"loaded\":false"));
        }
    }

    @Test
    public void aShotEndsForAStatedReasonRatherThanJustDisappearing() throws Exception {
        exec("artest shot clear 0");
        long id = readLong(exec("artest shot fire 0 " + 41_000.5D + " " + POSE_BAND_Y + " " + 41_000.5D
                + " 10 0 0 4000 3"), "id");
        assertTrue("the launch was refused", id > 0);

        for (int tick = 0; tick < 5; tick++) {
            exec("artest shield tick 0");
        }

        String gone = exec("artest shot read 0 " + id);
        assertTrue("a shot with a three-tick lifetime was still up after five: " + gone,
                gone.contains("\"present\":false"));
        assertEquals("a shot that timed out must say so — a weapon that cannot tell a miss from a hit"
                + " cannot report either: " + gone, "EXPIRED", extractString(gone, "ended"));
    }

    @Test
    public void aFastShotCannotPassThroughAOneBlockWall() throws Exception {
        exec("artest shot clear 0");
        exec("artest damage clear-impacts");
        int wallX = X + 40;
        buildWall(wallX);

        int stageCost = extractInt(exec("artest damage stage 0 " + wallX + " " + Y + " " + Z),
                "stageCost");
        assertTrue("no stage cost for the wall, so nothing here could show damage", stageCost > 0);

        // 60 blocks a tick against a wall one block thick: a per-tick position test looks before the
        // wall and then well past it, and sees stone at neither.
        long id = readLong(exec("artest shot fire 0 " + (X + 0.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)
                + " 60 0 0 " + stageCost + " 40"), "id");
        assertTrue("the launch was refused", id > 0);
        exec("artest shield tick 0");

        String after = exec("artest shot read 0 " + id);
        assertTrue("the shot flew straight through a solid wall — the step is longer than the wall is"
                + " thick, which is exactly the case a swept segment exists for: " + after,
                after.contains("\"present\":false"));
        assertEquals("it stopped, but not by hitting anything: " + after,
                "STRUCTURE_IMPACT", extractString(after, "ended"));

        String wall = exec("artest damage stage 0 " + wallX + " " + Y + " " + Z);
        assertTrue("the shot stopped at the wall but the wall took nothing: " + wall,
                extractInt(wall, "stage") > 0 || wall.contains("\"wasDestroyed\":true"));
    }

    @Test
    public void aShotIsOnlyEverInTheWorldItWasFiredIn() throws Exception {
        // The isolation is structural — one registry per world, with no reference between them — so
        // what is worth pinning is that firing into one world leaves the other's count alone.
        assertTrue("could not bring the second dimension up",
                exec("artest chunk forceload -1 0 0").contains("\"ok\":true"));
        exec("artest shot clear 0");
        exec("artest shot clear -1");

        long id = readLong(exec("artest shot fire 0 " + 42_000.5D + " " + POSE_BAND_Y + " " + 42_000.5D
                + " 20 0 0 4000 200"), "id");
        assertTrue("the launch was refused", id > 0);

        assertEquals("the shot was fired into the overworld and is not there: "
                + exec("artest shot list 0"), 1, extractInt(exec("artest shot list 0"), "count"));
        assertEquals("a shot fired in one world turned up in another: " + exec("artest shot list -1"),
                0, extractInt(exec("artest shot list -1"), "count"));

        exec("artest shield tick -1");
        assertTrue("ticking the other world stepped this world's shot: " + exec("artest shot read 0 " + id),
                exec("artest shot read 0 " + id).contains("\"present\":true"));

        exec("artest shot clear 0");
        exec("artest chunk release -1 0 0");
    }

    @Test
    public void aShotThatMeetsAChargedShellBouncesOffItAndStaysUp() throws Exception {
        // The one place the substrate calls the shield and reads a velocity back. The shell owns the
        // reflection law — this pins that the answer is USED: the round turns around, resumes from
        // the crossing and is still in the air, rather than stopping at the shield or ploughing on.
        exec("artest shot clear 0");
        int gx = 1040, gz = 880, gy = 96;
        int ex = gx + 1;
        clearShieldSite(gx, gy, gz);
        place("affs:shield_generator", gx, gy, gz);
        place("affs:field_generator", ex, gy, gz);
        for (int i = 0; i < 15; i++) {
            exec("artest energy inject 0 " + gx + " " + gy + " " + gz + " 4000");
            exec("artest tile force-tick 0 " + gx + " " + gy + " " + gz + " 1");
            exec("artest shield tick 0");
        }
        String emitter = exec("artest shield read 0 " + ex + " " + gy + " " + gz);
        assertTrue("the emitter never powered, so there is no shell to bounce off: " + emitter,
                emitter.contains("\"powered\":true"));

        // Fired from outside the +Z shell straight inward, at a speed that reaches it this tick.
        double cz = gz + 0.5D;
        double startZ = cz + SHELL_RADIUS + 3.0D;
        long id = readLong(exec("artest shot fire 0 " + (ex + 0.5D) + " " + (gy + 0.5D) + " " + startZ
                + " 0 0 -4 2000 300"), "id");
        assertTrue("the launch was refused", id > 0);
        exec("artest shield tick 0");

        String after = exec("artest shot read 0 " + id);
        assertTrue("a shell that could afford the round consumed it instead of mirroring it — a "
                + "kinetic body declared to the shield must come back out: " + after,
                after.contains("\"present\":true"));
        double vz = readDouble(after, "vz");
        assertTrue("the round is still travelling inward (vz=" + vz + ", it arrived at -4): the shell's"
                + " answer was read but not applied: " + after, vz > 0.0D);
        double z = readDouble(after, "z");
        assertTrue("the round bounced but is still inside the shell (z=" + z + ", shell face at "
                + (cz + SHELL_RADIUS) + "): it resumed on the wrong side of the crossing: " + after,
                z >= cz + SHELL_RADIUS - 1.0D);
        assertTrue("the round left faster than it arrived (vz=" + vz + " vs 4): a mirror returns"
                + " energy, it does not create it: " + after, vz <= 4.0D + 1.0E-6D);

        exec("artest shot clear 0");
    }

    @Test
    public void aBeamIsAbsorbedByAChargedShellRatherThanMirroredOffIt() throws Exception {
        // The same shell, the same energy, the same approach as the bounce above — only the KIND
        // differs. What the shell does with a strike is decided by what it was told the strike is, so
        // a substrate that declares every round as a travelling lump of metal makes the shell mirror
        // light. A beam's energy arrives and stays: there is nothing to send back.
        exec("artest shot clear 0");
        int gx = 1040, gz = 912, gy = 96;
        int ex = gx + 1;
        clearShieldSite(gx, gy, gz);
        place("affs:shield_generator", gx, gy, gz);
        place("affs:field_generator", ex, gy, gz);
        for (int i = 0; i < 15; i++) {
            exec("artest energy inject 0 " + gx + " " + gy + " " + gz + " 4000");
            exec("artest tile force-tick 0 " + gx + " " + gy + " " + gz + " 1");
            exec("artest shield tick 0");
        }
        String emitter = exec("artest shield read 0 " + ex + " " + gy + " " + gz);
        assertTrue("the emitter never powered, so there is no shell to absorb anything: " + emitter,
                emitter.contains("\"powered\":true"));

        double cz = gz + 0.5D;
        double startZ = cz + SHELL_RADIUS + 3.0D;
        long id = readLong(exec("artest shot fire 0 " + (ex + 0.5D) + " " + (gy + 0.5D) + " " + startZ
                + " 0 0 -4 2000 300 BEAM"), "id");
        assertTrue("the launch was refused", id > 0);
        exec("artest shield tick 0");

        String after = exec("artest shot read 0 " + id);
        assertTrue("a beam came back off the shell: the substrate declared it as a travelling body,"
                + " and a shell mirrors a body it can afford: " + after,
                after.contains("\"present\":false"));
        assertEquals("the beam ended, but not by being drunk by the shell — a shot that stops for the"
                + " wrong stated reason is a weapon that cannot report what happened: " + after,
                "FIELD_ABSORBED", extractString(after, "ended"));

        exec("artest shot clear 0");
    }

    private void clearShieldSite(int gx, int gy, int gz) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + ((gx - 16) >> 4) + " "
                + ((gz - 16) >> 4) + " " + ((gx + 16) >> 4) + " " + ((gz + 16) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill 0 " + (gx - 12) + " " + (gy - 4) + " "
                + (gz - 12) + " " + (gx + 12) + " " + (gy + 8) + " " + (gz + 12) + " minecraft:air")
                .contains("\"ok\":true"));
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    /** A wall one block thick, three by three, with clear air on both sides of it. */
    private void buildWall(int wallX) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + ((X - 4) >> 4) + " "
                + ((Z - 4) >> 4) + " " + ((wallX + 8) >> 4) + " " + ((Z + 4) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the range", exec("artest fill 0 " + (X - 2) + " " + (Y - 2) + " "
                + (Z - 2) + " " + (wallX + 8) + " " + (Y + 3) + " " + (Z + 2) + " minecraft:air")
                .contains("\"ok\":true"));
        assertTrue("could not build the wall", exec("artest fill 0 " + wallX + " " + (Y - 1) + " "
                + (Z - 1) + " " + wallX + " " + (Y + 1) + " " + (Z + 1) + " minecraft:stone")
                .contains("\"ok\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Long.parseLong(m.group(1));
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
