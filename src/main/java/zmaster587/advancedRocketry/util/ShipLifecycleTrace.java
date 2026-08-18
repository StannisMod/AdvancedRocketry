package zmaster587.advancedRocketry.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import zmaster587.advancedRocketry.api.event.ShipLifecycleEvent;

/**
 * What the ship-was-named announcement actually said, kept so a test can check it.
 *
 * <p>The property under test is an EDGE — "this fired exactly once, for this ship, with this cause" —
 * and an edge cannot be read off the world afterwards: by the time anything asks, the ship is simply
 * named, which is what it would look like whether the announcement fired once, three times, or not at
 * all. So the announcements themselves are recorded.</p>
 *
 * <h2>Keyed by the ship's own identity</h2>
 *
 * <p>Per-ship counts are keyed on the physics identity carried by the event, never on which ship was
 * nearest a point or which one happened to move last. A scenario with two craft in one world — a
 * crossing that lands beside a parked hull is exactly that — would otherwise attribute one ship's
 * edge to the other and read as a plausible pass.</p>
 *
 * <p>The durable id is recorded beside it because the two answer different questions: the physics
 * identity names a REGISTRATION, and a craft that crosses gets a new one, while the durable id names
 * the VESSEL and survives. A consumer that must not act twice for one vessel is checked against the
 * second, and a readout carrying only the first could not tell whether it did.</p>
 *
 * <h2>Every field in every state</h2>
 *
 * <p>A ship nothing has been recorded for reports zeroes, not a missing object: "nothing happened
 * here" is usually the interesting answer, and an instrument that expresses it by changing its own
 * shape breaks the consumer that came to read it.</p>
 *
 * <p><b>Bounded.</b> The per-ship table evicts its oldest entry past a cap and the timeline is capped
 * too, so a long session cannot grow this without limit. Eviction is counted and reported, so a
 * reading taken after the fact can say whether it is looking at the whole history or at the tail of
 * one.</p>
 */
public final class ShipLifecycleTrace {

    private ShipLifecycleTrace() {}

    /** Ships remembered at once. Well past any scenario; the cap exists so a session cannot leak. */
    private static final int MAX_SHIPS = 256;

    /** Announcements remembered in the timeline. */
    private static final int MAX_TIMELINE = 512;

    /** Per-ship counts, one slot per {@link ShipLifecycleEvent.Cause}, oldest ship evicted first. */
    private static final Map<UUID, int[]> PER_SHIP =
            new LinkedHashMap<UUID, int[]>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, int[]> eldest) {
                    boolean evict = size() > MAX_SHIPS;
                    if (evict) {
                        shipsEvicted++;
                    }
                    return evict;
                }
            };

    /** The last durable id seen for each ship, so a readout can say which vessel a registration was. */
    private static final Map<UUID, UUID> DURABLE_OF = new LinkedHashMap<>();

    /** The announcements in the order they were made. */
    private static final List<String> TIMELINE = new ArrayList<>();

    /** Global count per cause — proves the path ran at all, independent of any one ship. */
    private static final int[] TOTALS = new int[ShipLifecycleEvent.Cause.values().length];

    private static int shipsEvicted;
    private static int timelineDropped;

    /**
     * The Forge subscriber, registered for the whole run rather than only under the test flag: the
     * counters are a handful of increments per ship lifetime, and a recorder that is only switched on
     * when somebody expects an event cannot answer the question that matters — whether one fired when
     * nobody was looking.
     */
    public static final class Hooks {
        @SubscribeEvent
        public void onShipLifecycle(ShipLifecycleEvent event) {
            record(event.shipUuid, event.durableId, event.cause,
                    event.world == null ? Integer.MIN_VALUE : event.world.provider.getDimension());
        }
    }

    static synchronized void record(UUID shipUuid, UUID durableId, ShipLifecycleEvent.Cause cause,
                                    int dimension) {
        if (shipUuid == null || cause == null) {
            return;
        }
        TOTALS[cause.ordinal()]++;
        int[] counts = PER_SHIP.get(shipUuid);
        if (counts == null) {
            counts = new int[ShipLifecycleEvent.Cause.values().length];
            PER_SHIP.put(shipUuid, counts);
        }
        counts[cause.ordinal()]++;
        if (durableId != null) {
            DURABLE_OF.put(shipUuid, durableId);
        }
        if (TIMELINE.size() >= MAX_TIMELINE) {
            TIMELINE.remove(0);
            timelineDropped++;
        }
        TIMELINE.add(cause + "@dim" + dimension + ":" + shipUuid
                + (durableId == null ? "" : "/" + durableId));
    }

    /** Forget everything, so a test leg starts against an empty recorder. */
    public static synchronized void reset() {
        PER_SHIP.clear();
        DURABLE_OF.clear();
        TIMELINE.clear();
        java.util.Arrays.fill(TOTALS, 0);
        shipsEvicted = 0;
        timelineDropped = 0;
    }

    /**
     * What was announced for the ship carrying {@code shipUuid}, as a JSON object body (no braces).
     *
     * <p>A ship with no announcements answers with the same fields at zero, and says so through
     * {@code seen:false} rather than by leaving anything out.</p>
     */
    public static synchronized String summaryOf(UUID shipUuid) {
        int[] counts = shipUuid == null ? null : PER_SHIP.get(shipUuid);
        UUID durable = shipUuid == null ? null : DURABLE_OF.get(shipUuid);
        StringBuilder out = new StringBuilder();
        out.append("\"ship\":\"").append(shipUuid).append('"');
        out.append(",\"durableId\":")
                .append(durable == null ? "null" : "\"" + durable + "\"");
        out.append(",\"seen\":").append(counts != null);
        for (ShipLifecycleEvent.Cause cause : ShipLifecycleEvent.Cause.values()) {
            out.append(",\"").append(cause.name().toLowerCase(java.util.Locale.ROOT)).append("\":")
                    .append(counts == null ? 0 : counts[cause.ordinal()]);
        }
        out.append(",\"named\":").append(counts == null ? 0 : namedTotal(counts));
        out.append(",\"unnamed\":").append(counts == null ? 0 : unnamedTotal(counts));
        return out.toString();
    }

    /**
     * The whole recorder: per-cause totals over every ship, how many ships are being remembered, and
     * the timeline. Reported beside a per-ship summary so a zero there can be told apart from a
     * recorder that never received anything at all.
     */
    public static synchronized String summary() {
        StringBuilder out = new StringBuilder();
        out.append("\"ships\":").append(PER_SHIP.size());
        out.append(",\"shipsEvicted\":").append(shipsEvicted);
        out.append(",\"timelineDropped\":").append(timelineDropped);
        for (ShipLifecycleEvent.Cause cause : ShipLifecycleEvent.Cause.values()) {
            out.append(",\"total_").append(cause.name().toLowerCase(java.util.Locale.ROOT))
                    .append("\":").append(TOTALS[cause.ordinal()]);
        }
        out.append(",\"timeline\":[");
        for (int i = 0; i < TIMELINE.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(TIMELINE.get(i)).append('"');
        }
        out.append(']');
        return out.toString();
    }

    private static int namedTotal(int[] counts) {
        return counts[ShipLifecycleEvent.Cause.ASSEMBLED.ordinal()]
                + counts[ShipLifecycleEvent.Cause.PASTED.ordinal()]
                + counts[ShipLifecycleEvent.Cause.LOADED.ordinal()];
    }

    private static int unnamedTotal(int[] counts) {
        return counts[ShipLifecycleEvent.Cause.UNLOADED.ordinal()]
                + counts[ShipLifecycleEvent.Cause.DESTROYED.ordinal()];
    }
}
