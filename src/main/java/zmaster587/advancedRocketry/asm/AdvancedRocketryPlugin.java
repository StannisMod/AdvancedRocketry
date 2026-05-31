package zmaster587.advancedRocketry.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.MCVersion;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.TransformerExclusions;

import java.util.Map;

@TransformerExclusions(value = {"zmaster587.advancedRocketry.asm.ClassTransformer"})
@MCVersion("1.12.2")
public class AdvancedRocketryPlugin implements IFMLLoadingPlugin {
    public AdvancedRocketryPlugin() {
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{
                ClassTransformer.class.getName(),
                "zmaster587.advancedRocketry.asm.compat.plustic.PlusTiCPacketReleaseEntityTransformer"
        };
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
