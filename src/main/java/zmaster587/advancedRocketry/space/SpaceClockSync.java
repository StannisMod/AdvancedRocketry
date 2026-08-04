package zmaster587.advancedRocketry.space;

/**
 * The client's copy of the space clock.
 *
 * <p>The space clock is the SERVER's counter — the overworld's total world time. A client has no
 * access to it: the server broadcasts each world's OWN total time to the players in it, and Advanced
 * Rocketry gives every non-overworld dimension a clock that only advances while that dimension
 * ticks. So a client asking "what time is it, for the space subsystem" through any world it can see
 * gets an answer that belongs to a different clock — which is precisely the defect this class
 * exists to make unnecessary.</p>
 *
 * <p><b>Baseline plus local advance, not a packet per tick.</b> The clock is monotonic at a fixed
 * rate, so the whole of it is one number plus elapsed ticks: a sync sets the baseline, and every
 * client tick after it adds one. Re-syncs are periodic and exist only to bound the drift between
 * the client's tick rate and a server that is running behind.</p>
 *
 * <p><b>Drift budget.</b> A moon travels about half a block per tick, and the descent trigger is
 * hundreds of blocks wide, so hundreds of ticks of drift are harmless here. A re-sync MAY correct
 * the value BACKWARDS — the server's counter is the truth and a client that ran ahead is simply
 * wrong. Nothing may assume this value never decreases.</p>
 *
 * <p>Before the first sync ever arrives the answer is {@code 0}, which is what a client-side read of
 * the space clock returned before this class existed; {@link #hasSync()} distinguishes "not told
 * yet" from "told it is tick zero".</p>
 *
 * <p>Plain state, no Minecraft types: it is written by a packet on the client and advanced by a
 * client tick, but it must load on a dedicated server like any other common class.</p>
 */
public final class SpaceClockSync {

    /** No baseline has been received. Distinguishable from a legitimate tick 0. */
    private static final long NO_SYNC = Long.MIN_VALUE;

    private static long baseTick = NO_SYNC;
    private static long baseLocal;
    private static long localTicks;

    private SpaceClockSync() {
    }

    /** Take a fresh baseline from the server. */
    public static void accept(long serverTick) {
        baseTick = serverTick;
        baseLocal = localTicks;
    }

    /** One client tick elapsed. */
    public static void onClientTick() {
        localTicks++;
    }

    /** Whether a baseline has ever arrived. */
    public static boolean hasSync() {
        return baseTick != NO_SYNC;
    }

    /** The space clock as this client currently believes it, or {@code 0} before the first sync. */
    public static long now() {
        return baseTick == NO_SYNC ? 0L : baseTick + (localTicks - baseLocal);
    }

    /**
     * Forget the baseline. Called when the client leaves a server: the next server's counter is
     * unrelated to this one, and carrying the old baseline across would answer confidently with a
     * number from a different world.
     */
    public static void reset() {
        baseTick = NO_SYNC;
        baseLocal = 0L;
        localTicks = 0L;
    }
}
