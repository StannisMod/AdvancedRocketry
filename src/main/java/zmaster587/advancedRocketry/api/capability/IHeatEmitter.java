package zmaster587.advancedRocketry.api.capability;

/**
 * A machine that makes waste heat, offered to whatever coolant loop happens to touch it.
 * <p>
 * A capability rather than a plain interface, because the machines this exists for are mostly not
 * ours: another mod's reactor is the whole reason a ship needs a thermal system, and it cannot be
 * made to implement anything. It can be GIVEN a capability — by us through
 * {@code AttachCapabilitiesEvent}, or by the mod itself if it would rather say the number. Our own
 * machines host it directly, so there is exactly one read path and no {@code instanceof} anywhere.
 * <p>
 * Deliberately a DRAIN and not a rate: a machine standing between two loops must have its heat
 * split between them rather than counted once for each, and the only way to express that without a
 * per-tick bookkeeping table is to let the taker remove what it took. A {@link #getPendingHeat()}
 * that ignores {@link #takeHeat(int)} silently doubles the output of any machine two loops reach.
 * <p>
 * A machine nobody is cooling still gets rid of its heat: an implementation caps what it holds and
 * lets the excess go, which is convection into the surrounding air and one of the three ways heat
 * is allowed to leave a ship.
 */
public interface IHeatEmitter {

    /** Waste heat waiting to be picked up, in heat units. */
    int getPendingHeat();

    /** Remove up to {@code amount} of it; returns what was actually taken. */
    int takeHeat(int amount);
}
