package zmaster587.advancedRocketry.projectile;

import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration;

import java.util.ArrayList;
import java.util.List;

/**
 * What a shot's step DECIDED, tick by tick — a recorder, not a mechanism.
 *
 * <h3>Why this exists</h3>
 * <p>A round that crosses a wall spending nothing and a round whose impact was refused look exactly
 * the same from outside: the wall is unmarked and the budget is whole either way. The difference is
 * a single comparison inside the step — which layer answered a distance, and which one won — and
 * nothing above that comparison can see it. So the comparison says so itself, and the question stops
 * being a matter of inference.</p>
 *
 * <h3>Off unless the harness is running</h3>
 * <p>Every entry point is gated on {@link TestProbeCommandRegistration#isTestMode()}, the same flag
 * the {@code /artest} surface is registered under. In an ordinary game this class records nothing and
 * costs one boolean read per crossing.</p>
 *
 * <h3>It LEAKS across scenarios, and that is stated rather than hidden</h3>
 * <p>The ring is a single-writer diagnostic with no owner: it is written by the world tick, it
 * outlives any one test, and on a shared server it carries entries from whatever ran before. It
 * decides nothing — no production branch reads it — but a reader who forgets that will attribute
 * somebody else's round to their own. {@link #reset()} is how a scenario claims a clean instrument,
 * and every read reports {@code dropped} so a truncated history cannot pass for a quiet one.</p>
 */
public final class ShotCrossingTrace {

    /**
     * How many crossing decisions are kept. A shot lives up to its lifetime in ticks and spends at
     * least one decision per tick, so this holds a full 1200-tick flight of a single round with room
     * for the scenario around it — which matters because the interesting decision is usually the
     * FIRST one, and a ring too small to hold a whole flight throws exactly that one away.
     */
    private static final int CAPACITY = 2048;

    private static final List<String> ENTRIES = new ArrayList<String>();

    private static long queries;
    private static long structureFound;
    private static long fieldWon;
    private static long nothingFound;
    private static long dropped;
    private static long impacts;

    private ShotCrossingTrace() {
    }

    /**
     * Whether anything is recorded at all — read once, at class initialisation.
     *
     * <p>The flag is a launch property and cannot change while the game runs, and this is asked on
     * the shot hot path: once per crossing, per shot, per tick. Asking {@code System.getProperty}
     * that often would be a synchronised lookup inside the tick loop of every round in flight, paid
     * by every ordinary game to answer a question whose answer was fixed at startup. Constant, not
     * mutable state — there is nothing here to reset.</p>
     */
    private static final boolean ENABLED = TestProbeCommandRegistration.isTestMode();

    /** Whether anything is being recorded at all — read by the caller so it can skip the formatting. */
    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * One crossing decision of one shot.
     *
     * <p>{@code fieldDistance} and {@code structureDistance} are as the step itself saw them, in
     * blocks along this segment, with {@code -1} meaning "that layer answered nothing" — the same
     * value the layers use, kept rather than collapsed into a verdict, because a verdict is what is
     * in dispute. The segment is carried in full for the same reason: a decision reported without the
     * question it answered cannot be re-asked.</p>
     */
    public static synchronized void crossing(long shotId, int age, String hullAsked, Vec3d from,
                                             Vec3d to, double radius, double fieldDistance,
                                             double structureDistance, String struckBlock) {
        queries++;
        if (structureDistance >= 0.0D) {
            structureFound++;
        }
        if (fieldDistance >= 0.0D && (structureDistance < 0.0D || fieldDistance <= structureDistance)) {
            fieldWon++;
        }
        if (fieldDistance < 0.0D && structureDistance < 0.0D) {
            nothingFound++;
        }
        append("{\"id\":" + shotId + ",\"age\":" + age
                + ",\"hullAsked\":" + quoted(hullAsked)
                + ",\"fromX\":" + round(from.x) + ",\"fromY\":" + round(from.y)
                + ",\"fromZ\":" + round(from.z)
                + ",\"toX\":" + round(to.x) + ",\"toY\":" + round(to.y) + ",\"toZ\":" + round(to.z)
                + ",\"radius\":" + round(radius)
                + ",\"field\":" + round(fieldDistance)
                + ",\"structure\":" + round(structureDistance)
                + ",\"block\":" + quoted(struckBlock) + "}");
    }

    /**
     * What the damage service answered one declared impact, recorded beside the crossing that caused
     * it. The crossing and the impact are two different questions with one symptom — an unmarked wall
     * — so recording only the first leaves the second to be inferred, which is how this defect stayed
     * open.
     */
    public static synchronized void impact(long impactId, net.minecraft.util.math.BlockPos at,
                                           int budget, double reachBlocks, boolean resuming,
                                           String outcome, String stopReason, int spent, int left,
                                           double walked, int staged, int destroyed,
                                           Long rememberedAt, long now) {
        impacts++;
        append("{\"impactId\":" + impactId
                + ",\"x\":" + at.getX() + ",\"y\":" + at.getY() + ",\"z\":" + at.getZ()
                + ",\"budget\":" + budget + ",\"reach\":" + round(reachBlocks)
                + ",\"resuming\":" + resuming
                + ",\"outcome\":" + quoted(outcome) + ",\"stop\":" + quoted(stopReason)
                + ",\"spent\":" + spent + ",\"left\":" + left + ",\"walked\":" + round(walked)
                + ",\"staged\":" + staged + ",\"destroyed\":" + destroyed
                + ",\"rememberedAt\":" + (rememberedAt == null ? "null" : rememberedAt.toString())
                + ",\"now\":" + now + "}");
    }

    private static void append(String entry) {
        if (ENTRIES.size() >= CAPACITY) {
            // The OLDEST goes, and it is counted. A ring that silently forgot its first decisions
            // would answer "the wall was never seen" for a flight whose opening it no longer holds.
            ENTRIES.remove(0);
            dropped++;
        }
        ENTRIES.add(entry);
    }

    /** Forget everything, counters included: a scenario asking for an instrument of its own. */
    public static synchronized void reset() {
        ENTRIES.clear();
        queries = 0L;
        structureFound = 0L;
        fieldWon = 0L;
        nothingFound = 0L;
        dropped = 0L;
        impacts = 0L;
    }

    /**
     * The recording, optionally narrowed to one shot and capped in length.
     *
     * <p>The cap keeps the EARLIEST matching entries rather than the latest: a round that flew
     * through something did so in its first few ticks and then travelled for a thousand more, so the
     * tail of such a flight is the one stretch guaranteed to say nothing. {@code matched} is reported
     * beside them so a truncated answer announces itself.</p>
     *
     * <p>Every field is emitted in every state, zeros and an empty array included — an instrument
     * that reports "nothing here" by changing its own shape breaks the reader who came to ask exactly
     * that.</p>
     */
    public static synchronized String summaryJson(long onlyShotId, int limit) {
        String needle = onlyShotId < 0L ? null : "{\"id\":" + onlyShotId + ",";
        int matched = 0;
        StringBuilder listed = new StringBuilder();
        for (String entry : ENTRIES) {
            if (needle != null && !entry.startsWith(needle)) {
                continue;
            }
            matched++;
            if (matched <= limit) {
                if (listed.length() > 0) {
                    listed.append(',');
                }
                listed.append(entry);
            }
        }
        return "{\"enabled\":" + enabled() + ",\"queries\":" + queries
                + ",\"structureFound\":" + structureFound
                + ",\"fieldWon\":" + fieldWon
                + ",\"nothingFound\":" + nothingFound
                + ",\"dropped\":" + dropped
                + ",\"impacts\":" + impacts
                + ",\"held\":" + ENTRIES.size()
                + ",\"matched\":" + matched
                + ",\"entries\":[" + listed + "]}";
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }
}
