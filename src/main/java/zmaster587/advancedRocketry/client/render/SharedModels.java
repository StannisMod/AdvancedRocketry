package zmaster587.advancedRocketry.client.render;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.backwardCompat.ModelFormatException;
import zmaster587.advancedRocketry.backwardCompat.WavefrontObject;

/**
 * Models that more than one renderer draws.
 *
 * <p>This exists because a shared model used to be a static field on whichever renderer happened to
 * load it first: the planet and star holograms both drew a spinning ring that was only ever
 * initialised as a side effect of a machine renderer being registered. That works right up until the
 * machine is retired, and then two unrelated renderers break for a reason neither of them mentions.
 * Loading it here makes the sharing explicit and gives it an owner.</p>
 */
@SideOnly(Side.CLIENT)
public final class SharedModels {

    private static WavefrontObject orbitRing;

    private SharedModels() {
    }

    /** The rotating ring the planet and star holograms are drawn inside. Loaded once, on demand. */
    public static WavefrontObject orbitRing() {
        if (orbitRing == null) {
            try {
                orbitRing = new WavefrontObject(
                        new ResourceLocation("advancedrocketry:models/warpcore.obj"));
            } catch (ModelFormatException badModel) {
                badModel.printStackTrace();
            }
        }
        return orbitRing;
    }
}
