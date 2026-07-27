package zmaster587.advancedRocketry.test.unit;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.hyperdrive.ComponentScan;
import zmaster587.advancedRocketry.hyperdrive.DampenerField;
import zmaster587.advancedRocketry.hyperdrive.DriveTuning;
import zmaster587.advancedRocketry.hyperdrive.JumpSpeed;
import zmaster587.advancedRocketry.hyperdrive.ShipDriveStats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shape of the hyperdrive's numbers — never the numbers themselves.
 *
 * <p>Every value these machines produce is a balance knob, so nothing here pins one. What is pinned
 * is what a player can rely on while he is building: a bigger machine is a better machine, a
 * stronger drive costs more to start and more to run, a heavier hull on the same drive is slower,
 * and a dampener protects who is near it and nobody else.</p>
 */
public class HyperdriveStatsTest {

    /** A world made of a set of positions, so a scan can be driven with no Minecraft at all. */
    private static ComponentScan.Component blocks(final String kind, final Set<BlockPos> present) {
        return new ComponentScan.Component() {
            @Override
            public String kindAt(BlockPos pos) {
                return present.contains(pos) ? kind : null;
            }
        };
    }

    // ─── The scan ──────────────────────────────────────────────────────────────

    @Test
    public void theScanCountsWhatIsWeldedToTheController() {
        Set<BlockPos> coils = new HashSet<>();
        for (int i = 1; i <= 5; i++) {
            coils.add(new BlockPos(i, 0, 0));
        }

        ComponentScan.Result result = ComponentScan.from(BlockPos.ORIGIN, blocks("coil", coils), 64);

        assertEquals(5, result.count("coil"));
        assertFalse(result.truncated());
    }

    @Test
    public void theScanStopsAtItsOwnEdge() {
        // A coil across a gap belongs to somebody else's machine, or to nothing at all. Counting it
        // would let two ships parked side by side lend each other power.
        Set<BlockPos> coils = new HashSet<>();
        coils.add(new BlockPos(1, 0, 0));
        coils.add(new BlockPos(5, 0, 0)); // detached

        ComponentScan.Result result = ComponentScan.from(BlockPos.ORIGIN, blocks("coil", coils), 64);

        assertEquals("only the connected coil counts", 1, result.count("coil"));
    }

    @Test
    public void theScanIsBoundedAndSaysWhenItStopped() {
        Set<BlockPos> coils = new HashSet<>();
        for (int i = 1; i <= 50; i++) {
            coils.add(new BlockPos(i, 0, 0));
        }

        ComponentScan.Result result = ComponentScan.from(BlockPos.ORIGIN, blocks("coil", coils), 10);

        assertEquals(10, result.count("coil"));
        assertTrue("a capped scan must admit it was capped rather than under-report silently",
                result.truncated());
    }

    // ─── The drive's stats ─────────────────────────────────────────────────────

    @Test
    public void aBiggerGeneratorIsABetterGenerator() {
        ShipDriveStats small = ShipDriveStats.ofPower(2_000L);
        ShipDriveStats large = ShipDriveStats.ofPower(20_000L);

        assertTrue("more power crosses deeper wells and crosses them faster",
                large.drivePower() > small.drivePower());
        assertTrue("and signs the player up for a bigger capacitor",
                large.burstCost() > small.burstCost());
        assertTrue("and a heavier bill for the flight itself",
                large.inFlightDraw() > small.inFlightDraw());
    }

    @Test
    public void aShipWithNoGeneratorHasNoDrive() {
        assertFalse(ShipDriveStats.NONE.present());
        assertEquals(0L, ShipDriveStats.NONE.burstCost());
        assertFalse("a generator of zero power is the same thing as no generator",
                ShipDriveStats.ofPower(0L).present());
    }

    @Test
    public void driveStatsSurviveAnNbtRoundTrip() {
        ShipDriveStats original = ShipDriveStats.ofPower(12_345L);
        NBTTagCompound nbt = new NBTTagCompound();
        original.writeToNBT(nbt);

        ShipDriveStats restored = ShipDriveStats.readFromNBT(nbt);

        assertEquals(original.drivePower(), restored.drivePower());
        assertEquals(original.inFlightDraw(), restored.inFlightDraw());
        assertEquals(original.burstCost(), restored.burstCost());
    }

    // ─── Speed ─────────────────────────────────────────────────────────────────

    @Test
    public void aHeavierShipOnTheSameDriveIsSlower() {
        long light = JumpSpeed.blocksPerTick(DriveTuning.BASELINE_DRIVE_POWER, 1_000L);
        long heavy = JumpSpeed.blocksPerTick(DriveTuning.BASELINE_DRIVE_POWER, 100_000L);

        assertTrue("mass is what makes a cruiser need a cruiser's drive", heavy < light);
    }

    @Test
    public void aStrongerDriveOnTheSameHullIsFaster() {
        long weak = JumpSpeed.blocksPerTick(1_000L, DriveTuning.BASELINE_SHIP_MASS);
        long strong = JumpSpeed.blocksPerTick(50_000L, DriveTuning.BASELINE_SHIP_MASS);

        assertTrue(strong > weak);
    }

    @Test
    public void evenAnAbsurdlyOverloadedShipStillMoves() {
        // The transit integrator refuses a zero step, so a ship that computes to "slower than one
        // block per tick" must round up to one rather than becoming a permanent fixture of
        // hyperspace.
        long speed = JumpSpeed.blocksPerTick(1L, Long.MAX_VALUE / 2L);

        assertTrue("a crawling ship is a slow ship, not a stuck one", speed >= 1L);
    }

    @Test
    public void aShipWithNoDriveHasNoSpeedAtAll() {
        assertEquals("refused upstream, not flown slowly", 0L, JumpSpeed.blocksPerTick(0L, 100L));
    }

    @Test
    public void theForecastAgreesWithTheFlight() {
        // The pilot commits on the strength of an ETA. It has to be the same arithmetic the transit
        // actually runs: distance divided by speed, rounded the same way.
        assertEquals(10L, JumpSpeed.transitTicks(1_000.0D, 100L));
        assertEquals("a partial step still costs a whole tick", 11L,
                JumpSpeed.transitTicks(1_001.0D, 100L));
    }

    // ─── Dampeners ─────────────────────────────────────────────────────────────

    @Test
    public void aDampenerProtectsWhoIsNearIt() {
        BlockPos crew = new BlockPos(0, 64, 0);
        java.util.List<BlockPos> near = java.util.Collections.singletonList(new BlockPos(2, 64, 0));

        long residual = DampenerField.residualSpeed(400_000L, crew, near, 500_000L);

        assertEquals("a dampener rated above the exit speed leaves nothing to feel", 0L, residual);
        assertEquals(0.0F, DampenerField.crewImpact(residual), 0.0001F);
    }

    @Test
    public void aDampenerProtectsNobodyElse() {
        BlockPos crew = new BlockPos(0, 64, 0);
        java.util.List<BlockPos> faraway =
                java.util.Collections.singletonList(new BlockPos(500, 64, 0));

        long residual = DampenerField.residualSpeed(400_000L, crew, faraway, 500_000L);

        assertEquals("which is why a big ship needs several", 400_000L, residual);
        assertTrue(DampenerField.crewImpact(residual) > 0.0F);
    }

    @Test
    public void speedBeyondTheDampenersTierGetsThrough() {
        BlockPos crew = new BlockPos(0, 64, 0);
        java.util.List<BlockPos> near = java.util.Collections.singletonList(new BlockPos(1, 64, 0));

        long residual = DampenerField.residualSpeed(900_000L, crew, near, 500_000L);

        assertEquals("tier decides how much it eats, not whether it eats everything",
                400_000L, residual);
        assertTrue(DampenerField.crewImpact(residual) > 0.0F);
    }

    @Test
    public void anUnprotectedCrewOnAPlannedArrivalIsStillUnhurt() {
        // Dampeners exist for emergency exits. A flight that ends the way it meant to has no speed
        // to dump into anybody, dampeners or not.
        long residual = DampenerField.residualSpeed(0L, new BlockPos(0, 64, 0),
                java.util.Collections.<BlockPos>emptyList(), 500_000L);

        assertEquals(0L, residual);
        assertEquals(0.0F, DampenerField.crewImpact(residual), 0.0001F);
    }
}
