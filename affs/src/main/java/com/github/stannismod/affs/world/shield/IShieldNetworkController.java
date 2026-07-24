package com.github.stannismod.affs.world.shield;

public interface IShieldNetworkController extends IShieldNetworkNode {

    double getShieldEnergyResistanceBias();

    void applyNetworkState(ShieldNetworkState state);
}
