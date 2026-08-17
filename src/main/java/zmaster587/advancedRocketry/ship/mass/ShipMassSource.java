package zmaster587.advancedRocketry.ship.mass;

import java.util.UUID;

import javax.annotation.Nullable;

/**
 * Where anything that needs to know a ship's mass asks for it.
 *
 * <p>The key is the craft's own durable id, not the machine that happens to compute the answer
 * today. That distinction is the whole point of the interface: at present a craft's mass model is
 * built around its flight computer, because that is the block that knows a craft is a craft — but a
 * hull with no computer is still a physical object, and the day something needs its mass (bodies
 * colliding with each other being the obvious candidate) the answer must come from swapping the
 * implementation, not from rewriting every caller.</p>
 *
 * <p>The key is deliberately an identity and never a position. Positions are ambiguous while two
 * hulls overlap, and they are worse than ambiguous before the physics engine has named a craft: a
 * ship's blocks load and tick before its ship object exists, and every coordinate they hold in that
 * window belongs to the shipyard rather than to anywhere a player can stand.</p>
 *
 * <p>A {@code null} answer is a complete, correct behaviour rather than a half-state: it means "this
 * craft is not one we model", and the physics engine's own mass stands, which is exactly what
 * happens today for every object we do not manage. Callers must not invent a fallback of their
 * own — two fallbacks are two mass models, and the point of this seam is that there is one.</p>
 */
public interface ShipMassSource {

    /**
     * The mass frame of the craft carrying {@code shipId}, or {@code null} when that craft is not one
     * we model.
     */
    @Nullable
    ShipMassFrame massFrame(UUID shipId);
}
