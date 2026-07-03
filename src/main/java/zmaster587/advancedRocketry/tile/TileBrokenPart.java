package zmaster587.advancedRocketry.tile;

import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.util.IBrokenPartBlock;

import java.util.Random;

/**
 * Wear host for rocket motors: a {@link TileWearable} that also renders the
 * breaking overlay (motors render INVISIBLE and rely on this TESR) and drops a
 * staged item so a worn motor keeps its wear when picked up and replaced.
 */
public class TileBrokenPart extends TileWearable {

    public TileBrokenPart() {
        super();
    }

    public TileBrokenPart(int stage, int maxStage, float transitionProb, Random rand) {
        super(stage, maxStage, transitionProb, rand);
    }

    public TileBrokenPart(int maxStage, float transitionProb, Random rand) {
        super(maxStage, transitionProb, rand);
    }

    public TileBrokenPart(int maxStage, float transitionProb) {
        super(maxStage, transitionProb);
    }

    @Override
    public boolean canRenderBreaking() {
        return true;
    }

    public ItemStack getDrop() {
        return ((IBrokenPartBlock) this.getBlockType()).getDropItem(world.getBlockState(pos), world, this);
    }
}
