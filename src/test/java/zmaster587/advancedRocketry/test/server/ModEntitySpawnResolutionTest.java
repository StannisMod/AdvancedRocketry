package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every modded entity this jar registers must arrive at a client as ITSELF.
 *
 * <p>A modded entity is not spawned to the client by registry name: the server writes
 * {@code (modId, per-mod network id)} into the FML spawn message and the receiving client resolves
 * that pair back to a class by taking the FIRST registration under that mod carrying the id. Our
 * jar hosts more than one code base under ONE mod container — the physics engine and the shield
 * mod are vendored in rather than loaded as separate mods — so their entity numbering shares a
 * single space with this mod's own. Two entities that pick the same number are indistinguishable
 * on the wire, and the loser is built as the winner's class and then fed the sender's
 * data-watcher slots by index, with no type check on the path: the first reader of a slot casts
 * and takes the whole client down.</p>
 *
 * <p>The probe performs exactly the client's resolution for every registered entity and reports
 * the ones that come back as a different registration. This test is the numbering's only guard —
 * nothing in Forge rejects a duplicate, and the failure is silent until a player is near one of
 * the entities involved.</p>
 */
public class ModEntitySpawnResolutionTest extends AbstractHeadlessServerTest {

    private static final Pattern CHECKED = Pattern.compile("\"checked\":(\\d+)");
    private static final Pattern MISMATCH_COUNT = Pattern.compile("\"mismatchCount\":(\\d+)");

    /**
     * The floor below which the scan is not evidence. This jar registers ten entities of its own
     * before either vendored code base adds one, so a scan that saw fewer than that examined a
     * registry that had not finished loading and its clean result means nothing.
     */
    private static final int MIN_SCANNED = 10;

    @Test
    public void everyModdedEntityResolvesBackToItself() throws Exception {
        String report = String.join("\n", client().execute("artest entity registry"));
        assertTrue("the registry probe must answer: " + report, report.contains("\"ok\":true"));

        Matcher scanned = CHECKED.matcher(report);
        assertTrue("the probe must report how many entities it examined: " + report, scanned.find());
        int checked = Integer.parseInt(scanned.group(1));
        assertTrue("the scan must cover at least this mod's own entities (saw " + checked
                + ", expected >= " + MIN_SCANNED + "): " + report, checked >= MIN_SCANNED);

        Matcher mismatches = MISMATCH_COUNT.matcher(report);
        assertTrue("the probe must report a mismatch count: " + report, mismatches.find());
        assertEquals("every registered entity must resolve back to itself through the spawn path;"
                        + " a listed mismatch is an entity that arrives at the client as another"
                        + " class and corrupts its synced data: " + report,
                0, Integer.parseInt(mismatches.group(1)));
    }
}
