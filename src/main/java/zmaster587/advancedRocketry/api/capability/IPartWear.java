package zmaster587.advancedRocketry.api.capability;

/**
 * Wear state of a rocket part. Exposed as a Forge capability
 * ({@link CapabilityWear#PART_WEAR}) so wear can ride on a dedicated
 * {@link zmaster587.advancedRocketry.tile.TileBrokenPart} or, in the future,
 * on a block's own TileEntity without a second tile.
 *
 * <p>Stage convention: {@code 0} = pristine, {@code getMaxStage()} = fully
 * worn / broken. Consequence formulas (thrust loss, leak/explosion chance)
 * live in the consumers, not here — this is pure state.</p>
 */
public interface IPartWear {

    /** Current wear stage (0 = pristine ... maxStage = broken). */
    int getStage();

    /** Maximum wear stage (the broken state). */
    int getMaxStage();

    /** Set the current wear stage (used by repair to reset to 0). */
    void setStage(int stage);

    /**
     * Advance wear by one probabilistic step (called once per flight on
     * landing). Returns true if the part changed stage or is already broken.
     */
    boolean transition();
}
