package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;

import static org.junit.Assert.assertFalse;

/**
 * The shared-client base for the Valkyrien Skies / tier-2 ship scenarios.
 *
 * <p>It is a separate base class and not two more commands in {@link AbstractSharedClientE2ETest}
 * because that class's reset is paid by every scenario in the client tier, and the two channels
 * below belong to ship scenarios only.</p>
 *
 * <h2>The two channels a ship scenario leaves behind</h2>
 *
 * <ol>
 *   <li><b>The player is still RIDING.</b> Nearly every scenario here ends seated on a pilot seat's
 *       dummy or captured by a deck. A passenger is not moved by {@code /tp}, so without this the
 *       shared reset's plot assertion fails naming coordinates — the symptom, not the cause — and a
 *       scenario that opens by mounting would mount a seat it is already sitting on.</li>
 *   <li><b>{@code vs permaload}.</b> The headless affordance that keeps a freshly assembled ship
 *       loaded with no player to hold it. Several scenarios switch it on and never switch it off,
 *       which hands the next scenario a world where ships never unload — and a scenario whose
 *       subject IS the unload (a reload, a client-load gate) would then silently measure the
 *       affordance instead of the product. It is reset to OFF, so a scenario that needs it SETS
 *       it.</li>
 * </ol>
 *
 * <p>Both are closed and then ASSERTED, on the same principle as the base reset: a reset nobody
 * checks is indistinguishable from no reset.</p>
 */
public abstract class AbstractSharedVsClientE2ETest extends AbstractSharedClientE2ETest {

    /**
     * Where the parking plots live for a ship class.
     *
     * <p>Ship fixtures in this tier are built on the ground along the x==z diagonal between roughly
     * 2800 and 6500, and each scenario keeps the base coordinates its green runs were taken on. The
     * plots this lane hands out are only the place the reset PARKS the player between scenarios, so
     * they are pushed well off that diagonal: a plot that contained another scenario's ship would
     * make "stay inside your plot" mean nothing.</p>
     */
    protected static final Plot.Lane SHIP_PARKING_LANE = new Plot.Lane(2000, 8000, Plot.SIZE);

    /**
     * How far from its query point a {@code vs ship-info} answer may be and still be believed to be
     * THIS scenario's ship, in blocks.
     *
     * <p>{@code vs ship-info} is a NEAREST-ship lookup ({@code VSBridge.nearestShip}) — it reports
     * whichever loaded ship is closest to the point, and until this constant existed it had no
     * distance bound at all. With one ship in the world that is exact. With several, it answers with
     * a NEIGHBOUR the moment this scenario's ship unloads or flies off, and the reply is
     * indistinguishable from a correct one: the caller gets a plausible position, attitude and
     * angular velocity belonging to a ship it never built.</p>
     *
     * <p>48 is chosen against the tier's own geometry: these classes space their fixtures <b>100
     * blocks</b> apart, so a bound below 50 can never admit a neighbour, and a ship of this
     * scenario's own that has travelled farther than 48 blocks reads {@code managed:false} — a loud
     * arrangement-shaped failure instead of a quiet wrong answer.</p>
     */
    protected static final int SHIP_QUERY_RADIUS = 48;

    @Override
    protected Plot.Lane lane() {
        return SHIP_PARKING_LANE;
    }

    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        exec("artest player dismount");
        exec("artest vs permaload false");

        // Asserted on the CLIENT's own view, and polled: the dismount is a server write and the
        // client learns it on the next update packet, so reading once would pin the round-trip
        // rather than the state.
        JsonObject riding = bot().reportRidingEntity();
        for (int waited = 0; waited < 40 && isRiding(riding); waited += 5) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
        }
        assertFalse("a ship scenario must start un-seated as the CLIENT renders it, or its own"
                + " mount step measures the previous scenario's seat — and /tp does not move a"
                + " passenger, so the plot assertion that follows would fail for the wrong reason."
                + " client reports " + riding, isRiding(riding));
        scenario().record("resetRiding", riding);
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    /** Is Valkyrien Skies on the server's classpath? Every scenario here is gated on it. */
    protected final boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }
}
