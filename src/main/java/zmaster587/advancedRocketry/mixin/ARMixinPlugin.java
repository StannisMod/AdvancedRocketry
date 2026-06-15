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
 * <p>Its only job today: gate the three per-dimension WorldInfo mixins
 * ({@link MixinWorldServerMulti} wrapper install, {@code MixinWorldServer}
 * per-dim time / sleep, {@link MixinPlayerList} weather sync) on the
 * {@code perDimWorldInfo} MASTER config flag, so that with the per-dimension
 * WorldInfo subsystem turned off those mixins are never woven into their target
 * classes at all — not merely no-ops at runtime. The other mixins (gravity,
 * atmosphere block-place, rocket inventory access) are unrelated and always
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
 * WorldInfo mixins apply, exactly as they did before this plugin existed. A
 * disabled-by-accident subsystem would be a worse surprise than the
 * pre-existing always-on behaviour.</p>
 */
public class ARMixinPlugin implements IMixinConfigPlugin {

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

    @Override
    public void onLoad(String mixinPackage) {
        try {
            File cfgFile = new File("config/advRocketry/advancedRocketry.cfg");
            if (cfgFile.isFile()) {
                Configuration cfg = new Configuration(cfgFile);
                cfg.load();
                perDimWorldInfo = cfg
                        .get("Planet", "perDimWorldInfo", true)
                        .getBoolean(true);
            }
        } catch (Throwable t) {
            // Fail-open: behave exactly as before the plugin (mixins on).
            perDimWorldInfo = true;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return shouldApply(perDimWorldInfo, mixinClassName);
    }

    /**
     * Pure decision function (no I/O, no state) so it can be unit-tested
     * directly: the three per-dimension WorldInfo mixins (wrapper install,
     * per-dim time/sleep, weather sync) apply iff {@code perDimWorldInfo} is
     * enabled; every other mixin always applies.
     */
    public static boolean shouldApply(boolean perDimWorldInfoEnabled, String mixinClassName) {
        if (MIXIN_WORLD_SERVER_MULTI.equals(mixinClassName)
                || MIXIN_PLAYER_LIST.equals(mixinClassName)
                || MIXIN_WORLD_SERVER.equals(mixinClassName)) {
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
