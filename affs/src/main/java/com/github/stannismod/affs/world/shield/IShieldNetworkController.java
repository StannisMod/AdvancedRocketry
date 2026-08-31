package com.github.stannismod.affs.world.shield;

import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkController;

/**
 * A shield console. This interface survives the collapse of the shield-network bridge layer because
 * it carries a member no other domain has: the resistance bias, which is a real shield setting and
 * not another name for something the primitive already provides.
 */
public interface IShieldNetworkController extends ISubsystemNetworkController {

    /** How much of an impact's energy the network answers with; the console owns it, 0..1. */
    double getShieldEnergyResistanceBias();
}
