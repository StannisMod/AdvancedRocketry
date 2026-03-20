package zmaster587.advancedRocketry.integration.theoneprobe;

import mcjty.theoneprobe.api.ITheOneProbe;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.FMLInterModComms;

import javax.annotation.Nullable;
import java.util.function.Function;

public class TopIntegration {

    private TopIntegration() {}

    public static void register() {
        if (!Loader.isModLoaded("theoneprobe")) {
            return;
        }

        FMLInterModComms.sendFunctionMessage(
                "theoneprobe",
                "getTheOneProbe",
                "zmaster587.advancedRocketry.integration.theoneprobe.TopIntegration$GetTheOneProbe"
        );
    }

    public static class GetTheOneProbe implements Function<ITheOneProbe, Void> {
        @Nullable
        @Override
        public Void apply(ITheOneProbe top) {
            top.registerEntityDisplayOverride(new RocketEntityDisplayOverride());
            top.registerEntityProvider(new RocketEntityProbeProvider());
            top.registerProvider(new DataBlockProbeProvider());
            return null;
        }
    }
}