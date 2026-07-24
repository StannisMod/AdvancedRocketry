package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ShieldNetworkRegistry {

    private static final Set<IShieldNetworkNode> NODES = new HashSet<>();

    private ShieldNetworkRegistry() {
    }

    public static synchronized void register(IShieldNetworkNode node) {
        if (node != null) {
            NODES.add(node);
            log("register", node);
        }
    }

    public static synchronized void unregister(IShieldNetworkNode node) {
        if (node != null) {
            NODES.remove(node);
            log("unregister", node);
        }
    }

    public static synchronized Set<IShieldNetworkNode> snapshot() {
        return Collections.unmodifiableSet(new HashSet<>(NODES));
    }

    public static synchronized void clearWorld(World world) {
        if (world == null) {
            return;
        }
        int dim = world.provider.getDimension();
        int before = NODES.size();
        NODES.removeIf(node -> node != null && matchesDimension(node.getNodeWorld(), dim));
        if (before != NODES.size()) {
            logMessage("clearWorld dim=" + dim + " removed=" + (before - NODES.size()) + " remaining=" + NODES.size());
        }
    }

    private static void log(String action, IShieldNetworkNode node) {
        if (node == null) {
            return;
        }
        String worldInfo = node.getNodeWorld() == null ? "null" : "dim=" + node.getNodeWorld().provider.getDimension();
        logMessage(action + " " + node.getClass().getSimpleName() + " pos=" + node.getNodePos() + " " + worldInfo + " total=" + NODES.size());
    }

    private static void logMessage(String message) {
        if (AdvancedForceFieldSystem.LOG != null) {
            AdvancedForceFieldSystem.LOG.info("[ShieldNetworkRegistry] {}", message);
        }
    }

    private static boolean matchesDimension(World nodeWorld, int dimension) {
        return nodeWorld != null && nodeWorld.provider.getDimension() == dimension;
    }
}
