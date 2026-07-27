package zmaster587.advancedRocketry.hyperdrive;

/**
 * The moment between deciding to jump and jumping: the spool, and the second look a warning buys.
 *
 * <p>Two things are being kept honest here. The first is that <b>nothing is spent until the window
 * opens</b> — a pilot who changes his mind while the drive winds up walks away having paid nothing,
 * which is what makes a spool a decision point rather than a delay. The second is that a warning has
 * to be <b>read</b> to be confirmed: a pilot meets an advisory, is told what it is, and only a
 * second, deliberate press inside a short window commits him. One press can never both raise a
 * warning and answer it.</p>
 *
 * <p>Deliberately not persisted. A spool interrupted by a server restart resolves the same way an
 * abort does, and free is the correct price for a jump that never happened.</p>
 */
public final class JumpSpool {

    private static final long NEVER = Long.MIN_VALUE;

    private long spoolEndsAt = NEVER;
    private long warnedAt = NEVER;

    /** Whether the drive is winding up right now. */
    public boolean spooling(long now) {
        return spoolEndsAt != NEVER && now < spoolEndsAt;
    }

    /** Whether the spool has run out and the window is due to open. */
    public boolean ready(long now) {
        return spoolEndsAt != NEVER && now >= spoolEndsAt;
    }

    /** Start winding up. */
    public void begin(long now) {
        spoolEndsAt = now + DriveTuning.SPOOL_TICKS;
        warnedAt = NEVER;
    }

    /** Stop, at no cost. Also the right answer to "the pilot pressed again mid-spool". */
    public void abort() {
        spoolEndsAt = NEVER;
        warnedAt = NEVER;
    }

    /** Ticks left before the window opens; {@code -1} when nothing is spooling. */
    public long remaining(long now) {
        return spoolEndsAt == NEVER ? -1L : Math.max(0L, spoolEndsAt - now);
    }

    /** Record that the pilot has just been warned, and now has a moment to mean it. */
    public void warn(long now) {
        warnedAt = now;
    }

    /**
     * Whether a press at {@code now} answers a warning the pilot has already seen. False the first
     * time — that press is what RAISED the warning — and false again once the moment has passed, so
     * a stale confirmation cannot ride in behind a warning the pilot has forgotten about.
     */
    public boolean confirming(long now) {
        return warnedAt != NEVER
                && now >= warnedAt
                && now - warnedAt <= DriveTuning.ADVISORY_CONFIRM_TICKS;
    }

    /** Forget any outstanding warning, without touching the spool. */
    public void clearWarning() {
        warnedAt = NEVER;
    }
}
