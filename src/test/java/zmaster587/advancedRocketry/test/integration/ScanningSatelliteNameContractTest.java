package zmaster587.advancedRocketry.test.integration;

import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.satellite.SatelliteComposition;
import zmaster587.advancedRocketry.satellite.SatelliteDensity;
import zmaster587.advancedRocketry.satellite.SatelliteMassScanner;
import zmaster587.advancedRocketry.satellite.SatelliteOptical;
import zmaster587.advancedRocketry.satellite.SatelliteOreMapping;
import zmaster587.advancedRocketry.satellite.SatelliteSpyTelescope;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Display-name contract for the scanning satellites.
 *
 * <p>Lives at the integration layer (not unit) because
 * {@code SatelliteBase.getName()} resolves through
 * {@code LibVulpes.proxy.getLocalizedString()}, which requires the proxy to be
 * bootstrapped (see {@link MinecraftBootstrap}). Headless, the proxy returns the
 * raw translation key — that is still enough to pin the player-visible contract
 * that every scanner has a non-null, non-empty, distinct name (a collision would
 * be a satellite-builder GUI ambiguity). The exact localized literal is a
 * lang-file concern and deliberately not pinned here.</p>
 */
public class ScanningSatelliteNameContractTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void allScanningSatellitesProduceNonEmptyDistinctNames() {
        SatelliteBase[] scanners = new SatelliteBase[] {
                new SatelliteOreMapping(),
                new SatelliteDensity(),
                new SatelliteComposition(),
                new SatelliteMassScanner(),
                new SatelliteOptical(),
                new SatelliteSpyTelescope(),
        };
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (SatelliteBase sat : scanners) {
            String name = sat.getName();
            assertNotNull(sat.getClass().getSimpleName() + ".getName() must be non-null",
                    name);
            assertFalse(sat.getClass().getSimpleName() + ".getName() must be non-empty",
                    name.isEmpty());
            assertTrue(sat.getClass().getSimpleName() + ".getName() must be unique "
                            + "across scanners (collision = satellite-builder GUI "
                            + "ambiguity); duplicate: " + name,
                    seenNames.add(name));
        }
    }
}
