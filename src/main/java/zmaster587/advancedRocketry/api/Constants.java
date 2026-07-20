package zmaster587.advancedRocketry.api;

public class Constants {
    public static final String modId = "advancedrocketry";
    public static final String DEPENDENCIES = "required-after:libvulpes@[0.5.0,);";
    public static final int INVALID_PLANET = Integer.MIN_VALUE + 1; //min value is used for warp
    public static final int GENTYPE_ASTEROID = 2;
    public static final int STAR_ID_OFFSET = 10000;

    /**
     * Config category and key of the per-dimension WorldInfo master switch.
     *
     * <p>Shared because two readers must agree on them and cannot share code:
     * {@code ARConfiguration} reads the flag in mod pre-init, while
     * {@code ARMixinPlugin} must read the same value during the coremod phase,
     * long before that singleton is populated. These are compile-time constants
     * (JLS §13.1), so javac inlines the literal and the coremod never loads this
     * class — but renaming either one now breaks compilation instead of silently
     * leaving the mixin gate stuck open.</p>
     */
    public static final String CONFIG_CATEGORY_PLANET = "Planet";
    public static final String CONFIG_KEY_PER_DIM_WORLD_INFO = "perDimWorldInfo";
}
