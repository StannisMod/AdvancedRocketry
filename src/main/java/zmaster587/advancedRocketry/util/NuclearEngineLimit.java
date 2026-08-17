package zmaster587.advancedRocketry.util;

/**
 * What a cluster of nuclear nozzles is actually allowed to produce once its reactor cores are
 * taken into account.
 *
 * <p>Nuclear motors cannot be throttled individually — a nozzle that does not get full power
 * shuts off — so the cluster runs only as many nozzles as the cores can feed, and its working
 * fluid draw drops in the same proportion. The three scan paths (rocket assembler, unmanned
 * vehicle assembler, and the packed {@link StorageChunk}) all need that derating and used to
 * carry their own copy of the arithmetic.</p>
 *
 * <p>The intermediate products are computed in {@code long}: thrust is in newtons, so a stack of
 * reactor cores runs into tens of millions and {@code use * nozzleThrust} overflows {@code int}
 * well inside buildable rocket sizes.</p>
 */
public final class NuclearEngineLimit {

    /** Thrust the cluster may produce, newtons. */
    public final int thrust;

    /** Working fluid the cluster actually consumes per tick, in the units the nozzles rate. */
    public final int workingFluidUse;

    private NuclearEngineLimit(int thrust, int workingFluidUse) {
        this.thrust = thrust;
        this.workingFluidUse = workingFluidUse;
    }

    /**
     * @param nozzleThrust       thrust of every eligible nuclear nozzle summed, newtons
     * @param reactorThrust      thrust the reactor cores can feed, newtons
     * @param workingFluidUseMax draw of every eligible nozzle summed, per tick
     * @return the derated cluster; all-zero when there are no nozzles, or when the nozzles claim
     *         no working fluid at all (which would otherwise divide by zero)
     */
    public static NuclearEngineLimit derive(long nozzleThrust, long reactorThrust, int workingFluidUseMax) {
        if (nozzleThrust <= 0 || workingFluidUseMax <= 0) {
            return new NuclearEngineLimit(0, 0);
        }
        long fed = Math.min(nozzleThrust, reactorThrust);
        int use = (int) (workingFluidUseMax * (fed / (double) nozzleThrust));
        // Re-derive the thrust from the fluid actually spent: `use` is truncated to whole units,
        // so a cluster is limited by the nozzles it can keep fully powered, not by the fraction.
        long thrust = (use * nozzleThrust) / workingFluidUseMax;
        return new NuclearEngineLimit((int) Math.min(Integer.MAX_VALUE, Math.max(0L, thrust)), use);
    }
}
