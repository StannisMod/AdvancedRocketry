package zmaster587.advancedRocketry.hyperdrive;

/**
 * Every balance number the hyperdrive family uses, in one place.
 *
 * <p>None of these are contracts. They are the knobs a pack author turns, and no test may pin one:
 * a test that asserts "a coil is worth 1000 drive power" fails the day someone rebalances, without
 * anything actually having broken. What the tests pin is the SHAPE — more coils give more power, a
 * heavier ship on the same drive is slower, an empty capacitor cannot open a window.</p>
 */
public final class DriveTuning {

    private DriveTuning() {
    }

    // ─── Field generator ───────────────────────────────────────────────────────

    /** Drive power contributed by the generator's controller block alone. */
    public static final long GENERATOR_BASE_POWER = 1_000L;
    /** Drive power contributed by each coil welded to the generator. */
    public static final long POWER_PER_COIL = 1_000L;
    /**
     * How many coils one generator will count. A cap is what stops a player from paving a continent
     * in coils, and it is what bounds the scan's cost — the scan runs on a pilot's key press, not
     * per tick, but an unbounded flood fill on a large ship is still a stall.
     */
    public static final int MAX_COILS = 512;

    /** Energy the drive draws per tick while the window is held open, per unit of drive power. */
    public static final double IN_FLIGHT_DRAW_PER_POWER = 0.05D;
    /** Energy the capacitor must dump in one moment to open the window, per unit of drive power. */
    public static final double BURST_COST_PER_POWER = 20.0D;

    // ─── The jump window ───────────────────────────────────────────────────────

    /**
     * Half-extent of the window the generator holds up on its own, with no hull emitters at all —
     * so a novice can jump a small ship the moment he has a generator, a capacitor and a navigation
     * computer. Emitters are what make the window big enough for a real hull.
     */
    public static final int GENERATOR_BASELINE_WINDOW_RADIUS = 2; // a 5x5x5 envelope
    /** Half-extent of the envelope one hull emitter adds around itself. */
    public static final int EMITTER_WINDOW_RADIUS = 6;

    // ─── Capacitor ─────────────────────────────────────────────────────────────

    /** Charge the capacitor's controller block holds on its own. */
    public static final long CAPACITOR_BASE_CAPACITY = 20_000L;
    /** Charge each capacitor cell adds. */
    public static final long CAPACITY_PER_CELL = 100_000L;
    /** Charge per tick the controller recovers on its own. */
    public static final long CAPACITOR_BASE_CHARGE_RATE = 10L;
    /**
     * Charge per tick each heat sink adds. Cooling does not get a mechanism of its own: a sink
     * raises the rate at which the capacitor refills, and the reload time — the cooldown a pilot
     * actually feels — is {@code burstCost / chargeRate} with no timer to persist.
     */
    public static final long CHARGE_RATE_PER_SINK = 40L;
    /** How many capacitor components (cells + sinks) one controller will count. */
    public static final int MAX_CAPACITOR_COMPONENTS = 256;

    // ─── Speed ─────────────────────────────────────────────────────────────────

    /**
     * The drive power a "baseline" drive has, and the mass of a "baseline" hull. A ship built to
     * both flies at {@link #BASELINE_SPEED_BLOCKS_PER_TICK}, and the bands that speed produces
     * (seconds inside a system, an hour across a galaxy, months across the universe) are the point
     * of the number — not the number itself.
     */
    public static final long BASELINE_DRIVE_POWER = 8_000L;
    public static final long BASELINE_SHIP_MASS = 4_000L;
    public static final long BASELINE_SPEED_BLOCKS_PER_TICK = 1_000_000L;

    // ─── Gravity dampeners ─────────────────────────────────────────────────────

    /** Exit speed one powered dampener fully absorbs, in blocks per tick. */
    public static final long DAMPENER_ABSORBED_SPEED = 500_000L;
    /** Radius, in blocks, within which a dampener protects a crew member. */
    public static final int DAMPENER_RADIUS = 12;
    /** Damage taken per block/tick of exit speed the dampeners failed to absorb. */
    public static final double DAMPENER_RESIDUAL_DAMAGE_PER_SPEED = 0.00002D;

    // ─── Trigger ───────────────────────────────────────────────────────────────

    /** Ticks between the pilot committing and the window opening. Aborting inside it costs nothing. */
    public static final int SPOOL_TICKS = 60;
    /**
     * How long a pilot who has just been warned has to press again and mean it. Short enough that a
     * confirmation is deliberate, long enough that reading the warning does not run it out.
     */
    public static final int ADVISORY_CONFIRM_TICKS = 100;

    // ─── The mass placeholder ──────────────────────────────────────────────────

    /**
     * The mass every hull reports until real per-ship mass exists. Deliberately a single number in
     * a single place: everything downstream of it — the speed formula, its tests, the forecast the
     * pilot reads — is real and stays real when the number stops being a constant.
     */
    public static final long PLACEHOLDER_SHIP_MASS = BASELINE_SHIP_MASS;
}
