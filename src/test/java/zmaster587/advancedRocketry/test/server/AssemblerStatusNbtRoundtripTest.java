package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 3 — C033 (FIND-012 / FIND-214 / FIND-192) reproduction +
 * regression guard.
 *
 * <p>Contract under test: {@link zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine}
 * persists its scan/build status as a bare {@code ErrorCodes.ordinal()} and
 * decodes it with {@code ErrorCodes.values()[nbt.getInteger("status")]}. Two
 * defects on the read path are player-visible on load:</p>
 * <ul>
 *   <li><b>missing key → SUCCESS</b>: an absent {@code "status"} tag decodes to
 *       {@code getInteger}'s default 0, and {@code ErrorCodes[0] == SUCCESS} — a
 *       legacy/imported save loads a spurious SUCCESS verdict rather than the
 *       neutral idle state;</li>
 *   <li><b>out-of-range → crash</b>: a corrupt or mod-downgrade ordinal
 *       {@code >= values().length} throws {@code ArrayIndexOutOfBoundsException}
 *       inside {@code readFromNBT} → tile/chunk load abort.</li>
 * </ul>
 *
 * <p>The probe writes the live tile to NBT, mutates the {@code "status"} tag to
 * simulate those saves, reads it back into a fresh peer tile, and reports the
 * decoded status / any throwable. Post-fix a missing key defaults to
 * {@code UNSCANNED} and an out-of-range ordinal is clamped to {@code UNSCANNED}
 * without throwing; the wire/save format (an ordinal int) is unchanged.</p>
 */
public class AssemblerStatusNbtRoundtripTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern THREW = Pattern.compile("\"threw\":\"([^\"]*)\"");
    private static final Pattern PEER_STATUS = Pattern.compile("\"peerStatus\":\"([^\"]*)\"");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int[] placeAssembler(int baseX) throws Exception {
        int baseY = 64;
        int baseZ = 740;
        ok(client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
                        + " minecraft:air"));
        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return new int[]{
                Integer.parseInt(bp.group(1)),
                Integer.parseInt(bp.group(2)),
                Integer.parseInt(bp.group(3))};
    }

    private static String field(Pattern p, String src, String name) {
        Matcher m = p.matcher(src);
        assertTrue(name + " missing in: " + src, m.find());
        return m.group(1);
    }

    /** A save with no persisted status must load the neutral idle verdict
     *  (UNSCANNED), never a spurious SUCCESS. */
    @Test
    public void missingStatusKeyDecodesToUnscannedNotSuccess() throws Exception {
        int[] pos = placeAssembler(9600);
        String resp = ok(client().execute("artest assembler nbt-roundtrip 0 "
                + pos[0] + " " + pos[1] + " " + pos[2] + " dropStatus"));
        assertTrue("nbt-roundtrip failed: " + resp, resp.contains("\"ok\":true"));

        assertTrue("no throw expected for a missing status key: " + resp,
                "null".equals(field(THREW, resp, "threw")));
        String peer = field(PEER_STATUS, resp, "peerStatus");
        assertTrue("a missing \"status\" key must decode to UNSCANNED, not the "
                        + "ordinal-0 SUCCESS default (C033); got " + peer + ": " + resp,
                "UNSCANNED".equals(peer));
    }

    /** An out-of-range persisted ordinal must clamp to UNSCANNED, not throw
     *  AIOOBE inside readFromNBT (which would abort tile/chunk load). */
    @Test
    public void outOfRangeStatusOrdinalDoesNotCrashLoad() throws Exception {
        int[] pos = placeAssembler(9700);
        String resp = ok(client().execute("artest assembler nbt-roundtrip 0 "
                + pos[0] + " " + pos[1] + " " + pos[2] + " setStatus=999"));
        assertTrue("nbt-roundtrip failed: " + resp, resp.contains("\"ok\":true"));

        assertTrue("an out-of-range status ordinal must not throw on load "
                        + "(no ArrayIndexOutOfBoundsException) (C033): " + resp,
                "null".equals(field(THREW, resp, "threw")));
        String peer = field(PEER_STATUS, resp, "peerStatus");
        assertTrue("an out-of-range ordinal must clamp to UNSCANNED; got "
                        + peer + ": " + resp,
                "UNSCANNED".equals(peer));
    }

    /** Counter-test: an unmutated round-trip must still decode a valid status
     *  without throwing — the read guard must not break the happy path. */
    @Test
    public void plainRoundtripPreservesStatus() throws Exception {
        int[] pos = placeAssembler(9800);
        String resp = ok(client().execute("artest assembler nbt-roundtrip 0 "
                + pos[0] + " " + pos[1] + " " + pos[2]));
        assertTrue("nbt-roundtrip failed: " + resp, resp.contains("\"ok\":true"));

        assertTrue("plain round-trip must not throw: " + resp,
                "null".equals(field(THREW, resp, "threw")));
        String peer = field(PEER_STATUS, resp, "peerStatus");
        assertTrue("plain round-trip must decode a non-empty status: " + resp,
                peer != null && !peer.isEmpty());
    }
}
