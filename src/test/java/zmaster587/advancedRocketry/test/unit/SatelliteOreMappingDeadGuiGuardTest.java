package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.satellite.SatelliteOreMapping;

import static org.junit.Assert.assertFalse;

/**
 * Regression guard (bug-report-workflow, finding L1) for the neutralised
 * {@code SatelliteOreMapping.performAction}.
 *
 * <p>The override formerly did {@code player.openGui(AdvancedRocketry.instance, 100,
 * ...)} — GUI id 100 is mapped by no GuiHandler ({@code GuiHandler.java:50-52}), so
 * the call either no-opped or, on the terminal auto-download path (which passes a
 * {@code null} player, {@code TileSatelliteTerminal.java:144}), NPE'd. Ore mapping is
 * driven by the handheld {@code ItemOreScanner} (which opens the real
 * {@code OreMappingSatellite} GUI), never by a terminal action, so the override had
 * no legitimate job. The fix makes it a no-op returning {@code false}; the orphaned
 * {@code ItemOreScanner.interactSatellite} (its only would-be caller) was deleted.</p>
 *
 * <p>This pins the corrected contract: invoking {@code performAction} on an
 * ore-mapping satellite returns {@code false} and does not open the dead GUI — with
 * a {@code null} player, the former body would have NPE'd on {@code player.openGui}.
 * Same {@code performAction(null, null, null)}-returns-false shape used by
 * {@code MissionResourceCollectionContractTest}. Pure unit tier: the no-arg ctor
 * only runs {@code SatelliteBase()} (no {@code Biome.getBiome} like the biome/weather
 * satellites), so it needs no Minecraft Bootstrap.</p>
 */
public class SatelliteOreMappingDeadGuiGuardTest {

    @Test
    public void performActionIsNoOpAndDoesNotOpenDeadGui() {
        assertFalse("SatelliteOreMapping.performAction must be a no-op returning false — it no longer opens the "
                        + "unmapped GUI id 100. If this throws (e.g. NPE on the null player the terminal passes) or "
                        + "returns true, finding L1's dead terminal-action path has been reintroduced.",
                new SatelliteOreMapping().performAction(null, null, null));
    }
}
