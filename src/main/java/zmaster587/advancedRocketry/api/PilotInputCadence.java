package zmaster587.advancedRocketry.api;

/**
 * When a pilot's control packet must go out, given that the last one may not have survived.
 *
 * <h3>Why this is not simply "on change"</h3>
 *
 * <p>Send-on-change is correct only if two things hold: delivery is lossless, and the value the server
 * stored stays stored. The second does not hold. The server keeps a pilot's input in a field on the
 * flight computer's TILE INSTANCE, and a tile instance is a perishable thing — a chunk reload or a
 * re-registration replaces the object, and the field comes back null. The client, meanwhile, has
 * nothing to notice: from where it sits the key is still down and the input has not changed, so under
 * send-on-change it never speaks again and the craft flies on with no one at the controls.</p>
 *
 * <p>Measured as a symptom before it was understood: a held climb key lifted a ship for about 100
 * ticks and then the ship simply held altitude, with the key still down and the residual vertical
 * velocity oscillating about zero — a craft being HELD, not one coasting. The probe path that
 * re-sent its command every tick never showed it, which is the same fact from the other side.</p>
 *
 * <h3>The rule</h3>
 *
 * <p>A CHANGE is sent immediately, as before. A held non-idle input is re-sent every
 * {@link #REPEAT_TICKS} ticks, so the cost of any single loss is bounded by that interval instead of
 * lasting until the pilot happens to move a control. An IDLE input is never repeated: losing "no
 * input" costs nothing, because the absence of input is what the server falls back to anyway.</p>
 *
 * <p>The phase is derived from the seat, not shared: a fixed {@code tick % N} would stack every pilot
 * on a server into the same tick, which is how a keep-alive turns into a burst. Two seats therefore
 * repeat on different ticks even when their pilots pressed at the same instant.</p>
 */
public final class PilotInputCadence {

    /**
     * How often a held input is re-asserted, in ticks — one second at 20 tps.
     *
     * <p>Chosen in the units of the defect: this is the worst-case time a craft can fly with a
     * command the server has forgotten. At 20 ticks the pilot may feel a stutter; the loss it
     * replaces lasted until he released the key, which in the measured case was the rest of the
     * flight. One packet per second per seated pilot is negligible beside the per-tick pose stream
     * the same ship already sends.</p>
     */
    public static final int REPEAT_TICKS = 20;

    private PilotInputCadence() { }

    /**
     * Whether this tick must put {@code input} on the wire.
     *
     * @param input     what the pilot is commanding right now; {@code null} is never sent
     * @param lastSent  the last input actually sent, or {@code null} if none has been
     * @param tick      a monotonically increasing client tick counter
     * @param seatPhase a per-seat phase offset (see the class doc); any stable integer derived from
     *                  the seat's identity will do
     */
    public static boolean shouldSend(FreeFlightInput input, FreeFlightInput lastSent,
                                     long tick, int seatPhase) {
        if (input == null) {
            return false;
        }
        if (!input.equals(lastSent)) {
            return true;
        }
        if (input.isIdle()) {
            return false;
        }
        return Math.floorMod(tick - seatPhase, REPEAT_TICKS) == 0L;
    }

    /**
     * A stable phase in {@code [0, REPEAT_TICKS)} for a seat at {@code (x,y,z)}. Deliberately not a
     * hash of the whole position object: two seats a block apart must land on different ticks, and
     * the sum of the coordinates does exactly that while staying trivially reproducible in a test.
     */
    public static int phaseOfSeat(int x, int y, int z) {
        return Math.floorMod(x + y + z, REPEAT_TICKS);
    }
}
