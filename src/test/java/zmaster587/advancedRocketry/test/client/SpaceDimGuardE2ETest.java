package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The space-dim "outside-any-station" teleport guard, both branches.
 *
 * <p>Production: {@link zmaster587.advancedRocketry.event.PlanetEventHandler#playerTick}. Every
 * server tick, if the player is in {@code ARConfiguration.spaceDimId}, NOT inside any registered
 * station's bounds, and NOT riding a rocket, the handler either teleports him to the FURTHEST
 * registered station's {@code getSpawnLocation()}, or — when no
 * {@link zmaster587.advancedRocketry.api.stations.ISpaceObject} is registered at all — transfers him
 * to dim 0 through {@code PlayerList.transferPlayerToDimension} with a {@code TeleporterNoPortal}.</p>
 *
 * <h2>Why this class boots ONE harness for two scenarios, and why it is still its own class</h2>
 *
 * <p>It used to spin up a server and a client per method — 175.1 s for two assertions, measured
 * 2026-08-07 at 8 forks. It now shares one, like the rest of the tier.</p>
 *
 * <p>It does not join a bigger group, and the reason is a constraint no other class here has:
 * <b>{@link #noStationFallbackTeleportsPlayerToOverworld()} requires a world in which no station has
 * ever been registered</b>. Its sibling registers one, so the order matters, and
 * {@code NAME_ASCENDING} delivers it (n &lt; r) — but that is a fact about two names, not a
 * guarantee anyone should lean on silently. So the precondition is ASSERTED as an arrangement step:
 * if a future scenario in this class ever registers a station first, this reddens as ARRANGEMENT
 * (the fixture was wrong) rather than as CONTRACT (production is broken), which are opposite
 * responses.</p>
 *
 * <p>The old class also created its own temp work directory to get "a workdir without persisted
 * station NBT". That bought nothing: {@code RealDedicatedServerHarness.start()} already creates a
 * fresh temp directory per harness, so the custom one was an empty temp dir standing in for an empty
 * temp dir. Dropping it is what let this class share the base at all.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SpaceDimGuardE2ETest extends AbstractSharedClientE2ETest {

    private static final Pattern DIM = Pattern.compile("\"dim\":(-?\\d+)");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.eE+-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.eE+-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.eE+-]+)");
    private static final Pattern SPAWN_X = Pattern.compile("\"spawnX\":(-?\\d+)");
    private static final Pattern SPAWN_Y = Pattern.compile("\"spawnY\":(-?\\d+)");
    private static final Pattern SPAWN_Z = Pattern.compile("\"spawnZ\":(-?\\d+)");
    private static final Pattern STATION_ID = Pattern.compile("\"id\":(-?\\d+)");

    /** {@code ARConfiguration.spaceDimId}'s default. */
    private static final int SPACE_DIM = -2;

    @Override
    protected String subsystem() {
        return "space-dim-guard";
    }

    private int intField(Pattern p, String src, String name) {
        Matcher m = p.matcher(src);
        assertTrue("field " + name + " missing in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }

    private double doubleField(Pattern p, String src, String name) {
        Matcher m = p.matcher(src);
        assertTrue("field " + name + " missing in: " + src, m.find());
        return Double.parseDouble(m.group(1));
    }

    /**
     * With NO registered station, a player who lands in the space dim gets kicked back to the
     * overworld on the next server tick.
     */
    @Test
    public void noStationFallbackTeleportsPlayerToOverworld() throws Exception {
        scenario().arranging("confirm this world has no station in it yet")
                .describeOnFailureWith("artest station list", "artest player health");

        // The guard has two branches and this one is only reachable while the station registry is
        // EMPTY. On a shared world that is a property of the run, not of the boot, so it is checked
        // rather than assumed — see the class javadoc.
        String list = exec("artest station list");
        scenario().requireArranged("this scenario exercises the NO-STATION branch, so the registry"
                + " must still be empty when it runs; it holds: " + list,
                list.contains("\"stations\":[]"));

        String pre = exec("artest player health");
        scenario().requireArranged("baseline must be overworld dim 0; " + pre,
                0 == intField(DIM, pre, "dim"));

        scenario().asserting("the guard transfers a station-less player back to the overworld");
        exec("artest tp " + SPACE_DIM);
        // A few ticks for one full server tick cycle so the playerTick guard fires reliably.
        bot().waitTicks(40);

        String after = exec("artest player health");
        int dim = intField(DIM, after, "dim");
        assertEquals("no-station fallback must transfer player back to overworld; player is in dim "
                + dim + " — " + after, 0, dim);
    }

    /**
     * With a registered station, a player who lands in the space dim outside the station's bounds
     * gets teleported to the station's spawn location — not back to the overworld.
     */
    @Test
    public void registeredStationTeleportTargetsStationSpawn() throws Exception {
        scenario().arranging("create a station orbiting the overworld")
                .describeOnFailureWith("artest station list", "artest player health");
        String createResp = exec("artest station create " + plot().dim);
        scenario().requireArranged("station create must succeed: " + createResp,
                !createResp.contains("\"error\""));
        int stationId = intField(STATION_ID, createResp, "station id");
        scenario().record("stationId", stationId);

        String info = exec("artest station info " + stationId);
        int spawnX = intField(SPAWN_X, info, "spawnX");
        int spawnY = intField(SPAWN_Y, info, "spawnY");
        int spawnZ = intField(SPAWN_Z, info, "spawnZ");
        scenario().record("stationSpawn", spawnX + "," + spawnY + "," + spawnZ);

        // The default space-dim spawn lands in station-id-1's slot (the spiral indexing puts the
        // first station near origin), which would make the guard skip teleporting (the player is
        // "in" the station's slot). So immediately move him 50 000 blocks away — that resolves to a
        // slot index our station does not occupy, so getSpaceStationFromBlockCoords returns null
        // and the guard fires.
        scenario().asserting("the guard puts an out-of-bounds player on the station's spawn");
        exec("artest tp " + SPACE_DIM);
        bot().waitTicks(20);
        exec("tp @a 50000 100 50000");

        // Gated on the guard having FIRED, not on a tick count. The original waited exactly 5
        // ticks, reasoning that the guard runs every tick and that further ticks only let gravity
        // drag the player away from spawnY — true of the wait's PURPOSE, but a fixed wait says how
        // long we are willing to wait, and under a loaded run the server's player tick does not
        // arrive on our schedule (measured 2026-08-07: the player was still standing at 50000 when
        // the five ticks were up, and the leg indicted production for it). Polling exits at the
        // EARLIEST tick the teleport is visible, which is also the least free-fall the posY check
        // below can be handed.
        String after = exec("artest player health");
        int waitedTicks = 0;
        while (waitedTicks < 120
                && Math.abs(doubleField(POS_X, after, "posX") - 50000.5) < 1.0) {
            bot().waitTicks(2);
            waitedTicks += 2;
            after = exec("artest player health");
        }
        scenario().record("ticksUntilGuardMovedHim", waitedTicks);
        int dim = intField(DIM, after, "dim");
        assertEquals("player must remain in the space dim — he should be teleported to the "
                + "station's spawn, not back to overworld; dim=" + dim + " " + after,
                SPACE_DIM, dim);

        double posX = doubleField(POS_X, after, "posX");
        double posY = doubleField(POS_Y, after, "posY");
        double posZ = doubleField(POS_Z, after, "posZ");
        // The handler uses setPositionAndUpdate(spawn.x, spawn.y, spawn.z) exactly. X/Z motion in
        // vacuum is zero (no input), so a tight 2.0 epsilon holds. Y drifts down: gravity pulls the
        // player ~1 block/tick after a few ticks of accumulation, so 6.0 covers the 5-tick
        // free-fall window while still pinning "teleported to the spawn area, not to the overworld".
        assertEquals("player posX must match station spawnX after the guard fires; spawn=("
                        + spawnX + "," + spawnY + "," + spawnZ + ") player=(" + posX + "," + posY
                        + "," + posZ + ")", spawnX, posX, 2.0);
        assertEquals("player posY must match station spawnY (within the free-fall window)",
                spawnY, posY, 6.0);
        assertEquals("player posZ must match station spawnZ", spawnZ, posZ, 2.0);
        assertNotEquals("player must NOT be in overworld (station exists -> teleport-to-station "
                + "branch, not fallback): " + after, 0, dim);
    }
}
