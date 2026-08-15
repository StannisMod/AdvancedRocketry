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
