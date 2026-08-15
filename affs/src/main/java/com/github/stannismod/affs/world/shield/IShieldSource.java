package com.github.stannismod.affs.world.shield;

import zmaster587.advancedRocketry.subsystem.network.ISubsystemSource;

public interface IShieldSource extends IShieldNetworkNode, ISubsystemSource {

    int getAvailableShieldEnergy();

    int extractShieldEnergy(int amount);

    @Override
    default int getAvailable() {
        return getAvailableShieldEnergy();
    }

    @Override
    default int extract(int amount) {
        return extractShieldEnergy(amount);
    }
}
