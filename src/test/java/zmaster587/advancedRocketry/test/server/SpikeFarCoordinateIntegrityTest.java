package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;

import org.junit.Test;
import zmaster587.advancedRocketry.test.ServerTicks;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — does anything actually break past ±2M blocks, the bound the space cell was sized for?
 *
 * <p>`space-model.md` says a 4M cell exists because "entity doubles / chunks / lighting degrade past
 * ~±2M blocks in 1.12.2". Three mechanisms in one sentence, and the cell size — hence how big a planet
 * may be drawn — rests on all three. This measures the two a server can see: <b>chunk generation</b>
 * and <b>block storage</b>. The third (render jitter) is client-side and is measured separately.</p>
 *
 * <p><b>This spike is designed to come back NO.</b> The acceptance number is stated here, before the
 * run: at each sampled X, terrain must generate (a non-air top block at a plausible height) and a
 * placed block must read back as itself. A coordinate where either fails is a real ceiling; a
 * coordinate where both hold tells us the ceiling is not here.</p>
 *
 * <p>Throwaway by intent: it exists to answer one question once. If it is kept, it becomes a
 * regression test for "the world still works at the coordinates our cells use", which is a different
 * claim from the one it was written for.</p>
 */
public class SpikeFarCoordinateIntegrityTest extends AbstractHeadlessServerTest {

    /**
     * The ladder. 2M is today's half-cell; 8M and 16M are the growth steps a bigger cell would need;
     * 28M is just inside the vanilla world border (29 999 984), i.e. the last coordinate that can
     * exist at all.
     */
    private static final int[] X_LADDER = {2_000_000, 8_000_000, 16_000_000, 28_000_000};

    private static final int OVERWORLD = 0;
    private static final int PLACE_Y = 100;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void chunksAndBlockStorageStillWorkFarFromTheOrigin() throws Exception {
        List<String> report = new ArrayList<>();
        List<String> broken = new ArrayList<>();

        for (int x : X_LADDER) {
            int chunkX = x >> 4;
            exec("artest chunk forceload " + OVERWORLD + " " + chunkX + " 0");
            // Generation at a fresh, distant chunk is not instant; give the server real ticks rather
            // than reading an empty chunk and calling it a ceiling.
            ServerTicks.await(client(), OVERWORLD, 40);

            String sample = exec("artest worldgen sample " + OVERWORLD + " " + chunkX + " 0");
            String placed = exec("artest place " + OVERWORLD + " " + x + " " + PLACE_Y + " 0 "
                    + "minecraft:diamond_block");
            String readBack = exec("artest block at " + OVERWORLD + " " + x + " " + PLACE_Y + " 0");

            boolean terrainOk = !sample.contains("\"error\"") && !sample.contains("minecraft:air");
            boolean storageOk = readBack.contains("diamond_block");
            report.add("x=" + x + " terrain=" + (terrainOk ? "ok" : "FAIL") + " storage="
                    + (storageOk ? "ok" : "FAIL") + " sample=" + oneLine(sample)
                    + " placed=" + oneLine(placed) + " readBack=" + oneLine(readBack));
            if (!terrainOk || !storageOk) {
                broken.add(Integer.toString(x));
            }
        }

        // The whole point is the REPORT, so it is emitted either way — a spike that only speaks when
        // it fails cannot tell you where the ceiling ISN'T.
        System.out.println("[SPIKE far-coordinate integrity]");
        for (String line : report) {
            System.out.println("  " + line);
        }

        assertTrue("terrain or block storage failed at: " + broken + "\n" + String.join("\n", report),
                broken.isEmpty());
    }

    /**
     * Do ENTITY DOUBLES hold a sub-block X far from the origin?
     *
     * <p>This is the third of `space-model.md`'s three claimed mechanisms, and the only delivery that
     * can reach the far coordinates at all: the subject is SPAWNED there rather than moved there.
     * Every earlier attempt teleported a connected player and was rubber-banded by
     * {@code NetHandlerPlayServer} ("moved too quickly!"), so it measured the anti-cheat and not the
     * coordinate.</p>
     *
     * <p>Two stands 0.05 apart are spawned at each coordinate. If both read back exactly, entity
     * doubles do not degrade there — which is what the arithmetic says they should not: a double's
     * ULP at 2.8e7 is about 5e-9 of a block.</p>
     */
    @Test
    public void doEntityDoublesHoldASubBlockXFarFromTheOrigin() throws Exception {
        List<String> report = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (int x : X_LADDER) {
            exec("artest chunk forceload " + OVERWORLD + " " + (x >> 4) + " 0");
            ServerTicks.await(client(), OVERWORLD, 40);

            String near = spawnAndRead(x + 0.5500d);
            String far = spawnAndRead(x + 0.6000d);
            // Compare NUMERICALLY. Java prints a double above 1e7 in E-notation, so a textual check
            // reported 16000000.55 as a miss when the value was exact — the check was wrong, not the
            // coordinate, and a spike whose verdict is its own formatting is worse than no spike.
            boolean nearOk = Math.abs(asDouble(near) - (x + 0.55d)) < 1e-6d;
            boolean farOk = Math.abs(asDouble(far) - (x + 0.60d)) < 1e-6d;
            boolean distinct = !near.equals(far);
            report.add("x=" + x + " asked " + x + ".55 got " + near
                    + " | asked " + x + ".60 got " + far
                    + " | exact=" + (nearOk && farOk) + " distinct=" + distinct);
            if (!nearOk || !farOk || !distinct) {
                broken.add(Integer.toString(x));
            }
        }
        System.out.println("[SPIKE far-coordinate entity doubles]");
        for (String line : report) {
            System.out.println("  " + line);
        }
        assertTrue("a sub-block X was lost at: " + broken + " " + String.join(" | ", report),
                broken.isEmpty());
    }

    private static double asDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** Spawn an armour stand at an exact X and return the position the server reports for it. */
    private String spawnAndRead(double x) throws Exception {
        String spawned = exec("artest vs drop-stand " + OVERWORLD + " "
                + String.format(java.util.Locale.ROOT, "%.4f", x) + " 150 0.5");
        java.util.regex.Matcher idm = java.util.regex.Pattern
                .compile("\"entityId\"\\s*:\\s*(-?\\d+)").matcher(spawned);
        if (!idm.find()) {
            return "NO-SPAWN:" + oneLine(spawned);
        }
        String info = exec("artest entity info " + OVERWORLD + " " + idm.group(1));
        java.util.regex.Matcher xm = java.util.regex.Pattern
                .compile("\"posX\"\\s*:\\s*([-0-9.eE]+)").matcher(info);
        return xm.find() ? xm.group(1) : ("UNREADABLE:" + oneLine(info));
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
