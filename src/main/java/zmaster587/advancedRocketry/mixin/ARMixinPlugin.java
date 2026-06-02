package zmaster587.advancedRocketry.mixin;

import net.minecraftforge.common.config.Configuration;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for {@code mixins.advancedrocketry.json}.
 *
 * <p>Its only job today: gate the two WEATHER mixins
 * ({@link MixinWorldServerMulti}, {@link MixinPlayerList}) on the
 * {@code enableCustomPlanetWeather} config flag, so that with custom planet
 * weather turned off those mixins are never woven into their target classes
 * at all — not merely no-ops at runtime. The other mixins (gravity, atmosphere
 * block-place, rocket inventory access) are unrelated to weather and always
 * apply.</p>
 *
 * <p><b>Timing.</b> {@code shouldApplyMixin} is evaluated lazily, when each
 * target class first loads ({@code WorldServerMulti} at dimension creation,
 * {@code PlayerList} at server start) — late enough that the config file
 * exists. We still read the {@code .cfg} directly here rather than going
 * through {@link zmaster587.advancedRocketry.api.ARConfiguration}, because that
 * singleton is populated in mod pre-init, which runs AFTER the coremod phase
 * that constructs this plugin. Reading the file ourselves removes any
 * dependence on mod-lifecycle ordering.</p>
 *
 * <p><b>Fail-open.</b> If the config can't be read for any reason (missing
 * file on first launch, parse error), we default to {@code true} — i.e. the
 * weather mixins apply, exactly as they did before this plugin existed. A
 * disabled-by-accident weather system would be a worse surprise than the
 * pre-existing always-on behaviour.</p>
 */
public class ARMixinPlugin implements IMixinConfigPlugin {

    /** Fully-qualified names of the weather mixins gated by the config flag. */
    private static final String MIXIN_WORLD_SERVER_MULTI =
            "zmaster587.advancedRocketry.mixin.MixinWorldServerMulti";
    private static final String MIXIN_PLAYER_LIST =
            "zmaster587.advancedRocketry.mixin.MixinPlayerList";

    private boolean customPlanetWeather = true;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            File cfgFile = new File("config/advRocketry/advancedRocketry.cfg");
            if (cfgFile.isFile()) {
                Configuration cfg = new Configuration(cfgFile);
                cfg.load();
                customPlanetWeather = cfg
                        .get("Planet", "enableCustomPlanetWeather", true)
                        .getBoolean(true);
            }
        } catch (Throwable t) {
            // Fail-open: behave exactly as before the plugin (weather mixins on).
            customPlanetWeather = true;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return shouldApply(customPlanetWeather, mixinClassName);
    }

    /**
     * Pure decision function (no I/O, no state) so it can be unit-tested
     * directly: the two weather mixins apply iff custom planet weather is
     * enabled; every other mixin always applies.
     */
    public static boolean shouldApply(boolean customPlanetWeatherEnabled, String mixinClassName) {
        if (MIXIN_WORLD_SERVER_MULTI.equals(mixinClassName)
                || MIXIN_PLAYER_LIST.equals(mixinClassName)) {
            return customPlanetWeatherEnabled;
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
