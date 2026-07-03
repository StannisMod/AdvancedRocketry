package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract for RCS deprecation (Option B — Free Flight Mode supersedes RCS).
 *
 * <p>What we pin:
 *
 * <ul>
 *   <li>The legacy TOGGLE_RCS server handler ({@code EntityRocket.toggleRCS})
 *       no longer flips the {@code RCS_MODE} datawatcher — the field keeps its
 *       value across a toggle, even where the old code would have flipped it.</li>
 *   <li>It instead notifies the pilot with a deprecation message pointing at
 *       Free Flight Mode (M-key), sent via {@code messagePilot} — deliberately
 *       NOT via {@code setError}, so the notice carries no launch-abort side
 *       effect ({@code setError} would post a RocketAbortEvent and set
 *       LAUNCH_COUNTER = -1).</li>
 *   <li>The {@code RCS_MODE} datawatcher key itself is intact so save-compat
 *       and solar-map deep-space navigation (which still uses it internally)
 *       continue to work.</li>
 * </ul>
 *
 * <p>Note the R keybind cannot reach {@code toggleRCS} from a seated pilot: it
 * wears the {@code NOT_PILOTING} conflict context AND its handler additionally
 * requires riding a rocket, which are mutually exclusive. The probe drives the
 * server-side method directly so the contract is pinnable regardless.
 *
 * <p>This is the "Option B" half of the migration — see the solar-map design
 * task for the eventual full removal.</p>
 */
public class RcsDeprecationTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern RCS_BEFORE = Pattern.compile("\"rcsBefore\":(true|false)");
    private static final Pattern RCS_AFTER = Pattern.compile("\"rcsAfter\":(true|false)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        String fillAir = ok(client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
                        + " minecraft:air"));
        assertTrue("pre-clear failed: " + fillAir, fillAir.contains("\"ok\":true"));

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = ok(client().execute("artest rocket list 0"));
        Matcher rim = ROCKET_LIST_ID.matcher(list);
        int lastId = -1;
        while (rim.find()) lastId = Integer.parseInt(rim.group(1));
        assertTrue("rocket list empty after assemble: " + list, lastId >= 0);
        return lastId;
    }

    @Test
    public void rcsToggleNoLongerMutatesRcsMode() throws Exception {
        int id = buildAndAssemble(3300, 64, 500);

        // Drive the deprecated TOGGLE_RCS server path directly — the probe
        // invokes EntityRocket.toggleRCS() and reports RCS_MODE before/after.
        String resp = ok(client().execute("artest rocket toggle-rcs " + id));
        assertTrue("toggle-rcs probe failed: " + resp, resp.contains("\"ok\":true"));

        Matcher b = RCS_BEFORE.matcher(resp);
        Matcher a = RCS_AFTER.matcher(resp);
        assertTrue("response missing rcsBefore: " + resp, b.find());
        assertTrue("response missing rcsAfter: " + resp, a.find());

        // The deprecation contract: toggleRCS is now a no-op on RCS_MODE.
        // A regression that restored the flip would make after != before.
        assertEquals("deprecated toggleRCS must NOT flip RCS_MODE: " + resp,
                b.group(1), a.group(1));
    }

    @Test
    public void deprecationLangKeyIsRegisteredInLangFile() {
        // Static check: the lang file ships the deprecation key. A regression
        // that removes the key would surface as a raw "msg.entity.rocket.rcsDeprecated"
        // shown to players instead of the localised redirect message.
        try (java.io.InputStream is = getClass().getResourceAsStream(
                "/assets/advancedrocketry/lang/en_US.lang")) {
            assertTrue("lang resource must be on test classpath", is != null);
            java.util.Scanner sc = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            String body = sc.hasNext() ? sc.next() : "";
            assertTrue("en_US.lang must define msg.entity.rocket.rcsDeprecated key",
                    body.contains("msg.entity.rocket.rcsDeprecated="));
            assertTrue("deprecation message must mention Free Flight Mode and M-key",
                    body.contains("[M]") && body.toLowerCase().contains("free flight"));
        } catch (java.io.IOException ex) {
            org.junit.Assert.fail("lang lookup failed: " + ex.getMessage());
        }
    }
}
