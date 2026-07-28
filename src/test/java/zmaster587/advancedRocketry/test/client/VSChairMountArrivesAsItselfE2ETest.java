package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A mount entity is the SAME entity on both sides.
 *
 * <p>Sitting on a chair spawns a mount entity that the server owns and the client must rebuild as
 * itself. When it does not, nothing reports an error: the client silently builds some other
 * registered class, is handed the sender's synced fields BY SLOT INDEX, and the first read of a
 * slot whose type no longer matches takes the whole client to the crash screen mid-tick. From the
 * player's side that is the worst failure this mod can produce, and it needs no command and no
 * unusual action — only sitting down.
 *
 * <p><b>Why this must be a client test.</b> The defect lives only on the receiving side: the
 * server's own view is correct and every server-tier query agrees with itself. The subject is what
 * the CLIENT rebuilt, so only the client can answer.
 *
 * <p><b>Why an ordinary chair on the ground.</b> The mix-up is a property of the entity numbering
 * this jar ships, not of any ship: a plain chair mounts through the same spawn path, with no
 * physics object, no assembly and no timing window, so the reproduction is deterministic. The
 * riding report is its own control — {@code riding=true} means the mount did reach the client, so
 * a wrong class cannot be explained away as "the client never saw it".
 *
 * <p>Gated on real Valkyrien Skies being on the server.
 */
public class VSChairMountArrivesAsItselfE2ETest extends AbstractClientE2ETest {

    private static final Pattern SERVER_ENTITY =
            Pattern.compile("\\{\"id\":(-?\\d+),\"class\":\"([^\"]+)\"");

    /** Far from every other fixture's build site, and high enough to be clear of any terrain. */
    private static final int FX = 7700, FY = 90, FZ = 7700;
    /** The chair block, one step from where the player stands — inside interaction reach. */
    private static final int CX = FX + 1, CY = FY + 1, CZ = FZ;

    private static final String CHAIR_ENTITY =
            "org.valkyrienskies.mod.common.entity.EntityMountableChair";

    @Test
    public void aChairMountArrivesAtTheClientAsItself() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server", serverHasVs());

        // ---- ARRANGE: a floor, a chair on it, and the player standing next to the chair. --------
        assertTrue("ARRANGEMENT: chunk warmup failed",
                exec("artest chunk warmup 0 " + (FX >> 4) + " " + (FZ >> 4) + " "
                        + ((FX + 1) >> 4) + " " + ((FZ + 1) >> 4)).contains("\"ok\":true"));
        assertTrue("ARRANGEMENT: floor fill failed",
                exec("artest fill 0 " + (FX - 2) + " " + FY + " " + (FZ - 2) + " "
                        + (FX + 2) + " " + FY + " " + (FZ + 2) + " minecraft:stone")
                        .contains("\"ok\":true"));
        assertTrue("ARRANGEMENT: clearing the space above the floor failed",
                exec("artest fill 0 " + (FX - 2) + " " + (FY + 1) + " " + (FZ - 2) + " "
                        + (FX + 2) + " " + (FY + 3) + " " + (FZ + 2) + " minecraft:air")
                        .contains("\"ok\":true"));
        // The chair block carries the HOST mod's domain, not the physics engine's: vendored code
        // registers under the container it is loaded in. An unknown block id fills air here and
        // still reports success, which is why the placement is read back below.
        String chair = exec("artest fill 0 " + CX + " " + CY + " " + CZ + " "
                + CX + " " + CY + " " + CZ + " advancedrocketry:passenger_chair");
        assertTrue("ARRANGEMENT: the chair block must be placeable: " + chair,
                chair.contains("\"ok\":true"));
        String rightAfter = exec("artest block at 0 " + CX + " " + CY + " " + CZ);
        assertTrue("ARRANGEMENT: the chair must actually be in the world once the fill reports"
                + " success: " + rightAfter, rightAfter.contains("passenger_chair"));
        // The platform is built into a chunk the client may not hold yet, so the first teleport can
        // land the player on nothing and he falls out of reach of the chair. Re-place him until his
        // own client agrees he is standing on it.
        JsonObject stood = null;
        double standY = Double.NaN;
        for (int attempt = 0; attempt < 6 && !(Math.abs(standY - (FY + 1)) < 0.6); attempt++) {
            exec("tp @a " + (FX + 0.5) + " " + (FY + 1) + " " + (FZ + 0.5) + " 90 0");
            bot().waitTicks(15);
            stood = bot().reportState();
            standY = stood.get("playerY").getAsDouble();
        }
        assertEquals("ARRANGEMENT: the player must end up standing ON the platform - one that fell"
                        + " off it is out of reach of the chair: " + stood,
                FY + 1, standY, 0.6);
        String placed = exec("artest block at 0 " + CX + " " + CY + " " + CZ);
        assertTrue("ARRANGEMENT: the chair block must still be there when the player reaches for"
                + " it: " + placed, placed.contains("passenger_chair"));

        // ---- ACT: the player sits down, through a real right-click on his own client. -----------
        JsonObject click = bot().interactBlock(CX, CY, CZ);
        assertTrue("ARRANGEMENT: the right-click must be accepted by the client: " + click,
                click != null);
        bot().waitTicks(20);

        // ---- The server's view: the truth the client is supposed to reproduce. Read FIRST, so
        // that a client already dead to this very defect still leaves the arrangement on record. --
        String serverSide = exec("artest entity near 0 " + CX + " " + CY + " " + CZ + " 8");
        int chairEntityId = -1;
        String chairEntityClass = null;
        Matcher m = SERVER_ENTITY.matcher(serverSide);
        while (m.find()) {
            if (m.group(2).equals(CHAIR_ENTITY)) {
                chairEntityId = Integer.parseInt(m.group(1));
                chairEntityClass = m.group(2);
            }
        }
        assertTrue("ARRANGEMENT: sitting on the chair must give the server a mount entity -"
                + " without one this test has no subject: " + serverSide, chairEntityClass != null);

        // ---- ASSERT: the client is riding THAT entity, and it is the same class. ----------------
        JsonObject riding = ridingOrDie();
        assertTrue("CONTROL: the client must report itself riding - if the mount never reached it,"
                + " nothing below is evidence about class identity: " + riding,
                riding.get("riding").getAsBoolean());
        assertEquals("the mount entity must carry the same network id on both sides: server="
                        + serverSide + " client=" + riding,
                chairEntityId, riding.get("entityId").getAsInt());
        assertEquals("the entity the player sits on must be the SAME class on both sides; another"
                        + " class here means the client rebuilt it as something else and is"
                        + " applying this entity's synced fields to that object, slot by slot,"
                        + " until one of them is read at the wrong type: server=" + serverSide,
                chairEntityClass, riding.get("entityClass").getAsString());
    }

    // ---- helpers -----------------------------------------------------------------------------

    /**
     * The client's riding report. A client that has already died to a mis-built entity answers
     * nothing at all — the bot sees only a dropped connection — so that case is named here rather
     * than surfacing as a bare socket error with no cause in it.
     */
    private JsonObject ridingOrDie() throws Exception {
        try {
            return bot().reportRidingEntity();
        } catch (Exception dead) {
            fail("the client stopped answering after it was seated on a mount entity - the symptom"
                    + " of having rebuilt it as another class and then read one of its synced"
                    + " fields at the wrong type. The cause is in the preserved client log, never"
                    + " in this error: " + dead);
            return null; // unreachable
        }
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }
}
