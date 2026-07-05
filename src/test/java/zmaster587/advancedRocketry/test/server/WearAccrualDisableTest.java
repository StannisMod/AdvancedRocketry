package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Disableability contract for the parts-wear system.
 *
 * <p>Wear ACCRUAL goes through {@code StorageChunk.damageParts()}. With
 * {@code partsWearSystem} off, no part may advance a wear stage, so a rocket
 * driven through that entry point any number of times keeps a zero breaking
 * probability; with the system on, its motors wear and the breaking probability
 * rises. This pins the player-facing promise that turning the wear system off in
 * the config stops parts wearing at all (the consequences — thrust loss, tank
 * leak, seat block — are already gated and covered by {@code WearSystemTest}).</p>
 */
public class WearAccrualDisableTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern BREAKING_PROB =
            Pattern.compile("\"breakingProb\":(-?\\d+(?:\\.\\d+)?)");

    private String cmd(String c) throws Exception {
        return String.join("\n", client().execute(c));
    }

    private void preClear(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        client().execute("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        client().execute("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air");
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        preClear(baseX, baseY, baseZ);
        String fixture = cmd("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple");
        assertTrue("fixture build failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("no builderPos: " + fixture, bp.find());
        String assemble = cmd("artest rocket assemble 0 "
                + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));
        String list = cmd("artest rocket list 0");
        Matcher m = ROCKET_LIST_ID.matcher(list);
        int id = -1;
        while (m.find()) id = Integer.parseInt(m.group(1));
        assertTrue("no rocket id after assemble: " + list, id >= 0);
        return id;
    }

    private double damagePartsAndReadProb(int rocketId, int iterations) throws Exception {
        String r = cmd("artest wear damage-parts " + rocketId + " " + iterations);
        assertTrue("damage-parts must find the rocket: " + r, r.contains("\"found\":true"));
        Matcher m = BREAKING_PROB.matcher(r);
        assertTrue("no breakingProb in damage-parts response: " + r, m.find());
        return Double.parseDouble(m.group(1));
    }

    @Test
    public void wearAccruesOnlyWhenSystemEnabled() throws Exception {
        try {
            // Make motors wear deterministically fast so the "on" case is not flaky.
            assertTrue(cmd("artest config set increaseWearIntensityProb 1.0").contains("\"ok\":true"));

            // --- system ON: a worn motor raises the breaking probability ---
            assertTrue(cmd("artest config set partsWearSystem true").contains("\"ok\":true"));
            int onRocket = buildAndAssemble(3200, 64, 3200);
            double probOn = damagePartsAndReadProb(onRocket, 200);
            assertTrue("with the wear system ON, driving damageParts must accrue wear "
                    + "(breaking probability > 0), got " + probOn, probOn > 0);

            // --- system OFF: identical driving accrues nothing ---
            assertTrue(cmd("artest config set partsWearSystem false").contains("\"ok\":true"));
            int offRocket = buildAndAssemble(3260, 64, 3200);
            double probOff = damagePartsAndReadProb(offRocket, 200);
            assertEquals("with the wear system OFF, damageParts must not advance any wear "
                    + "stage (breaking probability stays 0)", 0.0, probOff, 1e-9);
        } finally {
            // Restore shared-harness defaults for any later test in this JVM.
            client().execute("artest config set partsWearSystem true");
            client().execute("artest config set increaseWearIntensityProb 0.025");
        }
    }
}
