package zmaster587.advancedRocketry.test.client;

/**
 * The surveyed natural stand-spot shared by every client fixture that just needs the player to be
 * standing on solid ground somewhere.
 *
 * <p>These fixtures used to stand the player on a single stone block placed in mid-air at
 * {@code (8.5, 79, 8.5)}. With the harness world seed pinned, that column is open OCEAN: the block
 * was a 1×1 pillar ~16 blocks above the water, the player clipped its own corner for a point of
 * {@code inWall} suffocation damage and then fell in and sank. Everything downstream followed from
 * that — health assertions that read "the suit stopped protecting" when the player had merely
 * suffocated, a vacuum tick that never fires because the atmosphere handler skips a submerged
 * entity, and a suit that drains air per tick because the in-water branch of the player tick asks
 * whether the suit protects (and that question commits a decrement).</p>
 *
 * <p>So the spot is now real, natural, flat ground, surveyed once against the pinned seed with
 * {@code /artest worldgen find-biome} + {@code find-site} and cross-checked with {@code site-check}:
 * plains, pad radius 4, headroom 6, deviation 0. Nothing is placed and nothing is levelled — the
 * player is simply teleported onto ground that is already there.</p>
 *
 * <p>Pinned by {@code HarnessFixtureSitesTest}: if the seed or the generator changes, that guard
 * fails with "the fixture site is no longer flat" instead of half a dozen client tests failing with
 * symptoms that read like production bugs.</p>
 *
 * <p>All these tests get a FRESH world per test method ({@code AbstractClientE2ETest} starts a new
 * server harness in a new temp directory in {@code @Before}), so they can share one spot: the
 * per-method x-offsets the old fixtures carried bought no isolation.</p>
 */
public final class HarnessPlayerSite {

    /** Surveyed site (plains, spread 0). */
    public static final int X = 736;
    public static final int Z = 2036;

    /** Y of the topmost ground block — the player stands on top of it. */
    public static final int GROUND_Y = 64;

    /** Y the player's feet occupy. */
    public static final int STAND_Y = GROUND_Y + 1;

    /** Teleports every player onto the site, centred on the block. */
    public static String tpCommand() {
        return "tp @a " + (X + 0.5) + " " + STAND_Y + " " + (Z + 0.5);
    }

    /** Centre X of the stand block. */
    public static double standX() {
        return X + 0.5;
    }

    /** Centre Z of the stand block. */
    public static double standZ() {
        return Z + 0.5;
    }

    /** Z two blocks in front of the player — where the ride fixtures spawn their craft. */
    public static double frontZ() {
        return Z + 2.5;
    }

    private HarnessPlayerSite() {
    }
}
