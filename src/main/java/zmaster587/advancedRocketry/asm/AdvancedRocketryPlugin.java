package zmaster587.advancedRocketry.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.MCVersion;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

import java.util.Map;

@MCVersion("1.12.2")
public class AdvancedRocketryPlugin implements IFMLLoadingPlugin {

    public AdvancedRocketryPlugin() {
        // Register our mixin config programmatically. In the dev workspace the mod
        // is loaded from build/classes/java/main with no manifest, so nothing else
        // bootstraps Mixin or sees our config — we must do it ourselves.
        //
        // In a packaged jar a Mixin host (MixinBooter) is present: it bootstraps
        // Mixin on the LaunchClassLoader and registers our config from the
        // `MixinConfigs` manifest attribute. Re-running MixinBootstrap.init() from
        // this coremod (loaded on the AppClassLoader) then re-initiates loading of
        // org.spongepowered.asm.launch.GlobalProperties$Keys on a second classloader
        // and the JVM throws a LinkageError ("loader constraint violation"), which
        // crashes FML at launch. So guard the self-bootstrap: attempt it, and if a
        // host already owns Mixin, swallow the error and let the manifest drive
        // registration. The dev path (no host) succeeds and self-registers.
        try {
            MixinBootstrap.init();
            Mixins.addConfiguration("mixins.advancedrocketry.json");
        } catch (Throwable t) {
            org.apache.logging.log4j.LogManager.getLogger("AdvancedRocketry").info(
                    "Skipping AR self-bootstrap of Mixin — a Mixin host (e.g. MixinBooter) "
                    + "is present and loads mixins.advancedrocketry.json from the jar "
                    + "manifest. Cause: " + t);
        }
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
