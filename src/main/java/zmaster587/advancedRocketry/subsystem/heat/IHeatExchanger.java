package zmaster587.advancedRocketry.subsystem.heat;

/**
 * A machine that moves heat OUT of the coolant loop it sits in — today a radiator, later a chiller
 * or a turbine.
 * <p>
 * <b>The machine does not get to say HOW MUCH.</b> It declares only how much working surface it
 * currently has, and the thermal system computes the energy from the loop's own temperature. That is
 * the whole guard on this seam: emitting heat is a cost and so an open extension point for it
 * polices itself, while REMOVING heat is a benefit — an interface that let a machine name the amount
 * would let one mod ship a "heat absorber" and switch the entire mechanic off. The same discipline
 * as a slug's capacity being derived from its material and never authored.
 * <p>
 * <b>And heat may not VANISH here.</b> Everything on this seam is a TRANSFER: it ends up somewhere
 * that is either outside the ship for good (a radiator radiates it away) or in another reservoir
 * still aboard. A second kind of exchanger adds a kind selector to say which formula applies to it,
 * not a second interface — otherwise there end up being two ways for heat to leave a loop and only
 * one of them is safe to expose.
 */
public interface IHeatExchanger extends IHeatNode {

    /**
     * Working surface right now, in radiating cells. Zero means this exchanger cannot work at all
     * this tick — obstructed, unpowered, switched off — and it is how a degradation is expressed:
     * one blocked cell out of forty costs a fortieth, not the whole array.
     */
    int getExchangeCells();

    /**
     * Take the amount the thermal system worked out, and put it where this machine puts it. Returns
     * what was actually moved, which is what leaves the loop.
     * <p>
     * <b>The amount may be NEGATIVE, and an implementation may not clamp it away.</b> A radiating
     * surface works in both directions: parked under a star it absorbs more than it sheds, and the
     * honest report of that tick is heat arriving. Treating the backwards case as zero would hand a
     * ship immunity to its environment for free, which is precisely what the environment term exists
     * to deny.
     */
    long exchange(long amount);
}
