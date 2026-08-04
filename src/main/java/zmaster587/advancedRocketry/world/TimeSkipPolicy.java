package zmaster587.advancedRocketry.world;

import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.world.provider.WorldProviderAsteroid;
import zmaster587.advancedRocketry.world.provider.WorldProviderPlanet;
import zmaster587.advancedRocketry.world.provider.WorldProviderSpace;

/**
 * Whether a world's time of day may be SKIPPED — jumped forward by a bed or by {@code /time} —
 * as opposed to advancing on its own.
 *
 * <p>A planet's day is the turning of a body in an orbit. A bed that fast-forwards it, and a command
 * that sets it to noon everywhere at once, are arcade conveniences that contradict that: they are
 * what Minecraft is, not what a planet is. So they become a choice — kept on the overworld, where a
 * player is still playing Minecraft, and off past it, where he is somewhere that turns at its own
 * rate.</p>
 *
 * <p><b>This governs the SKIPS and nothing else.</b> The natural advance of one tick per tick is
 * untouched: a locked planet still turns, its sun still rises, and its night still ends — you just
 * have to be there when it does.</p>
 *
 * <p>Pure decision surface, deliberately: {@link #allows(boolean, boolean, boolean, boolean)} takes
 * every input as an argument so the whole policy is testable without a world, a server or a config
 * file.</p>
 */
public final class TimeSkipPolicy {

    private TimeSkipPolicy() {
    }

    /**
     * The policy, as arithmetic.
     *
     * @param isOverworld    dimension 0
     * @param isPlanet       an Advanced Rocketry PLANET (see {@link #isPlanet(WorldProvider)} for
     *                       what that deliberately excludes)
     * @param allowOnPlanets the {@code allowTimeSkipOnPlanets} config flag
     * @param allowOnOverworld the {@code allowTimeSkipOnOverworld} config flag
     * @return whether a bed or {@code /time} may move this world's clock
     */
    public static boolean allows(boolean isOverworld, boolean isPlanet,
                                 boolean allowOnPlanets, boolean allowOnOverworld) {
        // The overworld is asked FIRST and unconditionally. A pack may hand dimension 0 planetary
        // properties, and if it does, the flag a player reaches for is still the one with
        // "Overworld" in its name.
        if (isOverworld) {
            return allowOnOverworld;
        }
        if (isPlanet) {
            return allowOnPlanets;
        }
        // ANYTHING ELSE IS NOT OURS TO DECIDE. The Nether, the End, another mod's world - Advanced
        // Rocketry does not get an opinion about how a bed works in them.
        return true;
    }

    /**
     * Whether {@code provider} is a planet for this policy's purposes.
     *
     * <p><b>Space stations and asteroid fields are excluded on purpose</b>, and the exclusion has to
     * be written out because both {@link WorldProviderSpace} and {@link WorldProviderAsteroid}
     * EXTEND {@link WorldProviderPlanet} — the plain {@code instanceof} that reads correctly sweeps
     * them in. Neither is a body turning under a sun: a station's celestial angle comes from an
     * entirely different path, and there is no dawn on an asteroid to wait for. They keep whatever
     * they do today and neither flag reaches them.</p>
     */
    public static boolean isPlanet(WorldProvider provider) {
        return provider instanceof WorldProviderPlanet
                && !(provider instanceof WorldProviderSpace)
                && !(provider instanceof WorldProviderAsteroid);
    }

    /** The live decision for {@code world}. {@code true} (permissive) when the config is unreadable. */
    public static boolean allows(World world) {
        if (world == null) {
            return true;
        }
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();
        if (cfg == null) {
            return true;
        }
        return allows(world.provider.getDimension() == 0, isPlanet(world.provider),
                cfg.allowTimeSkipOnPlanets, cfg.allowTimeSkipOnOverworld);
    }
}
