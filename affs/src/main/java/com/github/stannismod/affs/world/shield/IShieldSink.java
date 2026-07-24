package com.github.stannismod.affs.world.shield;

public interface IShieldSink extends IShieldNetworkNode {

    int getRequestedShieldEnergy();

    int getFreeShieldCapacity();

    int receiveShieldEnergy(int amount);
}
