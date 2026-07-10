package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;

import static org.junit.Assert.assertArrayEquals;

/**
 * Contract pinning for {@link FreeFlightPhysics#shipGravityDelta} - the tier-2 "gravity binds to
 * the ship floor" redirect. Pure math (no Minecraft entities), so the safety-critical property is
 * checked without booting: an UPRIGHT ship must leave gravity byte-for-byte unchanged, so a ship
 * that never tilts can never regress vanilla / per-dimension gravity.
 *
 * <p>The delta is added on top of the vanilla world-down gravity {@code (0,-g,0)} that the entity
 * receives later in the same tick, so {@code delta + (0,-g,0)} is the net gravity - which must
 * equal {@code g * shipDown}.</p>
 */
public class ShipFloorGravityTest {

    private static final double DELTA = 1e-9;
    private static final double G = 0.0755; // a representative per-tick living-entity gravity

    /** delta + vanilla world-down == the desired net gravity g*shipDown, for any orientation. */
    private static void assertNetGravityMatches(double[] shipDown) {
        double[] delta = FreeFlightPhysics.shipGravityDelta(G, shipDown);
        double[] net = {delta[0], delta[1] - G, delta[2]}; // vanilla adds (0,-G,0)
        assertArrayEquals(new double[]{G * shipDown[0], G * shipDown[1], G * shipDown[2]}, net, DELTA);
    }

    @Test
    public void uprightShipIsAByteForByteNoOp() {
        // The regression-free property: floor-down == world-down -> zero delta.
        assertArrayEquals(new double[]{0.0, 0.0, 0.0},
                FreeFlightPhysics.shipGravityDelta(G, new double[]{0.0, -1.0, 0.0}), DELTA);
    }

    @Test
    public void invertedShipFlipsGravityToTheNewFloor() {
        assertArrayEquals(new double[]{0.0, 2.0 * G, 0.0},
                FreeFlightPhysics.shipGravityDelta(G, new double[]{0.0, 1.0, 0.0}), DELTA);
        assertNetGravityMatches(new double[]{0.0, 1.0, 0.0});
    }

    @Test
    public void shipOnItsSidePullsSideways() {
        assertArrayEquals(new double[]{G, G, 0.0},
                FreeFlightPhysics.shipGravityDelta(G, new double[]{1.0, 0.0, 0.0}), DELTA);
        assertNetGravityMatches(new double[]{1.0, 0.0, 0.0});
        assertNetGravityMatches(new double[]{0.0, 0.0, -1.0});
    }

    @Test
    public void tiltedShipNetGravityAlwaysPointsAtTheFloor() {
        // 45-degree roll: floor-down leans toward +X.
        double s = Math.sin(Math.toRadians(45));
        assertNetGravityMatches(new double[]{s, -s, 0.0});
        // Arbitrary attitude derived from the FF quaternion helper (integration-consistent).
        double[] down = FreeFlightPhysics.Quat
                .fromAxisAngle(0, 0, 1, 30) // 30-degree bank about the nose
                .rotate(0.0, -1.0, 0.0);
        assertNetGravityMatches(down);
    }
}
