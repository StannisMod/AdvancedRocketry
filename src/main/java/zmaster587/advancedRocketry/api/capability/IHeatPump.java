package zmaster587.advancedRocketry.api.capability;

import net.minecraft.util.math.BlockPos;

/**
 * A chiller: a machine standing BETWEEN two coolant loops, moving heat from the cold one to the hot
 * one and paying electricity for the privilege.
 * <p>
 * This is the whole of why the tier exists. Radiated power goes as the fourth power of temperature,
 * so a loop the chiller has driven hot sheds several times what the same array managed at the
 * temperature the ship's machines produced. What it costs is the work — and the work joins the HOT
 * side, so the radiators must shed the heat plus the work.
 * <p>
 * <b>The lift is not a number anybody chooses.</b> The pump moves energy; the energy accumulates on
 * the hot side against that side's own heat capacity; the temperature follows. So the hot side is a
 * real reservoir with real state, a burst can heat it, and how far it can be driven is bounded by
 * physics rather than by a config entry: a pump's efficiency is capped by Carnot,
 * {@code COP ≤ T_hot / (T_hot − T_cold)}, which falls as the gap widens. Pushing further costs more
 * for less, which is the ceiling the design asks for without anyone having to place one.
 * <p>
 * <b>The pump declares only where it is and how much it can shift.</b> Throughput is a build
 * quantity, like a radiator's cells: a mod that inflates it has built a bigger chiller, which the
 * game already allows. The energy and its price are computed by the thermal system from the two
 * temperatures — for the same reason a slug's capacity is derived from its material and never
 * authored, and so that a foreign pump cannot hand its owner free thermodynamics.
 */
public interface IHeatPump {

    /**
     * The most this pump will shift in one tick, in heat units. Zero when it cannot run at all —
     * unpowered, switched off, or with nothing on one of its sides.
     */
    int getThroughputPerTick();

    /**
     * True when the loop containing {@code loopMemberPos} is this pump's COLD side, the one it draws
     * from. A pump touches two loops and must act on exactly one of them, or it would pump the hot
     * side into itself.
     */
    boolean drawsFrom(BlockPos loopMemberPos);

    /** A block of this pump's HOT side, or null when it has none — the loop it delivers into. */
    BlockPos getHotSideAnchor();

    /**
     * Charge {@code work} of electricity and answer what could actually be paid. An underpowered
     * pump is not a free pump: the thermal system moves only what was paid for, so a chiller starved
     * of power degrades toward doing nothing rather than working for nothing.
     */
    long payWork(long work);
}
