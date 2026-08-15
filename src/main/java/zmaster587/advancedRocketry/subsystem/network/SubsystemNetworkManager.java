package zmaster587.advancedRocketry.subsystem.network;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import zmaster587.advancedRocketry.api.Constants;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shared subsystem-network primitive: sources, sinks and capacity-limited lines, solved once a
 * tick per connected component.
 * <p>
 * Every subsystem that distributes something over built blocks — shields, ventilation, heat,
 * turret control — is one {@link SubsystemNetworkDomain} over this one solver, rather than its own
 * copy of the same graph code. A network is a connected component over block adjacency of that
 * domain's nodes: two touching nodes are one network with no cable between them, so cables are a
 * reach-and-capacity tool rather than a requirement.
 * <p>
 * Delivery is a max flow, not a share-out. That matters: it means a route that cannot carry the
 * commodity does not silently reduce the whole network to its own capacity, and it means the
 * per-tick answer names WHICH constraint bound it ({@link SubsystemNetworkStatus}) instead of only
 * how much arrived. Under a deficit, sink demand is opened in descending priority tiers, so a
 * starved supply fills what the player marked important first and equal priorities share the rest.
 */
@Mod.EventBusSubscriber(modid = Constants.modId)
public final class SubsystemNetworkManager {

    private static final int INF = 1_000_000_000;

    private static final Map<SubsystemNetworkDomain, Map<Integer, WorldState>> WORLD_STATES = new HashMap<>();

    private SubsystemNetworkManager() {
    }

    /** Call when the topology changed — a node placed, broken, or its connectivity altered. */
    public static void markDirty(SubsystemNetworkDomain domain, World world) {
        if (domain == null || world == null || world.isRemote) {
            return;
        }
        getState(domain, world).dirty = true;
    }

    /** The network the block at this position belongs to, or null if it is in none. */
    public static SubsystemNetworkState getState(SubsystemNetworkDomain domain, World world, BlockPos pos) {
        if (domain == null || world == null || pos == null) {
            return null;
        }
        Map<Integer, WorldState> byDim = WORLD_STATES.get(domain);
        if (byDim == null) {
            return null;
        }
        WorldState state = byDim.get(world.provider.getDimension());
        return state == null ? null : state.stateByPos.get(pos);
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        World world = event.world;
        if (world == null || world.isRemote) {
            return;
        }
        for (SubsystemNetworkDomain domain : SubsystemNetworkRegistry.domains()) {
            WorldState state = getState(domain, world);
            if (state.dirty) {
                // Topology changes are expensive; only rebuild adjacency when the network actually changed.
                state.rebuild(domain, world);
            }
            // Capacities and demands still change every tick, so max-flow is solved against the
            // cached topology each tick.
            state.solve();
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        for (SubsystemNetworkDomain domain : SubsystemNetworkRegistry.domains()) {
            Map<Integer, WorldState> byDim = WORLD_STATES.get(domain);
            if (byDim != null) {
                byDim.remove(world.provider.getDimension());
            }
            SubsystemNetworkRegistry.clearWorld(domain, world);
        }
    }

    private static WorldState getState(SubsystemNetworkDomain domain, World world) {
        Map<Integer, WorldState> byDim = WORLD_STATES.computeIfAbsent(domain, key -> new HashMap<>());
        return byDim.computeIfAbsent(world.provider.getDimension(), key -> new WorldState());
    }

    private static final class WorldState {
        private boolean dirty = true;
        private final List<ComponentTopology> components = new ArrayList<>();
        private final Map<BlockPos, SubsystemNetworkState> stateByPos = new HashMap<>();

        private void rebuild(SubsystemNetworkDomain domain, World world) {
            components.clear();
            Map<BlockPos, SubsystemNetworkState> previousStateByPos = new HashMap<>(stateByPos);
            Set<SubsystemNetworkState> consumedStates = new HashSet<>();
            stateByPos.clear();

            Set<ISubsystemNetworkNode> nodes = SubsystemNetworkRegistry.snapshot(domain);
            Map<BlockPos, ISubsystemCable> cables = new HashMap<>();
            Map<BlockPos, ISubsystemSource> sources = new HashMap<>();
            Map<BlockPos, ISubsystemSink> sinks = new HashMap<>();
            Map<BlockPos, ISubsystemNetworkController> controllers = new HashMap<>();
            int skippedNull = 0;
            int skippedWorld = 0;

            for (ISubsystemNetworkNode node : nodes) {
                if (node == null) {
                    skippedNull++;
                    continue;
                }
                World nodeWorld = node.getNodeWorld();
                if (nodeWorld == null || nodeWorld.provider.getDimension() != world.provider.getDimension()) {
                    skippedWorld++;
                    continue;
                }
                // Roles are not exclusive: a store is both a source and a sink, so it must land in
                // both maps. A block that is a cable is only a cable (transport, not a store).
                BlockPos nodePos = node.getNodePos();
                if (node instanceof ISubsystemCable) {
                    cables.put(nodePos, (ISubsystemCable) node);
                }
                if (node instanceof ISubsystemSource) {
                    sources.put(nodePos, (ISubsystemSource) node);
                }
                if (node instanceof ISubsystemSink) {
                    sinks.put(nodePos, (ISubsystemSink) node);
                }
                if (node instanceof ISubsystemNetworkController) {
                    controllers.put(nodePos, (ISubsystemNetworkController) node);
                }
            }

            if (domain.getLogger() != null) {
                domain.getLogger().info(
                        "[{}Network] rebuild dim={} snapshot={} skippedNull={} skippedWorld={} cables={} sources={} sinks={} controllers={}",
                        domain.getName(),
                        world.provider.getDimension(),
                        nodes.size(),
                        skippedNull,
                        skippedWorld,
                        cables.size(),
                        sources.size(),
                        sinks.size(),
                        controllers.size()
                );
            }

            Set<BlockPos> allPositions = new HashSet<>();
            allPositions.addAll(cables.keySet());
            allPositions.addAll(sources.keySet());
            allPositions.addAll(sinks.keySet());
            allPositions.addAll(controllers.keySet());

            Set<BlockPos> visited = new HashSet<>();
            for (BlockPos startPos : allPositions) {
                if (!visited.add(startPos)) {
                    continue;
                }

                Set<BlockPos> component = new HashSet<>();
                ArrayDeque<BlockPos> queue = new ArrayDeque<>();
                queue.add(startPos);

                while (!queue.isEmpty()) {
                    BlockPos current = queue.removeFirst();
                    component.add(current);
                    for (EnumFacing facing : EnumFacing.VALUES) {
                        BlockPos next = current.offset(facing);
                        if (allPositions.contains(next) && visited.add(next)) {
                            queue.add(next);
                        }
                    }
                }

                List<CableNode> componentCables = new ArrayList<>();
                List<SourceNode> componentSources = new ArrayList<>();
                List<SinkNode> componentSinks = new ArrayList<>();
                List<ISubsystemNetworkController> componentControllers = new ArrayList<>();
                for (BlockPos pos : component) {
                    ISubsystemCable cable = cables.get(pos);
                    if (cable != null) {
                        componentCables.add(new CableNode(pos, cable));
                    }
                    ISubsystemSource source = sources.get(pos);
                    if (source != null) {
                        componentSources.add(new SourceNode(pos, source));
                    }
                    ISubsystemSink sink = sinks.get(pos);
                    if (sink != null) {
                        componentSinks.add(new SinkNode(pos, sink));
                    }
                    ISubsystemNetworkController controller = controllers.get(pos);
                    if (controller != null) {
                        componentControllers.add(controller);
                    }
                }

                List<BlockPos> componentMemberPositions = new ArrayList<>(component);
                BlockPos anchor = componentCables.isEmpty() ? startPos : componentCables.get(0).pos;

                SubsystemNetworkState state =
                        findExistingState(componentMemberPositions, previousStateByPos, consumedStates);
                if (state == null) {
                    state = domain.newState();
                }
                domain.onComponentRebuilt(state, componentControllers);
                state.clearMembers();
                state.setRoot(anchor);
                for (BlockPos pos : componentMemberPositions) {
                    state.addMember(pos);
                    stateByPos.put(pos, state);
                }

                components.add(new ComponentTopology(
                        state, componentCables, componentSources, componentSinks, componentControllers));
                if (domain.getLogger() != null) {
                    domain.getLogger().info(
                            "[{}Network] component anchor={} cables={} sources={} sinks={} controllers={}",
                            domain.getName(),
                            anchor,
                            componentCables.size(),
                            componentSources.size(),
                            componentSinks.size(),
                            componentControllers.size()
                    );
                }
            }

            dirty = false;
        }

        /**
         * A rebuilt component inherits the state of whichever old network its members came from, so
         * console settings survive re-laying a line. A component that split takes a copy, because
         * two networks may not share one settings object.
         */
        private SubsystemNetworkState findExistingState(List<BlockPos> memberPositions,
                                                        Map<BlockPos, SubsystemNetworkState> previousStateByPos,
                                                        Set<SubsystemNetworkState> consumedStates) {
            SubsystemNetworkState best = null;
            for (BlockPos pos : memberPositions) {
                SubsystemNetworkState candidate = previousStateByPos.get(pos);
                if (candidate != null) {
                    best = candidate;
                    break;
                }
            }
            if (best == null) {
                return null;
            }
            if (consumedStates.add(best)) {
                return best;
            }
            return best.copy();
        }

        private void solve() {
            for (ComponentTopology component : components) {
                component.solve();
            }
        }
    }

    private static final class ComponentTopology {
        private final SubsystemNetworkState state;
        private final List<CableNode> cables;
        private final List<SourceNode> sources;
        private final List<SinkNode> sinks;
        private final List<ISubsystemNetworkController> controllers;

        private ComponentTopology(SubsystemNetworkState state, List<CableNode> cables, List<SourceNode> sources,
                                  List<SinkNode> sinks, List<ISubsystemNetworkController> controllers) {
            this.state = state;
            this.cables = cables;
            this.sources = sources;
            this.sinks = sinks;
            this.controllers = controllers;
        }

        private void solve() {
            if (sources.isEmpty() || sinks.isEmpty()) {
                publishDisconnected();
                return;
            }

            MaxFlowSolver solver = new MaxFlowSolver();
            int superSource = solver.addNode();
            int superSink = solver.addNode();

            // Unified port model: every node exposes a supply port (the commodity leaves here) and/or
            // a demand port (it enters here). A cable owns both, joined internally by its throughput
            // edge; a source owns only supply; a sink only demand; a store owns both. Adjacent nodes
            // are linked supplyOut(A) -> demandIn(B) at INF, so a source touching a sink connects
            // with no cable, while a cable's finite in->out edge is the only throttled link.
            Map<BlockPos, Integer> supplyOut = new HashMap<>();
            Map<BlockPos, Integer> demandIn = new HashMap<>();
            Map<BlockPos, MaxFlowSolver.EdgeRef> cableThroughputRefs = new HashMap<>();
            int totalCableCapacity = 0;

            for (CableNode cable : cables) {
                int in = solver.addNode();
                int out = solver.addNode();
                demandIn.put(cable.pos, in);
                supplyOut.put(cable.pos, out);
                int throughput = Math.max(0, cable.cable.getThroughputPerTick());
                totalCableCapacity += throughput;
                cableThroughputRefs.put(cable.pos, solver.addEdge(in, out, throughput, cable.cable));
            }

            List<MaxFlowSolver.EdgeRef> sourceRefs = new ArrayList<>();
            int totalSourceAvailable = 0;
            int totalGenerationPerTick = 0;
            for (SourceNode source : sources) {
                int sourceNode = solver.addNode();
                supplyOut.put(source.pos, sourceNode);
                int available = Math.max(0, source.source.getAvailable());
                totalSourceAvailable += available;
                totalGenerationPerTick += Math.max(0, source.source.getGenerationPerTick());
                sourceRefs.add(solver.addEdge(superSource, sourceNode, available, source.source));
            }

            List<MaxFlowSolver.EdgeRef> sinkRefs = new ArrayList<>();
            List<int[]> sinkDemand = new ArrayList<>(); // parallel to sinkRefs: [requested, priority]
            int totalSinkRequested = 0;
            int totalConsumptionPerTick = 0;
            for (SinkNode sink : sinks) {
                int sinkNode = solver.addNode();
                demandIn.put(sink.pos, sinkNode);
                int requested = Math.max(0, sink.sink.getRequested());
                totalSinkRequested += requested;
                totalConsumptionPerTick += Math.max(0, sink.sink.getConsumptionPerTick());
                // Open the demand edge at capacity 0; priority tiers below raise it to `requested`.
                sinkRefs.add(solver.addEdge(sinkNode, superSink, 0, sink.sink));
                sinkDemand.add(new int[]{requested, sink.sink.getPriority()});
            }

            // Link each supply port to the demand port of every adjacent node (INF): transport across
            // touching blocks, including the direct source->sink edge that makes cables optional.
            for (Map.Entry<BlockPos, Integer> entry : supplyOut.entrySet()) {
                int from = entry.getValue();
                for (EnumFacing facing : EnumFacing.VALUES) {
                    Integer to = demandIn.get(entry.getKey().offset(facing));
                    if (to != null) {
                        solver.addEdge(from, to, INF, null);
                    }
                }
            }

            // Priority-tiered redistribution: open the sink demand edges in descending priority order,
            // augmenting the flow at each tier, so a scarce supply fills the highest-priority
            // consumers first and equal-priority ones share what remains. With a single priority (the
            // default — everything in one implicit group) this is one pass, identical to plain max-flow.
            TreeSet<Integer> priorityTiers = new TreeSet<>(Collections.reverseOrder());
            for (int[] demand : sinkDemand) {
                priorityTiers.add(demand[1]);
            }
            int maxFlow = 0;
            for (int tier : priorityTiers) {
                for (int i = 0; i < sinkRefs.size(); i++) {
                    if (sinkDemand.get(i)[1] == tier) {
                        sinkRefs.get(i).setCapacity(sinkDemand.get(i)[0]);
                    }
                }
                maxFlow += solver.maxFlow(superSource, superSink);
            }

            boolean hasCables = !cables.isEmpty();
            int saturatedCables = 0;
            BlockPos bottleneckCable = state.getRoot();
            int bottleneckUtilizationPermille = 0;

            for (MaxFlowSolver.EdgeRef ref : sourceRefs) {
                int used = ref.edge.flow;
                if (used > 0) {
                    ((ISubsystemSource) ref.owner).extract(used);
                }
            }

            for (MaxFlowSolver.EdgeRef ref : sinkRefs) {
                int used = ref.edge.flow;
                if (used > 0) {
                    ((ISubsystemSink) ref.owner).receive(used);
                }
            }

            for (Map.Entry<BlockPos, MaxFlowSolver.EdgeRef> entry : cableThroughputRefs.entrySet()) {
                int used = Math.max(0, entry.getValue().edge.flow);
                if (used > 0) {
                    ((ISubsystemCable) entry.getValue().owner).addTransferred(used);
                }
                int capacity = Math.max(0, entry.getValue().edge.capacity);
                int permille = capacity <= 0 ? 0 : (int) Math.round((used * 1000.0D) / capacity);
                if (permille >= bottleneckUtilizationPermille) {
                    bottleneckUtilizationPermille = permille;
                    bottleneckCable = entry.getKey();
                }
                if (used >= capacity && capacity > 0) {
                    saturatedCables++;
                }
            }

            state.setStatistics(
                    true,
                    statusFor(totalSourceAvailable, totalSinkRequested, maxFlow, totalCableCapacity, hasCables),
                    state.getRoot(),
                    cables.size(),
                    sources.size(),
                    sinks.size(),
                    totalSourceAvailable,
                    totalSinkRequested,
                    totalCableCapacity,
                    maxFlow,
                    saturatedCables,
                    bottleneckCable,
                    bottleneckUtilizationPermille,
                    totalGenerationPerTick,
                    totalConsumptionPerTick
            );

            publish();
        }

        private void publishDisconnected() {
            BlockPos anchor = state.getRoot();
            state.setStatistics(false, SubsystemNetworkStatus.DISCONNECTED, anchor,
                    cables.size(), sources.size(), sinks.size(), 0, 0, 0, 0, 0, anchor, 0, 0, 0);
            // Cables only, NOT controllers — inherited asymmetry, kept deliberately so this
            // extraction changes no behaviour. It looks wrong (a console on a network that just lost
            // its last source keeps displaying the readout from when it still worked), and it is
            // written down as such rather than fixed in passing.
            for (CableNode cable : cables) {
                cable.cable.onNetworkStats(state);
            }
        }

        /** One report, to everything that displays it. */
        private void publish() {
            for (ISubsystemNetworkController controller : controllers) {
                controller.applyNetworkState(state);
            }
            for (CableNode cable : cables) {
                cable.cable.onNetworkStats(state);
            }
        }

        private int statusFor(int sourceAvailable, int sinkRequested, int maxFlow, int cableCapacity, boolean hasCables) {
            if (sourceAvailable <= 0 || sinkRequested <= 0) {
                return SubsystemNetworkStatus.DISCONNECTED;
            }
            int limiting = Math.min(sourceAvailable, sinkRequested);
            if (hasCables && maxFlow < limiting && maxFlow < cableCapacity) {
                return SubsystemNetworkStatus.CABLE_LIMITED;
            }
            if (sourceAvailable <= sinkRequested && maxFlow >= sourceAvailable) {
                return SubsystemNetworkStatus.SOURCE_LIMITED;
            }
            if (sinkRequested < sourceAvailable && maxFlow >= sinkRequested) {
                return SubsystemNetworkStatus.SINK_LIMITED;
            }
            return SubsystemNetworkStatus.BALANCED;
        }
    }

    private static final class CableNode {
        private final BlockPos pos;
        private final ISubsystemCable cable;

        private CableNode(BlockPos pos, ISubsystemCable cable) {
            this.pos = pos;
            this.cable = cable;
        }
    }

    private static final class SourceNode {
        private final BlockPos pos;
        private final ISubsystemSource source;

        private SourceNode(BlockPos pos, ISubsystemSource source) {
            this.pos = pos;
            this.source = source;
        }
    }

    private static final class SinkNode {
        private final BlockPos pos;
        private final ISubsystemSink sink;

        private SinkNode(BlockPos pos, ISubsystemSink sink) {
            this.pos = pos;
            this.sink = sink;
        }
    }

    /** Dinic's algorithm. Small graphs (one network's blocks), rebuilt per solve. */
    private static final class MaxFlowSolver {
        private final List<List<Edge>> graph = new ArrayList<>();

        private int addNode() {
            graph.add(new ArrayList<>());
            return graph.size() - 1;
        }

        private EdgeRef addEdge(int from, int to, int capacity, Object owner) {
            Edge forward = new Edge(to, capacity);
            Edge backward = new Edge(from, 0);
            forward.rev = graph.get(to).size();
            backward.rev = graph.get(from).size();
            graph.get(from).add(forward);
            graph.get(to).add(backward);
            return new EdgeRef(forward, owner);
        }

        private int maxFlow(int source, int sink) {
            int flow = 0;
            int[] level = new int[graph.size()];
            while (bfs(source, sink, level)) {
                int[] next = new int[graph.size()];
                int pushed;
                while ((pushed = dfs(source, sink, INF, level, next)) > 0) {
                    flow += pushed;
                }
            }
            return flow;
        }

        private boolean bfs(int source, int sink, int[] level) {
            for (int i = 0; i < level.length; i++) {
                level[i] = -1;
            }
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            level[source] = 0;
            queue.add(source);
            while (!queue.isEmpty()) {
                int v = queue.removeFirst();
                for (Edge edge : graph.get(v)) {
                    if (edge.remaining() > 0 && level[edge.to] < 0) {
                        level[edge.to] = level[v] + 1;
                        queue.add(edge.to);
                    }
                }
            }
            return level[sink] >= 0;
        }

        private int dfs(int v, int sink, int pushed, int[] level, int[] next) {
            if (v == sink) {
                return pushed;
            }
            List<Edge> edges = graph.get(v);
            for (; next[v] < edges.size(); next[v]++) {
                Edge edge = edges.get(next[v]);
                if (edge.remaining() <= 0 || level[edge.to] != level[v] + 1) {
                    continue;
                }
                int tr = dfs(edge.to, sink, Math.min(pushed, edge.remaining()), level, next);
                if (tr <= 0) {
                    continue;
                }
                edge.flow += tr;
                graph.get(edge.to).get(edge.rev).flow -= tr;
                return tr;
            }
            return 0;
        }

        private static final class Edge {
            private final int to;
            // Not final: a sink's demand edge is opened tier-by-tier for priority redistribution, so
            // its capacity is raised from 0 to the requested amount between max-flow augmentations.
            private int capacity;
            private int flow;
            private int rev;

            private Edge(int to, int capacity) {
                this.to = to;
                this.capacity = capacity;
            }

            private int remaining() {
                return capacity - flow;
            }
        }

        private static final class EdgeRef {
            private final Edge edge;
            private final Object owner;

            private EdgeRef(Edge edge, Object owner) {
                this.edge = edge;
                this.owner = owner;
            }

            private void setCapacity(int capacity) {
                edge.capacity = capacity;
            }
        }
    }
}
