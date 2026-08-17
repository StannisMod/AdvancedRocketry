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

    /**
     * The coil count a "baseline" generator has — the smallest build worth calling a drive, and the
     * one every quoted speed and every band figure is measured against.
     */
    public static final int BASELINE_COILS = 7;

    /**
     * How a generator's power grows with its SIZE: {@code base + per_coil · n^α}. Above 1, one large
     * machine is worth more than several small ones of the same total volume, which is what makes
     * building a bigger drive a progression rather than an addition.
     *
     * <p><b>Currently 1, and the reason it is not the 2 the design derived is an invariant this file
     * cannot satisfy alone.</b> Every energy cost of a drive is proportional to its power — the window
     * burst above all — while the capacitor that must pay that burst grows only with its COMPONENT
     * count, capped at {@link #MAX_CAPACITOR_COMPONENTS}. So power spans {@code (512/7)^α} while the
     * bank that feeds it spans a few hundred, and above α = 1 the two detach: at α = 2 a fully built
     * drive's burst is roughly two hundred times a full bank, and a jump is REFUSED outright once the
     * coil count passes about 35. Raising α therefore needs the capacitor economy re-derived with it,
     * and no single constant does that — lifting the bank's capacity leaves the reload time absurd,
     * and lowering the burst deletes the capacitor as an early-game requirement.</p>
     *
     * <p>What holds the line is the invariant, not this comment: a fully built drive must be able to
     * open its own window. It is pinned by a test, so raising this number turns that test red instead
     * of shipping a drive that gets slower the moment it is finished.</p>
     */
    public static final double COIL_POWER_EXPONENT = 1.0D;

    /**
     * The drive power a generator with {@code coils} coils is worth. <b>The one place the law lives</b>
     * — every quoted power, the baseline, the maximum and the tile that scans a real ship all read it
     * here, so the exponent above cannot apply in some places and not others.
     */
    public static long powerForCoils(int coils) {
        int n = Math.max(0, Math.min(MAX_COILS, coils));
        if (n == 0) {
            return GENERATOR_BASE_POWER;
        }
        double scaled = POWER_PER_COIL * Math.pow(n, COIL_POWER_EXPONENT);
        return GENERATOR_BASE_POWER + (long) Math.min((double) Long.MAX_VALUE, Math.round(scaled));
    }

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
     * The drive power a "baseline" drive has, and the mass of a "baseline" hull. A ship built to both,
     * on the baseline TIER, flies at {@link #BASELINE_SPEED_BLOCKS_PER_TICK}.
     *
     * <p>DERIVED from {@link #BASELINE_COILS} through {@link #powerForCoils}, never written down: as a
     * literal it silently stopped meaning "what a seven-coil generator is worth" the moment the power
     * law gained an exponent, and the entry-level speed — a datum from play, and the one the maintainer
     * has said is already acceptable — would have moved without anybody choosing to move it.</p>
     */
    public static final long BASELINE_DRIVE_POWER = powerForCoils(BASELINE_COILS);
    public static final long BASELINE_SHIP_MASS = 4_000L;
    public static final long BASELINE_SPEED_BLOCKS_PER_TICK = 1_000_000L;

    /**
     * What a FULLY built generator is worth — the top of what size alone can buy, and the number the
     * capacitor economy has to be able to feed. Derived for the same reason as the baseline.
     */
    public static final long MAX_DRIVE_POWER = powerForCoils(MAX_COILS);

    // ─── Gravity dampeners ─────────────────────────────────────────────────────

    /**
     * How much of a BASELINE arrival one powered dampener absorbs. A fraction and not an absolute
     * speed: the balance it encodes is "two dampeners cover the ship a novice actually flies", and
     * stated in blocks per tick that promise detached silently the first time the speed law moved —
     * a tier multiplies every speed by its efficiency, so an absolute half of the old baseline would
     * have become a rounding error on the next generation of drive.
     */
    public static final double DAMPENER_ABSORBED_BASELINE_FRACTION = 0.5D;

    /** Exit speed one powered dampener fully absorbs, in blocks per tick. Derived from the fraction. */
    public static final long DAMPENER_ABSORBED_SPEED =
            (long) Math.max(1d, Math.round(BASELINE_SPEED_BLOCKS_PER_TICK
                    * DAMPENER_ABSORBED_BASELINE_FRACTION));
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
