package zmaster587.advancedRocketry.subsystem.heat;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.capability.CapabilityHeatEmitter;
import zmaster587.advancedRocketry.api.capability.CapabilityHeatPump;
import zmaster587.advancedRocketry.api.capability.IHeatEmitter;
import zmaster587.advancedRocketry.api.capability.IHeatPump;
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
 * The coolant loop: the pipes, accumulators and radiators a ship uses to get its machines' waste heat
 * to somewhere it can be thrown away.
 * <p>
 * <b>The commodity is ENERGY, and its unit is a heat unit per tick</b> — the same currency the
 * machines run on, so a reactor's draw, a chiller's work and the energy sitting in the pipes are one
 * number and never need converting. Every surface a player reads calls it a heat unit and never an
 * energy one: naming it after the electricity it came from would say the heat can be put back to
 * work, and it cannot.
 * <p>
 * <b>Heat does not FLOW inside a loop.</b> A connected loop is one thermodynamic object with one
 * temperature, so there is nothing for the shared network solver to move between its members. What
 * the primitive contributes is membership — which blocks are one loop — and the energy arrives by
 * generation, moves between loops through chillers, and leaves through radiators.
 * <p>
 * <b>Two loops are how a heat pump is expressed.</b> A ship's machines heat one loop; a chiller moves
 * that heat into a second one and pays for it; the second loop therefore runs hot, and radiated power
 * is quartic in temperature, so it sheds several times what the first could. Nobody sets the hot
 * loop's temperature: energy accumulates in it against its own capacity and the temperature follows,
 * which is what makes it a real reservoir a burst can heat.
 */
public final class HeatNetwork {

    /**
     * The domain handle. Heat nodes register under it, so a coolant pipe and a ventilation duct laid
     * through the same wall never join one graph.
     */
    public static final SubsystemNetworkDomain DOMAIN = new SubsystemNetworkDomain("Heat") {
        @Override
        public SubsystemNetworkState newState() {
            return new HeatNetworkState();
        }

        @Override
        public void onComponentRebuilt(SubsystemNetworkState state, List<ISubsystemNetworkController> controllers,
                                       List<ISubsystemNetworkNode> members) {
            cacheNeighbours(state, members);
        }

        @Override
        public void onComponentTicked(SubsystemNetworkState state, List<ISubsystemNetworkNode> members) {
            tickThermodynamics(state, members);
        }
    };

    /** The network solves every tick; the config states rates per second, as the rest of AR does. */
    public static final int TICKS_PER_SECOND = 20;

    /**
     * A pump's efficiency is capped at this multiple of Carnot however wide the gap gets, so a hot
     * side that has crept up to its cold side's temperature cannot divide by nearly zero and hand a
     * ship unbounded free cooling.
     */
    private static final double MAX_COP = 50.0D;

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
     * A block next to a loop changed. Machines are not network nodes, so nothing else would tell the
     * loop that the thing it is cooling — or the chiller feeding it — has arrived or gone. This is the
     * signal, and it comes from the neighbour notification vanilla already sends whichever way the
     * block was placed.
     */
    public static void onLoopNeighbourChanged(World world, BlockPos loopPos) {
        if (world == null || world.isRemote) {
            return;
        }
        SubsystemNetworkManager.markDirty(DOMAIN, world);
    }

    /**
     * Which machines this loop touches — worked out when the loop is rebuilt, not every tick.
     * <p>
     * The set changes only when a block changes, so walking six neighbours of every member every tick
     * spends hundreds of world lookups to re-derive an answer that did not move. What DOES move every
     * tick is how much each machine is holding, and that is all the per-tick step reads.
     * <p>
     * One walk, two answers: the machines that GIVE the loop heat, and the chillers that move it
     * between loops. Both merely touch the loop rather than belonging to it — a machine that was a
     * member would join everything it touched into one network, which for a chiller would merge the
     * very two temperatures it exists to keep apart.
     */
    static void cacheNeighbours(SubsystemNetworkState raw, List<ISubsystemNetworkNode> members) {
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
        List<BlockPos> pumps = new ArrayList<>();
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
                TileEntity tile = world.getTileEntity(neighbour);
                if (CapabilityHeatEmitter.get(tile) != null) {
                    emitters.add(neighbour);
                }
                if (CapabilityHeatPump.get(tile) != null) {
                    pumps.add(neighbour);
                }
            }
        }
        ((HeatNetworkState) raw).setEmitterPositions(emitters);
        ((HeatNetworkState) raw).setPumpPositions(pumps);
    }

    /**
     * One tick of the loop as a physical object: pick up what the machines around it made, hand what
     * the chillers take to the loops they feed, throw away what the radiators can, and spread the rest
     * over the loop's blocks so they are all at one temperature.
     */
    static void tickThermodynamics(SubsystemNetworkState raw, List<ISubsystemNetworkNode> members) {
        if (!(raw instanceof HeatNetworkState)) {
            return;
        }
        HeatNetworkState state = (HeatNetworkState) raw;
        if (!enabled()) {
            state.setThermalState(0L, 0L, ambientKelvin(), 0);
            state.setExchangeState(0L, 0L, 0L, 0L, 0L, 0, 0, 0.0D);
            return;
        }

        List<IHeatNode> mass = new ArrayList<>();
        long capacity = 0L;
        long stored = 0L;
        World world = null;
        BlockPos where = null;
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
                where = node.getNodePos();
            }
            mass.add(heatNode);
            capacity += nodeCapacity;
            stored += Math.max(0L, heatNode.getStoredHeat());
        }

        // A chiller bolted to this loop is part of its thermal mass, though it is not a member of the
        // graph. Physically it is a lump of metal and refrigerant in contact with the coolant, so it
        // heats and cools with the loop — which is why a hot side with a chiller on it climbs more
        // slowly than its pipes alone would explain. Folding its capacity in and handing it back a
        // share at one common temperature IS the calorimeter rule the gas model already runs on.
        if (world != null) {
            for (IHeatNode bolted : boltedMass(world, state)) {
                int boltedCapacity = Math.max(0, bolted.getHeatCapacity());
                if (boltedCapacity <= 0) {
                    continue;
                }
                mass.add(bolted);
                capacity += boltedCapacity;
                stored += Math.max(0L, bolted.getStoredHeat());
            }
        }

        if (capacity <= 0L || world == null) {
            // A loop of consoles and nothing else has nowhere to put heat, so it must not collect any:
            // taking it would be the one thing conservation forbids.
            state.setThermalState(0L, 0L, ambientKelvin(), 0);
            state.setExchangeState(0L, 0L, 0L, 0L, 0L, exchangerCount(members), 0, 0.0D);
            return;
        }

        // Where the ship IS, resolved once for the whole loop: a star is millions of blocks away and a
        // ship is tens across, so the sources do not vary between two of its blocks. Only the shield
        // does, and that is asked per cell.
        HeatEnvironment environment = HeatEnvironment.at(world, where);

        long generated = collectGeneration(world, state.getEmitterPositions());
        stored += generated;

        // What the chillers take off THIS loop, and what they paid to put it somewhere hotter.
        Pumped pumped = runPumps(world, state, capacity, stored);
        stored -= pumped.movedOut;

        // Signed: negative means the surface ran backwards and the environment put heat IN.
        long rejected = rejectHeat(environment, members, capacity, stored);
        stored = Math.max(0L, stored - rejected);
        distribute(mass, capacity, stored);

        state.setThermalState(stored, capacity, temperature(stored, capacity),
                (int) Math.min(Integer.MAX_VALUE, generated));
        state.setExchangeState(rejected, pumped.movedOut, pumped.delivered, state.takePumpedIn(),
                pumped.work, exchangerCount(members), radiatingCells(members),
                environment.unshieldedFluxPerCell());
    }

    /**
     * What the chillers drawing on this loop did in one tick: what came off this loop, what was handed
     * to the hot sides, and what was paid. All three from one tick of one component, so the clause
     * "the hot side receives the heat plus the work" is checkable without depending on the order two
     * components happen to be solved in.
     */
    /**
     * Machines bolted to this loop that carry thermal mass without being members of it — today the
     * chillers whose HOT side this loop is. A pump drawing FROM this loop is on the other side of the
     * exchange and is not part of this loop's mass.
     */
    private static List<IHeatNode> boltedMass(World world, HeatNetworkState state) {
        List<BlockPos> pumpPositions = state.getPumpPositions();
        if (pumpPositions.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        Set<BlockPos> memberPositions = state.getMemberPositions();
        List<IHeatNode> bolted = new ArrayList<>();
        for (BlockPos pumpPos : pumpPositions) {
            if (!world.isBlockLoaded(pumpPos)) {
                continue;
            }
            TileEntity tile = world.getTileEntity(pumpPos);
            IHeatPump pump = CapabilityHeatPump.get(tile);
            if (pump == null || !(tile instanceof IHeatNode)) {
                continue;
            }
            // Only the loop on the pump's HOT face carries its metal; the cold side merely feeds it.
            if (drawsFromThisLoop(pump, pumpPos, memberPositions)) {
                continue;
            }
            bolted.add((IHeatNode) tile);
        }
        return bolted;
    }

    private static final class Pumped {
        private final long movedOut;
        private final long delivered;
        private final long work;

        private Pumped(long movedOut, long delivered, long work) {
            this.movedOut = movedOut;
            this.delivered = delivered;
            this.work = work;
        }

        private static Pumped none() {
            return new Pumped(0L, 0L, 0L);
        }
    }

    /**
     * Run every chiller whose COLD side is this loop: take heat off it, and deliver that heat PLUS the
     * work into the loop on the chiller's other face.
     * <p>
     * <b>The price is physics, not a setting.</b> A heat pump's efficiency is bounded by Carnot,
     * {@code COP ≤ T_hot / (T_hot − T_cold)}, and the config says only what fraction of that a real
     * machine manages. So driving the hot side further up costs more for less, and the ceiling the
     * design asks for appears instead of being placed. Nobody sets a temperature anywhere: the hot
     * loop's temperature is whatever its own capacity makes of the energy it has been given.
     */
    private static Pumped runPumps(World world, HeatNetworkState state, long capacity, long stored) {
        List<BlockPos> pumpPositions = state.getPumpPositions();
        if (pumpPositions.isEmpty() || stored <= 0L) {
            return Pumped.none();
        }
        Set<BlockPos> members = state.getMemberPositions();
        double coldKelvin = temperature(stored, capacity);
        long movedTotal = 0L;
        long deliveredTotal = 0L;
        long workTotal = 0L;

        for (BlockPos pumpPos : pumpPositions) {
            if (!world.isBlockLoaded(pumpPos)) {
                continue;
            }
            IHeatPump pump = CapabilityHeatPump.get(world.getTileEntity(pumpPos));
            if (pump == null) {
                continue;
            }
            // A pump touches two loops; it must act only on the one that is its cold side, or it would
            // pump its own hot side back into itself. Six checks, because the cold anchor is by
            // construction adjacent to the pump.
            if (!drawsFromThisLoop(pump, pumpPos, members)) {
                continue;
            }
            int throughput = Math.max(0, pump.getThroughputPerTick());
            if (throughput <= 0) {
                continue;
            }
            BlockPos hotAnchor = pump.getHotSideAnchor();
            if (hotAnchor == null || !world.isBlockLoaded(hotAnchor)) {
                continue;
            }
            SubsystemNetworkState hotRaw = SubsystemNetworkManager.getState(DOMAIN, world, hotAnchor);
            // Both faces on one loop is not a pump, it is a short circuit — and pumping a loop into
            // itself would be free cooling, so it does nothing at all.
            if (!(hotRaw instanceof HeatNetworkState) || hotRaw == state) {
                continue;
            }
            HeatNetworkState hot = (HeatNetworkState) hotRaw;
            if (hot.getHeatCapacity() <= 0L) {
                continue;
            }

            long available = Math.max(0L, stored - movedTotal);
            long wanted = Math.min(throughput, available);
            if (wanted <= 0L) {
                break;
            }
            double hotKelvin = hot.getTemperatureKelvin();
            double cop = coefficientOfPerformance(coldKelvin, hotKelvin);
            long workWanted = Math.max(1L, (long) (wanted / cop));
            long workPaid = Math.max(0L, pump.payWork(workWanted));
            if (workPaid <= 0L) {
                continue;
            }
            // Only what was paid for moves: a chiller short of power is a weaker chiller, never a
            // free one.
            long moved = workPaid >= workWanted ? wanted : wanted * workPaid / workWanted;
            if (moved <= 0L) {
                continue;
            }

            // The hot side receives the heat AND the work. This is the clause: a pump whose own work
            // does not join the hot side has invented energy from nowhere.
            hot.addPumpedIn(moved + workPaid);
            depositInto(world, hot, moved + workPaid);
            movedTotal += moved;
            deliveredTotal += moved + workPaid;
            workTotal += workPaid;
        }
        return new Pumped(movedTotal, deliveredTotal, workTotal);
    }

    /**
     * Carnot, scaled by what a real machine manages. Where the hot side is no hotter than the cold
     * one there is no gradient to fight, so the bound is only the absolute ceiling.
     */
    static double coefficientOfPerformance(double coldKelvin, double hotKelvin) {
        double fraction = Math.max(0.001D,
                ARConfiguration.getCurrentConfig().shipHeatChillerCopFraction / 1000.0D);
        double carnot = hotKelvin <= coldKelvin
                ? MAX_COP
                : hotKelvin / (hotKelvin - coldKelvin);
        return Math.max(1.0D, Math.min(MAX_COP, carnot * fraction));
    }

    private static boolean drawsFromThisLoop(IHeatPump pump, BlockPos pumpPos, Set<BlockPos> members) {
        for (EnumFacing facing : EnumFacing.VALUES) {
            BlockPos neighbour = pumpPos.offset(facing);
            if (members.contains(neighbour) && pump.drawsFrom(neighbour)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Put energy into a loop. It goes into one member and the loop spreads it over the rest on its own
     * tick, which is the same thing as saying the loop is one body at one temperature.
     */
    private static void depositInto(World world, HeatNetworkState hot, long amount) {
        if (amount <= 0L) {
            return;
        }
        for (BlockPos pos : hot.getMemberPositions()) {
            if (!world.isBlockLoaded(pos)) {
                continue;
            }
            TileEntity tile = world.getTileEntity(pos);
            if (tile instanceof IHeatNode && ((IHeatNode) tile).getHeatCapacity() > 0) {
                IHeatNode node = (IHeatNode) tile;
                node.setStoredHeat(node.getStoredHeat() + amount);
                return;
            }
        }
    }

    /**
     * The NET heat crossing the loop's radiating surface this tick: what the cells radiate away, minus
     * what the outside is putting back into them.
     * <p>
     * {@code net = A · (k·T⁴ − incidentFlux)} in floating point, with `k` expressed as a reference power
     * at a reference temperature so the config states a point on the curve rather than a bare
     * coefficient. The quartic is why a chiller is worth its electricity: area buys rejection linearly,
     * temperature buys it to the fourth power.
     * <p>
     * <b>There is no {@code T_amb} term any more, and its absence is the point.</b> The classical
     * {@code k·A·(T⁴ − T_amb⁴)} hides an incident flux inside a temperature; written out, that second
     * half is the environment, which is a real thing with real contributors rather than a config number
     * standing in for all of them. See {@link HeatEnvironment}.
     * <p>
     * <b>The answer may be NEGATIVE, and a caller must honour that.</b> Where more arrives than the
     * cells can shed — near a star, or under someone else's radiators — the surface runs backwards and
     * the loop heats no matter what its own temperature is. That is the whole reason the environment is
     * modelled at all; clamping it at zero would give a ship free immunity by having built nothing.
     * <p>
     * The machines are told how much to move; they never say. That asymmetry is deliberate — see
     * {@link IHeatExchanger}.
     */
    private static long rejectHeat(HeatEnvironment environment, List<ISubsystemNetworkNode> members,
                                   long capacity, long stored) {
        List<IHeatExchanger> exchangers = new ArrayList<>();
        long totalCells = 0L;
        for (ISubsystemNetworkNode node : members) {
            if (!(node instanceof IHeatExchanger)) {
                continue;
            }
            int cells = Math.max(0, ((IHeatExchanger) node).getExchangeCells());
            if (cells <= 0) {
                continue;
            }
            exchangers.add((IHeatExchanger) node);
            totalCells += cells;
        }
        if (totalCells <= 0L) {
            return 0L;
        }

        double gross = cellPowerAt(temperature(stored, capacity));
        long net = 0L;
        for (IHeatExchanger exchanger : exchangers) {
            int cells = Math.max(0, exchanger.getExchangeCells());
            // Per exchanger rather than per loop, because the shield is positional: one cell can be
            // under a field its neighbour on the same run is outside of.
            double perCell = gross - environment.incidentFluxPerCell(exchanger.getNodePos());
            long share = (long) (perCell * cells);
            // A loop cannot shed more than it is holding; it can always ABSORB, which is why only the
            // positive side is capped.
            if (share > 0L) {
                share = Math.min(share, Math.max(0L, stored - net));
            }
            if (share != 0L) {
                net += exchanger.exchange(share);
            }
        }
        return Math.min(stored, net);
    }

    /**
     * The reference-point form, and the one place it is written: what a single cell of surface radiates
     * at {@code kelvin}, per tick.
     * <p>
     * Shared by the radiator and by every environment contributor, so a warm planet and a hot loop are
     * quoted on the same curve and can simply be subtracted. It is also what makes the star term
     * legible — its config number is a TEMPERATURE, not an energy.
     */
    static double cellPowerAt(double kelvin) {
        if (kelvin <= 0.0D) {
            return 0.0D;
        }
        double reference = Math.max(1.0D, ARConfiguration.getCurrentConfig().shipHeatRadiatorReferenceKelvin);
        return perTick(ARConfiguration.getCurrentConfig().shipHeatRadiatorCellPower)
                * pow4(kelvin) / pow4(reference);
    }

    /** `T⁴` in double, never int: 2000 K overflows a 32-bit accumulator immediately. */
    private static double pow4(double kelvin) {
        double squared = kelvin * kelvin;
        return squared * squared;
    }

    private static int exchangerCount(List<ISubsystemNetworkNode> members) {
        int count = 0;
        for (ISubsystemNetworkNode node : members) {
            if (node instanceof IHeatExchanger) {
                count++;
            }
        }
        return count;
    }

    private static int radiatingCells(List<ISubsystemNetworkNode> members) {
        long cells = 0L;
        for (ISubsystemNetworkNode node : members) {
            if (node instanceof IHeatExchanger) {
                cells += Math.max(0, ((IHeatExchanger) node).getExchangeCells());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, cells);
    }

    /**
     * Take the waste heat of every machine touching the loop. A machine gives its heat up ONCE — the
     * loop drains it — so a reactor wedged between two loops is cooled by both rather than heating
     * each of them by its whole output.
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
     * written back sums to exactly what was there — rounding may not quietly cost a ship energy it is
     * supposed to still be carrying.
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
