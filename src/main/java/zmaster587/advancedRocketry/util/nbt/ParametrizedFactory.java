package zmaster587.advancedRocketry.util.nbt;

public interface ParametrizedFactory<I, O> {

    O create(I param);
}
