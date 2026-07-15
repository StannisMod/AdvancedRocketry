package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.satellite.SatelliteData;
import zmaster587.advancedRocketry.satellite.SatelliteOreMapping;

import static org.junit.Assert.assertFalse;

/**
 * Finding L1 — {@code SatelliteOreMapping.performAction} opens {@code openGui(
 * AdvancedRocketry.instance, 100, …)}, an id no GuiHandler maps
 * ({@code GuiHandler.java:50-52}). It cannot be reproduced through gameplay: no
 * honest path reaches {@code SatelliteOreMapping.performAction}. Its only direct
 * caller ({@code ItemOreScanner.interactSatellite}) has zero callers; the
 * player-reachable {@code TileSatelliteTerminal} invokes {@code performAction}
 * only for satellites that are {@code instanceof SatelliteData}
 * ({@code TileSatelliteTerminal.java:85,228}), and {@code SatelliteOreMapping}
 * extends {@code SatelliteBase} directly, not {@code SatelliteData}.
 *
 * <p>So L1 is dead/unreachable code — there is nothing to reproduce (per the
 * repro-first SOP, a "repro" that force-called {@code performAction} would be a
 * forbidden fake). This test instead pins the class-hierarchy invariant that
 * KEEPS it unreachable: if {@code SatelliteOreMapping} ever became a
 * {@code SatelliteData}, the terminal would bind it and the dead id-100 open
 * would go live. The honest remediation is to delete the dead
 * {@code performAction} override; this guard documents why until then.</p>
 */
public class SatelliteOreMappingDeadGuiGuardTest {

    @Test
    public void oreMappingIsNotSatelliteData_soTerminalNeverReachesDeadId100OpenGui() {
        assertFalse("SatelliteOreMapping must not be a SatelliteData; otherwise "
                        + "TileSatelliteTerminal would bind it via its `instanceof SatelliteData` "
                        + "gate and reach the unreachable/no-op id-100 openGui "
                        + "(SatelliteOreMapping.java:69). If this fails, finding L1 has gone live.",
                SatelliteData.class.isAssignableFrom(SatelliteOreMapping.class));
    }
}
