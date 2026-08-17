package zmaster587.advancedRocketry.subsystem.heat;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.capability.CapabilityHeatEmitter;
import zmaster587.advancedRocketry.api.capability.IHeatEmitter;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkController;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkNode;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The coolant loop: the pipes and accumulators a ship uses to get its machines' waste heat to
 * somewhere it can be thrown away.
 * <p>
 * <b>The commodity is ENERGY, and its unit is a heat unit per tick</b> — the same currency the
 * machines run on, so a reactor's draw, a chiller's work and the energy sitting in the pipes are
 * one number and never need converting. Every surface a player reads calls it a heat unit and never
 * an energy one: naming it after the electricity it came from would say the heat can be put back to
 * work, and it cannot.
 * <p>
 * <b>Heat does not FLOW inside a loop.</b> A whole connected loop is one thermodynamic object with
 * one temperature, so there is nothing for the solver to move between its members — what the shared
 * network primitive contributes here is membership (which blocks are one loop) and, once radiators
 * exist, the transport limit between the loop and the things that are NOT part of it. The energy
 * itself arrives by generation and leaves by rejection, and in between it simply raises `T`.
 * <p>
 * Which is why the domain does its own per-tick step: {@code T = T_ambient + Q / C}, over the
 * capacity of every block in the loop. A bigger loop is a slower one, and that is the first thing
 * the mechanic has to make true.
 */
public final class HeatNetwork {

    /**
     * The domain handle. Heat nodes register under it, so a coolant pipe and a ventilation duct
     * laid through the same wall never join one graph.
     */
    public static final SubsystemNetworkDomain DOMAIN = new SubsystemNetworkDomain("Heat") {
        @Override
        public SubsystemNetworkState newState() {
            return new HeatNetworkState();
        }

        @Override
        public void onComponentRebuilt(SubsystemNetworkState state, List<ISubsystemNetworkController> controllers,
                                       List<ISubsystemNetworkNode> members) {
            cacheEmitters(state, members);
        }

        @Override
        public void onComponentTicked(SubsystemNetworkState state, List<ISubsystemNetworkNode> members) {
            tickThermodynamics(state, members);
        }
    };

    /** The network solves every tick; the config states rates per second, as the rest of AR does. */
    public static final int TICKS_PER_SECOND = 20;

    private HeatNetwork() {
    }

    /** A per-second rate as the per-tick amount the loop deals in. */
    public static int perTick(int ratePerSecond) {
        return Math.max(0, ratePerSecond / TICKS_PER_SECOND);
    }

    public static boolean enabled() {
        return ARConfiguration.getCurrentConfig().shipHeat;
    }

    /** What a loop holding nothing sits at, in kelvin. */
    public static int ambientKelvin() {
        return Math.max(1, ARConfiguration.getCurrentConfig().shipHeatAmbientKelvin);
    }

    /** {@code T = T_ambient + Q / C}, in kelvin. A loop with no thermal mass has no temperature. */
    public static double temperature(long storedHeat, long heatCapacity) {
        if (heatCapacity <= 0) {
            return ambientKelvin();
        }
        return ambientKelvin() + (double) storedHeat / heatCapacity;
    }

    /**
     * A block next to a loop changed. Machines are not network nodes, so nothing else would tell
     * the loop that the thing it is cooling has arrived or gone — this is the signal, and it comes
     * from the neighbour notification vanilla already sends whichever way the block was placed.
     */
    public static void onLoopNeighbourChanged(World world, BlockPos loopPos) {
        if (world == null || world.isRemote) {
            return;
        }
        SubsystemNetworkManager.markDirty(DOMAIN, world);
    }

    /**
     * Which machines this loop is touching — worked out when the loop is rebuilt, not every tick.
     * <p>
     * The set changes only when a block changes, so walking six neighbours of every member every
     * tick spends hundreds of world lookups to re-derive an answer that did not move. What DOES
     * move every tick is how much each of those machines is holding, and that is all the per-tick
     * step reads.
     */
    static void cacheEmitters(SubsystemNetworkState raw, List<ISubsystemNetworkNode> members) {
        if (!(raw instanceof HeatNetworkState)) {
            return;
        }
        Set<BlockPos> loop = new HashSet<>();
        for (ISubsystemNetworkNode node : members) {
            if (node instanceof IHeatNode && node.getNodePos() != null) {
                loop.add(node.getNodePos());
            }
        }
        List<BlockPos> emitters = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (ISubsystemNetworkNode node : members) {
            if (!(node instanceof IHeatNode)) {
                continue;
            }
            World world = node.getNodeWorld();
            BlockPos pos = node.getNodePos();
            if (world == null || pos == null) {
                continue;
            }
            for (EnumFacing facing : EnumFacing.VALUES) {
                BlockPos neighbour = pos.offset(facing);
                // A member of the loop is not a machine standing next to it; skipping them keeps a
                // long run from re-testing its own middle six times over.
                if (loop.contains(neighbour) || !seen.add(neighbour) || !world.isBlockLoaded(neighbour)) {
                    continue;
                }
                if (CapabilityHeatEmitter.get(world.getTileEntity(neighbour)) != null) {
                    emitters.add(neighbour);
                }
            }
        }
        ((HeatNetworkState) raw).setEmitterPositions(emitters);
    }

    /**
     * One tick of the loop as a physical object: pick up what the machines around it made, then
     * spread the whole of it over the loop's blocks so they are all at one temperature.
     */
    static void tickThermodynamics(SubsystemNetworkState raw, List<ISubsystemNetworkNode> members) {
        if (!(raw instanceof HeatNetworkState)) {
            return;
        }
        HeatNetworkState state = (HeatNetworkState) raw;
        if (!enabled()) {
            state.setThermalState(0L, 0L, ambientKelvin(), 0);
            return;
        }

        List<IHeatNode> mass = new ArrayList<>();
        long capacity = 0L;
        long stored = 0L;
        World world = null;
        for (ISubsystemNetworkNode node : members) {
            if (!(node instanceof IHeatNode)) {
                continue;
            }
            IHeatNode heatNode = (IHeatNode) node;
            int nodeCapacity = Math.max(0, heatNode.getHeatCapacity());
            if (nodeCapacity <= 0) {
                continue;
            }
            if (world == null) {
                world = node.getNodeWorld();
            }
            mass.add(heatNode);
            capacity += nodeCapacity;
            stored += Math.max(0L, heatNode.getStoredHeat());
        }

        if (capacity <= 0L || world == null) {
            // A loop of consoles and nothing else has nowhere to put heat, so it must not collect
            // any: taking it would be the one thing conservation forbids.
            state.setThermalState(0L, 0L, ambientKelvin(), 0);
            return;
        }

        long generated = collectGeneration(world, state.getEmitterPositions());
        stored += generated;
        distribute(mass, capacity, stored);

        state.setThermalState(stored, capacity, temperature(stored, capacity),
                (int) Math.min(Integer.MAX_VALUE, generated));
    }

    /**
     * Take the waste heat of every machine touching the loop. A machine gives its heat up ONCE —
     * the loop drains it — so a reactor wedged between two loops is cooled by both rather than
     * heating each of them by its whole output.
     */
    private static long collectGeneration(World world, List<BlockPos> emitterPositions) {
        long generated = 0L;
        for (BlockPos pos : emitterPositions) {
            if (!world.isBlockLoaded(pos)) {
                continue;
            }
            IHeatEmitter emitter = CapabilityHeatEmitter.get(world.getTileEntity(pos));
            if (emitter == null) {
                continue;
            }
            int pending = Math.max(0, emitter.getPendingHeat());
            if (pending > 0) {
                generated += Math.max(0, emitter.takeHeat(pending));
            }
        }
        return generated;
    }

    /**
     * Spread the loop's energy over its blocks in proportion to their capacity, which is the same
     * statement as "the loop is at one temperature". The last block takes the remainder, so what is
     * written back sums to exactly what was there — rounding may not quietly cost a ship energy it
     * is supposed to still be carrying.
     */
    private static void distribute(List<IHeatNode> mass, long capacity, long stored) {
        double perKelvin = (double) stored / capacity;
        long assigned = 0L;
        for (int i = 0; i < mass.size(); i++) {
            IHeatNode node = mass.get(i);
            long share;
            if (i == mass.size() - 1) {
                share = stored - assigned;
            } else {
                share = (long) (Math.max(0, node.getHeatCapacity()) * perKelvin);
                assigned += share;
            }
            node.setStoredHeat(share);
        }
    }
}
