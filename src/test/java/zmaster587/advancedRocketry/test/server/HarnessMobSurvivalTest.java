package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A living entity spawned in the harness world used to be removed within a few ticks, which blocked
 * any client e2e needing a live render subject (e.g. the remote-body model-roll gate).
 *
 * <p>Root cause (fixed): the test server runs with {@code spawn-animals} off, and vanilla
 * {@code WorldServer.updateEntityWithOptionalForce} {@code setDead()}s every {@code EntityAnimal}
 * each tick while {@code canSpawnAnimals()} is false — persistence does not exempt it, so a spawned
 * cow vanished on its first update tick. The {@code drop-living} probe now flips the server's
 * {@code canSpawnAnimals} flag on, and {@code canSpawnAnimals()} reads it live. This test pins that:
 * a persistent, no-AI cow spawned on a laid floor (so a fall is not a confound) must still be alive
 * after ~6 s of real server ticks.</p>
 */
public class HarnessMobSurvivalTest extends AbstractSharedServerTest {

    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void aPersistentNoAiMobSurvivesInTheHarnessWorld() throws Exception {
        final int x = 7930, y = 80, z = 7930;

        // Warm the chunk and lay ONE floor block so the subject is supported the moment it spawns —
        // a fall from an unsupported spawn would kill a 10-HP cow and masquerade as the bug.
        exec("artest chunk warmup 0 " + (x >> 4) + " " + (z >> 4) + " " + (x >> 4) + " " + (z >> 4));
        exec("artest fill 0 " + x + " " + (y - 1) + " " + z + " " + x + " " + (y - 1) + " " + z
                + " minecraft:stone");

        String spawned = exec("artest vs drop-living 0 minecraft:cow "
                + (x + 0.5) + " " + y + " " + (z + 0.5));
        System.out.println("[mobsurv] spawn: " + spawned.replace('\n', ' '));
        assertTrue("subject must spawn: " + spawned, spawned.contains("\"ok\":true"));
        Matcher m = ENTITY_ID.matcher(spawned);
        assertTrue("spawn must report an entity id: " + spawned, m.find());
        int id = Integer.parseInt(m.group(1));

        // The dedicated server keeps ticking while this JVM sleeps: ~6 s ≈ 120 real ticks.
        Thread.sleep(6000L);

        // deck-capture answers "entity not found" once the subject has been removed from the world.
        String after = exec("artest vs deck-capture 0 " + id);
        System.out.println("[mobsurv] after: " + after.replace('\n', ' '));

        assertTrue("a persistent, no-AI mob must survive >=100 ticks in the harness world "
                        + "(WorldServer culls every EntityAnimal while canSpawnAnimals() is false); "
                        + "after=" + after,
                !after.contains("entity not found"));
    }
}
