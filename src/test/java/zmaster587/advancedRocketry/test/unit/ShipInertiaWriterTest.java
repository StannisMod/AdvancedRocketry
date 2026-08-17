package zmaster587.advancedRocketry.test.unit;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.junit.Test;
import org.valkyrienskies.mod.common.ships.physics_data.ShipInertiaData;
import zmaster587.advancedRocketry.integration.vs.ShipInertiaWriter;
import zmaster587.advancedRocketry.ship.mass.MassContributor;
import zmaster587.advancedRocketry.ship.mass.ShipMassFrame;
import zmaster587.advancedRocketry.ship.mass.ShipMassFrameBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The rules the mass seam owes the physics engine, pinned against the engine's own record — which is a
 * plain data holder, so no server is needed to hold one.
 *
 * <p>These are contracts, not arithmetic: the three fields move together, a tensor the physics tick
 * could not invert is refused before it gets there, and a disagreement between the cheap incremental
 * path and the authoritative recompute is REPORTED rather than quietly corrected.</p>
 */
public class ShipInertiaWriterTest {

    /** A frame with a genuinely three-dimensional mass distribution, so its tensor is invertible. */
    private static ShipMassFrame hull() {
        return new ShipMassFrameBuilder()
                .add(MassContributor.ofBlock(0, 0, 0, 1000.0, MassContributor.Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(4, 0, 0, 1000.0, MassContributor.Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0, 3, 0, 1000.0, MassContributor.Kind.STRUCTURAL))
                .add(MassContributor.ofBlock(0, 0, 2, 1000.0, MassContributor.Kind.STRUCTURAL))
                .build();
    }

    @Test
    public void aWriteMovesMassCentreAndTensorTogether() {
        // Any consumer that saw one of the three updated and not the others would be integrating a body
        // that never existed: a mass that belongs to one hull about the centre of another.
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();

        assertTrue("a well-formed frame must be accepted",
                ShipInertiaWriter.applyTo(record, frame, "unit-hull"));

        assertEquals("total mass must be the frame's", frame.getTotalMass(),
                record.getGameTickMass(), 1e-9);
        assertEquals("centre of mass must be the frame's", 0.0,
                new Vector3d(frame.getCentreOfMass())
                        .sub(new Vector3d(record.getGameTickCenterOfMass())).length(), 1e-9);
        assertEquals("the tensor must be the frame's", new Matrix3d(frame.getInertia()),
                new Matrix3d(record.getGameMoITensor()));
    }

    @Test
    public void aSingularTensorIsRefusedBeforeItReachesThePhysicsTick() {
        // The physics loop inverts the tensor every step, so a degenerate one is a NaN torque rather
        // than a rounding error. Refusing it here keeps the ship's name attached to the complaint; a
        // crash inside the tick names neither the craft nor the writer.
        ShipInertiaData record = new ShipInertiaData();
        double massBefore = record.getGameTickMass();

        // A single point of mass: no extent in any direction, so the tensor is identically zero -
        // exactly the shape the physics tick cannot invert.
        ShipMassFrame degenerate = new ShipMassFrameBuilder()
                .add(MassContributor.of(0, 0, 0, 7.0, 0.0, MassContributor.Kind.STRUCTURAL))
                .build();

        assertFalse("a tensor the physics tick cannot invert must be refused",
                ShipInertiaWriter.applyTo(record, degenerate, "unit-degenerate"));
        assertEquals("a refused write must leave the record untouched, not half-written",
                massBefore, record.getGameTickMass(), 0.0);
    }

    @Test
    public void aRecordThatAgreesWithTheAuthorityReportsNothing() {
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();
        ShipInertiaWriter.applyTo(record, frame, "unit-hull");

        assertNull("a record just written from the authority cannot be in drift",
                ShipInertiaWriter.compare(record, frame, "unit-hull"));
    }

    @Test
    public void driftIsReportedWithItsSignAndMagnitudeAndTheRecordIsLEFTALONE() {
        // The whole design of this reconciliation: it is an instrument, not a repair. Substituting the
        // right number here would turn a safety net into normal operation and destroy the only signal
        // that a trigger is missing — and the repair for drift is the missing trigger.
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();
        ShipInertiaWriter.applyTo(record, frame, "unit-hull");

        double stale = frame.getTotalMass() * 0.80; // 20% light: removals that were never applied
        record.setGameTickMass(stale);

        String drift = ShipInertiaWriter.compare(record, frame, "unit-hull");
        assertNotNull("a 20% mass disagreement must be reported", drift);
        assertTrue("the report must name the ship: " + drift, drift.contains("unit-hull"));
        assertTrue("the report must carry the magnitude: " + drift, drift.contains("20"));
        assertTrue("a light record must be reported as NEGATIVE, because a light record and a heavy one "
                + "point at different missing triggers: " + drift, drift.contains("-"));

        assertEquals("compare() must not repair the record it is describing",
                stale, record.getGameTickMass(), 0.0);
    }

    @Test
    public void aDisagreementSmallerThanTheToleranceIsNotDrift() {
        // The tolerance is relative on mass so it means the same thing for a shuttle and a capital
        // hull. Floating-point accumulation over thousands of block events is not a defect.
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();
        ShipInertiaWriter.applyTo(record, frame, "unit-hull");

        record.setGameTickMass(frame.getTotalMass() * 1.005); // half a percent heavy

        assertNull("half a percent is accumulation, not a missing trigger",
                ShipInertiaWriter.compare(record, frame, "unit-hull"));
    }

    @Test
    public void aCentreThatHasWalkedAwayIsDriftEvenWhenTheMassAgrees() {
        // Mass and centre fail independently: a block moved from bow to stern changes the centre and
        // not the total, and a reconciliation watching only mass would call that hull healthy.
        ShipInertiaData record = new ShipInertiaData();
        ShipMassFrame frame = hull();
        ShipInertiaWriter.applyTo(record, frame, "unit-hull");

        record.setGameTickCenterOfMass(
                new Vector3d(frame.getCentreOfMass()).add(0.0, 1.5, 0.0));

        String drift = ShipInertiaWriter.compare(record, frame, "unit-hull");
        assertNotNull("a centre of mass a block and a half out must be reported", drift);
        assertTrue("the report must say the centre moved: " + drift, drift.contains("centre"));
    }
}
