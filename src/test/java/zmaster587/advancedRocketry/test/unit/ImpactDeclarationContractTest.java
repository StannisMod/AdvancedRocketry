package zmaster587.advancedRocketry.test.unit;

import com.github.stannismod.affs.world.shield.ShieldStrikeKind;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import zmaster587.advancedRocketry.api.damage.DamageOutcome;
import zmaster587.advancedRocketry.api.damage.DamageReport;
import zmaster587.advancedRocketry.api.damage.ImpactKind;
import zmaster587.advancedRocketry.api.damage.ImpactRequest;
import zmaster587.advancedRocketry.api.damage.SelectionMode;
import zmaster587.advancedRocketry.api.damage.StopReason;
import zmaster587.advancedRocketry.damage.ImpactKindMapping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a declared impact promises before any world is involved — the half of the damage seam that a
 * weapon can rely on without a server running.
 *
 * <p>The load-bearing one is the kind mapping. A hull kind that no shell knows how to bill is not a
 * missing feature, it is a shot that costs a shield nothing and a hull everything; the mapping is
 * many-to-two on purpose, and the test that matters is that it is <b>total</b>, not what any
 * particular row says.</p>
 */
public class ImpactDeclarationContractTest {

    @Test
    public void everyHullImpactKindDeclaresHowAShellBillsIt() {
        for (ImpactKind kind : ImpactKind.values()) {
            ShieldStrikeKind billed = ImpactKindMapping.toShieldKind(kind);
            assertNotNull("impact kind " + kind + " has no shield billing — a kind a shell cannot "
                    + "charge for passes a raised shield free of charge", billed);
        }
    }

    @Test
    public void matterBearingKindsBillAsPhysicalAndRadiationAsEnergy() {
        // Not a pin on the individual rows for their own sake: this is the property the resistance
        // bias exists to express. A ship tuned against beams must not thereby resist slugs.
        assertEquals(ShieldStrikeKind.KINETIC, ImpactKindMapping.toShieldKind(ImpactKind.KINETIC));
        assertEquals(ShieldStrikeKind.KINETIC, ImpactKindMapping.toShieldKind(ImpactKind.EXPLOSIVE));
        assertEquals(ShieldStrikeKind.RADIANT, ImpactKindMapping.toShieldKind(ImpactKind.THERMAL));
        assertEquals(ShieldStrikeKind.RADIANT, ImpactKindMapping.toShieldKind(ImpactKind.BEAM));
    }

    @Test
    public void aDeclaredImpactCarriesAUnitDirectionWhateverTheCallerHandedIt() {
        ImpactRequest request = ImpactRequest.penetrating(1L, new Vec3d(0, 64, 0),
                new Vec3d(0, 0, -37.5D), 5000, ImpactKind.KINETIC);
        Vec3d dir = request.getDirection();
        double length = Math.sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z);
        assertEquals("a direction reaches the engine as a unit vector, so nothing downstream has to "
                + "guess whether the caller's magnitude meant anything", 1.0D, length, 1.0E-9D);
        assertTrue("the sense of the direction must survive normalisation", dir.z < 0.0D);
    }

    @Test
    public void aDegenerateDirectionDoesNotBecomeAnArbitraryOne() {
        // The engine refuses to walk a zero direction. Inventing one here would send a shot off in a
        // direction nobody asked for, which is worse than doing nothing.
        ImpactRequest request = ImpactRequest.penetrating(1L, new Vec3d(0, 64, 0),
                new Vec3d(0, 0, 0), 5000, ImpactKind.KINETIC);
        Vec3d dir = request.getDirection();
        assertEquals(0.0D, dir.x + dir.y + dir.z, 0.0D);
    }

    @Test
    public void aRefusedDuplicateSpendsNothingAndHandsTheWholeBudgetBack() {
        DamageReport report = DamageReport.duplicate(7000);
        assertEquals(DamageOutcome.NOTHING_STRUCK, report.getOutcome());
        assertEquals(StopReason.DUPLICATE_IMPACT, report.getStopReason());
        assertEquals("a refused duplicate must not charge the caller a second time", 0,
                report.getBudgetSpent());
        assertEquals("the caller keeps its budget, so a retry that meets the refusal is not a loss",
                7000, report.getBudgetLeft());
        assertEquals(0, report.getBlocksStaged());
        assertEquals(0, report.getBlocksDestroyed());
        assertNull(report.getEntryPoint());
    }

    @Test
    public void aRequestWithNoModeStatedResolvesAsPenetrating() {
        // The by-point call is the one weapons make; defaulting it to the geometric mode keeps a
        // caller that forgot from silently getting the star's flank-bathing behaviour instead.
        ImpactRequest request = new ImpactRequest(1L, new Vec3d(0, 64, 0), new Vec3d(1, 0, 0),
                100, ImpactKind.KINETIC, null);
        assertEquals(SelectionMode.PENETRATING, request.getSelectionMode());
    }
}
