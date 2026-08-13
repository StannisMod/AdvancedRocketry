package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Spike — in WHICH coordinate frame does an oxygen vent seal, once its blocks belong to an
 * assembled Valkyrien Skies ship, and does the crew's own breathe gate resolve against that seal?
 *
 * <p>Two identical sealed cabins are built and pressurised in the same run:</p>
 *
 * <ul>
 *   <li><b>Control — static world.</b> A cabin on the ground, far from any ship. It proves the
 *       instrument is sensitive: the vent seals, the position query reports
 *       {@code PressurizedAir}, and the player standing inside it has that atmosphere cached by
 *       the per-entity gate. Without this leg an "air" reading on the ship leg would be
 *       unfalsifiable — it could equally mean the vent, the probe or the cache never worked.</li>
 *   <li><b>Subject — aboard an assembled ship.</b> The same cabin built at the ship's SUBSPACE
 *       addresses, its vent sealed there, then queried twice: at the subspace cell, and at the
 *       WORLD cell the same cabin actually occupies (mapped through the live ship transform).
 *       The player is teleported into that world cell — physically inside the pressurised
 *       cabin — and his cached atmosphere is read back.</li>
 * </ul>
 *
 * <p>The subject can falsify the premise: if the blob were built (or re-keyed) in world
 * coordinates, the world-cell query and the aboard player would both report {@code PressurizedAir}
 * and no ship-frame conversion would be needed at the gate at all. The run prints every raw
 * payload, and each assertion carries them, so a red is a finished measurement rather than the
 * start of one.</p>
 *
 * <p>Gated on real VS — run with {@code -PwithVS}; skips otherwise.</p>
 *
 * <p><b>What it measured (2026-07-26).</b> The vent seals identically in both frames
 * ({@code blobSize=28} on the ground and aboard), so a ship's own blocks seal normally; the blob's
 * member cells are the ship's SUBSPACE addresses ({@code 5120005,129,51200} &rarr;
 * {@code PressurizedAir}), while the WORLD cell the same cabin occupies ({@code 5207,70,5203})
 * reports the dimension default, and a player standing inside that pressurised cabin resolves
 * {@code air} — against {@code PressurizedAir} for the identical cabin on the ground. The
 * assertions therefore pin the CURRENT behaviour: when the atmosphere gate learns to resolve in
 * the ship frame this test goes red and must be rewritten to the new contract.</p>
 */
public class VSShipAtmosphereFrameSpikeTest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern ATM_TYPE = Pattern.compile("\"type\":\"([^\"]*)\"");
    private static final Pattern CACHED_ATM = Pattern.compile("\"cachedAtmosphere\":\"([^\"]*)\"");
    private static final Pattern BLOB_SIZE = Pattern.compile("\"blobSize\":(-?\\d+)");
    private static final Pattern SEAT_SUB = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern WORLD_XYZ = Pattern.compile(
            "\"worldX\":(-?[0-9.E\\-]+),\"worldY\":(-?[0-9.E\\-]+),\"worldZ\":(-?[0-9.E\\-]+)");
    private static final Pattern SHIP_WORLD = Pattern.compile(
            "\"shipWorldX\":(-?[0-9.E\\-]+),\"shipWorldY\":(-?[0-9.E\\-]+),\"shipWorldZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";

    /** Build site of the ship; distinct from every other VS client test's site. */
    private static final int BX = 5200, BY = 64, BZ = 5200;
    /** Static control cabin, far from the ship and from its shipyard. */
    private static final int CX = 5600, CY = 70, CZ = 5600;

    @Test
    public void aSealedShipCabinDoesNotReachItsOwnCrew_documentsKnownBug() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());

        // ── Control leg: the same cabin, in the plain world ───────────────────────────────
        buildCabin(CX, CY, CZ);
        String ctrlSeal = sealCabin(CX, CY, CZ);
        int ctrlBlob = readInt(ctrlSeal, BLOB_SIZE);
        String ctrlAtm = atmosphereAt(CX, CY, CZ);
        System.out.println("[S1/control] seal=" + ctrlSeal + " atm=" + ctrlAtm);
        assertTrue("CONTROL: a vent in a sealed cabin in the plain world must seal a non-empty "
                        + "blob — otherwise nothing measured on the ship means anything (seal="
                        + ctrlSeal + ")",
                ctrlBlob > 0);
        assertTrue("CONTROL: the sealed cabin's own cell must read PressurizedAir (got " + ctrlAtm
                        + ", raw=" + ctrlSeal + ")",
                "PressurizedAir".equalsIgnoreCase(ctrlAtm));

        String ctrlCached = cachedAtmosphereWithPlayerAt(CX + 0.5, CY, CZ + 0.5);
        System.out.println("[S1/control] cachedForPlayer=" + ctrlCached);
        assertTrue("CONTROL: the per-entity gate must see the seal for a player standing INSIDE "
                        + "the static cabin — this is the instrument the ship leg reads (cached="
                        + ctrlCached + ")",
                "PressurizedAir".equalsIgnoreCase(ctrlCached));

        // ── Subject leg: the same cabin, aboard an assembled ship ─────────────────────────
        // Bring the client to the build site BEFORE assembling: a client near the ship is what
        // makes VS load it (a headless server alone never does), and one run that assembled with
        // the player still 400 blocks away at the control cabin left the registry empty.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 8) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        String assemble = assembleFixture(BX, BY, BZ);
        System.out.println("[S1/ship] assemble=" + assemble);
        assertTrue("a with-pilot-seat build must route to a VS ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int all = 0;
        for (int i = 0; i < 60 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("ARRANGEMENT: assembly must create a VS ship (all=" + all + ", assemble="
                + assemble + ")", all >= 1);

        int loaded = 0;
        for (int i = 0; i < 40 && loaded < 1; i++) {
            bot().waitTicks(5);
            loaded = count("ship-count");
        }
        assertTrue("ARRANGEMENT: the ship must LOAD with the client present (loaded=" + loaded
                + ", all=" + all + ")", loaded >= 1);

        String found = exec("artest vs find-seat 0 " + BX + " " + (BY + 5) + " " + BZ);
        Matcher sm = SEAT_SUB.matcher(found);
        assertTrue("ARRANGEMENT: find-seat must resolve the ship's subspace seat: " + found,
                sm.find());
        int sx = Integer.parseInt(sm.group(1));
        int sy = Integer.parseInt(sm.group(2));
        int sz = Integer.parseInt(sm.group(3));
        System.out.println("[S1/ship] seatSubspace=" + sx + "," + sy + "," + sz);

        // The cabin goes beside the seat, in the ship's own (subspace) addresses.
        int vx = sx + 4, vy = sy, vz = sz;
        buildCabin(vx, vy, vz);
        String shipSeal = sealCabin(vx, vy, vz);
        int shipBlob = readInt(shipSeal, BLOB_SIZE);
        System.out.println("[S1/ship] seal=" + shipSeal);

        // RESULT 1 — does a vent whose blocks belong to a ship seal at all?
        assertTrue("RESULT-1: a vent in a sealed cabin built on an ASSEMBLED ship must still seal "
                        + "a blob (control blob=" + ctrlBlob + ", ship seal=" + shipSeal + ")",
                shipBlob > 0);

        // RESULT 2 — the frame the member cells actually live in.
        String subAtm = atmosphereAt(vx, vy, vz);
        double[] w = toWorld(vx, vy, vz);
        int wx = (int) Math.floor(w[0]), wy = (int) Math.ceil(w[1]), wz = (int) Math.floor(w[2]);
        String worldAtm = atmosphereAt(wx, wy, wz);
        System.out.println("[S1/ship] subspaceCell=" + vx + "," + vy + "," + vz + " -> " + subAtm
                + " | worldCell=" + wx + "," + wy + "," + wz + " -> " + worldAtm);

        double separation = Math.abs(w[0] - vx) + Math.abs(w[1] - vy) + Math.abs(w[2] - vz);
        assertTrue("ARRANGEMENT: the two frames must genuinely differ, else the comparison is "
                        + "vacuous (subspace=" + vx + "," + vy + "," + vz + " world=" + w[0] + ","
                        + w[1] + "," + w[2] + " separation=" + separation + ")",
                separation > 100.0);

        assertTrue("RESULT-2: the sealed cabin's SUBSPACE cell reports " + subAtm
                        + " (expected PressurizedAir — the blob is keyed in ship-block addresses)",
                "PressurizedAir".equalsIgnoreCase(subAtm));
        assertTrue("RESULT-2: the WORLD cell the cabin actually occupies reports " + worldAtm
                        + " — with a subspace-keyed blob it must NOT be PressurizedAir; if it IS, "
                        + "the atmosphere already resolves in the world frame for ship blocks",
                !"PressurizedAir".equalsIgnoreCase(worldAtm));

        // RESULT 3 — the gate that actually matters: a body standing inside the sealed cabin.
        // Re-map first: the ship keeps drifting, so the cabin's world image is only valid now.
        double[] wNow = toWorld(vx, vy, vz);
        String aboardCached = cachedAtmosphereWithPlayerAt(wNow[0], wNow[1], wNow[2]);
        System.out.println("[S1/ship] cachedForPlayerAboard=" + aboardCached);
        assertTrue("RESULT-3: a player standing INSIDE the ship's pressurised cabin resolves "
                        + aboardCached + " — the per-entity gate keys his WORLD position against "
                        + "a SUBSPACE-keyed blob, so a sealed hull does not reach its own crew "
                        + "(control, same cabin on the ground: " + ctrlCached + ")",
                !"PressurizedAir".equalsIgnoreCase(aboardCached));
    }

    // ── cabin construction / sealing ──────────────────────────────────────────────────────

    /**
     * A 3×3×3 air cavity inside a solid stone shell, with the vent set into the floor directly
     * under the cavity — the same shape as the static-world vent tests, so the two legs differ
     * only in which frame the blocks live in.
     */
    private void buildCabin(int x, int y, int z) throws Exception {
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + ((x - 2) >> 4) + " " + ((z - 2) >> 4)
                        + " " + ((x + 4) >> 4) + " " + ((z + 4) >> 4)).contains("\"ok\":true"));
        assertTrue("cabin shell fill failed",
                exec("artest fill 0 " + (x - 1) + " " + (y - 2) + " " + (z - 1)
                        + " " + (x + 3) + " " + (y + 3) + " " + (z + 3) + " minecraft:stone")
                        .contains("\"ok\":true"));
        assertTrue("cabin cavity fill failed",
                exec("artest fill 0 " + x + " " + y + " " + z
                        + " " + (x + 2) + " " + (y + 2) + " " + (z + 2) + " minecraft:air")
                        .contains("\"ok\":true"));
    }

    /** Place, fuel and force-seal the cabin's vent; returns the raw {@code vent reseal} payload. */
    private String sealCabin(int x, int y, int z) throws Exception {
        String place = exec("artest place 0 " + x + " " + (y - 1) + " " + z
                + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + place, place.contains("\"placed\":true"));
        assertTrue("energy inject failed",
                exec("artest energy inject 0 " + x + " " + (y - 1) + " " + z + " 1000000")
                        .contains("\"ok\":true"));
        assertTrue("oxygen inject failed",
                exec("artest fluid inject 0 " + x + " " + (y - 1) + " " + z + " oxygen 16000")
                        .contains("\"ok\":true"));
        exec("artest tile force-tick 0 " + x + " " + (y - 1) + " " + z + " 1");
        String reseal = exec("artest vent reseal 0 " + x + " " + (y - 1) + " " + z);
        exec("artest tile force-tick 0 " + x + " " + (y - 1) + " " + z + " 5");
        return reseal;
    }

    // ── observation ───────────────────────────────────────────────────────────────────────

    private String atmosphereAt(int x, int y, int z) throws Exception {
        String info = exec("artest atmosphere get 0 " + x + " " + y + " " + z);
        Matcher m = ATM_TYPE.matcher(info);
        assertTrue("atmosphere type not found in: " + info, m.find());
        return m.group(1);
    }

    /**
     * Teleport the player to a point and read back what the per-entity gate cached for him. The
     * cache is only written when the resolved atmosphere CHANGES, so the two legs are run in
     * opposite directions (pressurised control first, ship second) and each read is polled.
     */
    private String cachedAtmosphereWithPlayerAt(double x, double y, double z) throws Exception {
        exec("tp @a " + x + " " + y + " " + z + " 0 0");
        String cached = "";
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(5);
            String resp = exec("artest atmosphere cached-for-player");
            Matcher m = CACHED_ATM.matcher(resp);
            if (m.find()) {
                cached = m.group(1);
                if (!cached.isEmpty()) {
                    break;
                }
            }
        }
        return cached;
    }

    /**
     * A world point currently INSIDE the ship, needed by every world-keyed VS lookup. The build
     * site is not one — the ship's blocks sit above the pad's base course, and the ship drifts
     * under gravity while the cabin is being built — so the anchor is re-derived from the seat's
     * own live world position (which is keyed off its SUBSPACE block and so never goes stale)
     * every time it is used.
     */
    private int[] anchor = {BX, BY + 5, BZ};

    private int[] refreshAnchor() throws Exception {
        int[][] candidates = {anchor, {BX, BY + 5, BZ}, {BX, BY + 3, BZ}, {BX, BY + 8, BZ}};
        for (int[] c : candidates) {
            String found = exec("artest vs find-seat 0 " + c[0] + " " + c[1] + " " + c[2]);
            Matcher m = SHIP_WORLD.matcher(found);
            if (m.find()) {
                anchor = new int[]{(int) Math.floor(Double.parseDouble(m.group(1))),
                        (int) Math.floor(Double.parseDouble(m.group(2))),
                        (int) Math.floor(Double.parseDouble(m.group(3)))};
                return anchor;
            }
        }
        throw new AssertionError("ARRANGEMENT: no world anchor inside the ship could be resolved "
                + "from the build site — the ship is gone or was never loaded");
    }

    private double[] toWorld(int sx, int sy, int sz) throws Exception {
        int[] a = refreshAnchor();
        String resp = exec("artest vs to-world 0 " + a[0] + " " + a[1] + " " + a[2]
                + " " + sx + " " + sy + " " + sz);
        Matcher m = WORLD_XYZ.matcher(resp);
        assertTrue("ARRANGEMENT: subspace->world mapping failed (anchor=" + a[0] + "," + a[1] + ","
                + a[2] + "): " + resp, m.find());
        return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3))};
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────

    private int readInt(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
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
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
