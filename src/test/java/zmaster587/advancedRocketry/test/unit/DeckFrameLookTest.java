package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Quat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract pinning for the deck-frame look building block
 * ({@link FreeFlightPhysics#lookQuat}): the rotation a crew member's deck-held yaw/pitch is
 * composed through to become a world aim and a camera. Pure math; no Minecraft types, so the
 * whole surface runs under testUnit without a server.
 *
 * <p>The player-visible contract behind these pins: a crew member walking a rolled ship aims and
 * turns RELATIVE TO THE DECK - the crosshair goes where the deck-frame mouse points, at any ship
 * attitude, including the deck-vertical attitudes where roll-only horizon levelling is degenerate
 * (the worst input/view divergence reported in play was at 90 degrees of roll).</p>
 */
public class DeckFrameLookTest {

    /** Angular tolerance (degrees) for Euler round-trips. */
    private static final double ANGLE_DELTA = 1e-3;
    /** Tolerance for comparing the ACTION of two rotations on a test vector. */
    private static final double ROT_DELTA = 1e-6;

    private static double[] look(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double wrap180(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    // Away from the +-90 pitch gimbal of the Euler DERIVATION (the quat itself has no pole).
    private static final double[] YAWS = {-170, -90, -45, 0, 30, 90, 135, 179};
    private static final double[] PITCHES = {-85, -45, 0, 30, 60, 85};

    // Ship attitudes as body rates (pitch, yaw, roll) from identity - includes the 90-degree
    // roll where the old horizon levelling was singular, and a fully inverted deck.
    private static final double[][] SHIP_ATTITUDES = {
            {0, 0, 0}, {0, 0, 45}, {0, 0, 90}, {0, 0, 135}, {0, 0, 179},
            {30, 0, 0}, {0, 40, 0}, {25, 40, 60}, {-30, 20, -90}
    };

    /**
     * This test fails if production breaks the contract that the look quat AIMS WHERE VANILLA
     * AIMS: {@code lookQuat(yaw, pitch)} carries body +Z to exactly Minecraft's look vector for
     * that yaw/pitch. Everything downstream (the derived world aim, the camera forward, the
     * crosshair) points through this equality.
     */
    @Test
    public void lookQuatCarriesForwardToTheVanillaLookVector() {
        for (double yaw : YAWS) {
            for (double pitch : PITCHES) {
                double[] fwd = FreeFlightPhysics.lookQuat(yaw, pitch).rotate(0, 0, 1);
                double[] mc = look(yaw, pitch);
                for (int i = 0; i < 3; i++) {
                    assertEquals("forward[" + i + "] at yaw=" + yaw + " pitch=" + pitch,
                            mc[i], fwd[i], ROT_DELTA);
                }
            }
        }
    }

    /**
     * This test fails if production breaks the contract that the look quat is ROLL-FREE and
     * ROUND-TRIPS: {@code eulerFromQuat(lookQuat(yaw, pitch))} gives back exactly
     * {@code {yaw, pitch, 0}}. A roll smuggled in here would tilt every walking crew camera on a
     * level deck; a yaw/pitch skew would make the crosshair and the aim disagree.
     */
    @Test
    public void lookQuatRoundTripsThroughEulerWithZeroRoll() {
        for (double yaw : YAWS) {
            for (double pitch : PITCHES) {
                float[] e = FreeFlightPhysics.eulerFromQuat(FreeFlightPhysics.lookQuat(yaw, pitch));
                assertEquals("yaw at " + yaw + "/" + pitch, 0.0, wrap180(e[0] - yaw), ANGLE_DELTA);
                assertEquals("pitch at " + yaw + "/" + pitch, pitch, e[1], ANGLE_DELTA);
                assertEquals("roll at " + yaw + "/" + pitch, 0.0, wrap180(e[2]), ANGLE_DELTA);
            }
        }
    }

    /**
     * This test fails if production breaks the contract that a DECK-YAW TURN SWEEPS THE WORLD AIM
     * ABOUT THE DECK NORMAL at any ship attitude: composing a ship attitude with the look quat,
     * a pure deck-yaw change (the horizontal mouse) leaves the aim's angle to the ship's up
     * unchanged and advances it by exactly the turned angle about that up - including on a
     * 90-degree-rolled and a near-inverted ship, the attitudes where levelling a world aim to the
     * horizon is degenerate. This is "the mouse turns deck-relative" as one piece of math.
     */
    @Test
    public void aDeckYawTurnSweepsTheAimAboutTheShipsUpAtAnyAttitude() {
        for (double[] att : SHIP_ATTITUDES) {
            Quat ship = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, att[0], att[1], att[2]);
            double[] shipUp = ship.rotate(0, 1, 0);
            for (double pitch : new double[]{-40, 0, 55}) {
                double[] a = ship.mul(FreeFlightPhysics.lookQuat(20.0, pitch)).rotate(0, 0, 1);
                double[] b = ship.mul(FreeFlightPhysics.lookQuat(20.0 + 90.0, pitch)).rotate(0, 0, 1);
                // The angle to the deck normal is set by the deck PITCH alone - both aims sit on
                // the same cone about the ship's up, whatever the ship attitude.
                assertEquals("cone angle constant (att roll=" + att[2] + " pitch=" + pitch + ")",
                        dot(shipUp, a), dot(shipUp, b), ROT_DELTA);
                assertEquals("the cone IS the deck pitch (att roll=" + att[2] + ")",
                        -Math.sin(Math.toRadians(pitch)), dot(shipUp, a), ROT_DELTA);
                // And the sweep about that normal is the turned angle: for a 90-degree deck-yaw
                // turn the two aims' components in the deck plane are perpendicular.
                double[] aPlane = new double[]{a[0] - shipUp[0] * dot(shipUp, a),
                        a[1] - shipUp[1] * dot(shipUp, a), a[2] - shipUp[2] * dot(shipUp, a)};
                double[] bPlane = new double[]{b[0] - shipUp[0] * dot(shipUp, b),
                        b[1] - shipUp[1] * dot(shipUp, b), b[2] - shipUp[2] * dot(shipUp, b)};
                assertEquals("90-degree deck turn is 90 degrees about the deck normal (att roll="
                        + att[2] + " pitch=" + pitch + ")", 0.0, dot(aPlane, bPlane), ROT_DELTA);
            }
        }
    }

    /**
     * This test fails if production breaks the contract that the DERIVED WORLD AIM IS THE CAMERA
     * FORWARD: mapping the deck look vector through the ship attitude (how the player's world
     * yaw/pitch are derived) lands on the same direction as the composed camera quat's forward
     * (how the view is rendered). If these two paths diverge, the crosshair picks a block the
     * camera is not centred on.
     */
    @Test
    public void theDerivedWorldAimIsTheComposedCamerasForward() {
        for (double[] att : SHIP_ATTITUDES) {
            Quat ship = FreeFlightPhysics.integrateBodyRates(Quat.IDENTITY, att[0], att[1], att[2]);
            for (double yaw : new double[]{-120, 0, 75}) {
                for (double pitch : new double[]{-50, 0, 35}) {
                    double[] deckFwd = look(yaw, pitch);
                    double[] derived = ship.rotate(deckFwd[0], deckFwd[1], deckFwd[2]);
                    double[] camFwd = ship.mul(FreeFlightPhysics.lookQuat(yaw, pitch)).rotate(0, 0, 1);
                    assertTrue("aim==camera at att roll=" + att[2] + " look=" + yaw + "/" + pitch
                                    + " dot=" + dot(derived, camFwd),
                            dot(derived, camFwd) > 1.0 - ROT_DELTA);
                }
            }
        }
    }
}
