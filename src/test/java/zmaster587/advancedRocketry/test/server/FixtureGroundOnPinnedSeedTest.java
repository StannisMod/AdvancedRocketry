package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;
import zmaster587.advancedRocketry.test.client.Plot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every fixture that builds on the ground stands on the surface it was calibrated to.
 *
 * <p>This test fails if the terrain under any ground fixture in the suite stops being what that
 * fixture assumes — a different height, a step, water, or something standing in its assembly
 * volume.</p>
 *
 * <h2>Why a server test guards fixtures that live in the client tier</h2>
 * These fixtures build a ship at a HARD-CODED Y. While nothing checked the ground, that was a bet
 * on the landscape, and the bet was lost silently for weeks: surveyed 2026-08-14, two client
 * fixtures sat inside a mountain (surface y=80..93 and y=80..99 against their y=64), one had 49
 * water columns in its footprint, and one stood under a forest floor at y=71..79. The symptoms were
 * arbitrary and expensive — a ship that would not climb, a pilot whose controls answered nothing, a
 * body that would not stand where it was put — and two of them were ledgered as production defects
 * (#161, #237) before anyone looked at the ground. Measuring it here costs one server boot instead
 * of eleven minutes of client harness per class, and a failure names the fixture.
 *
 * <h2>Per-fixture, not one global rule</h2>
 * A fixture is calibrated to ITS OWN spot. The four that were moved now share the two plots the
 * pinned seed offers that are clean across a whole footprint ({@link Plot#CLEAN_GROUND_X}); the
 * nav-computer fixture was always calibrated to the hilltop it stands on, is green there, and is
 * asserted at ITS height rather than normalised to the others. Only a fixture that needs a clean
 * footprint is held to one.
 *
 * <h2>What it does NOT prove</h2>
 * That those client classes pass. Standable ground is necessary for them, not sufficient.
 */
public class FixtureGroundOnPinnedSeedTest extends AbstractHeadlessServerTest {

    /**
     * Every ground fixture in the suite: {@code name, x, expected surface Y, z, needs a clean
     * footprint}. Add a row when you add a fixture that builds on terrain — that is the whole
     * maintenance cost of never repeating the weeks this test exists because of.
     */
    private static final Object[][] FIXTURES = {
            {"VSPilotSeatRelogControl", Plot.CLEAN_GROUND_X, Plot.CLEAN_GROUND_Y, Plot.CLEAN_GROUND_Z},
            {"VSShipRenderPoseSkew", Plot.CLEAN_GROUND_X, Plot.CLEAN_GROUND_Y, Plot.CLEAN_GROUND_Z},
            {"VSShipEntryRefused", Plot.CLEAN_GROUND_X, Plot.CLEAN_GROUND_Y, Plot.CLEAN_GROUND_Z},
            {"VSRemoteBodyModelGate.standing", Plot.CLEAN_GROUND_X, Plot.CLEAN_GROUND_Y,
                    Plot.CLEAN_GROUND_Z},
            {"VSRemoteBodyModelGate.carried", Plot.CLEAN_GROUND_X, Plot.CLEAN_GROUND_Y,
                    Plot.CLEAN_GROUND_Z2},
    };

    // DELIBERATELY NOT LISTED: VSNavComputerAssemblyE2ETest, which builds at (7200, 80, 7200).
    // Measured 2026-08-14: the surface at its own build column is y=90, so that fixture is
    // assembled ten blocks inside a hill — and the test is GREEN, because it only builds, scans and
    // links; nothing about it flies, stands or falls. Adding it here asserted a contract it does not
    // have, and the row failed while the test it was supposed to protect passed. A fixture belongs
    // in this table when its MECHANIC touches the ground, not merely because it has coordinates.

    private static final Pattern RELIEF = Pattern.compile("\"relief\":(-?\\d+)");
    private static final Pattern MODE_TOP = Pattern.compile("\"modeTopY\":(-?\\d+)");
    private static final Pattern MODE_SHARE = Pattern.compile("\"modeTopShare\":([0-9.]+)");
    private static final Pattern LIQUID = Pattern.compile("\"liquidColumns\":(\\d+)");
    private static final Pattern SOLID = Pattern.compile("\"solidObstructedColumns\":(\\d+)");

    @Test
    public void everyGroundFixtureStandsOnTheSurfaceItWasCalibratedTo() throws Exception {
        for (Object[] fixture : FIXTURES) {
            assertStandable((String) fixture[0], (Integer) fixture[1], (Integer) fixture[2],
                    (Integer) fixture[3]);
        }
    }

    /**
     * Every property the fixture consumes, asserted one at a time so a failure names which one
     * moved instead of saying "the ground changed".
     */
    private void assertStandable(String name, int baseX, int surfaceY, int baseZ) throws Exception {
        int x0 = baseX + Plot.CLEAN_GROUND_FOOT_MIN;
        int z0 = baseZ + Plot.CLEAN_GROUND_FOOT_MIN;
        int x1 = baseX + Plot.CLEAN_GROUND_FOOT_MAX;
        int z1 = baseZ + Plot.CLEAN_GROUND_FOOT_MAX;
        String survey = String.join("\n", client().execute(
                "artest worldgen survey 0 " + x0 + " " + z0 + " " + x1 + " " + z1));
        String where = name + " @ (" + baseX + "," + surfaceY + "," + baseZ + "), footprint "
                + x0 + "," + z0 + ".." + x1 + "," + z1 + ": " + survey;

        assertEquals("the surface must be the height this fixture builds at, or its ship is"
                + " assembled underground (or left floating) — " + where,
                surfaceY, intField(MODE_TOP, survey));
        assertEquals("the footprint must be level — " + where, 0, intField(RELIEF, survey));
        assertEquals("every column at that one height, not most of them — " + where,
                "1.0", stringField(MODE_SHARE, survey));
        assertEquals("no liquid under the fixture — " + where, 0, intField(LIQUID, survey));
        assertEquals("nothing solid standing in the assembly volume — " + where,
                0, intField(SOLID, survey));
    }

    private static int intField(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        assertTrue("survey reply is missing " + pattern.pattern() + ": " + text, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static String stringField(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        assertTrue("survey reply is missing " + pattern.pattern() + ": " + text, m.find());
        return m.group(1);
    }
}
