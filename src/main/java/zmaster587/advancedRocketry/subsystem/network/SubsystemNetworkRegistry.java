package zmaster587.advancedRocketry.subsystem.network;

import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which nodes exist, per domain. A tile registers itself when it joins the world and unregisters
 * when it leaves; the manager reads a snapshot when it rebuilds.
 * <p>
 * Keyed by domain so the graphs stay apart. Synchronized because tiles are created and invalidated
 * off the tick that reads them.
 */
public final class SubsystemNetworkRegistry {

    private static final Map<SubsystemNetworkDomain, Set<ISubsystemNetworkNode>> NODES = new HashMap<>();

    private SubsystemNetworkRegistry() {
    }

    public static synchronized void register(SubsystemNetworkDomain domain, ISubsystemNetworkNode node) {
        if (domain == null || node == null) {
            return;
        }
        nodesOf(domain).add(node);
        log(domain, "register", node);
    }

    public static synchronized void unregister(SubsystemNetworkDomain domain, ISubsystemNetworkNode node) {
        if (domain == null || node == null) {
            return;
        }
        nodesOf(domain).remove(node);
        log(domain, "unregister", node);
    }

    public static synchronized Set<ISubsystemNetworkNode> snapshot(SubsystemNetworkDomain domain) {
        if (domain == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(nodesOf(domain)));
    }

    /** Every domain that has ever registered a node — what the manager ticks. */
    public static synchronized Set<SubsystemNetworkDomain> domains() {
        return new LinkedHashSet<>(NODES.keySet());
    }

    public static synchronized void clearWorld(SubsystemNetworkDomain domain, World world) {
        if (domain == null || world == null) {
            return;
        }
        int dim = world.provider.getDimension();
        Set<ISubsystemNetworkNode> nodes = nodesOf(domain);
        int before = nodes.size();
        nodes.removeIf(node -> node != null && matchesDimension(node.getNodeWorld(), dim));
        if (before != nodes.size() && domain.getLogger() != null) {
            domain.getLogger().info("[{}Network] clearWorld dim={} removed={} remaining={}",
                    domain.getName(), dim, before - nodes.size(), nodes.size());
        }
    }

    private static Set<ISubsystemNetworkNode> nodesOf(SubsystemNetworkDomain domain) {
        return NODES.computeIfAbsent(domain, key -> new HashSet<>());
    }

    private static void log(SubsystemNetworkDomain domain, String action, ISubsystemNetworkNode node) {
        if (domain.getLogger() == null) {
            return;
        }
        String worldInfo = node.getNodeWorld() == null
                ? "null"
                : "dim=" + node.getNodeWorld().provider.getDimension();
        domain.getLogger().info("[{}NetworkRegistry] {} {} pos={} {} total={}",
                domain.getName(), action, node.getClass().getSimpleName(), node.getNodePos(),
                worldInfo, nodesOf(domain).size());
    }

    private static boolean matchesDimension(World nodeWorld, int dimension) {
        return nodeWorld != null && nodeWorld.provider.getDimension() == dimension;
    }
}
