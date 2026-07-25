package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * Registration smoke for the vendored Advanced Force Field System (the shield subsystem
 * folded into AR's single mod container).
 *
 * <p>The merge's real risk is <em>silent</em>: if the guest's
 * {@code @Mod.EventBusSubscriber} no longer resolves to a loaded mod container, its
 * {@code RegistryEvent.Register} handlers never fire and the AFFS blocks are simply
 * absent — the server still boots green. This test refuses that trap: a block that is
 * not in the registry cannot be placed, so a successful {@code /artest place} of each
 * AFFS block is direct proof it registered under AR's container with the {@code affs:}
 * domain preserved.</p>
 */
public class AffsVendorSmokeTest extends AbstractSharedServerTest {

    @Test
    public void vendoredAffsBlocksAreRegisteredAndPlaceable() throws Exception {
        int y = 64, z = 800, baseX = 800;

        // The AFFS shield core. projected_field (the legacy proxy) is deliberately excluded —
        // it is scheduled to be dropped in the P1 trims and is not part of the live field.
        Map<String, Integer> blocks = new LinkedHashMap<>();
        blocks.put("affs:field_generator", 0);
        blocks.put("affs:shield_generator", 2);
        blocks.put("affs:shield_accumulator", 3);
        blocks.put("affs:shield_cable", 4);
        blocks.put("affs:shield_console", 6);
        blocks.put("affs:admin_energy_source", 8);
        blocks.put("affs:contour_frame", 10);
        blocks.put("affs:contour_injector", 12);

        StringBuilder failures = new StringBuilder();
        for (Map.Entry<String, Integer> e : blocks.entrySet()) {
            int x = baseX + e.getValue();
            String resp = join(client().execute(
                    "artest place 0 " + x + " " + y + " " + z + " " + e.getKey()));
            if (!resp.contains("\"placed\":true")) {
                failures.append(e.getKey()).append(" -> ").append(resp).append('\n');
            }
        }

        assertTrue("vendored AFFS blocks failed to register/place (the @Mod merge dropped them "
                + "silently if these are 'unknown block'):\n" + failures, failures.length() == 0);
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
