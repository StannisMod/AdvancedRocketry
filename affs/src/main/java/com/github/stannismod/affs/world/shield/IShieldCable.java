package com.github.stannismod.affs.world.shield;

public interface IShieldCable extends IShieldNetworkNode {

    int getThroughputPerTick();

    void addTransferredShield(int amount);
}
