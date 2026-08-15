package com.github.stannismod.affs.world.shield;

import net.minecraft.world.World;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkNode;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkRegistry;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The shield domain's view of the shared node registry. Kept as its own type so shield tiles do not
 * have to name the domain at every call site.
 */
public final class ShieldNetworkRegistry {

    private ShieldNetworkRegistry() {
    }

    public static void register(IShieldNetworkNode node) {
        SubsystemNetworkRegistry.register(ShieldNetworkManager.DOMAIN, node);
    }

    public static void unregister(IShieldNetworkNode node) {
        SubsystemNetworkRegistry.unregister(ShieldNetworkManager.DOMAIN, node);
    }

    public static Set<IShieldNetworkNode> snapshot() {
        Set<IShieldNetworkNode> shieldNodes = new HashSet<>();
        for (ISubsystemNetworkNode node : SubsystemNetworkRegistry.snapshot(ShieldNetworkManager.DOMAIN)) {
            if (node instanceof IShieldNetworkNode) {
                shieldNodes.add((IShieldNetworkNode) node);
            }
        }
        return Collections.unmodifiableSet(shieldNodes);
    }

    public static void clearWorld(World world) {
        SubsystemNetworkRegistry.clearWorld(ShieldNetworkManager.DOMAIN, world);
    }
}
