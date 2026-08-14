package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * P3 (§4.3): the shield must ride an assembled Valkyrien Skies ship. A ship's blocks live in a
 * distant SUBSPACE shipyard while the hull flies around the world, so a naive world-frame shield would
 * project its shell at the shipyard, thousands of blocks from where the ship is. The {@code FieldFrame}
 * seam maps each emitter's subspace centre out through the ship transform, so the shell tracks the
 * flying hull, and exposes the hull's own velocity so impacts are taken relative to it (a cruising ship
 * must not bill its own crew).
 *
 * <p>This is a CLIENT e2e because a VS ship only loads with an observer present (the headless server
 * cannot load one — {@code VSShipMotionServerTest} documents that limit). With the client near the ship
 * the hull loads on both sides, so the shield's frame is verified through precise server probes:
 * <ul>
 *   <li><b>Ship-framed.</b> The emitter's field is ship-framed and its world centre sits at the loaded
 *       ship's world position — far from its subspace block pos.</li>
 *   <li><b>Tracks the hull.</b> Pushing the ship moves the shell's world centre with it, and the shell's
 *       reported surface velocity becomes non-zero — the relative-velocity input the deflection uses.</li>
 *   <li><b>Deflects on board.</b> A charged shield on the ship deflects an inbound arrow off its shell
 *       (the same absorption math, now around the ship-transformed centre).</li>
 * </ul>
 * The frame-blind deflection math itself is pinned standalone by {@code ShieldImpactAbsorptionTest}; here
 * we verify the two frame-dependent quantities (moving world centre + live surface velocity) on a real
 * loaded ship, plus one on-hull deflection. Gated on real VS — run with {@code -PwithVS}.</p>
 */
public class VSShipFrameShieldE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern EMITTER_COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");

    private static final String VARIANT = "with-shield-emitter";
    private static final int BX = 5200, BY = 64, BZ = 5200;

    @Test
    public void shieldRidesTheAssembledShipAndDeflectsOnBoard() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");
        exec("tp @a " + (BX + 40) + " 120 " + (BZ + 40) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleFixture(BX, BY, BZ);
        assertTrue("a with-shield-emitter build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int all = 0;
        for (int i = 0; i < 40 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a VS ship (all=" + all + ")", all >= 1);

        // Sit the client on the ship so the hull (and the emitter's chunk) loads server-side.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);
        boolean loaded = false;
        for (int i = 0; i < 40 && !loaded; i++) {
            bot().waitTicks(5);
            loaded = count("ship-count") >= 1;
        }
        assertTrue("the ship must LOAD with the client present", loaded);

        // Take its identity now, while nothing else is at the build spot. Every later question and
        // every push below names THIS ship: the hull ends up 20+ blocks up, and a nearest-ship lookup
        // at the build spot would quietly start answering for a neighbour on a shared client.
        final String shipId = captureShipId();

        // Discover the ship's emitter through the registry (we do not know its subspace coords).
        String emitters = "";
        for (int i = 0; i < 40; i++) {
            bot().waitTicks(5);
            emitters = exec("artest shield emitters 0");
            if (emitterCount(emitters) >= 1 && emitters.contains("\"shipFramed\":true")) {
                break;
            }
        }
        assertTrue("the ship's emitter must load and resolve a SHIP frame — a world-frame shield on a "
                + "ship would project its shell at the shipyard, not the flying hull:\n" + emitters,
                emitterCount(emitters) >= 1 && emitters.contains("\"shipFramed\":true"));

        int spX = (int) f(emitters, "posX"), spY = (int) f(emitters, "posY"), spZ = (int) f(emitters, "posZ");
        double wx1 = f(emitters, "worldX"), wy1 = f(emitters, "worldY"), wz1 = f(emitters, "worldZ");

        // Check 1: the shell's world centre is at the loaded ship, FAR from the emitter's subspace pos
        // (VS relocates a ship's blocks thousands of blocks away into its shipyard).
        double subToWorld = dist(spX + 0.5, spY + 0.5, spZ + 0.5, wx1, wy1, wz1);
        assertTrue("the shell's world centre coincides with the raw subspace block pos (subToWorld="
                + subToWorld + ") — the frame did not map the centre out to the flying hull:\n" + emitters,
                subToWorld > 64.0);
        assertTrue("the shell's world centre is not near the ship's world position (worldXZ=" + wx1 + ","
                + wz1 + " ship=" + BX + "," + BZ + ") — the shell is not on the hull:\n" + emitters,
                Math.abs(wx1 - (BX + 0.5)) < 24.0 && Math.abs(wz1 - (BZ + 0.5)) < 24.0);

        // Check 3 (before pushing, while the ship is roughly settled): charge the emitter and deflect an
        // inbound arrow off the ship-framed shell. Re-read the world centre immediately so the arrow is
        // aimed at where the shell actually is this instant.
        String read = exec("artest shield read 0 " + spX + " " + spY + " " + spZ);
        int radius = (int) f(read, "radius");
        exec("artest shield charge 0 " + spX + " " + spY + " " + spZ + " 40000");
        String reRead = exec("artest shield read 0 " + spX + " " + spY + " " + spZ);
        assertTrue("charged emitter did not power:\n" + reRead, reRead.contains("\"powered\":true"));
        double cx = f(reRead, "worldX"), cy = f(reRead, "worldY"), cz = f(reRead, "worldZ");

        double sx = cx, sy = cy, sz = cz + radius; // on the +Z shell, aimed inward at the centre
        String spawn = exec("artest entity spawn 0 " + sx + " " + sy + " " + sz + " minecraft:arrow");
        int arrowId = entityId(spawn);
        exec("artest entity set-motion 0 " + arrowId + " 0 0 -0.4");
        exec("artest entity tick 0 " + arrowId + " 1");
        exec("artest tile force-tick 0 " + spX + " " + spY + " " + spZ + " 1");
        String arrow = exec("artest entity info 0 " + arrowId);
        // Re-read the centre once more; the deflection is measured against where the shell is now.
        double ncx = f(exec("artest shield read 0 " + spX + " " + spY + " " + spZ), "worldX");
        double dcx = ncx - cx; // how far the hull drifted while we set this up
        if (arrow.contains("\"isAlive\":true")) {
            double ax = f(arrow, "posX"), ay = f(arrow, "posY"), az = f(arrow, "posZ");
            double d = dist(ax, ay, az, cx + dcx, cy, cz);
            assertTrue("the arrow ended up inside the ship's shell (dist=" + d + " <= radius " + radius
                    + ") — a charged shield on a VS ship did not deflect it off the hull:\n" + arrow,
                    d > radius);
        }
        // (If the arrow died it was absorbed rather than deflected — also a shield interaction, but the
        // kinetic path should reflect; a dead arrow would fail the alive check above, so we require it.)
        assertTrue("the arrow was consumed, not deflected — the kinetic path should reflect it off the "
                + "ship's shell:\n" + arrow, arrow.contains("\"isAlive\":true"));

        // Check 2: the shell rides the hull as it MOVES, and its surface velocity is live (the
        // relative-velocity input). A just-assembled free hull drifts under its own physics; we perturb
        // it and confirm the shell's world centre stays LOCKED to the ship's world position across the
        // displacement — a direction- and rotation-agnostic "rides the hull" test (a raw +axis delta is
        // unreliable because a free hull also rotates, swinging an off-centre emitter, and push-ship
        // does not reliably impose a chosen direction here).
        double[] ship1 = shipPos(shipId);
        double[] shell1 = shellCenter();
        assertTrue("precondition: the shell must sit on the hull before it moves (shell=" + str(shell1)
                + " ship=" + str(ship1) + ")", dist(shell1, ship1) < 32.0);
        for (int i = 0; i < 25; i++) {
            String push = exec("artest vs push-ship-by-id 0 " + shipId + " 0 14 0");
            assertTrue("ARRANGEMENT: the push must reach THIS ship: " + push,
                    push.contains("\"pushed\":true"));
            bot().waitTicks(2);
        }
        double[] ship2 = shipPos(shipId);
        String moved = exec("artest shield emitters 0");
        double[] shell2 = new double[]{f(moved, "worldX"), f(moved, "worldY"), f(moved, "worldZ")};
        double speed = Math.sqrt(sq(f(moved, "velX")) + sq(f(moved, "velY")) + sq(f(moved, "velZ")));
        double shipMoved = dist(ship1, ship2);
        double shellMoved = dist(shell1, shell2);
        assertTrue("ARRANGEMENT: the hull must actually move to test tracking (shipMoved=" + shipMoved
                + ") — perturb harder if VS pinned it", shipMoved > 1.5);
        assertTrue("the shell's world centre did not move with the hull (shellMoved=" + shellMoved
                + " shipMoved=" + shipMoved + ") — it is frozen, not tracking the flying hull:\n" + moved,
                shellMoved > 0.5);
        assertTrue("the shell detached from the hull after it moved (shell=" + str(shell2) + " ship="
                + str(ship2) + " dist=" + dist(shell2, ship2) + ") — the shell does not ride the hull:\n"
                + moved, dist(shell2, ship2) < 32.0);
        assertTrue("the shell's surface velocity stayed zero on a moving ship (speed=" + speed + ") — the "
                + "relative-velocity input is dead, so a cruising ship would bill its own crew:\n" + moved,
                speed > 0.0);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private int emitterCount(String json) {
        Matcher m = EMITTER_COUNT.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private int entityId(String json) {
        Matcher m = ENTITY_ID.matcher(json);
        assertTrue("no entityId in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    /** The ship's identity, captured once while it is provably the only one at the build spot. */
    private String captureShipId() throws Exception {
        String si = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
        Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(si);
        assertTrue("ship-info must name WHICH ship answered: " + si, m.find());
        return m.group(1);
    }

    /** Where THIS ship is — asked by identity, so it keeps answering about the same hull once the
     *  push has carried it away from the spot it was built on. */
    private double[] shipPos(String shipId) throws Exception {
        String si = exec("artest vs ship-info 0 id " + shipId);
        return new double[]{f(si, "posX"), f(si, "posY"), f(si, "posZ")};
    }

    private double[] shellCenter() throws Exception {
        String em = exec("artest shield emitters 0");
        return new double[]{f(em, "worldX"), f(em, "worldY"), f(em, "worldZ")};
    }

    private static double dist(double[] a, double[] b) {
        return dist(a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    private static String str(double[] a) {
        return "(" + a[0] + "," + a[1] + "," + a[2] + ")";
    }

    private double f(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.E\\-]+)").matcher(json);
        assertTrue("expected key " + key + " in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double dist(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Math.sqrt(sq(x1 - x2) + sq(y1 - y2) + sq(z1 - z2));
    }

    private static double sq(double v) {
        return v * v;
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
