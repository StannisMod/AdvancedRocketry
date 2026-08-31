package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.weapon.GunSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A part in poor condition gives less of what it gives.
 *
 * <p>The claim is about DIRECTION, not about a formula: whatever a part contributes, a damaged one
 * contributes less of it — including the contributions that are negative, because a barrel exists to
 * tighten the cone and a ruined barrel must tighten it less rather than as though nothing had
 * happened. That last one is the case a naive "multiply the number" gets backwards, so it has its
 * own test.</p>
 */
public class GunPartConditionTest {

    private static final double EPSILON = 1.0E-9D;

    /** A whole part contributes wholly — the scale is a modifier, not a new pricing. */
    @Test
    public void aPristinePartContributesExactlyWhatItSays() {
        GunSpec pristine = new GunSpec.Builder()
                .withContributionScale(1.0D)
                .addMuzzleSpeed(2.0D).addImpactEnergy(40).countPart()
                .build();
        GunSpec unscaled = new GunSpec.Builder()
                .addMuzzleSpeed(2.0D).addImpactEnergy(40).countPart()
                .build();

        assertEquals(unscaled.getMuzzleSpeed(), pristine.getMuzzleSpeed(), EPSILON);
        assertEquals(unscaled.getImpactEnergy(), pristine.getImpactEnergy());
    }

    /** Halve the condition, halve what it adds. */
    @Test
    public void aDamagedPartAddsLess() {
        GunSpec whole = new GunSpec.Builder()
                .addMuzzleSpeed(2.0D).addImpactEnergy(40).countPart().build();
        GunSpec half = new GunSpec.Builder()
                .withContributionScale(0.5D)
                .addMuzzleSpeed(2.0D).addImpactEnergy(40).countPart().build();

        assertTrue("a damaged part must not add as much speed: " + half.getMuzzleSpeed(),
                half.getMuzzleSpeed() < whole.getMuzzleSpeed());
        assertTrue("nor as much energy: " + half.getImpactEnergy(),
                half.getImpactEnergy() < whole.getImpactEnergy());
    }

    /**
     * The one that is easy to get backwards. Spread is contributed NEGATIVELY — a barrel makes a gun
     * truer — so a damaged barrel must leave the cone WIDER than a whole one, never tighter.
     */
    @Test
    public void aDamagedBarrelTightensTheConeLessRatherThanMore() {
        GunSpec whole = new GunSpec.Builder()
                .addSpreadDegrees(-2.0D).countPart().build();
        GunSpec battered = new GunSpec.Builder()
                .withContributionScale(0.25D)
                .addSpreadDegrees(-2.0D).countPart().build();

        assertTrue("a battered barrel made the gun MORE accurate than a whole one: "
                + battered.getSpreadDegrees() + " vs " + whole.getSpreadDegrees(),
                battered.getSpreadDegrees() > whole.getSpreadDegrees());
    }

    /** A part damaged to nothing is still bolted on: it counts, and contributes almost nothing. */
    @Test
    public void aPartDamagedToNothingStillCounts() {
        GunSpec ruined = new GunSpec.Builder()
                .withContributionScale(0.0D)
                .addMuzzleSpeed(2.0D).addImpactEnergy(40).countPart()
                .build();

        assertEquals("it is still part of the build", 1, ruined.getPartCount());
        assertEquals("and it gives nothing", 0.0D, ruined.getMuzzleSpeed(), EPSILON);
        assertEquals(0, ruined.getImpactEnergy());
    }

    /** The scale applies to the part it was set for, and does not leak into the next one. */
    @Test
    public void theScaleIsPerPart() {
        GunSpec mixed = new GunSpec.Builder()
                .withContributionScale(0.0D).addImpactEnergy(40).countPart()
                .withContributionScale(1.0D).addImpactEnergy(40).countPart()
                .build();

        assertEquals("a ruined part must not silence the intact one beside it", 40,
                mixed.getImpactEnergy());
    }
}
