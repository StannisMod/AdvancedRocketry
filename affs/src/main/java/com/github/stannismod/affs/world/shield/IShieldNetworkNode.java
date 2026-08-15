package com.github.stannismod.affs.world.shield;

import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkNode;

/**
 * A shield-network member. The graph, the solve and the priority redistribution all live in the
 * shared subsystem-network primitive; this family adds only the shield vocabulary on top, so the
 * tiles keep speaking about shield energy while the network speaks about a commodity.
 */
public interface IShieldNetworkNode extends ISubsystemNetworkNode {
}
