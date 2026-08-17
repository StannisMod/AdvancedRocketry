package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.sensor.SignatureModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The one claim this model exists to make: <b>how far away a thing can be noticed and how well it
 * can be held are different questions with different answers.</b>
 *
 * <p>Everything below is a way of failing if those two ever collapse into one number. Nothing here
 * pins a constant — the reference temperature, the reference range and the blocks-per-watt scale are
 * balance, and a test that pinned them would go red the first time somebody tuned the mechanic
 * without changing anything about how it works. What is pinned is the SHAPE: which variable each
 * term depends on, and which it must be blind to.</p>
 */
public class SignatureModelTest {

    private static final double EPSILON = 1.0E-9D;

    /**
     * The whole design in one scenario. Two ships shed <b>exactly the same watts</b> — one a large
     * cool array, the other a compact array sixteen times hotter and sixteen times smaller. They are
     * therefore noticed at the same range, and they are not remotely the same target: the compact
     * hot one is held sixteen times better at any distance.
     *
     * <p>This fails the moment either term is computed from the other's input — if range stopped
     * depending on area, or quality started to.</p>
     */
    @Test
    public void twoTargetsSheddingTheSameWattsAreNoticedAlikeAndHeldNothingAlike() {
        double coolTemperature = 300.0D, coolArea = 160.0D;
        double hotTemperature = coolTemperature * 2.0D;      // radiance ×16
        double hotArea = coolArea / 16.0D;                   // so the total power is identical

        assertEquals("the scenario is only about the difference between the two terms if the total"
                + " power really is equal",
                SignatureModel.radiatedPower(coolTemperature, coolArea),
                SignatureModel.radiatedPower(hotTemperature, hotArea), 1.0E-6D);

        assertEquals("equal total power must mean an equal detection range — that term may not see"
                + " temperature except through the power it produces",
                SignatureModel.detectionRangeBlocks(coolTemperature, coolArea),
                SignatureModel.detectionRangeBlocks(hotTemperature, hotArea), 1.0E-6D);

        double range = 200.0D;
        double coolLock = SignatureModel.passiveQuality(coolTemperature, range);
        double hotLock = SignatureModel.passiveQuality(hotTemperature, range);
        assertTrue("this comparison means nothing if either quality is clamped",
                coolLock > 0.0D && hotLock > 0.0D && hotLock < 1.0D);
        assertEquals("the hotter array must be sixteen times the lock at the same range: quality is"
                + " radiance, and radiance is temperature to the fourth — if area leaked into this"
                + " term, these two would be equal and the build trade would be gone",
                16.0D, hotLock / coolLock, 1.0E-6D);
    }

    /**
     * The two terms disagree about which of two targets is the better one, and that disagreement is
     * the mechanic. A large cool radiator sheds MORE total power than a small hot one — so it is
     * noticed from further away — while the small hot one is the better lock at any given range.
     */
    @Test
    public void aLargeCoolTargetIsSeenFurtherOffAndHeldWorseThanASmallHotOne() {
        double coolTemperature = 350.0D, coolArea = 400.0D;
        double hotTemperature = 900.0D, hotArea = 1.0D;
        assertTrue("this scenario needs the cool one to be the brighter total emitter, or it is not"
                + " testing the disagreement at all",
                SignatureModel.radiatedPower(coolTemperature, coolArea)
                        > SignatureModel.radiatedPower(hotTemperature, hotArea));

        assertTrue("the bigger total emitter must be detectable further away",
                SignatureModel.detectionRangeBlocks(coolTemperature, coolArea)
                        > SignatureModel.detectionRangeBlocks(hotTemperature, hotArea));
        assertTrue("and the hotter one must still be the better lock at the same range — if the"
                + " brighter total emitter also locked better, there would be one number here",
                SignatureModel.passiveQuality(hotTemperature, 60.0D)
                        > SignatureModel.passiveQuality(coolTemperature, 60.0D));
    }

    /** Range falls off with the square: twice as far is a quarter as well held. */
    @Test
    public void passiveQualityFallsWithTheSquareOfTheRange() {
        double near = SignatureModel.passiveQuality(400.0D, 40.0D);
        double far = SignatureModel.passiveQuality(400.0D, 80.0D);
        assertTrue("this scenario needs a quality that is not already clamped at either end",
                near > 0.0D && near < 1.0D && far > 0.0D);
        assertEquals("doubling the range must quarter the quality", near / 4.0D, far, near * 1.0E-6D);
    }

    /**
     * The reason the active mode exists: a cold, quiet thing that passive listening cannot hold is
     * held perfectly well the moment you illuminate it — at the price of illuminating.
     */
    @Test
    public void illuminatingHoldsAColdTargetThatListeningCannot() {
        double coldTarget = 280.0D;
        double range = 90.0D;
        double listening = SignatureModel.passiveQuality(coldTarget, range);
        double illuminating = SignatureModel.activeQuality(range, 128.0D, 0.95D);

        assertTrue("a cold target at range must be nearly unresolvable by listening alone, or going"
                + " dark buys a target nothing: " + listening, listening < 0.1D);
        assertTrue("and illuminating must hold it, or going active buys the shooter nothing: "
                + illuminating, illuminating > 0.5D);
    }

    /** Nothing is invisible: silence moves the line at which a thing is noticed, it does not erase it. */
    @Test
    public void aSilentColdBodyIsStillDetectableSomewhere() {
        double range = SignatureModel.detectionRangeBlocks(SignatureModel.AMBIENT_BODY_KELVIN, 2.0D);
        assertTrue("an ordinary warm body radiates above the background and must be detectable at"
                + " some finite range: " + range, range > 0.0D);
    }

    /** Outside the envelope an illuminator holds nothing at all — the radius is a real limit. */
    @Test
    public void illuminationStopsAtTheEdgeOfTheEnvelope() {
        assertEquals("a contact beyond the sensor's radius is not held, however bright the beam",
                0.0D, SignatureModel.activeQuality(130.0D, 128.0D, 0.95D), EPSILON);
        assertTrue("and just inside it is held, so the zero above is about the edge and not about"
                + " the whole mode being dead",
                SignatureModel.activeQuality(120.0D, 128.0D, 0.95D) > 0.0D);
    }
}
