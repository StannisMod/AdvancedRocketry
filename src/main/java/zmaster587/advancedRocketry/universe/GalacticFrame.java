package zmaster587.advancedRocketry.universe;

/**
 * Which law carries a position through time — the intergalactic regime, in two states and no more.
 *
 * <p><b>There is no "nowhere".</b> Every point belongs to exactly one galaxy CELL; a galaxy occupies a
 * small sphere inside its cell and the rest of that cell is void. So no coordinate ever carries a null
 * galaxy and no call site needs a branch for a point that is in no galaxy at all — only for a point
 * that is in the void OF one.</p>
 *
 * <p>The two states are physically different, not a convenience: matter bound to a galaxy co-rotates
 * with it and does not expand, while matter in the void is carried by the Hubble flow. A craft parked
 * in the void stays put relative to the void while the galaxies recede from it.</p>
 *
 * <h3>The frame is LATCHED at the crossing, never re-derived per tick</h3>
 * <p>The boundary is a threshold, so anything hovering on it would flip frame every tick — and the
 * frame decides both rotation and expansion. {@link GalaxyField#frameAt} answers the question ONCE, at
 * a crossing; a moving craft stores the answer alongside the cell binding it already stores. Every
 * position-keyed defect this tree has logged has the same shape: a decision re-derived from a
 * coordinate instead of held as identity.</p>
 */
public enum GalacticFrame {

    /**
     * Bound to a galaxy: the position is an offset from the galaxy's CENTRE, it turns with the disc at
     * {@code ω(r)}, and it does not expand.
     */
    GALACTIC,

    /**
     * Out in the void: the position is an offset from the galaxy CELL's origin and it is comoving —
     * it scales with {@code a(t)} and does not rotate.
     */
    COMOVING
}
