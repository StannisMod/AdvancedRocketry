package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ledger #143's own repro, at the server tier, against the real universe registry: <b>a body does not
 * leave its cell because time passed.</b>
 *
 * <p>The measured defect was a pilot sitting still in a cell that held six bodies and watching all six
 * disappear 84 seconds later. The cause was that a body's address was re-derived from its LIVE orbital
 * angle on every query, so the address moved with the world clock and membership of a cell expired
 * while its occupant was still there. This ages the universe by roughly eleven and a half real-time
 * days and asks the registry the same question twice.</p>
 *
 * <h2>Why this is not satisfiable by a universe that does not move</h2>
 * A name that never changes is trivially still the same name if nothing else changed either, so the
 * invariance below would mean nothing on its own. The control is the cell's FRAME: {@code artest space
 * frame} reports where the cell actually IS at the current clock &mdash; a cell rides the body it is
 * named for, so its origin is that body's own position at the tick you ask &mdash; and that number
 * must have moved a long way between the two samples. One assertion says the identity held; the other
 * says there was something for it to hold against. The control is asserted FIRST, so a frozen universe
 * fails loudly rather than passing quietly.
 *
 * <h2>Why the clock is set rather than ticked</h2>
 * The orbital law is evaluated against the overworld's TOTAL world time, and {@code /time set} moves
 * the day-cycle time and leaves total time alone. Twenty million real ticks is not an option, so
 * {@code artest space set-clock} writes the counter the production code reads &mdash; and puts it back
 * afterwards, because this server is shared with every other test in the fork and an aged clock is
 * exactly the kind of leaked state that produces a failure three classes later.
 *
 * <p>Nothing else is synthetic: the bodies are the shipped world's own authored system, the query is
 * the production registry's, and the frame reading comes from the production {@code CellFrames}
 * lookup. No Valkyrien Skies and no ship &mdash; the subject is what the registry answers about a
 * cell, which is upstream of everything a ship does with it.</p>
 */
public class ParkedShipKeepsItsBodiesE2ETest extends AbstractSharedServerTest {

    /** The dimension whose cell is watched. Dim 0 is the overworld body: always present, always authored. */
    private static final int WATCHED_DIM = 0;

    /**
     * How far to age the universe: twenty million ticks, ~11.6 real days at 20 tps. The measured
     * defect emptied a cell in 84 SECONDS, so this is four orders of magnitude past the failure it
     * guards, and well past a full orbit of the inner bodies.
     */
    private static final long AGE_TICKS = 20_000_000L;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void aBodyStaysInItsOwnCellAcrossAVeryLongDwell() throws Exception {
        // Where the registry says the body is, and what its cell holds — as of now.
        String cellKey = dimCell(exec("artest space cell-info 0 0 0 " + WATCHED_DIM));
        String[] sectors = cellKey.split("_");
        assertEquals("a cell key is a sector triple: " + cellKey, 3, sectors.length);
        String cellArgs = sectors[0] + " " + sectors[1] + " " + sectors[2];

        String before = exec("artest space cell-info " + cellArgs + " " + WATCHED_DIM);
        int bodiesBefore = jsonInt(before, "bodiesAt");
        assertTrue("the fixture must actually have a body in this cell to lose: " + before,
                bodiesBefore > 0);
        assertTrue("...and it must be the watched dimension: " + before,
                before.contains("\"dim\":" + WATCHED_DIM + ",\"kind\""));

        String frameBefore = exec("artest space frame " + cellArgs);
        long originBefore = jsonLong(frameBefore, "originX");
        long clockBefore = jsonLong(frameBefore, "clock");

        String after;
        String frameAfter;
        String reDerived;
        try {
            String set = exec("artest space set-clock " + (clockBefore + AGE_TICKS));
            assertTrue("the clock must move: " + set, set.contains("\"ok\":true"));
            assertEquals("the space subsystem must read the clock that was set: " + set,
                    clockBefore + AGE_TICKS, jsonLong(set, "spaceClock"));

            after = exec("artest space cell-info " + cellArgs + " " + WATCHED_DIM);
            frameAfter = exec("artest space frame " + cellArgs);

            // Now take away the SECOND reason the name could be stable. A recorded name wins over
            // any derivation, so up to this line the clause is satisfied by the store alone and says
            // nothing about the derivation underneath it — measured: with the derivation deliberately
            // re-pointed at the live clock, everything above still passed. Forgetting the record
            // forces the next query to derive, at the aged clock, and that answer must be the same
            // cell: a name is derived from the layout alone — the system anchor and the body's own
            // authored orbital elements — and never from the clock.
            String forget = exec("artest space forget-name " + WATCHED_DIM);
            assertTrue("the registry must have been holding a recorded name to forget: " + forget,
                    forget.contains("\"held\":true"));
            reDerived = exec("artest space cell-info " + cellArgs + " " + WATCHED_DIM);
        } finally {
            // Hand the shared server back the clock it had. A test that ages a world by eleven days
            // and leaves it there is a test that breaks somebody else's.
            exec("artest space set-clock " + clockBefore);
        }

        // THE CONTROL.
        long originAfter = jsonLong(frameAfter, "originX");
        assertNotEquals("the cell's FRAME must have moved over " + AGE_TICKS + " ticks, or the"
                + " invariance below is a statement about a universe that stands still; before="
                + frameBefore + " after=" + frameAfter, originBefore, originAfter);
        assertTrue("...and moved FAR — a cell is 4,000,000 blocks wide, so a smaller move would not"
                        + " even have left the cell under the old derivation; moved="
                        + Math.abs(originAfter - originBefore),
                Math.abs(originAfter - originBefore) > 4_000_000L);

        // THE CLAUSE. Same cell key, same occupants, same count.
        assertEquals("a body's own cell may not change because time passed (ledger #143): " + after,
                cellKey, dimCell(after));
        assertEquals("...and the cell must still report the same number of bodies standing in it: "
                + before + " -> " + after, bodiesBefore, jsonInt(after, "bodiesAt"));
        assertTrue("...including the watched dimension itself: " + after,
                after.contains("\"dim\":" + WATCHED_DIM + ",\"kind\""));

        // And the same again with the store's protection REMOVED: a name derived fresh at the aged
        // clock is the same name. This is the leg that fails if the derivation ever reads a clock.
        assertEquals("a name derived fresh, eleven days later, must still be the same cell — the"
                + " layout decides a name, from the system anchor and the authored orbit, and the"
                + " clock has no part in it: " + reDerived,
                cellKey, dimCell(reDerived));
        assertTrue("...and the body must still be standing in it: " + reDerived,
                reDerived.contains("\"dim\":" + WATCHED_DIM + ",\"kind\""));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String dimCell(String json) {
        Matcher m = Pattern.compile("\"dimCell\":\"([^\"]+)\"").matcher(json);
        assertTrue("probe response carries no \"dimCell\": " + json, m.find());
        return m.group(1);
    }

    private static int jsonInt(String json, String field) {
        return (int) jsonLong(json, field);
    }

    private static long jsonLong(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?\\d+)").matcher(json);
        assertTrue("probe response carries no numeric \"" + field + "\": " + json, m.find());
        return Long.parseLong(m.group(1));
    }
}
