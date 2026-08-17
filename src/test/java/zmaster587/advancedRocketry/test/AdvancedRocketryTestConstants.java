package zmaster587.advancedRocketry.test;

import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipTransitManager;

/**
 * Shared constants for AR test fixtures. Keep values stable across runs so
 * snapshot/round-trip assertions stay deterministic.
 *
 * Naming follows the project's test-naming convention.
 */
public final class AdvancedRocketryTestConstants {

    /** Test-only system property gating /artest probe commands and other test hooks. */
    public static final String TEST_MODE_PROPERTY = "advancedrocketry.tests";

    /** Deterministic world seed for any worldgen scenario. */
    public static final long DETERMINISTIC_WORLD_SEED = 0x4151544553544CL; // "AQTESTL"

    /**
     * How far apart the space fixtures put their two cells: one sector.
     * {@code artest space transit-setup*} builds origin and target one sector apart, and it is the only
     * distance a fixture jump is ever priced over.
     *
     * <p>MEASURED through the same law the departure prices a jump with, never written down. It was
     * written down once, as 4M, from a probe comment that predated the cell growing to 32M — and the
     * speeds derived from it put a "hyperspace" fixture 2 560 ticks from its destination.</p>
     */
    public static final long FIXTURE_CELL_SPACING_BLOCKS = (long) Math.ceil(
            CellFrames.STATIC.distanceBetween(
                    GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L),
                    GalacticCoord.ofSectorLocal(1L, 0L, 0L, 0L, 0L, 0L), 0L));

    /**
     * A jump speed that gives a REAL hyperspace flight over {@link #FIXTURE_CELL_SPACING_BLOCKS} —
     * with a lane, a park and a mid-flight a stimulus can land inside.
     *
     * <p>Derived from the rule that chooses the mechanism rather than written down beside it: a jump
     * of at most {@link ShipTransitManager#DIRECT_CROSSING_MAX_TICKS} ticks is performed as a single
     * crossing instead, so a fixture that means to test hyperspace must be slower than that — but only
     * just. Every tick of the flight is a tick some test has to drive, so the margin is ten ticks and
     * not a factor: a comfortable factor of two would double the cost of every hyperspace e2e in the
     * suite for no coverage at all.</p>
     */
    public static final long HYPERSPACE_JUMP_SPEED =
            FIXTURE_CELL_SPACING_BLOCKS / (ShipTransitManager.DIRECT_CROSSING_MAX_TICKS + 10L);

    /**
     * A jump speed that makes the same distance a DIRECT cell&rarr;cell crossing: one tick of flight,
     * so the rule fires and no hyperspace lane is ever allocated.
     */
    public static final long DIRECT_JUMP_SPEED = FIXTURE_CELL_SPACING_BLOCKS;

    /** Stable dimension ids the test fixtures assume. */
    public static final int TEST_PLANET_EARTHLIKE_DIM = 9001;
    public static final int TEST_PLANET_VACUUM_DIM = 9002;
    public static final int TEST_PLANET_MOON_DIM = 9003;
    public static final int TEST_PLANET_RINGED_DIM = 9004;

    private AdvancedRocketryTestConstants() {}

    public static boolean isTestMode() {
        return Boolean.getBoolean(TEST_MODE_PROPERTY);
    }
}
