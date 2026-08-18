package zmaster587.advancedRocketry.mixin;

import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import zmaster587.advancedRocketry.api.Constants;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for {@code mixins.advancedrocketry.json}.
 *
 * <p>Its only job: gate the three per-dimension WorldInfo mixins
 * ({@link MixinWorldServerMulti} wrapper install, {@code MixinWorldServer}
 * per-dim time / sleep, {@link MixinPlayerList} weather sync) on the
 * {@code perDimWorldInfo} MASTER config flag, so that with the per-dimension
 * WorldInfo subsystem turned off those mixins are never woven into their target
 * classes at all — not merely no-ops at runtime. The other mixins (gravity,
 * atmosphere block-place, rocket inventory access) are unrelated and always
 * apply.</p>
 *
 * <p><b>Timing.</b> The config is read EAGERLY, once, in {@link #onLoad} during
 * the coremod phase; only the per-target <em>consultation</em>
 * ({@link #shouldApplyMixin}) is lazy, as each target class first loads
 * ({@code WorldServerMulti} at dimension creation, {@code PlayerList} at server
 * start). The snapshot is therefore frozen for the life of the JVM. We read the
 * {@code .cfg} directly rather than going through
 * {@link zmaster587.advancedRocketry.api.ARConfiguration}, because that
 * singleton is populated in mod pre-init, which runs AFTER the coremod phase
 * that constructs this plugin — at {@code onLoad} time there is no loaded
 * config to ask.</p>
 *
 * <p><b>Fail-open.</b> If the config can't be read for any reason (missing
 * file on first launch, parse error), we default to {@code true} — i.e. the
 * WorldInfo mixins apply, exactly as they did before this plugin existed. A
 * disabled-by-accident subsystem would be a worse surprise than the
 * pre-existing always-on behaviour. Two of the three gated mixins additionally
 * re-check the flag at runtime, so a fail-open weave leaves them inert rather
 * than active; {@code MixinPlayerList} is the exception. Which branch fired is
 * logged, because a gate stuck open is otherwise invisible.</p>
 */
public class ARMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry");

    /** Fully-qualified names of the per-dimension WorldInfo mixins gated by the
     *  {@code perDimWorldInfo} master flag: wrapper install, per-dim time/sleep,
     *  and weather sync. */
    private static final String MIXIN_WORLD_SERVER_MULTI =
            "zmaster587.advancedRocketry.mixin.MixinWorldServerMulti";
    private static final String MIXIN_PLAYER_LIST =
            "zmaster587.advancedRocketry.mixin.MixinPlayerList";
    private static final String MIXIN_WORLD_SERVER =
            "zmaster587.advancedRocketry.mixin.MixinWorldServer";

    private boolean perDimWorldInfo = true;
    private boolean allowTimeSkipOnPlanets = false;
    private boolean allowTimeSkipOnOverworld = true;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            // Resolve against the REAL game directory rather than the process
            // CWD. Launch.minecraftHome is null unless --gameDir was passed, so
            // mirror FMLTweaker's own fallback instead of dereferencing it.
            File gameDir = net.minecraft.launchwrapper.Launch.minecraftHome;
            File cfgFile = new File(gameDir != null ? gameDir : new File("."),
                    "config/advRocketry/advancedRocketry.cfg");
            if (cfgFile.isFile()) {
                // The Configuration(File) constructor loads the file itself.
                Configuration cfg = new Configuration(cfgFile);
                perDimWorldInfo = cfg
                        .get(Constants.CONFIG_CATEGORY_PLANET,
                                Constants.CONFIG_KEY_PER_DIM_WORLD_INFO, true)
                        .getBoolean(true);
                allowTimeSkipOnPlanets = cfg
                        .get("Planet", "allowTimeSkipOnPlanets", false)
                        .getBoolean(false);
                allowTimeSkipOnOverworld = cfg
                        .get("Planet", "allowTimeSkipOnOverworld", true)
                        .getBoolean(true);
            } else {
                // First launch: the file does not exist yet. The flag's own
                // default is true, so nothing can have been disabled — but say
                // so, or a mis-resolved path looks identical to a fresh install.
                LOGGER.info("AR mixin gate: no config at {} — defaulting {}=true",
                        cfgFile.getAbsolutePath(), Constants.CONFIG_KEY_PER_DIM_WORLD_INFO);
            }
        } catch (Throwable t) {
            // Fail-open: behave exactly as before the plugin (mixins on).
            perDimWorldInfo = true;
            allowTimeSkipOnPlanets = false;
            allowTimeSkipOnOverworld = true;
            LOGGER.warn("AR mixin gate: could not read {} — defaulting to true, "
                            + "per-dimension WorldInfo mixins WILL be woven",
                    Constants.CONFIG_KEY_PER_DIM_WORLD_INFO, t);
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return shouldApply(perDimWorldInfo, allowTimeSkipOnPlanets, allowTimeSkipOnOverworld,
                mixinClassName);
    }

    /**
     * Pure decision function (no I/O, no state) so it can be unit-tested
     * directly: the three per-dimension WorldInfo mixins (wrapper install,
     * per-dim time/sleep, weather sync) apply iff {@code perDimWorldInfo} is
     * enabled; every other mixin always applies.
     */
    public static boolean shouldApply(boolean perDimWorldInfoEnabled, String mixinClassName) {
        return shouldApply(perDimWorldInfoEnabled, true, true, mixinClassName);
    }

    /**
     * The same decision, told about the time-skip policy as well.
     *
     * <p>{@code MixinWorldServer} owns TWO things that happen at the same call: the per-dimension
     * rounding of a sleep to the planet's own dawn (which needs {@code perDimWorldInfo}, since
     * without it there is no per-dimension clock to round), and the refusal to skip at all
     * (which does not). So it is woven whenever EITHER is wanted. Without this, turning off
     * {@code allowTimeSkipOnOverworld} on a pack that also runs {@code perDimWorldInfo=false} would
     * be a flag that silently does nothing — the mixin carrying its only seam would never be woven.
     * The mixin's own body still checks {@code perDimWorldInfo} at runtime, so weaving it in that
     * configuration changes nothing else.</p>
     */
    public static boolean shouldApply(boolean perDimWorldInfoEnabled, boolean allowSkipOnPlanets,
                                      boolean allowSkipOnOverworld, String mixinClassName) {
        if (MIXIN_WORLD_SERVER.equals(mixinClassName)) {
            return perDimWorldInfoEnabled || !allowSkipOnPlanets || !allowSkipOnOverworld;
        }
        if (MIXIN_WORLD_SERVER_MULTI.equals(mixinClassName)
                || MIXIN_PLAYER_LIST.equals(mixinClassName)) {
            return perDimWorldInfoEnabled;
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
