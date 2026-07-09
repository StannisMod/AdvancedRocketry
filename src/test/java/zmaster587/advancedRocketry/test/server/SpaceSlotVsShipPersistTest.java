package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Layer-1b: does a VS ship's DATA survive our synchronous slot unload/reload?
 *
 * <p>Assembles a small VS ship in a pool slot, confirms it enters VS's per-world queryable ship
 * registry, then unloads the slot (which must save VS's ship data) and reloads it bound to the same
 * cell — the ship must still be in the registry. This proves our {@code setWorld(null)} unload +
 * reinit does not nuke VS state and that VS's per-world save round-trips through it (the risky
 * physics-thread interaction). The full pilotable-after-rebind check needs a client and lives at the
 * client tier. Run with {@code -PwithVS}; skipped otherwise.</p>
 */
public class SpaceSlotVsShipPersistTest extends AbstractSharedServerTest {

    private static final Pattern SLOT = Pattern.compile("\"slot\":(-?\\d+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern COUNT_AFTER = Pattern.compile("\"countAfterReload\":(-?\\d+)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** VS assembly is queued on the physics thread; the dedicated server ticks in its own JVM, so a
     *  short test-JVM sleep lets VS process the spawn queue. */
    private int pollCount(int dim, int want, int tries) throws Exception {
        int c = -1;
        for (int i = 0; i < tries; i++) {
            Thread.sleep(500);
            Matcher m = COUNT.matcher(exec("artest space vs-count " + dim));
            c = m.find() ? Integer.parseInt(m.group(1)) : -1;
            if (c >= want) return c;
        }
        return c;
    }

    @Test
    public void vsShipDataSurvivesSlotUnloadReload() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        String asm = exec("artest space vs-assemble deep");
        Matcher m = SLOT.matcher(asm);
        assertTrue("vs-assemble must report a slot dim: " + asm, m.find());
        int slot = Integer.parseInt(m.group(1));

        int before = pollCount(slot, 1, 20);
        assertTrue("a VS ship must enter the pool world's registry (count=" + before + ")", before >= 1);

        // Synchronous unload (saves VS ship data) + reload the same cell; the count is read inside
        // the same probe call (before any auto-unload), so it is not masked by the keepLoaded=false
        // world unloading between calls.
        String reload = exec("artest space reload " + slot + " deep");
        assertTrue("slot must reload after a VS-ship unload: " + reload, reload.contains("\"present\":true"));
        Matcher rm = COUNT_AFTER.matcher(reload);
        int after = rm.find() ? Integer.parseInt(rm.group(1)) : -99;
        assertTrue("the VS ship's data must survive the slot unload/reload: " + reload, after >= 1);
    }
}
