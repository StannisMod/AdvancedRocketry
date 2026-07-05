package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * player-visible side of
 * {@link zmaster587.advancedRocketry.item.ItemHovercraft#onItemRightClick},
 * driven the way the player drives it: a REAL item right-click
 * ({@code ClientBot.useItem} → {@code CPacketPlayerTryUseItem}) with the look
 * aimed via {@code setLook}, observed at the layer the player sees —
 * the CLIENT world's entity list ({@code reportEntities}) and the
 * CLIENT-rendered held stack ({@code reportPlayerItems}).
 *
 * <p>Contract: right-click while looking at a block within ~5 blocks spawns
 * an EntityHoverCraft at the hit position and (in survival) consumes one
 * item; right-click into open air passes without spawning or consuming.</p>
 *
 * <p>Fixture: stone block at (X, Y, Z), player two blocks above looking
 * straight down — the item's 5-block eye ray hits the stone top face.</p>
 */
public class ItemHovercraftSpawnE2ETest extends AbstractClientE2ETest {

    private static final int DIM = 0;
    // Distinct fixture column from SealDetector (300..350) and the
    // inventory-bypass test (-200..-200) so multiple tests can share one
    // testClient JVM without colliding.
    private static final int X = 400;
    // High above natural overworld terrain so the EntityHoverCraft's
    // bounding box has guaranteed empty neighbours at the hit pos.
    private static final int Y_BLOCK = 150;
    private static final int Z = 300;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private void forceLoadAround(int x, int z) throws Exception {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                exec("artest chunk forceload " + DIM + " " + (cx + dx) + " " + (cz + dz));
            }
        }
    }

    /** Right-click looking down at a stone block must spawn exactly one
     *  EntityHoverCraft the CLIENT can see, and consume the held stack
     *  (survival). */
    @Test
    public void rightClickAtTargetBlockSpawnsHovercraftAndConsumesStack() throws Exception {
        bot().waitForWorld();
        forceLoadAround(X, Z);

        String placeResp = exec("artest place " + DIM + " " + X + " " + Y_BLOCK + " " + Z + " minecraft:stone");
        assertFalse("place must not error; resp=" + placeResp, placeResp.contains("\"error\""));

        // Arrange: survival player two blocks above the stone, holding the
        // hovercraft item.
        exec("gamemode survival @a");
        String give = exec("artest player give-held advancedrocketry:hovercraft");
        assertTrue("give-held must succeed: " + give, give.contains("\"ok\":true"));
        exec("tp @a " + (X + 0.5) + " " + (Y_BLOCK + 2) + " " + (Z + 0.5));
        bot().waitTicks(10);
        assertEquals("arrange: client must render the hovercraft item in hand",
                "advancedrocketry:hovercraft",
                bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString());

        // The REAL stimulus: aim straight down, right-click the held item.
        bot().setLook(0f, 90f);
        bot().useItem();

        // CLIENT truth #1: the client world now contains exactly one
        // hovercraft near the player.
        int seen = waitForClientEntityCount("EntityHoverCraft", 1);
        assertEquals("client must see exactly one spawned EntityHoverCraft", 1, seen);

        // CLIENT truth #2: the held stack was consumed (survival).
        JsonObject held = bot().reportPlayerItems().getAsJsonObject("held");
        assertEquals("survival right-click must consume the held hovercraft item; held="
                + held, 0, held.get("count").getAsInt());
    }

    /** Right-click into open air (no block within 5 blocks of the eye) must
     *  pass: no entity spawned, stack preserved. Pins the empty-ray-trace
     *  branch. */
    @Test
    public void rightClickIntoEmptyAirReturnsPassWithoutSpawn() throws Exception {
        bot().waitForWorld();
        forceLoadAround(X + 20, Z);

        exec("gamemode survival @a");
        String give = exec("artest player give-held advancedrocketry:hovercraft");
        assertTrue("give-held must succeed: " + give, give.contains("\"ok\":true"));
        exec("tp @a " + (X + 20 + 0.5) + " 200 " + (Z + 0.5));
        bot().waitTicks(10);

        // Aim straight UP into empty sky and right-click.
        bot().setLook(0f, -90f);
        bot().useItem();
        bot().waitTicks(20);

        assertEquals("no hovercraft must spawn on an empty ray-trace",
                0, bot().reportEntities("EntityHoverCraft", 32).get("count").getAsInt());
        JsonObject held = bot().reportPlayerItems().getAsJsonObject("held");
        assertEquals("stack must NOT be consumed on PASS; held=" + held,
                1, held.get("count").getAsInt());
    }

    /** Polls until the CLIENT sees {@code expected} entities of the class (~10 s cap). */
    private int waitForClientEntityCount(String classContains, int expected) throws Exception {
        int seen = -1;
        for (int waited = 0; waited < 200; waited += 10) {
            bot().waitTicks(10);
            seen = bot().reportEntities(classContains, 32).get("count").getAsInt();
            if (seen == expected) return seen;
        }
        return seen;
    }
}
