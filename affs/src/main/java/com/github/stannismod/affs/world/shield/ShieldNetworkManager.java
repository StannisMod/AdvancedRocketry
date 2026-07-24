package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.te.TileEntityShieldCable;
import com.github.stannismod.affs.te.TileEntityShieldGenerator;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.*;

@Mod.EventBusSubscriber(modid = AdvancedForceFieldSystem.MODID)
public final class ShieldNetworkManager {

    private static final int INF = 1_000_000_000;
    private static final int STATUS_DISCONNECTED = 1;
    private static final int STATUS_SOURCE_LIMITED = 2;
    private static final int STATUS_SINK_LIMITED = 3;
    private static final int STATUS_CABLE_LIMITED = 4;
    private static final int STATUS_BALANCED = 5;
    private static final Map<Integer, WorldState> WORLD_STATES = new HashMap<>();

    private ShieldNetworkManager() {
    }

    public static void markDirty(World world) {
        if (world == null || world.isRemote) {
            return;
        }
        getState(world).dirty = true;
    }

    public static ShieldNetworkState getState(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        WorldState state = WORLD_STATES.get(world.provider.getDimension());
        return state == null ? null : state.stateByPos.get(pos);
    }

    public static void setShieldEnergyResistanceBias(World world, BlockPos pos, double bias) {
        if (world == null || world.isRemote || pos == null) {
            return;
        }
        WorldState state = getState(world);
        ShieldNetworkState networkState = state.stateByPos.get(pos);
        if (networkState != null) {
            networkState.setShieldEnergyResistanceBias(bias);
        }
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

        WorldState state = getState(world);
        if (state.dirty) {
            // Topology changes are expensive; only rebuild adjacency when the network actually changed.
            state.rebuild(world);
        }
        // Capacities and demands still change every tick, so max-flow is solved against the cached topology each tick.
        state.solve(world);
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        WORLD_STATES.remove(world.provider.getDimension());
        ShieldNetworkRegistry.clearWorld(world);
    }

    private static WorldState getState(World world) {
        int dim = world.provider.getDimension();
        WorldState state = WORLD_STATES.get(dim);
        if (state == null) {
            state = new WorldState();
            WORLD_STATES.put(dim, state);
        }
        return state;
    }

    private static final class WorldState {
        private boolean dirty = true;
        private final List<ComponentTopology> components = new ArrayList<>();
        private final Map<BlockPos, ShieldNetworkState> stateByPos = new HashMap<>();

        private void rebuild(World world) {
            components.clear();
            Map<BlockPos, ShieldNetworkState> previousStateByPos = new HashMap<>(stateByPos);
            Set<ShieldNetworkState> consumedStates = new HashSet<>();
            stateByPos.clear();

            Set<IShieldNetworkNode> nodes = ShieldNetworkRegistry.snapshot();
            Map<BlockPos, IShieldCable> cables = new HashMap<>();
            Map<BlockPos, IShieldSource> sources = new HashMap<>();
            Map<BlockPos, IShieldSink> sinks = new HashMap<>();
            Map<BlockPos, IShieldNetworkController> controllers = new HashMap<>();
            int skippedNull = 0;
            int skippedWorld = 0;

            for (IShieldNetworkNode node : nodes) {
                if (node == null) {
                    skippedNull++;
                    continue;
                }
                World nodeWorld = node.getNodeWorld();
                if (nodeWorld == null || nodeWorld.provider.getDimension() != world.provider.getDimension()) {
                    skippedWorld++;
                    continue;
                }
                if (node instanceof IShieldCable) {
                    cables.put(node.getNodePos(), (IShieldCable) node);
                } else if (node instanceof IShieldSource) {
                    sources.put(node.getNodePos(), (IShieldSource) node);
                } else if (node instanceof IShieldSink) {
                    sinks.put(node.getNodePos(), (IShieldSink) node);
                } else if (node instanceof IShieldNetworkController) {
                    controllers.put(node.getNodePos(), (IShieldNetworkController) node);
                }
            }

            if (AdvancedForceFieldSystem.LOG != null) {
                AdvancedForceFieldSystem.LOG.info(
                    "[ShieldNetwork] rebuild dim={} snapshot={} skippedNull={} skippedWorld={} cables={} sources={} sinks={} controllers={}",
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

            Set<BlockPos> visited = new HashSet<>();
            for (BlockPos cablePos : cables.keySet()) {
                if (!visited.add(cablePos)) {
                    continue;
                }

                Set<BlockPos> cableComponent = new HashSet<>();
                ArrayDeque<BlockPos> queue = new ArrayDeque<>();
                queue.add(cablePos);

                while (!queue.isEmpty()) {
                    BlockPos current = queue.removeFirst();
                    cableComponent.add(current);
                    for (EnumFacing facing : EnumFacing.VALUES) {
                        BlockPos next = current.offset(facing);
                        if (cables.containsKey(next) && visited.add(next)) {
                            queue.add(next);
                        }
                    }
                }

                List<CableNode> componentCables = new ArrayList<>();
                for (BlockPos pos : cableComponent) {
                    componentCables.add(new CableNode(pos, cables.get(pos)));
                }

                List<SourceNode> componentSources = collectAttachedSources(cableComponent, sources);
                List<SinkNode> componentSinks = collectAttachedSinks(cableComponent, sinks);
                List<ControllerNode> componentControllers = collectAttachedControllers(cableComponent, controllers);

                List<BlockPos> componentMemberPositions = new ArrayList<>(cableComponent);
                for (SourceNode source : componentSources) {
                    componentMemberPositions.add(source.pos);
                }
                for (SinkNode sink : componentSinks) {
                    componentMemberPositions.add(sink.pos);
                }
                for (ControllerNode controller : componentControllers) {
                    componentMemberPositions.add(controller.pos);
                }

                ShieldNetworkState state = findExistingState(componentMemberPositions, previousStateByPos, consumedStates);
                if (state == null) {
                    state = new ShieldNetworkState();
                }
                seedShieldEnergyBiasFromControllers(state, componentControllers);
                state.clearMembers();
                state.setRoot(cablePos);
                for (BlockPos pos : componentMemberPositions) {
                    state.addMember(pos);
                    stateByPos.put(pos, state);
                }

                components.add(new ComponentTopology(state, componentCables, componentSources, componentSinks, componentControllers));
                if (AdvancedForceFieldSystem.LOG != null) {
                    AdvancedForceFieldSystem.LOG.info(
                        "[ShieldNetwork] component anchor={} cables={} sources={} sinks={} controllers={}",
                        cablePos,
                        componentCables.size(),
                        componentSources.size(),
                        componentSinks.size(),
                        componentControllers.size()
                    );
                }
            }

            dirty = false;
        }

        private ShieldNetworkState findExistingState(List<BlockPos> memberPositions, Map<BlockPos, ShieldNetworkState> previousStateByPos, Set<ShieldNetworkState> consumedStates) {
            ShieldNetworkState best = null;
            for (BlockPos pos : memberPositions) {
                ShieldNetworkState candidate = previousStateByPos.get(pos);
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

        private void seedShieldEnergyBiasFromControllers(ShieldNetworkState state, List<ControllerNode> controllers) {
            if (state == null || controllers == null || controllers.isEmpty()) {
                return;
            }
            for (ControllerNode controller : controllers) {
                if (controller == null || controller.controller == null) {
                    continue;
                }
                state.setShieldEnergyResistanceBias(controller.controller.getShieldEnergyResistanceBias());
                return;
            }
        }

        private void solve(World world) {
            if (components.isEmpty()) {
                return;
            }

            for (ComponentTopology component : components) {
                component.solve();
            }
        }

        private List<SourceNode> collectAttachedSources(Set<BlockPos> cableComponent, Map<BlockPos, IShieldSource> sources) {
            List<SourceNode> result = new ArrayList<>();
            for (Map.Entry<BlockPos, IShieldSource> entry : sources.entrySet()) {
                BlockPos pos = entry.getKey();
                if (isAdjacentToAnyCable(pos, cableComponent)) {
                    result.add(new SourceNode(pos, entry.getValue()));
                }
            }
            return result;
        }

        private List<SinkNode> collectAttachedSinks(Set<BlockPos> cableComponent, Map<BlockPos, IShieldSink> sinks) {
            List<SinkNode> result = new ArrayList<>();
            for (Map.Entry<BlockPos, IShieldSink> entry : sinks.entrySet()) {
                BlockPos pos = entry.getKey();
                if (isAdjacentToAnyCable(pos, cableComponent)) {
                    result.add(new SinkNode(pos, entry.getValue()));
                }
            }
            return result;
        }

        private List<ControllerNode> collectAttachedControllers(Set<BlockPos> cableComponent, Map<BlockPos, IShieldNetworkController> controllers) {
            List<ControllerNode> result = new ArrayList<>();
            for (Map.Entry<BlockPos, IShieldNetworkController> entry : controllers.entrySet()) {
                BlockPos pos = entry.getKey();
                if (isAdjacentToAnyCable(pos, cableComponent)) {
                    result.add(new ControllerNode(pos, entry.getValue()));
                }
            }
            return result;
        }

        private boolean isAdjacentToAnyCable(BlockPos pos, Set<BlockPos> cables) {
            for (EnumFacing facing : EnumFacing.VALUES) {
                if (cables.contains(pos.offset(facing))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ComponentTopology {
        private final ShieldNetworkState state;
        private final List<CableNode> cables;
        private final List<SourceNode> sources;
        private final List<SinkNode> sinks;
        private final List<ControllerNode> controllers;

        private ComponentTopology(ShieldNetworkState state, List<CableNode> cables, List<SourceNode> sources, List<SinkNode> sinks, List<ControllerNode> controllers) {
            this.state = state;
            this.cables = cables;
            this.sources = sources;
            this.sinks = sinks;
            this.controllers = controllers;
        }

        private void solve() {
            if (cables.isEmpty()) {
                publishDisconnected();
                return;
            }
            if (sources.isEmpty() || sinks.isEmpty()) {
                publishDisconnected();
                return;
            }

            MaxFlowSolver solver = new MaxFlowSolver();
            int superSource = solver.addNode();
            int superSink = solver.addNode();

            Map<BlockPos, Integer> cableIn = new HashMap<>();
            Map<BlockPos, Integer> cableOut = new HashMap<>();
            Map<BlockPos, MaxFlowSolver.EdgeRef> cableThroughputRefs = new HashMap<>();
            int totalCableCapacity = 0;

            for (CableNode cable : cables) {
                int in = solver.addNode();
                int out = solver.addNode();
                cableIn.put(cable.pos, in);
                cableOut.put(cable.pos, out);
                int throughput = Math.max(0, cable.cable.getThroughputPerTick());
                totalCableCapacity += throughput;
                cableThroughputRefs.put(cable.pos, solver.addEdge(in, out, throughput, cable.cable));
            }

            for (CableNode cable : cables) {
                int out = cableOut.get(cable.pos);
                for (EnumFacing facing : EnumFacing.VALUES) {
                    BlockPos nextPos = cable.pos.offset(facing);
                    Integer nextIn = cableIn.get(nextPos);
                    if (nextIn != null) {
                        solver.addEdge(out, nextIn, INF, null);
                    }
                }
            }

            List<MaxFlowSolver.EdgeRef> sourceRefs = new ArrayList<>();
            int totalSourceAvailable = 0;
            int totalGenerationPerTick = 0;
            for (SourceNode source : sources) {
                int sourceNode = solver.addNode();
                int available = Math.max(0, source.source.getAvailableShieldEnergy());
                totalSourceAvailable += available;
                totalGenerationPerTick += estimateSourceGenerationPerTick(source.source);
                sourceRefs.add(solver.addEdge(superSource, sourceNode, available, source.source));
                for (EnumFacing facing : EnumFacing.VALUES) {
                    Integer adjacentIn = cableIn.get(source.pos.offset(facing));
                    if (adjacentIn != null) {
                        solver.addEdge(sourceNode, adjacentIn, INF, null);
                    }
                }
            }

            List<MaxFlowSolver.EdgeRef> sinkRefs = new ArrayList<>();
            int totalSinkRequested = 0;
            int totalConsumptionPerTick = 0;
            for (SinkNode sink : sinks) {
                int sinkNode = solver.addNode();
                for (EnumFacing facing : EnumFacing.VALUES) {
                    Integer adjacentOut = cableOut.get(sink.pos.offset(facing));
                    if (adjacentOut != null) {
                        solver.addEdge(adjacentOut, sinkNode, INF, null);
                    }
                }
                int requested = Math.max(0, sink.sink.getRequestedShieldEnergy());
                totalSinkRequested += requested;
                totalConsumptionPerTick += estimateSinkConsumptionPerTick(sink.sink);
                sinkRefs.add(solver.addEdge(sinkNode, superSink, requested, sink.sink));
            }

            int maxFlow = solver.maxFlow(superSource, superSink);

            int saturatedCables = 0;
            BlockPos bottleneckCable = cables.get(0).pos;
            int bottleneckUtilizationPermille = 0;
            for (MaxFlowSolver.EdgeRef ref : sourceRefs) {
                int used = ref.edge.flow;
                if (used > 0) {
                    ((IShieldSource) ref.owner).extractShieldEnergy(used);
                }
            }

            for (MaxFlowSolver.EdgeRef ref : sinkRefs) {
                int used = ref.edge.flow;
                if (used > 0) {
                    ((IShieldSink) ref.owner).receiveShieldEnergy(used);
                }
            }

            for (Map.Entry<BlockPos, MaxFlowSolver.EdgeRef> entry : cableThroughputRefs.entrySet()) {
                int used = Math.max(0, entry.getValue().edge.flow);
                if (used > 0) {
                    ((IShieldCable) entry.getValue().owner).addTransferredShield(used);
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
                statusFor(totalSourceAvailable, totalSinkRequested, maxFlow, totalCableCapacity),
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

            pushNetworkStateToControllers();

            for (CableNode cable : cables) {
                if (cable.cable instanceof TileEntityShieldCable) {
                    ((TileEntityShieldCable) cable.cable).setNetworkStats(
                        true,
                        statusFor(totalSourceAvailable, totalSinkRequested, maxFlow, totalCableCapacity),
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
                        bottleneckUtilizationPermille
                    );
                }
            }
        }

        private void publishDisconnected() {
            if (cables.isEmpty()) {
                state.setStatistics(false, STATUS_DISCONNECTED, state.getRoot(), 0, sources.size(), sinks.size(), 0, 0, 0, 0, 0, state.getRoot(), 0, 0, 0);
                return;
            }
            BlockPos anchor = cables.get(0).pos;
            state.setStatistics(false, STATUS_DISCONNECTED, anchor, cables.size(), sources.size(), sinks.size(), 0, 0, 0, 0, 0, anchor, 0, 0, 0);
            for (CableNode cable : cables) {
                if (cable.cable instanceof TileEntityShieldCable) {
                    ((TileEntityShieldCable) cable.cable).setNetworkStats(
                        false,
                        STATUS_DISCONNECTED,
                        anchor,
                        cables.size(),
                        sources.size(),
                        sinks.size(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        anchor,
                        0
                    );
                }
            }
        }

        private void pushNetworkStateToControllers() {
            for (ControllerNode controller : controllers) {
                controller.controller.applyNetworkState(state);
            }
        }

        private int estimateSourceGenerationPerTick(IShieldSource source) {
            if (source instanceof TileEntityShieldGenerator) {
                return ((TileEntityShieldGenerator) source).getShieldProductionPotential();
            }
            return Math.max(0, source.getAvailableShieldEnergy());
        }

        private int estimateSinkConsumptionPerTick(IShieldSink sink) {
            if (sink instanceof TileEntityFieldGenerator) {
                return ((TileEntityFieldGenerator) sink).getShieldDrainThisTick();
            }
            return Math.max(0, sink.getRequestedShieldEnergy());
        }

        private int statusFor(int sourceAvailable, int sinkRequested, int maxFlow, int cableCapacity) {
            if (sourceAvailable <= 0 || sinkRequested <= 0 || cableCapacity <= 0) {
                return STATUS_DISCONNECTED;
            }
            int limiting = Math.min(sourceAvailable, sinkRequested);
            if (maxFlow < limiting && maxFlow < cableCapacity) {
                return STATUS_CABLE_LIMITED;
            }
            if (sourceAvailable <= sinkRequested && maxFlow >= sourceAvailable) {
                return STATUS_SOURCE_LIMITED;
            }
            if (sinkRequested < sourceAvailable && maxFlow >= sinkRequested) {
                return STATUS_SINK_LIMITED;
            }
            return STATUS_BALANCED;
        }
    }

    private static final class CableNode {
        private final BlockPos pos;
        private final IShieldCable cable;

        private CableNode(BlockPos pos, IShieldCable cable) {
            this.pos = pos;
            this.cable = cable;
        }
    }

    private static final class SourceNode {
        private final BlockPos pos;
        private final IShieldSource source;

        private SourceNode(BlockPos pos, IShieldSource source) {
            this.pos = pos;
            this.source = source;
        }
    }

    private static final class SinkNode {
        private final BlockPos pos;
        private final IShieldSink sink;

        private SinkNode(BlockPos pos, IShieldSink sink) {
            this.pos = pos;
            this.sink = sink;
        }
    }

    private static final class ControllerNode {
        private final BlockPos pos;
        private final IShieldNetworkController controller;

        private ControllerNode(BlockPos pos, IShieldNetworkController controller) {
            this.pos = pos;
            this.controller = controller;
        }
    }

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
            private final int capacity;
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
        }
    }
}
