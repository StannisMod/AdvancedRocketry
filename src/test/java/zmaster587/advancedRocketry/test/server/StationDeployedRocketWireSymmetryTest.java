package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * C059 — wire write/read symmetry of
 * {@code EntityStationDeployedRocket}.
 *
 * <p><b>Contract</b>: {@code readDataFromNetwork} must consume exactly the
 * bytes {@code writeDataToNetwork} emitted — zero trailing bytes after a full
 * round-trip. Pinned for a representative inherited id ({@code TURNUPDATE})
 * plus the subclass's own {@code MENU_CHANGE}; the fix makes write a
 * structural mirror of read, so the dispatch-level property holds for every
 * id. Asymmetry means every inherited sub-packet
 * sent for a station-deployed rocket carries duplicate payload on the wire
 * (doubled bandwidth today; silent corruption the moment any field is appended
 * after the payload or a stricter codec is used).</p>
 *
 * <p>Driven via the {@code artest rocket wire-symmetry} probe, which
 * round-trips a throwaway (never spawned) {@code EntityStationDeployedRocket}
 * through the real write/read pair. {@code TURNUPDATE} is the probe default —
 * a self-contained inherited sub-packet (4 plain booleans, no external
 * state). The invariant pinned is {@code trailing == 0} / {@code written ==
 * read}, never a literal byte count.</p>
 *
 * <p>Repro history: pre-fix this test pinned the wrong behaviour (the writer
 * emitted the inherited payload twice while the reader consumed it once,
 * leaving {@code trailing == written - read > 0}); flipped to the corrected
 * contract with the C059 fix (Path B - the caller drops it).</p>
 */
public class StationDeployedRocketWireSymmetryTest extends AbstractSharedServerTest {

    private static final Pattern WRITTEN = Pattern.compile("\"written\":(-?\\d+)");
    private static final Pattern READ = Pattern.compile("\"read\":(-?\\d+)");
    private static final Pattern TRAILING = Pattern.compile("\"trailing\":(-?\\d+)");

    @Test
    public void inheritedSubPacketRoundTripsSymmetrically() throws Exception {
        String resp = join(client().execute("artest rocket wire-symmetry TURNUPDATE"));
        assertTrue("wire-symmetry probe errored: " + resp, resp.contains("\"ok\":true"));

        int written = intOf(WRITTEN, resp);
        int read = intOf(READ, resp);
        assertTrue("sanity: TURNUPDATE must write a non-empty payload, got " + resp,
                written > 0);

        // Contract: the reader consumes exactly what the writer emitted — no
        // duplicated payload, no trailing bytes.
        assertEquals("write/read must be byte-symmetric (written == read): " + resp,
                written, read);
        assertEquals("no trailing bytes may remain after a full round-trip: " + resp,
                0, intOf(TRAILING, resp));
    }

    @Test
    public void menuChangePacketRoundTripsSymmetrically() throws Exception {
        // MENU_CHANGE is the subclass's own payload (gasId short) and is
        // symmetric both pre- and post-fix — a regression guard that the fix
        // must not disturb the gas-selection path.
        String resp = join(client().execute("artest rocket wire-symmetry MENU_CHANGE"));
        assertTrue("wire-symmetry probe errored: " + resp, resp.contains("\"ok\":true"));
        assertTrue("sanity: MENU_CHANGE must carry the gasId payload on the wire, got "
                + resp, intOf(WRITTEN, resp) > 0);
        assertEquals("MENU_CHANGE must round-trip with zero trailing bytes: " + resp,
                0, intOf(TRAILING, resp));
    }

    private static int intOf(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertTrue("probe response missing " + pattern.pattern() + ": " + text,
                matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }
}
