package com.github.stannismod.affs.world.shield;

public interface IShieldSource extends IShieldNetworkNode {

    int getAvailableShieldEnergy();

    int extractShieldEnergy(int amount);
}
