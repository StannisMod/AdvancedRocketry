package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.world.TimeSkipPolicy;
import zmaster587.advancedRocketry.world.provider.WorldProviderAsteroid;
import zmaster587.advancedRocketry.world.provider.WorldProviderPlanet;
import zmaster587.advancedRocketry.world.provider.WorldProviderSpace;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The time-skip policy as arithmetic: WHICH worlds a bed or {@code /time} may fast-forward.
 *
 * <p>The whole decision is a pure function of four booleans, which is the point of it existing as
 * one — the alternative is the same three-way choice spelled out at each call site, where the third
 * branch (a world that is neither the overworld nor an AR planet) is the one that gets forgotten and
 * quietly hands Advanced Rocketry an opinion about how a bed works in somebody else's dimension.</p>
 */
public class TimeSkipPolicyTest {

    @Test
    public void eachWorldClassAnswersToItsOwnFlagAndOnlyToThat() {
        // Overworld: the overworld flag decides, whatever the planet flag says.
        assertTrue("the overworld follows its own flag",
                TimeSkipPolicy.allows(true, false, false, true));
        assertFalse("...in both directions",
                TimeSkipPolicy.allows(true, false, true, false));

        // Planet: the planet flag decides, whatever the overworld flag says.
        assertTrue("a planet follows its own flag",
                TimeSkipPolicy.allows(false, true, true, false));
        assertFalse("...in both directions",
                TimeSkipPolicy.allows(false, true, false, true));
    }

    @Test
    public void theShippedDefaultLocksPlanetsAndLeavesTheOverworldAlone() {
        // The pair a fresh config produces, asserted as a pair: the arcade mechanics survive where
        // a player is still playing Minecraft, and stop where he is standing on a body in an orbit.
        assertTrue("the overworld keeps its bed and its /time by default",
                TimeSkipPolicy.allows(true, false, false, true));
        assertFalse("a planet does not, by default",
                TimeSkipPolicy.allows(false, true, false, true));
    }

    @Test
    public void aWorldThatIsNeitherIsNeverTouchedByEitherFlag() {
        // The Nether, the End, another mod's dimension. Advanced Rocketry does not get a vote, and
        // there is no combination of its own flags that gives it one.
        for (boolean planets : new boolean[]{false, true}) {
            for (boolean overworld : new boolean[]{false, true}) {
                assertTrue("a non-AR world must keep vanilla behaviour at planets=" + planets
                                + " overworld=" + overworld,
                        TimeSkipPolicy.allows(false, false, planets, overworld));
            }
        }
    }

    /**
     * A station and an asteroid field are NOT planets for this policy — and the assertion is here
     * because the obvious predicate gets it wrong: {@link WorldProviderSpace} and
     * {@link WorldProviderAsteroid} both EXTEND {@link WorldProviderPlanet}, so a plain
     * {@code instanceof WorldProviderPlanet} sweeps them in and silently puts them under a flag that
     * was never meant to reach them. Neither has a dawn to wait for.
     */
    @Test
    public void aStationAndAnAsteroidFieldAreNotPlanets() {
        assertTrue("the control: a planet IS a planet, or the three below prove nothing",
                TimeSkipPolicy.isPlanet(new WorldProviderPlanet()));
        assertFalse("a space station is not a planet", TimeSkipPolicy.isPlanet(new WorldProviderSpace()));
        assertFalse("an asteroid field is not a planet",
                TimeSkipPolicy.isPlanet(new WorldProviderAsteroid()));
        assertFalse("and neither is nothing at all", TimeSkipPolicy.isPlanet(null));
    }
}
