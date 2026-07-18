package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Quat;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract pinning for the FF body-frame attitude quaternion
 * — the substrate that replaces the world-frame Euler triple so loops work and
 * the controls never invert relative to the pilot. NO Minecraft types here.
 *
 * <p>What matters (and is pinned below):
 * <ul>
 *   <li>Single-axis body rates reproduce {@link FreeFlightPhysics#bodyBasis}
 *       exactly — this calibrates the input sign convention.</li>
 *   <li>Pitch always rotates the nose about the craft's OWN right axis: at
 *       identity "push down" drops the world nose, but INVERTED the same input
 *       raises it — i.e. relative to the pilot the response is identical. This
 *       is the no-inversion contract (#4) a world-Euler system fails.</li>
 *   <li>Integrating a pitch rate through a full 360° returns to the start with
 *       no clamp — loops are possible (#3).</li>
 *   <li>The derived basis stays orthonormal and right-handed; Euler extraction
 *       round-trips the quaternion away from the pitch poles.</li>
 * </ul>
 */
public class FreeFlightAttitudeTest {

    private static final double DELTA = 1e-6;
    /** Looser tolerance for accumulated 360-step integration drift. */
    private static final double LOOP_DELTA = 1e-3;

    // -- helpers ----------------------------------------------------------

    private static void assertBasisEquals(double[] expected, double[] actual, double delta) {
        for (int i = 0; i < 9; i++) assertEquals("basis[" + i + "]", expected[i], actual[i], delta);
    }

    private static double dot(double[] b, int a0, int b0) {
        return b[a0] * b[b0] + b[a0 + 1] * b[b0 + 1] + b[a0 + 2] * b[b0 + 2];
    }

    // -- calibration: single body rates == legacy bodyBasis ---------------

    @Test
    public void identityBasisMatchesEulerZero() {
        assertBasisEquals(FreeFlightPhysics.bodyBasis(0f, 0f, 0f),
                FreeFlightPhysics.bodyBasisFromQuat(Quat.IDENTITY), DELTA);
    }

    @Test
    public void pitchRateAloneMatchesEulerPitch() {
        Quat q = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 30, 0, 0);
        assertBasisEquals(FreeFlightPhysics.bodyBasis(0f, 30f, 0f),
                FreeFlightPhysics.bodyBasisFromQuat(q), DELTA);
    }

    @Test
    public void yawRateAloneMatchesEulerYaw() {
        Quat q = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 0, 40, 0);
        assertBasisEquals(FreeFlightPhysics.bodyBasis(40f, 0f, 0f),
                FreeFlightPhysics.bodyBasisFromQuat(q), DELTA);
    }

    @Test
    public void rollRateAloneMatchesEulerRoll() {
        Quat q = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 0, 0, 50);
        assertBasisEquals(FreeFlightPhysics.bodyBasis(0f, 0f, 50f),
                FreeFlightPhysics.bodyBasisFromQuat(q), DELTA);
    }

    // -- pitch is body-frame (about craft right), heading independent -----

    @Test
    public void pitchDownDropsWorldNoseAtIdentity() {
        Quat q = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 20, 0, 0);
        double[] b = FreeFlightPhysics.bodyBasisFromQuat(q);
        assertTrue("nose should drop (forward.y < 0)", b[1] < -0.1);
    }

    @Test
    public void pitchAfterYaw90IsAboutCraftRightNotWorldX() {
        // Yaw 90° first: craft right axis now points along world +Z.
        Quat yawed = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 0, 90, 0);
        // Then pitch down: the nose (world -X) must tilt toward the belly (-Y),
        // i.e. still a body-frame pitch, unaffected by the world-frame heading.
        Quat q = FreeFlightPhysics.integrateBodyRates(yawed, 20, 0, 0);
        double[] b = FreeFlightPhysics.bodyBasisFromQuat(q);
        assertTrue("nose should drop after yaw (forward.y < 0)", b[1] < -0.1);
    }

    /** The headline #4 contract: pitch input is body-frame, so an inverted
     *  craft responds to "push down" by raising the world nose — identical to
     *  the pilot's frame. A world-Euler pitch would drop it either way (inverted
     *  controls). */
    @Test
    public void pitchInputDoesNotInvertWhenRolledOver() {
        double[] upright = FreeFlightPhysics.bodyBasisFromQuat(
                FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 20, 0, 0));
        Quat inverted = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 0, 0, 180);
        double[] afterPitch = FreeFlightPhysics.bodyBasisFromQuat(
                FreeFlightPhysics.integrateBodyRates(inverted, 20, 0, 0));
        assertTrue("upright: same pitch drops the world nose", upright[1] < -0.1);
        assertTrue("inverted: same pitch RAISES the world nose (body-frame)",
                afterPitch[1] > 0.1);
    }

    // -- loops: integrate past ±90° with no clamp -------------------------

    @Test
    public void fullPitchLoopReturnsToIdentity() {
        Quat q = Quat.IDENTITY;
        for (int i = 0; i < 360; i++) {
            q = FreeFlightPhysics.integrateBodyRates(q, 1, 0, 0);
        }
        assertBasisEquals(FreeFlightPhysics.bodyBasis(0f, 0f, 0f),
                FreeFlightPhysics.bodyBasisFromQuat(q), LOOP_DELTA);
    }

    @Test
    public void pitchPastVerticalDoesNotClamp() {
        // Nose straight up then over the top — a world-Euler clamp (±85°) would
        // freeze here; the quaternion keeps going.
        Quat q = Quat.IDENTITY;
        for (int i = 0; i < 120; i++) q = FreeFlightPhysics.integrateBodyRates(q, 1, 0, 0);
        double[] b = FreeFlightPhysics.bodyBasisFromQuat(q); // pitched 120° over the top
        // forward.z should be negative (nose now points backwards/down past vertical).
        assertTrue("nose passed vertical (forward.z < 0)", b[2] < 0);
    }

    // -- basis invariants -------------------------------------------------

    @Test
    public void derivedBasisIsOrthonormalRightHanded() {
        Quat q = FreeFlightPhysics.integrateBodyRates(
                FreeFlightPhysics.integrateBodyRates(
                        FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 37, 0, 0),
                        0, 52, 0),
                0, 0, 24);
        double[] b = FreeFlightPhysics.bodyBasisFromQuat(q);
        assertEquals("forward unit", 1.0, dot(b, 0, 0), DELTA);
        assertEquals("right unit",   1.0, dot(b, 3, 3), DELTA);
        assertEquals("up unit",      1.0, dot(b, 6, 6), DELTA);
        assertEquals("fwd·right", 0.0, dot(b, 0, 3), DELTA);
        assertEquals("fwd·up",    0.0, dot(b, 0, 6), DELTA);
        assertEquals("right·up",  0.0, dot(b, 3, 6), DELTA);
        // forward = right × up
        double cx = b[4] * b[8] - b[5] * b[7];
        double cy = b[5] * b[6] - b[3] * b[8];
        double cz = b[3] * b[7] - b[4] * b[6];
        assertEquals(b[0], cx, DELTA);
        assertEquals(b[1], cy, DELTA);
        assertEquals(b[2], cz, DELTA);
    }

    // -- Euler extraction round-trips (away from poles) -------------------

    @Test
    public void eulerFromQuatRoundTripsBodyBasis() {
        Quat q = FreeFlightPhysics.integrateBodyRates(
                FreeFlightPhysics.integrateBodyRates(
                        FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 25, 0, 0),
                        0, 60, 0),
                0, 0, 33);
        float[] e = FreeFlightPhysics.eulerFromQuat(q);
        assertBasisEquals(FreeFlightPhysics.bodyBasisFromQuat(q),
                FreeFlightPhysics.bodyBasis(e[0], e[1], e[2]), 1e-4);
    }

    // -- slerp ------------------------------------------------------------

    @Test
    public void slerpEndpointsAndUnitNorm() {
        Quat a = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 10, 0, 0);
        Quat b = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 0, 80, 0);
        assertBasisEquals(FreeFlightPhysics.bodyBasisFromQuat(a),
                FreeFlightPhysics.bodyBasisFromQuat(FreeFlightPhysics.slerp(a, b, 0.0)), DELTA);
        assertBasisEquals(FreeFlightPhysics.bodyBasisFromQuat(b),
                FreeFlightPhysics.bodyBasisFromQuat(FreeFlightPhysics.slerp(a, b, 1.0)), DELTA);
        Quat mid = FreeFlightPhysics.slerp(a, b, 0.5);
        double n = Math.sqrt(mid.w * mid.w + mid.x * mid.x + mid.y * mid.y + mid.z * mid.z);
        assertEquals("midpoint unit norm", 1.0, n, DELTA);
    }

    // -- quaternion translation layer parity ------------------------------

    /** Given the SAME attitude, the quaternion faStep produces the same thrust
     *  as the Euler faStep — the refactor is behaviour-preserving away from poles
     *  (the whole point is that the quaternion is ALSO valid AT the poles). */
    @Test
    public void faStepQuatMatchesEulerFaStep() {
        Quat q = FreeFlightPhysics.integrateBodyRates(
                FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, 25, 0, 0), 0, 50, 0);
        float[] e = FreeFlightPhysics.eulerFromQuat(q);
        Step viaQuat  = FreeFlightPhysics.faStep(0.2, -0.1, 0.3, q,
                1.0, 0.0, 0.5, 0.1, 0.02, true);
        Step viaEuler = FreeFlightPhysics.faStep(0.2, -0.1, 0.3, e[0], e[1], e[2],
                1.0, 0.0, 0.5, 0.1, 0.02, true);
        assertEquals(viaEuler.motionX, viaQuat.motionX, 1e-4);
        assertEquals(viaEuler.motionY, viaQuat.motionY, 1e-4);
        assertEquals(viaEuler.motionZ, viaQuat.motionZ, 1e-4);
    }

    @Test
    public void newtonianForwardThrustIsAlongNoseAtIdentity() {
        FreeFlightInput fwd = new FreeFlightInput(1f, 0f, 0f, 0f, 0f, 0f, 0f, false);
        Step s = FreeFlightPhysics.translateNewtonian(0, 0, 0, Quat.IDENTITY,
                fwd, 0.1, 0.0, true);
        assertEquals(0.0, s.motionX, DELTA);
        assertEquals(0.0, s.motionY, DELTA);
        assertEquals(0.1, s.motionZ, DELTA); // nose = +Z at identity
        assertTrue(s.thrustApplied);
    }

    @Test
    public void faHoverCancelsGravityAtIdentity() {
        // Zero setpoint, healthy thrust, gravity present -> FA holds a hover
        // (net vertical velocity ≈ 0 after gravity compensation).
        Step s = FreeFlightPhysics.faStep(0, 0, 0, Quat.IDENTITY,
                0.0, 0.0, 0.0, 0.1, 0.02, true);
        assertEquals(0.0, s.motionX, DELTA);
        assertEquals(0.0, s.motionY, DELTA);
        assertEquals(0.0, s.motionZ, DELTA);
    }
}
