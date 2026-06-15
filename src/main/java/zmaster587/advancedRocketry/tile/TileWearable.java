package zmaster587.advancedRocketry.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import zmaster587.advancedRocketry.api.capability.CapabilityWear;
import zmaster587.advancedRocketry.api.capability.IPartWear;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;

/**
 * Generic wear-bearing tile: holds a wear {@code stage} (0 = pristine ...
 * {@code maxStage} = broken) and exposes it via {@link CapabilityWear}.
 *
 * <p>This is the host for wear on blocks that have no special render
 * (fuel tanks, seats). {@link TileBrokenPart} extends it to add the motor
 * breaking-render and a staged item drop.</p>
 */
public class TileWearable extends TileEntitySyncable implements IPartWear {

    protected int stage;
    protected int maxStage;
    protected float transitionProb;
    protected float[] probs;
    protected final Random rand;

    public TileWearable() {
        this(0, 0);
    }

    public TileWearable(int stage, int maxStage, float transitionProb, Random rand) {
        this.stage = stage;
        this.maxStage = maxStage;
        this.rand = rand;
        this.initProb(transitionProb);
    }

    public TileWearable(int maxStage, float transitionProb, Random rand) {
        this(0, maxStage, transitionProb, rand);
    }

    public TileWearable(int maxStage, float transitionProb) {
        this(maxStage, transitionProb, new Random());
    }

    @Override
    public void setStage(int stage) {
        this.stage = stage;
        this.markDirty();
    }

    @Override
    public int getStage() {
        return this.stage;
    }

    @Override
    public int getMaxStage() {
        return this.maxStage;
    }

    protected void initProb(float transitionProb) {
        this.transitionProb = transitionProb;
        this.probs = new float[maxStage];

        for (int i = 0; i < maxStage; i++) {
            this.probs[i] = transitionProb / (float) Math.sqrt(2 * i + 1);
        }
    }

    @Override
    public boolean transition() {
        if (stage == maxStage) {
            return true;
        }
        for (int i = maxStage - 1; i >= 0; i--) {
            if (stage == i) {
                return false;
            }
            if (rand.nextFloat() < (stage + 1) * this.probs[i]) {
                stage = i;
                this.markDirty();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityWear.PART_WEAR) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityWear.PART_WEAR) {
            return CapabilityWear.PART_WEAR.cast(this);
        }
        return super.getCapability(capability, facing);
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(final NBTTagCompound compound) {
        compound.setInteger("stage", stage);
        compound.setInteger("maxStage", maxStage);
        compound.setFloat("transitionProb", transitionProb);
        return super.writeToNBT(compound);
    }

    @Override
    public void readFromNBT(@Nonnull final NBTTagCompound compound) {
        super.readFromNBT(compound);
        stage = compound.getInteger("stage");
        maxStage = compound.getInteger("maxStage");
        transitionProb = compound.getFloat("transitionProb");

        this.initProb(transitionProb);
    }
}
