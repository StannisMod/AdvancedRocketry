package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Quat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract pinning for the ship-frame crew math added to {@link FreeFlightPhysics}:
 * the deck-levelled camera ({@link FreeFlightPhysics#deckLevelledCameraQuat},
 * {@link FreeFlightPhysics#quatFromBasis}, {@link FreeFlightPhysics#yawFromForwardDeg})
 * and the ship attitude-hold controller ({@link FreeFlightPhysics#attitudeHoldAngAccel}).
 * Pure math; NO Minecraft types, so the whole surface runs under testUnit without a server.
 *
 * <p>Each assertion below protects a player-visible contract behind a real playtest bug:</p>
 * <ul>
 *   <li><b>The camera rotates WITH the ship without stealing the aim.</b> Levelling the view to
 *       a deck adds only roll: the yaw and pitch the player is looking along - and therefore
 *       block interaction, which derives from them - are never touched. An upright ship is a
 *       byte-for-byte no-op (roll 0); a ship rolled over turns the horizon upside down (roll 180);
 *       looking straight down the deck normal leaves the roll undefined (null, hold the old value).</li>
 *   <li><b>{@code quatFromBasis} is the exact inverse of {@code bodyBasisFromQuat}</b>, so a camera
 *       or model orientation assembled from vectors reproduces the intended rotation.</li>
 *   <li><b>"No pilot input" means "stop turning".</b> A force-controlled ship carries angular
 *       momentum, so with the flight cursor centred an already-spinning ship must be BRAKED, not
 *       left to coast; and an idle, pointed ship must not be dithered.</li>
 * </ul>
 */
public class ShipDeckCameraAndAttitudeHoldTest {

    /** Angular tolerance (degrees) for camera yaw/pitch/roll round-trips. */
    private static final double ANGLE_DELTA = 1e-3;
    /** Tolerance for comparing the ACTION of two rotations on a test vector. */
    private static final double ROT_DELTA = 1e-6;

    // -- small vector helpers (no MC, no external deps) --------------------

    private static double[] look(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    private static double mag(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] scale(double[] v, double s) {
        return new double[]{v[0] * s, v[1] * s, v[2] * s};
    }

    private static double[] normalize(double[] v) {
        double n = mag(v);
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    /** Reduce a degree difference into [-180, 180) so a yaw comparison ignores the +-180 wrap. */
    private static double wrap180(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** World-space up axis of a ship whose orientation is the given body rates from identity. */
    private static double[] shipUpFrom(double pitchDeg, double yawDeg, double rollDeg) {
        double[] b = FreeFlightPhysics.bodyBasisFromQuat(
                FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, pitchDeg, yawDeg, rollDeg));
        return new double[]{b[6], b[7], b[8]};
    }

    // A spread of viewing directions (skip |pitch| near the +-90 gimbal per the camera's own doc).
    private static final double[] LOOK_YAWS = {-150, -90, -30, 0, 45, 120, 170};
    private static final double[] LOOK_PITCHES = {-60, -30, 0, 20, 50};

    // A spread of ship attitudes, expressed as body rates (pitch, yaw, roll) from identity.
    private static final double[][] SHIP_ATTITUDES = {
            {0, 0, 0}, {0, 0, 30}, {0, 0, 90}, {20, 0, 0},
            {0, 35, 0}, {25, 40, 60}, {0, 0, 150}, {-30, 20, -45}
    };

    // =====================================================================
    // A. Deck-levelled camera
    // =====================================================================

    /**
     * THE camera-levelling contract: for every viewing direction over every ship attitude, levelling
     * the view returns the SAME yaw and pitch that built the look vector - only roll may change. The
     * player keeps aiming exactly where he was; the horizon is all that tilts.
     */
    @Test
    public void levellingTheViewToADeckNeverChangesWhereThePlayerIsLooking() {
        int checked = 0;
        for (double[] att : SHIP_ATTITUDES) {
            double[] shipUp = shipUpFrom(att[0], att[1], att[2]);
            for (double yaw : LOOK_YAWS) {
                for (double pitch : LOOK_PITCHES) {
                    double[] fwd = look(yaw, pitch);
                    Quat cam = FreeFlightPhysics.deckLevelledCameraQuat(fwd, shipUp);
                    if (cam == null) continue; // deck normal parallel to view -> roll undefined
                    float[] e = FreeFlightPhysics.eulerFromQuat(cam);
                    assertEquals("yaw preserved (att=" + att[2] + " look=" + yaw + "/" + pitch + ")",
                            0.0, wrap180(e[0] - yaw), ANGLE_DELTA);
                    assertEquals("pitch preserved (att=" + att[2] + " look=" + yaw + "/" + pitch + ")",
                            pitch, e[1], ANGLE_DELTA);
                    checked++;
                }
            }
        }
        assertTrue("the spread must actually exercise the levelling", checked > 100);
    }

    /**
     * An upright ship levels to exactly zero roll: the view is untouched, so ordinary play (a rocket
     * or a level ship) cannot regress into a tilted horizon.
     */
    @Test
    public void anUprightShipLevelsToZeroRollSoOrdinaryPlayIsUntouched() {
        double[] up = {0, 1, 0};
        for (double yaw : LOOK_YAWS) {
            for (double pitch : LOOK_PITCHES) {
                Quat cam = FreeFlightPhysics.deckLevelledCameraQuat(look(yaw, pitch), up);
                assertNotNull(cam);
                float[] e = FreeFlightPhysics.eulerFromQuat(cam);
                assertEquals("upright ship adds no roll (look=" + yaw + "/" + pitch + ")",
                        0.0, e[2], ANGLE_DELTA);
            }
        }
    }

    /**
     * A ship rolled 180 degrees about its nose (deck up pointing at world -Y) turns the horizon over:
     * the levelled view carries a roll of magnitude 180.
     */
    @Test
    public void aShipRolledOneEightyTurnsTheHorizonOverWithTheDeck() {
        double[] downUp = {0, -1, 0}; // ship's local +Y now points at world -Y
        // Look directions deliberately NOT parallel to the deck normal.
        double[][] looks = {look(0, 0), look(45, 20), look(-120, -30), look(170, 15)};
        for (double[] fwd : looks) {
            Quat cam = FreeFlightPhysics.deckLevelledCameraQuat(fwd, downUp);
            assertNotNull(cam);
            float[] e = FreeFlightPhysics.eulerFromQuat(cam);
            assertEquals("horizon flips with the deck", 180.0, Math.abs(e[2]), ANGLE_DELTA);
        }
    }

    /**
     * Looking straight along the deck normal leaves the roll undefined, so the function returns null
     * and the caller holds its previous roll rather than snapping the camera.
     */
    @Test
    public void lookingStraightAlongTheDeckNormalReturnsNullSoTheCallerHoldsItsRoll() {
        assertNull(FreeFlightPhysics.deckLevelledCameraQuat(new double[]{0, 1, 0}, new double[]{0, 1, 0}));
        // Non-unit but still parallel deck up.
        assertNull(FreeFlightPhysics.deckLevelledCameraQuat(new double[]{0, 1, 0}, new double[]{0, 2, 0}));
        // Anti-parallel (looking up the underside of the deck) is equally undefined.
        assertNull(FreeFlightPhysics.deckLevelledCameraQuat(new double[]{0, -1, 0}, new double[]{0, 1, 0}));
        // Control: a view across the deck IS defined.
        assertNotNull(FreeFlightPhysics.deckLevelledCameraQuat(new double[]{0, 0, 1}, new double[]{0, 1, 0}));
    }

    // =====================================================================
    // B. quatFromBasis is the inverse of bodyBasisFromQuat
    // =====================================================================

    /**
     * Feeding {@code quatFromBasis} the basis of a quaternion reconstructs the SAME rotation. Compared
     * by how the rotation acts on test vectors, because q and -q are the same rotation with opposite
     * components.
     */
    @Test
    public void quatFromBasisReconstructsTheRotationThatProducedTheBasis() {
        Quat[] cases = {
                Quat.IDENTITY,
                FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 30, 0, 0),
                FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 0, 120, 0),
                FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 0, 0, 175),
                FreeFlightPhysics.integrateBodyRates(
                        FreeFlightPhysics.integrateBodyRates(
                                FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 37, 0, 0),
                                0, 52, 0), 0, 0, 24),
                FreeFlightPhysics.integrateBodyRates(
                        FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, -80, 0, 0), 0, 200, 0)
        };
        double[][] probes = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0.3, -0.7, 0.5}, {-0.6, 0.2, 0.8}};

        for (Quat q : cases) {
            double[] b = FreeFlightPhysics.bodyBasisFromQuat(q);
            Quat back = FreeFlightPhysics.quatFromBasis(
                    new double[]{b[0], b[1], b[2]},
                    new double[]{b[3], b[4], b[5]},
                    new double[]{b[6], b[7], b[8]});
            for (double[] v : probes) {
                double[] want = q.rotate(v[0], v[1], v[2]);
                double[] got = back.rotate(v[0], v[1], v[2]);
                assertEquals(want[0], got[0], ROT_DELTA);
                assertEquals(want[1], got[1], ROT_DELTA);
                assertEquals(want[2], got[2], ROT_DELTA);
            }
        }
    }

    // =====================================================================
    // C. yawFromForwardDeg agrees with Minecraft's yaw convention
    // =====================================================================

    /**
     * {@code yawFromForwardDeg} recovers the Minecraft yaw of a look vector: for a yaw Y the look
     * vector built with MC's convention maps back to Y (modulo 360), so a crew member walks the deck
     * along the same heading he faces.
     */
    @Test
    public void yawFromForwardAgreesWithMinecraftYawConvention() {
        for (double yaw : LOOK_YAWS) {
            for (double pitch : LOOK_PITCHES) {
                double[] f = look(yaw, pitch);
                float back = FreeFlightPhysics.yawFromForwardDeg(f[0], f[1], f[2]);
                assertEquals("yaw recovered (look=" + yaw + "/" + pitch + ")",
                        0.0, wrap180(back - yaw), ANGLE_DELTA);
            }
        }
    }

    // =====================================================================
    // D. Attitude hold + rate damping
    // =====================================================================

    /**
     * THE attitude-hold fix: with zero orientation error but a real residual spin, the controller
     * commands an angular acceleration OPPOSING the spin (a negative dot with omega) - "no pilot
     * input" brakes the turn instead of coasting on angular momentum.
     */
    @Test
    public void zeroErrorWithResidualSpinCommandsAccelerationOpposingTheSpin() {
        double[] omega = {0.4, -0.2, 0.1};
        double[] a = FreeFlightPhysics.attitudeHoldAngAccel(
                0, 0, 1, /*errAngle*/0.0,
                omega[0], omega[1], omega[2],
                /*dt*/0.05, /*pGain*/4.0, /*maxAngSpeed*/2.0, /*maxAngAccel*/10.0);
        assertNotNull("a spinning ship must be actively braked, not coasted", a);
        assertTrue("acceleration opposes the residual spin", dot(a, omega) < 0.0);
    }

    /**
     * Zero error AND zero spin commands no torque at all (null): an idle, pointed ship is left alone
     * rather than dithered at the noise floor.
     */
    @Test
    public void zeroErrorAndZeroSpinCommandsNoTorqueAtAll() {
        double[] a = FreeFlightPhysics.attitudeHoldAngAccel(
                0, 0, 1, 0.0, 0.0, 0.0, 0.0,
                0.05, 4.0, 2.0, 10.0);
        assertNull("an idle, pointed ship is not dithered", a);
    }

    /**
     * The commanded acceleration is bounded by {@code maxAngAccel} even for a large error and a large
     * spin, so the physics solver never receives a runaway torque.
     */
    @Test
    public void commandedAccelerationNeverExceedsTheAccelerationCap() {
        double maxAngAccel = 10.0;
        double[] a = FreeFlightPhysics.attitudeHoldAngAccel(
                0, 0, 1, /*errAngle*/Math.PI,
                /*omega*/5.0, 5.0, 5.0,
                /*dt*/0.05, /*pGain*/4.0, /*maxAngSpeed*/2.0, maxAngAccel);
        assertNotNull(a);
        assertTrue("acceleration stays within the cap", mag(a) <= maxAngAccel + 1e-9);
    }

    /**
     * The desired turn rate is capped: with a huge error and zero spin, the acceleration stays within
     * {@code maxAngAccel} and the rate it would build this tick ({@code a*dt}) never exceeds
     * {@code maxAngSpeed} - the ship eases toward its target instead of snapping.
     */
    @Test
    public void theDesiredTurnRateIsCappedForAHugeError() {
        double dt = 0.5, maxAngSpeed = 2.0, maxAngAccel = 10.0;
        double[] a = FreeFlightPhysics.attitudeHoldAngAccel(
                0, 0, 1, /*errAngle (absurd)*/100.0,
                /*omega*/0.0, 0.0, 0.0,
                dt, /*pGain*/4.0, maxAngSpeed, maxAngAccel);
        assertNotNull(a);
        assertTrue("acceleration within cap", mag(a) <= maxAngAccel + 1e-9);
        assertTrue("implied desired rate within the speed cap",
                mag(scale(a, dt)) <= maxAngSpeed + 1e-9);
    }

    /**
     * A NaN input (here a NaN angular velocity) returns null rather than propagating NaN into the
     * physics solver, which would corrupt the whole rigid-body state.
     */
    @Test
    public void aNaNAngularVelocityReturnsNullRatherThanPropagatingNaN() {
        double[] a = FreeFlightPhysics.attitudeHoldAngAccel(
                0, 0, 1, 0.5,
                Double.NaN, 0.0, 0.0,
                0.05, 4.0, 2.0, 10.0);
        assertNull("NaN must not leak into the solver", a);
    }

    /**
     * With a real orientation error and no spin, the acceleration points ALONG the error axis, i.e.
     * it drives the ship straight at its target.
     */
    @Test
    public void aRealErrorWithNoSpinAcceleratesAlongTheErrorAxis() {
        double[] axis = normalize(new double[]{1, 2, -2});
        double[] a = FreeFlightPhysics.attitudeHoldAngAccel(
                axis[0], axis[1], axis[2], /*errAngle*/0.5,
                /*omega*/0.0, 0.0, 0.0,
                /*dt*/0.5, /*pGain*/1.0, /*maxAngSpeed*/2.0, /*maxAngAccel*/10.0);
        assertNotNull(a);
        assertTrue("acceleration drives toward the target (positive along the error axis)",
                dot(a, axis) > 0.0);
        assertEquals("acceleration is parallel to the error axis (points straight at the target)",
                1.0, dot(normalize(a), axis), 1e-9);
        assertEquals("no sideways component", 0.0, mag(cross(a, axis)), 1e-9);
    }
}
