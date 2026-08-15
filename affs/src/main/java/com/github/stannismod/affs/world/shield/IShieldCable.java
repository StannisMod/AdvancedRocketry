package com.github.stannismod.affs.world.shield;

import zmaster587.advancedRocketry.subsystem.network.ISubsystemCable;

public interface IShieldCable extends IShieldNetworkNode, ISubsystemCable {

    void addTransferredShield(int amount);

    @Override
    default void addTransferred(int amount) {
        addTransferredShield(amount);
    }
}
