package zmaster587.advancedRocketry.tile.hyperdrive;

import zmaster587.advancedRocketry.tile.TileShipComponent;

/**
 * A hull-mounted emitter: one of the things that make the jump window big enough to hold the ship.
 *
 * <p>It carries no state and no power connection of its own — it draws from the generator's field.
 * What it contributes is <b>reach</b>: the window is the union of what the generator holds up alone
 * and the envelope around each emitter, so spreading emitters over a long hull is how a long hull
 * gets to travel in one piece.</p>
 *
 * <p>Being visible on the outside is part of the point. An emitter is how another player can tell at
 * a glance that a ship can jump — and, later, what an attacker aims at to make sure it cannot.</p>
 */
public class TileJumpFieldEmitter extends TileShipComponent {
}
