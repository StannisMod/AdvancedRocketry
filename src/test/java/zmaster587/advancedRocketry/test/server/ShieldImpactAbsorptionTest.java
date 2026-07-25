package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * P1: a charged shield emitter must absorb a single impact whose cost exceeds the coil's per-tick
 * intake. An energy projectile (AFFS {@code laser_bolt}) costs {@code energyProjectileImpactEnergy}
 * (default 10000) — far above a Tier 0 emitter's per-tick recharge throughput (default 4000).
 * Absorption is all-or-nothing, so
 * if the coil could only release a per-tick sliver it would refuse the bolt outright (and burn the
 * sliver). This pins that a well-charged coil actually stops the bolt at the shell and pays its full
 * cost — guarding the fix that unthrottles coil extraction (ledger #99).
 *
 * <p>The emitter absorbs in its {@code update()} (via {@code containUnauthorizedEntities}); the test
 * drives one deterministic emitter tick with {@code /artest tile force-tick} after spawning the bolt
 * inside the influence box.</p>
 */
public class ShieldImpactAbsorptionTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 64;
    private static final int FE_PER_ITERATION = 4000;
    private static final int ENERGY_PROJECTILE_COST = 10_000; // ModConfig.energyProjectileImpactEnergy default
    private static final Pattern STORED = Pattern.compile("\"shieldStored\":(-?\\d+)");
    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");

    @Test
    public void chargedCoilAbsorbsEnergyProjectileCostingMoreThanIntake() throws Exception {
        int gx = 980, gz = 774;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);

        // Charge the coil well above one bolt's cost, so the coil charge is not the limiter — only the
        // (pre-fix) per-tick extract cap could stand between a full coil and absorbing the bolt.
        for (int i = 0; i < 15; i++) {
            exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
            exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
            exec("artest shield tick " + DIM);
        }
        String before = read(ex, gz);
        assertTrue("emitter never powered — cannot test absorption:\n" + before,
                before.contains("\"powered\":true"));
        long storedBefore = readStored(before);
        assertTrue("precondition: coil not charged above one bolt's cost (stored=" + storedBefore + ")",
                storedBefore > ENERGY_PROJECTILE_COST + 5_000L);

        // Spawn an energy projectile in the air just above the powered emitter (inside the influence
        // box, not embedded in a block so it cannot self-destruct on a collision).
        String spawn = exec("artest entity spawn " + DIM + " " + (ex + 0.5D) + " " + (Y + 1.5D)
                + " " + (gz + 0.5D) + " affs:laser_bolt");
        int boltId = readEntityId(spawn);

        // One deterministic emitter tick: containUnauthorizedEntities runs and absorbs the bolt.
        exec("artest tile force-tick " + DIM + " " + ex + " " + Y + " " + gz + " 1");

        String boltInfo = exec("artest entity info " + DIM + " " + boltId);
        assertTrue("the powered coil did not absorb the energy projectile (it survived): a full coil "
                        + "cannot block a hit larger than its per-tick intake — ledger #99:\n" + boltInfo,
                boltInfo.contains("\"isAlive\":false") || boltInfo.contains("\"isDead\":true"));

        long storedAfter = readStored(read(ex, gz));
        long drop = storedBefore - storedAfter;
        // Corroborate the bolt died to the shield, not to some incidental collision: the coil must have
        // actually paid roughly the projectile's cost. (Guards against a false green where the bolt
        // self-destructs without any absorption.)
        assertTrue("coil energy did not drop by the projectile's cost (before=" + storedBefore
                        + " after=" + storedAfter + " drop=" + drop + "): the bolt was not absorbed by "
                        + "the shield.", drop >= ENERGY_PROJECTILE_COST - 1_000L);
    }

    @Test
    public void chargedShieldProtectsBlocksFromExplosion() throws Exception {
        // Control first: an unshielded glass block is destroyed by the blast. Without this the shielded
        // case proves nothing — the block might survive because the explosion is too weak, not because
        // of the shield.
        int cx = 940, cz = 780;
        place("minecraft:glass", cx, cz);
        exec("artest shield explode " + DIM + " " + (cx + 0.5D) + " " + (Y + 1.5D) + " " + (cz + 0.5D) + " 4");
        String controlBlock = exec("artest block at " + DIM + " " + cx + " " + Y + " " + cz);
        assertTrue("control: the explosion did not destroy an unshielded glass block — the blast is "
                        + "not lethal here, so the shielded case would prove nothing:\n" + controlBlock,
                controlBlock.contains("\"isAir\":true"));

        // Shielded: the same block, same blast, but inside a powered field — it must survive.
        int gx = 986, gz = 780;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);
        for (int i = 0; i < 15; i++) {
            chargeIteration(gx, gz);
        }
        assertTrue("emitter never powered:\n" + read(ex, gz), read(ex, gz).contains("\"powered\":true"));

        int px = ex + 2, pz = gz; // inside the emitter's radius-4 field
        place("minecraft:glass", px, pz);
        long storedBefore = readStored(read(ex, gz));
        exec("artest shield explode " + DIM + " " + (px + 0.5D) + " " + (Y + 1.5D) + " " + (pz + 0.5D) + " 4");

        String shieldedBlock = exec("artest block at " + DIM + " " + px + " " + Y + " " + pz);
        assertTrue("a glass block inside a powered shield was destroyed by an explosion — the field did "
                        + "not protect it:\n" + shieldedBlock, shieldedBlock.contains("minecraft:glass"));
        long storedAfter = readStored(read(ex, gz));
        assertTrue("shield energy did not drop while absorbing the explosion (before=" + storedBefore
                        + " after=" + storedAfter + "): the block may have survived for another reason.",
                storedAfter < storedBefore);
    }

    @Test
    public void chargedShieldDeflectsAnArrow() throws Exception {
        int gx = 992, gz = 786;
        int ex = gx + 1;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, gz);
        for (int i = 0; i < 15; i++) {
            chargeIteration(gx, gz);
        }
        assertTrue("emitter never powered:\n" + read(ex, gz), read(ex, gz).contains("\"powered\":true"));

        double centerX = ex + 0.5D, centerY = Y + 0.5D, centerZ = gz + 0.5D;
        double radius = 4.0D;
        // An arrow on the +Z shell, aimed straight inward at the emitter.
        double sx = centerX, sy = centerY, sz = centerZ + radius;
        String spawn = exec("artest entity spawn " + DIM + " " + sx + " " + sy + " " + sz + " minecraft:arrow");
        int arrowId = readEntityId(spawn);
        exec("artest entity set-motion " + DIM + " " + arrowId + " 0 0 -0.3");
        // Step the arrow inward once so it physically advances (pos != prevPos): the shield's deflection
        // reads the pos->prevPos sweep, so a stationary arrow with only a motion field is never repelled.
        exec("artest entity tick " + DIM + " " + arrowId + " 1");
        exec("artest tile force-tick " + DIM + " " + ex + " " + Y + " " + gz + " 1");

        String info = exec("artest entity info " + DIM + " " + arrowId);
        assertTrue("the arrow is gone (absorbed/dead), not deflected — a kinetic projectile should be "
                        + "pushed back, not consumed:\n" + info,
                info.contains("\"isAlive\":true") && info.contains("\"isDead\":false"));
        double px = parseD(info, "posX"), py = parseD(info, "posY"), pz = parseD(info, "posZ");
        double dist = Math.sqrt(sq(px - centerX) + sq(py - centerY) + sq(pz - centerZ));
        assertTrue("the arrow ended up inside the shell (dist=" + dist + " <= radius " + radius
                        + "): it was not deflected back outside the shield:\n" + info, dist > radius);
    }

    private String read(int x, int z) throws Exception {
        return exec("artest shield read " + DIM + " " + x + " " + Y + " " + z);
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private void chargeIteration(int gx, int gz) throws Exception {
        exec("artest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
        exec("artest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
        exec("artest shield tick " + DIM);
    }

    private static double parseD(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE]-?\\d+)?)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static long readStored(String json) {
        Matcher m = STORED.matcher(json);
        assertTrue("no shieldStored field in probe response: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static int readEntityId(String json) {
        Matcher m = ENTITY_ID.matcher(json);
        assertTrue("no entityId in spawn response: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
