package com.github.stannismod.affs.world.shield;

import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkController;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;

public interface IShieldNetworkController extends IShieldNetworkNode, ISubsystemNetworkController {

    double getShieldEnergyResistanceBias();

    void applyNetworkState(ShieldNetworkState state);

    @Override
    default void applyNetworkState(SubsystemNetworkState state) {
        if (state instanceof ShieldNetworkState) {
            applyNetworkState((ShieldNetworkState) state);
        }
    }
}
