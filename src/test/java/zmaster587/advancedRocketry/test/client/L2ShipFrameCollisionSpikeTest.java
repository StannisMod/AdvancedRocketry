package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE, NOT A CONTRACT TEST. Delete once its question is answered.
 *
 * <p>Question (L2): if an entity's movement is resolved in the SHIP's frame - where the deck is
 * axis-aligned and its box is deck-aligned - does it stay put on a deck rolled 90 degrees, where
 * world-frame collision cannot hold it?</p>
 *
 * <p>Two identical items are settled on a level deck, one metre apart. One is left to the physics
 * mod's world-frame handling; the other has its {@code Entity.move} resolved in the ship frame. The
 * ship is then rolled 90 degrees, turning the deck into a wall as far as the world is concerned.</p>
 *
 * <p>The measurement is each item's position <em>in the ship's own coordinates</em>: an item that
 * genuinely rides the deck barely moves there, whatever the ship does in the world.</p>
 */
public class L2ShipFrameCollisionSpikeTest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern QZ = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");
    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");
    private static final Pattern LOCAL_X = Pattern.compile("\"localX\":(-?[0-9.E\\-]+)");
    private static final Pattern LOCAL_Y = Pattern.compile("\"localY\":(-?[0-9.E\\-]+)");
    private static final Pattern LOCAL_Z = Pattern.compile("\"localZ\":(-?[0-9.E\\-]+)");
    private static final Pattern FIRES = Pattern.compile("\"fires\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 2900, BY = 64, BZ = 2900;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private int readInt(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected an integer in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private double[] localOf(int entityId) throws Exception {
        String json = exec("artest vs player-ship-data 0 " + entityId);
        assertTrue("entity " + entityId + " must report a ship-frame position: " + json,
                json.contains("\"localX\""));
        return new double[]{readDouble(json, LOCAL_X), readDouble(json, LOCAL_Y), readDouble(json, LOCAL_Z)};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }


    @Test
    public void shipFrameCollisionHoldsAnEntityOnADeckRolledNinetyDegrees() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());

        exec("artest vs shiplocal off");
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int all = 0;
        for (int i = 0; i < 40 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a ship (all=" + all + ")", all >= 1);
        bot().waitTicks(40);

        exec("tp @a " + (BX + 0.5) + " " + (BY + 8) + " " + (BZ + 0.5) + " 0 0");
        int loaded = 0;
        for (int i = 0; i < 40 && loaded < 1; i++) {
            bot().waitTicks(5);
            loaded = count("ship-count");
        }
        assertTrue("the ship must LOAD with the client present", loaded >= 1);

        String where = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
        double sx = readDouble(where, POS_X), sy = readDouble(where, POS_Y), sz = readDouble(where, POS_Z);

        // Settle two identical items on the LEVEL deck, a metre apart so they cannot merge.
        int controlId = readInt(exec("artest vs drop-item 0 " + sx + " " + (sy + 3) + " " + sz), ENTITY_ID);
        int frameId = readInt(exec("artest vs drop-item 0 " + (sx + 1) + " " + (sy + 3) + " " + sz), ENTITY_ID);
        bot().waitTicks(70);

        double[] controlStart = localOf(controlId);
        double[] frameStart = localOf(frameId);
        System.out.println("[L2] settled on level deck: control=" + java.util.Arrays.toString(controlStart)
                + " frame=" + java.util.Arrays.toString(frameStart));

        // Hand ONE of them to ship-frame movement. Its authoritative position is seeded from where
        // it already rests, so both start from the same physical situation.
        String armed = exec("artest vs shiplocal shipframe " + frameId);
        System.out.println("[L2] armed: " + armed);

        // Roll the ship 90 degrees: in the world the deck becomes a wall.
        double half = Math.toRadians(90.0) / 2.0;
        String point = exec("artest vs point 0 " + BX + " " + BY + " " + BZ
                + " " + Math.cos(half) + " 0.0 0.0 " + Math.sin(half));
        assertTrue("attitude hold must accept the roll: " + point, point.contains("\"commanded\":true"));
        bot().waitTicks(160);

        String rolledInfo = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
        double qz = readDouble(rolledInfo, QZ);
        System.out.println("[L2] ship after roll: " + rolledInfo);
        assertTrue("the ship must actually have rolled a long way (qz=" + qz + ")", Math.abs(qz) > 0.5);

        double[] controlEnd = localOf(controlId);
        double[] frameEnd = localOf(frameId);
        String status = exec("artest vs shiplocal status");
        exec("artest vs shiplocal off");

        double controlDrift = distance(controlStart, controlEnd);
        double frameDrift = distance(frameStart, frameEnd);
        int fires = readInt(status, FIRES);

        System.out.println("[L2] control drift (world-frame collision) = " + controlDrift
                + "  end=" + java.util.Arrays.toString(controlEnd));
        System.out.println("[L2] ship-frame drift                      = " + frameDrift
                + "  end=" + java.util.Arrays.toString(frameEnd));
        System.out.println("[L2] status: " + status);

        assertTrue("the ship-frame hook must have run; fires=" + fires + ": " + status, fires > 0);
        assertTrue("the ship-frame entity must stay where it was on the deck; it drifted "
                        + frameDrift + " (start " + java.util.Arrays.toString(frameStart)
                        + " end " + java.util.Arrays.toString(frameEnd) + ")",
                frameDrift < 1.0);
        assertTrue("ship-frame collision must hold the entity far better than world-frame collision:"
                        + " control drifted " + controlDrift + ", ship-frame drifted " + frameDrift,
                frameDrift < controlDrift);
    }
}
