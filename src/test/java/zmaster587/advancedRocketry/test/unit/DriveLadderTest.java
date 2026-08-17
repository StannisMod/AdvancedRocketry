package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import zmaster587.advancedRocketry.hyperdrive.DriveTier;
import zmaster587.advancedRocketry.hyperdrive.DriveTuning;
import zmaster587.advancedRocketry.hyperdrive.JumpSpeed;
import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.StarSystem;
import zmaster587.advancedRocketry.universe.UniverseScale;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The progression the hyperdrive family is built around: <b>size buys POWER, the generation buys
 * EFFICIENCY</b>, and each generation owns one band of distance.
 *
 * <p>What is pinned here is the SHAPE of the ladder and nothing about its tuning. A test that asserted
 * "a full drive crosses a galaxy in 28 minutes" would fail the day anybody rebalanced, without anything
 * having broken. What may be asserted is the relations that make the ladder a ladder:</p>
 * <ul>
 *   <li>a full build of each generation crosses ITS OWN band in the same time — that is what "one tier
 *       per band" means, and it holds at any exponent and any baseline speed;</li>
 *   <li>a route's total energy does not depend on drive POWER — the property that makes "the tier buys
 *       efficiency" arithmetic rather than a slogan;</li>
 *   <li>nothing is ever refused for being far;</li>
 *   <li><b>a fully built drive can open its own window</b> — the invariant that decides how far the
 *       power law may be bent.</li>
 * </ul>
 */
public class DriveLadderTest {

    /** Distances are quoted in light years and flown in blocks; this is the one conversion. */
    private static double blocksForLightYears(double lightYears) {
        return lightYears * (double) AstronomicalBodyHelper.BLOCKS_PER_LIGHT_YEAR;
    }

    /** A fully built generator of {@code tier}, hauling the baseline hull. */
    private static long fullBuildSpeed(DriveTier tier) {
        return JumpSpeed.blocksPerTick(DriveTuning.MAX_DRIVE_POWER,
                DriveTuning.PLACEHOLDER_SHIP_MASS, tier);
    }

    // ── the ladder ────────────────────────────────────────────────────────────

    @Test
    public void aFullBuildOfEachGenerationCrossesITSOWNBandInTheSameTime() {
        // THE defining property, and the reason a generation's efficiency is derived rather than
        // chosen: a tier's efficiency IS the gap between its band and the previous one, so a player who
        // has finished building one generation and then installs the next stands in the same relation to
        // the new band as he did to the old. Independent of the exponent, the baseline speed and the
        // hull mass — which is exactly why it is the thing worth pinning.
        long interstellar = JumpSpeed.transitTicks(
                blocksForLightYears(DriveTier.INTERSTELLAR.bandLightYears()),
                fullBuildSpeed(DriveTier.INTERSTELLAR));
        long galactic = JumpSpeed.transitTicks(
                blocksForLightYears(DriveTier.GALACTIC.bandLightYears()),
                fullBuildSpeed(DriveTier.GALACTIC));

        System.out.println(String.format(
                "full build: interstellar band %.2f ly -> %d ticks (%.1f min); galactic band %.0f ly"
                        + " -> %d ticks (%.1f min)",
                DriveTier.INTERSTELLAR.bandLightYears(), interstellar, interstellar / 1200d,
                DriveTier.GALACTIC.bandLightYears(), galactic, galactic / 1200d));

        assertTrue("a band that takes no time at all is not a flight", interstellar > 0L);
        double ratio = galactic / (double) interstellar;
        assertEquals("each generation must stand in the same relation to its own band as the previous"
                + " one does to its own; the two crossings came out " + interstellar + " vs "
                + galactic + " ticks", 1d, ratio, 0.01d);
    }

    @Test
    public void theGalacticGenerationIsWorthMoreThanEveryCoilOnTheShip() {
        // A generation is only felt as an upgrade if it beats a MAXED build of the previous one, because
        // installing it puts the player back at a handful of coils. This is the condition that decides
        // how many tiers exist at all: a band gap smaller than what iron already buys is a tier that
        // would make its owner slower.
        double boughtBySize = DriveTuning.MAX_DRIVE_POWER / (double) DriveTuning.BASELINE_DRIVE_POWER;
        double boughtByTier = DriveTier.GALACTIC.efficiency();
        System.out.println(String.format(
                "size buys x%.0f (%d coils -> %d power); the galactic generation buys x%.0f",
                boughtBySize, DriveTuning.MAX_COILS, DriveTuning.MAX_DRIVE_POWER, boughtByTier));
        assertTrue("a fresh galactic drive (" + (long) boughtByTier + "x) must beat a maxed"
                        + " interstellar one (" + (long) boughtBySize + "x), or installing it is a"
                        + " downgrade wearing an upgrade's name",
                boughtByTier > boughtBySize);
    }

    @Test
    public void aRoutesENERGYdoesNotDependOnHowBigTheDriveIs() {
        // The property that makes "size buys power, the tier buys efficiency" literal: ticks go as
        // d.m/(eta.P) and the in-flight draw goes as P, so power cancels exactly. A bigger drive does
        // not change the bill for a trip, only how fast it is paid. If this ever stops holding, size
        // has started buying part of the efficiency and the two knobs have blurred into one.
        double distance = blocksForLightYears(10d);
        double small = JumpSpeed.routeEnergy(distance, DriveTuning.BASELINE_SHIP_MASS,
                DriveTier.INTERSTELLAR);
        double large = JumpSpeed.routeEnergy(distance, DriveTuning.BASELINE_SHIP_MASS,
                DriveTier.INTERSTELLAR);
        assertEquals("route energy must not read drive power at all", small, large, 0d);

        // And the same claim measured THROUGH the speed law rather than off the closed form, because the
        // closed form is where the cancellation could be true while the flight disagreed.
        assertEquals("the closed form and the flight must agree on the bill",
                flownEnergy(distance, DriveTuning.BASELINE_DRIVE_POWER, DriveTier.INTERSTELLAR),
                flownEnergy(distance, DriveTuning.MAX_DRIVE_POWER, DriveTier.INTERSTELLAR),
                flownEnergy(distance, DriveTuning.BASELINE_DRIVE_POWER, DriveTier.INTERSTELLAR)
                        * 0.02d);

        // A later generation is CHEAPER per unit distance — that is what efficiency means.
        assertTrue("a galactic drive must cost less energy for the same leg",
                JumpSpeed.routeEnergy(distance, DriveTuning.BASELINE_SHIP_MASS, DriveTier.GALACTIC)
                        < small);
    }

    /** The bill as actually flown: the per-tick draw times the ticks the speed law produces. */
    private static double flownEnergy(double distance, long drivePower, DriveTier tier) {
        long speed = JumpSpeed.blocksPerTick(drivePower, DriveTuning.BASELINE_SHIP_MASS, tier);
        long ticks = JumpSpeed.transitTicks(distance, speed);
        return ticks * drivePower * DriveTuning.IN_FLIGHT_DRAW_PER_POWER;
    }

    @Test
    public void aBaselineDriveAimedAcrossInterstellarSpaceIsNOTrefused() {
        // A generation is a coefficient, never a licence. The first drive a player builds, aimed at
        // something absurdly far, departs and takes what it takes — the barrier is then life support
        // and generation over that duration, which are real systems, rather than a red message.
        long speed = JumpSpeed.blocksPerTick(DriveTuning.BASELINE_DRIVE_POWER,
                DriveTuning.PLACEHOLDER_SHIP_MASS, DriveTier.INTERSTELLAR);
        long ticks = JumpSpeed.transitTicks(
                blocksForLightYears(DriveTier.GALACTIC.bandLightYears()), speed);

        assertTrue("a baseline drive must still have a speed", speed > 0L);
        assertTrue("and a finite, statable duration for a trip it has no business making: " + ticks,
                ticks > 0L && ticks < Long.MAX_VALUE);
        System.out.println("a baseline drive crosses a galaxy in " + ticks + " ticks ("
                + String.format("%.1f", ticks / 1728000d) + " in-game months) - unreasonable, not"
                + " impossible");
    }

    // ── the invariant that bounds the power law ────────────────────────────────

    @Test
    public void aFULLYBUILTdriveMustBeAbleToOpenItsOwnWindow() {
        // The invariant nobody had written down, and it is what decides how far the power law may be
        // bent. Every energy cost of a drive is proportional to its power — the window burst above all —
        // while the capacitor that pays that burst grows only with its COMPONENT count, which is capped.
        // So the two ranges have to be checked against each other: a drive whose burst outruns any bank
        // a player can build is REFUSED at the gate, which means growing it past some coil count makes
        // it useless. That is a lock, and a lock is the one thing a cost may not become.
        long fullBank = DriveTuning.CAPACITOR_BASE_CAPACITY
                + (long) DriveTuning.MAX_CAPACITOR_COMPONENTS * DriveTuning.CAPACITY_PER_CELL;
        long fullBurst = (long) Math.ceil(DriveTuning.MAX_DRIVE_POWER
                * DriveTuning.BURST_COST_PER_POWER);

        System.out.println(String.format(
                "at exponent %.2f a full drive is %d power, burst %d, against a full bank of %d"
                        + " (margin x%.2f)",
                DriveTuning.COIL_POWER_EXPONENT, DriveTuning.MAX_DRIVE_POWER, fullBurst, fullBank,
                fullBank / (double) fullBurst));

        assertTrue("a fully built drive cannot open its window: burst " + fullBurst
                        + " against a full capacitor bank of " + fullBank
                        + ". Raising COIL_POWER_EXPONENT needs the capacitor economy re-derived with"
                        + " it — see that constant's javadoc for the measured collision.",
                fullBurst <= fullBank);
    }

    @Test
    public void aBaselineDriveStillNEEDSacapacitorBank() {
        // The other end of the same bound, and the reason it cannot be fixed by simply making the burst
        // cheaper: a novice's window must cost more than the controller block holds on its own, or the
        // capacitor stops being something he has to build.
        long baselineBurst = (long) Math.ceil(DriveTuning.BASELINE_DRIVE_POWER
                * DriveTuning.BURST_COST_PER_POWER);
        assertTrue("a baseline window costs " + baselineBurst + ", which a bare controller ("
                        + DriveTuning.CAPACITOR_BASE_CAPACITY + ") already covers — the capacitor has"
                        + " stopped being a requirement",
                baselineBurst > DriveTuning.CAPACITOR_BASE_CAPACITY);
    }

    // ── the two knobs, and the derived numbers that must not detach ────────────

    @Test
    public void theBaselineIsWHATASEVENCOILGENERATORISWORTH_notALiteral() {
        // The entry-level speed is a datum from play and must not move unless somebody moves it. It did
        // move once, silently: the baseline power was a literal that stopped being the seven-coil figure
        // the moment the power law gained an exponent.
        assertEquals("the baseline power must BE the baseline build's power",
                DriveTuning.powerForCoils(DriveTuning.BASELINE_COILS),
                DriveTuning.BASELINE_DRIVE_POWER);
        assertEquals("so a baseline ship flies at exactly the baseline speed",
                DriveTuning.BASELINE_SPEED_BLOCKS_PER_TICK,
                JumpSpeed.blocksPerTick(DriveTuning.BASELINE_DRIVE_POWER,
                        DriveTuning.BASELINE_SHIP_MASS, DriveTier.INTERSTELLAR));
    }

    @Test
    public void aDampenerAbsorbsAFRACTIONofABaselineArrival() {
        // Expressed as a ratio, so what it promises — "a couple of these cover the ship a novice
        // flies" — survives the speed law moving. As an absolute it became a rounding error on the next
        // generation of drive the first time a tier multiplied every arrival.
        long baseline = JumpSpeed.blocksPerTick(DriveTuning.BASELINE_DRIVE_POWER,
                DriveTuning.BASELINE_SHIP_MASS, DriveTier.INTERSTELLAR);
        long absorbed = DriveTuning.DAMPENER_ABSORBED_SPEED;
        int needed = (int) Math.ceil(baseline / (double) absorbed);

        System.out.println("a baseline arrival of " + baseline + " needs " + needed + " dampener(s) at "
                + absorbed + " each");
        assertEquals("one dampener must absorb the configured fraction of a baseline arrival",
                DriveTuning.DAMPENER_ABSORBED_BASELINE_FRACTION,
                absorbed / (double) baseline, 1e-9d);
        assertTrue("and a baseline arrival must need more than one, or the dampener is free",
                needed > 1);
    }

    @Test
    public void theGalacticEfficiencyISTheBandGap_notANumberSomebodyPicked() {
        // Written as a literal it would be a number nobody could check, and one that silently stopped
        // meaning "one band" the first time the star separation or the galaxy size was retuned. It rests
        // on exactly two constants, and this is what says so.
        double expected = 2d * UniverseScale.REFERENCE_GALAXY_RADIUS_LY
                / UniverseScale.MEAN_STAR_SEPARATION_LY;
        assertEquals("the galactic generation's efficiency must BE the star -> galaxy gap", expected,
                DriveTier.GALACTIC.efficiency(), 1e-9d);
        assertEquals("the baseline generation is the unit every other is quoted against", 1d,
                DriveTier.INTERSTELLAR.efficiency(), 0d);
    }

    // ── measured through the real generator, over the same 20 seeds ────────────

    @Test
    public void theInterstellarBandIsWhatTheGeneratorActuallyProduces() {
        // The band figures above are arithmetic on two constants; this is the same span measured through
        // the real generator, over the same 20 seeds the leg reading uses. If the generator's actual
        // nearest-neighbour distance drifts away from the separation the ladder is derived against, the
        // tiers are no longer aimed at the bands they are named for.
        final double TOLERANCE_FACTOR = 2d;

        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(GalaxyGenConfig.defaults());
        long stride = 4L * GalaxyGenConfig.DEFAULT_MIN_SPACING;
        List<Double> legs = new ArrayList<>();
        for (long seed = 1L; seed <= 20L; seed++) {
            Map<GalacticCoord, StarSystem> found = gen.systemsInRegion(seed,
                    cell(-stride, -stride, -stride), cell(stride, stride, stride));
            GalacticCoord home = nearestTo(found.keySet(), cell(0L, 0L, 0L));
            GalacticCoord neighbour = home == null ? null : nearestTo(found.keySet(), home);
            if (neighbour == null) {
                continue;
            }
            legs.add(CellFrames.STATIC.distanceBetween(home, neighbour, 0L)
                    / (double) AstronomicalBodyHelper.BLOCKS_PER_LIGHT_YEAR);
        }
        Collections.sort(legs);
        assertTrue("no seed produced a pair of systems to measure a leg from", !legs.isEmpty());

        double median = legs.get(legs.size() / 2);
        double declared = DriveTier.INTERSTELLAR.bandLightYears();
        System.out.println(String.format(
                "interstellar band: declared %.2f ly, measured median %.2f ly over %d seeds"
                        + " (min %.2f, max %.2f)",
                declared, median, legs.size(), legs.get(0), legs.get(legs.size() - 1)));

        assertTrue("the measured leg " + String.format("%.2f", median) + " ly is not the band the"
                        + " interstellar generation is named for (" + String.format("%.2f", declared)
                        + " ly) within a factor of " + TOLERANCE_FACTOR,
                median >= declared / TOLERANCE_FACTOR && median <= declared * TOLERANCE_FACTOR);
    }

    private static GalacticCoord cell(long sx, long sy, long sz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, 0L, 0L, 0L);
    }

    private static GalacticCoord nearestTo(java.util.Collection<GalacticCoord> cells,
                                           GalacticCoord from) {
        GalacticCoord best = null;
        double bestDist = Double.MAX_VALUE;
        for (GalacticCoord c : cells) {
            double d = CellFrames.STATIC.distanceBetween(from, c, 0L);
            if (d > 0d && d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }
}
