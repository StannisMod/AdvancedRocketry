package zmaster587.advancedRocketry.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.MCVersion;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@MCVersion("1.12.2")
public class AdvancedRocketryPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    // Mixin registration is delegated to the Mixin host (MixinBooter) via
    // IEarlyMixinLoader. MixinBooter is present in BOTH the dev workspace and
    // the packaged environment; it calls getMixinConfigs() at the right point on
    // the LaunchClassLoader and queues our config.
    //
    // We deliberately do NOT call MixinBootstrap.init() / Mixins.addConfiguration()
    // from this coremod. The coremod is loaded on the AppClassLoader, where those
    // Spongepowered classes are also visible; referencing them here re-initiates
    // loading of org.spongepowered.asm.launch.GlobalProperties$Keys on a second
    // classloader. The JVM then throws a LinkageError ("loader constraint
    // violation"), and — even if that is caught — the partially-initialised Mixin
    // service poisons the host's own MixinTweaker, which dies with
    // "No mixin host service is available" and crashes the client at launch.
    //
    // Letting the host own bootstrap entirely is the supported pattern and keeps
    // the AppClassLoader from ever touching Mixin internals.

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.advancedrocketry.json");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return "zmaster587.advancedRocketry.asm.ModContainer";
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
